;; Talk to `codex app-server` over stdio JSON-RPC, using the ~/.codex login.
;;
;; Shared by codex-usage and codex-redeem-reset (each loads this file from its
;; own directory). The Codex binary is the --codex option if given, else
;; `codex` on PATH, falling back to the newest /nix/store/*-codex-0.1*/bin/codex;
;; it must speak account/rateLimits/read with rateLimitResetCredits
;; (codex-cli 0.161 and later carry it).
;;
;; JSON objects read from the server are array-maps built with `apply array-map`,
;; which keeps the order of the reply at any size; `get` reads them like any map.
(ns codex-app-server
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

;; ---- ordered JSON ----

(defn bigint-or-long [m]
  (let [b (biginteger m)] (if (< (.bitLength b) 64) (.longValue b) (bigint b))))

(defn read-json
  "Parse JSON text; objects become array-maps (insertion order), arrays vectors."
  [^String s]
  (let [n (count s)
        i (volatile! 0)
        fail #(throw (ex-info (str "bad JSON at " @i) {}))
        peek-ch #(when (< @i n) (.charAt s @i))
        skip-ws #(while (and (< @i n) (Character/isWhitespace (.charAt s @i))) (vswap! i inc))
        expect (fn [c] (skip-ws) (if (= (peek-ch) c) (vswap! i inc) (fail)))
        parse-string
        (fn []
          (expect \")
          (let [sb (StringBuilder.)]
            (loop []
              (let [c (peek-ch)]
                (cond
                  (nil? c) (fail)
                  (= c \") (vswap! i inc)
                  (= c \\) (let [e (.charAt s (inc @i))]
                             (vswap! i + 2)
                             (case e
                               \" (.append sb \") \\ (.append sb \\) \/ (.append sb \/)
                               \b (.append sb \backspace) \f (.append sb \formfeed)
                               \n (.append sb \newline) \r (.append sb \return) \t (.append sb \tab)
                               \u (do (.append sb (char (Integer/parseInt (subs s @i (+ @i 4)) 16)))
                                      (vswap! i + 4))
                               (fail))
                             (recur))
                  :else (do (.append sb c) (vswap! i inc) (recur)))))
            (str sb)))
        parse-value
        (fn parse-value []
          (skip-ws)
          (let [c (peek-ch)]
            (cond
              (= c \{) (do (vswap! i inc)
                           (skip-ws)
                           (if (= (peek-ch) \})
                             (do (vswap! i inc) {})
                             (loop [kvs []]
                               (skip-ws)
                               (let [k (parse-string)
                                     _ (expect \:)
                                     kvs (conj kvs k (parse-value))]
                                 (skip-ws)
                                 (case (peek-ch)
                                   \, (do (vswap! i inc) (recur kvs))
                                   \} (do (vswap! i inc) (apply array-map kvs))
                                   (fail))))))
              (= c \[) (do (vswap! i inc)
                           (skip-ws)
                           (if (= (peek-ch) \])
                             (do (vswap! i inc) [])
                             (loop [acc []]
                               (let [acc (conj acc (parse-value))]
                                 (skip-ws)
                                 (case (peek-ch)
                                   \, (do (vswap! i inc) (recur acc))
                                   \] (do (vswap! i inc) acc)
                                   (fail))))))
              (= c \") (parse-string)
              (str/starts-with? (subs s @i) "true") (do (vswap! i + 4) true)
              (str/starts-with? (subs s @i) "false") (do (vswap! i + 5) false)
              (str/starts-with? (subs s @i) "null") (do (vswap! i + 4) nil)
              :else (let [m (re-find #"^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?" (subs s @i))]
                      (when-not m (fail))
                      (vswap! i + (count m))
                      (if (re-find #"[.eE]" m) (Double/parseDouble m) (bigint-or-long m))))))]
    (let [v (parse-value)]
      (skip-ws)
      (when (< @i n) (fail))
      v)))

(defn- json-string [^String s]
  (let [sb (StringBuilder. "\"")]
    (doseq [c s]
      (case c
        \" (.append sb "\\\"") \\ (.append sb "\\\\")
        \newline (.append sb "\\n") \return (.append sb "\\r") \tab (.append sb "\\t")
        \backspace (.append sb "\\b") \formfeed (.append sb "\\f")
        (if (<= 0x20 (int c) 0x7e)
          (.append sb c)
          (.append sb (format "\\u%04x" (int c))))))
    (str (.append sb "\"") )))

(defn write-json
  "Pretty JSON the way Python's json.dumps(value, indent=1) writes it."
  ([value] (write-json value 0))
  ([value depth]
   (let [pad (fn [d] (apply str (repeat d " ")))]
     (cond
       (nil? value) "null"
       (true? value) "true"
       (false? value) "false"
       (string? value) (json-string value)
       (number? value) (str value)
       (map? value)
       (if (empty? value)
         "{}"
         (str "{\n"
              (str/join ",\n"
                        (for [[k v] value]
                          (str (pad (inc depth)) (json-string k) ": " (write-json v (inc depth)))))
              "\n" (pad depth) "}"))
       (sequential? value)
       (if (empty? value)
         "[]"
         (str "[\n"
              (str/join ",\n" (for [v value] (str (pad (inc depth)) (write-json v (inc depth)))))
              "\n" (pad depth) "]"))
       :else (json-string (str value))))))

(defn write-json* [v] (str/replace (write-json v) #"\n\s*" ""))

;; ---- finding codex ----

(defn- version [path]
  (if-let [[_ v] (re-find #"-codex-(\d+(?:\.\d+)*)" path)]
    (mapv #(Long/parseLong %) (str/split v #"\."))
    []))

(defn- compare-version [a b]
  (loop [a a b b]
    (cond (and (empty? a) (empty? b)) 0
          (empty? a) -1
          (empty? b) 1
          (not= (first a) (first b)) (compare (first a) (first b))
          :else (recur (rest a) (rest b)))))

(defn codex-candidates
  "The --codex path alone if given; else `codex` on PATH, then the newest store codex.
  The `codex` on PATH may be a wrapper that adds --remote, which app-server
  rejects; the store binaries run directly."
  [{:keys [codex]}]
  (if codex
    [codex]
    ;; the store is listed only if `codex` on PATH has failed
    (let [stored (fn [] (->> (fs/list-dir "/nix/store")
                      (filter #(re-find #"^[^/]*-codex-0\.1[^/]*\z" (str (fs/file-name %))))
                      (map #(str % "/bin/codex"))
                      (filter fs/exists?)
                      sort
                      (sort (fn [a b] (compare-version (version b) (version a))))))]
      (cons "codex" (lazy-seq (take 1 (stored)))))))

;; ---- JSON-RPC ----

(defn- rpc-error [message] (ex-info message {::rpc true}))

(defn call-with
  "Start one app-server, initialize it, make one request, return its result."
  [binary method params {:keys [timeout] :or {timeout 30}}]
  (let [requests [{"jsonrpc" "2.0" "id" 1 "method" "initialize"
                   "params" {"clientInfo" {"name" "codex-usage" "title" "codex-usage" "version" "0"}}}
                  {"jsonrpc" "2.0" "method" "initialized"}
                  {"jsonrpc" "2.0" "id" 2 "method" method "params" params}]
        cwd (fs/create-temp-dir)
        err-file (fs/path cwd ".app-server-stderr")]
    (try
      (let [proc (process/process [binary "app-server"]
                                  {:dir (str cwd) :err (fs/file err-file)})]
        (try
          (let [stdin (io/writer (:in proc))]
            (doseq [request requests]
              (.write stdin (str (cheshire/generate-string request) "\n")))
            (.flush stdin))
          (let [reader (io/reader (:out proc))]
            (loop []
              (if-let [line (.readLine reader)]
                (let [message (try (read-json line) (catch Exception _ nil))]
                  (if (and (map? message)
                           (number? (get message "id")) (== 2 (get message "id")))
                    (if (contains? message "error")
                      (throw (rpc-error (str method ": " (write-json* (get message "error")))))
                      (get message "result"))
                    (recur)))
                (throw (rpc-error (str method ": app-server closed without a reply: "
                                       (let [e (try (slurp (str err-file)) (catch Exception _ ""))]
                                         (subs e (max 0 (- (count e) 500))))))))))
          (finally
            (.destroyForcibly ^Process (:proc proc))
            (.waitFor ^Process (:proc proc) (long timeout) java.util.concurrent.TimeUnit/SECONDS))))
      (finally (fs/delete-tree cwd)))))

(defn call
  "Try each candidate codex binary until one answers."
  [method params opts]
  (loop [[binary & more] (codex-candidates opts) failure nil]
    (if (nil? binary)
      (throw failure)
      (let [r (try {:ok (call-with binary method params opts)}
                   (catch java.io.IOException e {:fail e})
                   (catch clojure.lang.ExceptionInfo e
                     (if (::rpc (ex-data e)) {:fail e} (throw e))))]
        (if (contains? r :ok)
          (:ok r)
          (recur more (:fail r)))))))

(defn read-usage [opts] (call "account/rateLimits/read" {} opts))

(defn available-credits
  "Available reset credits, soonest expiry first (the TUI's order)."
  [usage]
  (let [summary (get usage "rateLimitResetCredits")]
    (when (nil? summary)
      (throw (rpc-error (str "no rateLimitResetCredits in the reply; "
                             "the codex used is too old, pass --codex PATH of a newer codex"))))
    (->> (or (get summary "credits") [])
         (filter #(= "available" (get % "status")))
         (sort-by #(let [e (get % "expiresAt")] (if (and e (not (zero? e))) e Double/POSITIVE_INFINITY))))))

;; ---- report ----

(defn py-str
  "A value the way Python prints it in an f-string."
  [v]
  (cond (nil? v) "None" (true? v) "True" (false? v) "False" :else (str v)))

(def ^:private time-format
  (DateTimeFormatter/ofPattern "EEE dd MMM yyyy HH:mm zzz" Locale/ENGLISH))

(defn local-time [timestamp]
  (if (nil? timestamp)
    "never"
    (.format time-format (.atZone (Instant/ofEpochMilli (long (* 1000 timestamp))) (ZoneId/systemDefault)))))

(defn limits-of
  "The rate limits by id: rateLimitsByLimitId if present and non-empty, else the one 'codex' limit."
  [usage]
  (let [by-id (get usage "rateLimitsByLimitId")]
    (if (and by-id (seq by-id))
      by-id
      {"codex" (get usage "rateLimits")})))

(defn report [usage]
  (let [lines (volatile! [(str "ordinary usage allowed: " (py-str (get usage "ordinaryUsageAllowed")))])
        add! #(vswap! lines conj %)]
    (doseq [[limit-id limit] (limits-of usage)]
      (doseq [window-name ["primary" "secondary"]]
        (when-let [window (get limit window-name)]
          (add! (str limit-id " " window-name " (" (py-str (get window "windowDurationMins")) " min window): "
                     (- 100 (get window "usedPercent")) "% left, "
                     "resets " (local-time (get window "resetsAt"))))))
      (when-let [reached (get limit "rateLimitReachedType")]
        (add! (str limit-id " limit reached: " reached))))
    (let [summary (or (get usage "rateLimitResetCredits") {})
          credits (available-credits usage)]
      (add! (str "resets available: " (py-str (get summary "availableCount"))
                 " (details listed: " (count credits) ")"))
      (doseq [credit credits]
        (add! (str "  " (get credit "id") "  " (get credit "status") "  " (py-str (get credit "title"))
                   "  expires " (local-time (get credit "expiresAt"))
                   "  granted " (local-time (get credit "grantedAt"))))))
    (str/join "\n" @lines)))

;; ---- command lines ----

(defn parse-args
  "Split args into {:options {...} :positional [...]} given the options that take a value
  (value-options, a map of flag -> key) and the flags that stand alone (flags, flag -> key).
  An unknown --option throws."
  [args value-options flags]
  (loop [[a & more :as args] args options {} positional []]
    (cond
      (empty? args) {:options options :positional positional}
      (contains? flags a) (recur more (assoc options (flags a) true) positional)
      (contains? value-options a)
      (if (empty? more)
        (throw (ex-info (str a " needs a value") {:usage true}))
        (recur (rest more) (assoc options (value-options a) (first more)) positional))
      (str/starts-with? a "--") (throw (ex-info (str "unknown option " a) {:usage true}))
      :else (recur more options (conj positional a)))))

(defn script-dir
  "The directory holding a script, symlinks resolved."
  [file]
  (str (fs/parent (fs/real-path file))))

(defn main-guard
  "Run f; a failure that is not a refusal prints to stderr and exits 1, as an uncaught error did."
  [f]
  (try (f)
       (catch Throwable e
         (binding [*out* *err*] (println (str "error: " (.getMessage e))))
         (System/exit 1))))
