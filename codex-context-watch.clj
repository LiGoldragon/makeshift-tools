;; codex-context-watch.clj: a stopgap watcher for one Codex flow. It follows the
;; flow's rollout file and, when the context reaches the warning size, tells the
;; flow to write its handover. The Flow Nexus learns nothing of a Codex flow's
;; context growing, so the Codex flow never gets its context warning; the Codex
;; Harness Nexus is the real fix, and this stands in until then.
;;
;; Start by hand, as a transient user unit that survives the terminal:
;;   systemd-run --user --unit codex-context-watch-<id> bb /git/github.com/LiGoldragon/makeshift-tools/codex-context-watch.clj "<address>" <id> <size>
;; e.g.
;;   systemd-run --user --unit codex-context-watch-7a534b bb /git/github.com/LiGoldragon/makeshift-tools/codex-context-watch.clj "{ Mind harnessNexus Sol }" 7a534b 200000
;;
;; Run: bb codex-context-watch.clj ADDRESS FLOW_ID SIZE [ROLLOUT]
;;        [--margin TOKENS] [--idle-seconds SECONDS] [--home DIR]... [--dry]
;;   ADDRESS  the flow's address, "{ Aspect topic Model }"
;;   FLOW_ID  six hex characters
;;   SIZE     the warning size in tokens
;;   ROLLOUT  the rollout file; without it, the newest rollout-*.jsonl under
;;            <home>/sessions holding the text "Flow: <FLOW_ID>"
;;   --margin       tokens past SIZE at which the second, HardAbrupt send fires (25000)
;;   --idle-seconds exit when the rollout has had no new line this long (21600, 6 hours)
;;   --home         a Codex home (repeatable); defaults are the two in `flow-meta 'Configuration'`
;;   --dry          print each reading and what would be sent; send nothing
;;
;; The context size is `payload.info.last_token_usage.input_tokens` of a row whose
;; `payload.type` is `token_count` (`info` may be null): the input of the last model
;; call. The file is followed with `tail -n +1 -F` (inotify), line by line, from the
;; first line. Each of the two sends fires once, at the first reading at or above its
;; threshold. Exit on idle or SIGTERM. One line per send, and on exit, on stderr.
(ns codex-context-watch
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def default-homes ["/home/li/.codex-next" "/home/li/.codex-next-5ck97j38j3sb"])
(def default-margin 25000)
(def default-idle-seconds (* 6 60 60))
(def end-of-file ::end-of-file)

(def usage
  (str "usage: bb codex-context-watch.clj ADDRESS FLOW_ID SIZE [ROLLOUT] "
       "[--margin TOKENS] [--idle-seconds SECONDS] [--home DIR]... [--dry]"))

(defn fail [message]
  (binding [*out* *err*] (println message) (println usage))
  (System/exit 2))

(defn parse-arguments [arguments]
  (loop [remaining arguments
         result {:positional [] :homes []}]
    (if-let [argument (first remaining)]
      (case argument
        "--dry" (recur (rest remaining) (assoc result :dry true))
        "--margin" (recur (drop 2 remaining) (assoc result :margin (second remaining)))
        "--idle-seconds" (recur (drop 2 remaining) (assoc result :idle-seconds (second remaining)))
        "--home" (recur (drop 2 remaining) (update result :homes conj (second remaining)))
        (recur (rest remaining) (update result :positional conj argument)))
      result)))

(defn parse-integer [text name]
  (try (Long/parseLong (str text))
       (catch Exception _ (fail (str name " is not an integer: " text)))))

(defn configuration [arguments]
  (let [{:keys [positional homes dry margin idle-seconds]} (parse-arguments arguments)
        [address flow-id size rollout] positional]
    (when-not (<= 3 (count positional) 4) (fail "expected ADDRESS FLOW_ID SIZE [ROLLOUT]"))
    (when-not (re-matches #"\{ \w+ \w+ \w+ \}" address)
      (fail (str "the address is not \"{ Aspect topic Model }\": " address)))
    (when-not (re-matches #"[0-9a-f]{6}" flow-id)
      (fail (str "the flow id is not six hex characters: " flow-id)))
    {:address address
     :flow-id flow-id
     :size (parse-integer size "SIZE")
     :rollout rollout
     :margin (if margin (parse-integer margin "--margin") default-margin)
     :idle-seconds (if idle-seconds (parse-integer idle-seconds "--idle-seconds") default-idle-seconds)
     :homes (if (seq homes) homes default-homes)
     :dry (boolean dry)}))

(defn log [& parts]
  (binding [*out* *err*]
    (println (str (java.time.Instant/now)) "codex-context-watch" (str/join " " parts))))

;; Finding the rollout

(defn holds-text? [path text]
  (with-open [reader (clojure.java.io/reader (str path))]
    (boolean (some #(str/includes? % text) (line-seq reader)))))

(defn find-rollout [homes flow-id]
  (let [text (str "Flow: " flow-id)
        candidates (->> homes
                        (map #(str % "/sessions"))
                        (filter fs/directory?)
                        (mapcat #(fs/glob % "**/rollout-*.jsonl"))
                        (sort-by #(.toMillis (fs/last-modified-time %)) >))]
    (some #(when (holds-text? % text) (str %)) candidates)))

;; Readings

(defn context-size
  "The input tokens of the last model call, from one rollout line; nil when the
  line is no reading."
  [line]
  (when (str/includes? line "token_count")
    (let [row (try (json/parse-string line true) (catch Exception _ nil))
          payload (:payload row)]
      (when (= "token_count" (:type payload))
        (get-in payload [:info :last_token_usage :input_tokens])))))

;; The two sends

(defn order-text [{:keys [address flow-id]} tokens]
  (str "Your context has reached " tokens " tokens. Write your handover now at flows/" flow-id
       "/handover.md, a page at most: what you are pursuing, where it stands, what works, "
       "what does not, the next step. Then ask for your own refresh: flow Refresh." address))

(defn hard-order-text [{:keys [address flow-id]} tokens]
  (str "Your context has reached " tokens " tokens and your handover is still owed. Write your "
       "handover now at flows/" flow-id "/handover.md, or lose everything at the refresh. "
       "A page at most: what you are pursuing, where it stands, what works, what does not, "
       "the next step. Then ask for your own refresh: flow Refresh." address))

(defn send-datom [{:keys [address]} priority text]
  (str "Send." address " " priority ".Order.«" text "»"))

(defn send! [configuration priority text tokens]
  (let [datom (send-datom configuration priority text)]
    (if (:dry configuration)
      (do (println "DRY would send:" (str "flow '" datom "'"))
          (log "dry" priority "send for" tokens "tokens"))
      (let [result (process/shell {:out :string :err :string :continue true} "flow" datom)]
        (log priority "send at" tokens "tokens, flow exit" (:exit result)
             (str/trim (str (:out result) (:err result))))))))

(defn thresholds [{:keys [size margin]}]
  [{:name :first :at size :priority "MiddleAbrupt" :text order-text}
   {:name :second :at (+ size margin) :priority "HardAbrupt" :text hard-order-text}])

(defn react
  "Fire each threshold the reading has reached and that has not fired; returns
  the set of fired names."
  [configuration fired tokens]
  (reduce (fn [fired {:keys [name at priority text]}]
            (if (and (not (fired name)) (>= tokens at))
              (do (send! configuration priority (text configuration tokens) tokens)
                  (conj fired name))
              fired))
          fired
          (thresholds configuration)))

;; Following the file

(defn follow
  "Start `tail -n +1 -F` on the rollout; its lines arrive on the returned queue."
  [path]
  (let [tail (process/process {:err :inherit} "tail" "-n" "+1" "-F" path)
        queue (LinkedBlockingQueue.)]
    (future
      (with-open [reader (clojure.java.io/reader (:out tail))]
        (doseq [line (line-seq reader)] (.put queue line)))
      (.put queue end-of-file))
    {:tail tail :queue queue}))

(defn watch [configuration path]
  (let [{:keys [tail queue]} (follow path)
        reason (atom "tail ended")
        lines (atom 0)
        fired (atom #{})
        stop (fn [why]
               (log "exit:" why "after" @lines "lines of" path "; sends fired:"
                    (pr-str (sort @fired)))
               (process/destroy-tree tail))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (when (= "SIGTERM" @reason) (stop "SIGTERM")))))
    (reset! reason "SIGTERM")
    (loop []
      (let [line (.poll queue (:idle-seconds configuration) TimeUnit/SECONDS)]
        (cond
          (nil? line) (do (reset! reason "idle")
                          (stop (str "no new line for " (:idle-seconds configuration) " seconds")))
          (= end-of-file line) (do (reset! reason "tail ended") (stop "tail ended"))
          :else (do (swap! lines inc)
                    (when-let [tokens (context-size line)]
                      (when (:dry configuration)
                        (println "DRY reading:" tokens "tokens, line" @lines))
                      (swap! fired #(react configuration % tokens)))
                    (recur)))))))

(defn -main [& arguments]
  (let [configuration (configuration arguments)
        path (or (:rollout configuration)
                 (find-rollout (:homes configuration) (:flow-id configuration)))]
    (when-not (and path (fs/exists? path))
      (log "no rollout found for flow" (:flow-id configuration) "under" (pr-str (:homes configuration)))
      (System/exit 1))
    (log "watching" path "for" (:address configuration) "warning at" (:size configuration)
         "then" (+ (:size configuration) (:margin configuration))
         (if (:dry configuration) "(dry)" ""))
    (watch configuration path)
    (shutdown-agents)
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
