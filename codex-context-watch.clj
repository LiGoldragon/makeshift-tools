;; codex-context-watch.clj: a stopgap watcher for one Codex permaflow address.
;; The Codex Harness Nexus is the real fix: Flow does not know context size yet.
;; This watches session directories with inotify, follows the current rollout,
;; and follows a fresh flow of the same address automatically after a refresh.
;; Each flow gets at most two handover orders, even if its rollout is reopened.
;;
;; CriomOS-home declares one restartable user service per address.
;; Run: bb codex-context-watch.clj ADDRESS SIZE [ROLLOUT]
;;        [--margin TOKENS] [--home DIR]... [--inotifywait COMMAND] [--receipt-file PATH] [--dry]
;; Herdr identifies the current flow; ROLLOUT is an explicit starting recording for replay.
;; The receipt file keeps admitted requests across restarts. Dry mode never sends.
;;
;; Readings are payload.info.last_token_usage.input_tokens on token_count rows.
;; Only complete JSONL lines are consumed. Directory creation, rollout creation,
;; moves and appends wake the watcher; there is no idle timeout or timer scan.
;; Identity comes from session_meta's Metaflow line and a standalone developer
;; message "Flow: <id>", never from quoted messages or arbitrary rollout text.
(ns codex-context-watch
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import (java.io RandomAccessFile)
           (java.util.concurrent LinkedBlockingQueue)))

(def default-homes ["/home/li/.codex-next" "/home/li/.codex-next-5ck97j38j3sb"])
(def usage "usage: bb codex-context-watch.clj ADDRESS SIZE [ROLLOUT] [--margin TOKENS] [--home DIR]... [--inotifywait COMMAND] [--receipt-file PATH] [--dry]")

(defn fail [message] (throw (ex-info (str message "\n" usage) {})))

(defn parse-integer [text name]
  (let [n (try (Long/parseLong (str text))
               (catch Exception _ (fail (str name " is not an integer: " text))))]
    (when (neg? n) (fail (str name " must be nonnegative")))
    n))

(defn configuration [arguments]
  (let [options (loop [args arguments result {:positional [] :homes []}]
                  (if-let [arg (first args)]
                    (cond
                      (= arg "--dry") (recur (rest args) (assoc result :dry true))
                      (#{"--margin" "--home" "--inotifywait" "--receipt-file"} arg)
                      (let [value (second args)]
                        (when (or (nil? value) (str/starts-with? value "--"))
                          (fail (str "missing value for " arg)))
                        (recur (drop 2 args)
                               (if (= arg "--home") (update result :homes conj value)
                                   (assoc result (keyword (subs arg 2)) value))))
                      (str/starts-with? arg "--") (fail (str "unknown option: " arg))
                      :else (recur (rest args) (update result :positional conj arg)))
                    result))
        {:keys [positional homes margin inotifywait receipt-file dry]} options
        [address size rollout] positional]
    (when-not (<= 2 (count positional) 3) (fail "expected ADDRESS SIZE [ROLLOUT]"))
    (when-not (re-matches #"\{ (Psyche|Mind|Field) \w+ \w+ \}" address)
      (fail (str "invalid address: " address)))
    {:address address :size (parse-integer size "SIZE") :rollout rollout
     :margin (parse-integer (or margin "25000") "--margin")
     :homes (if (seq homes) homes default-homes)
     :receipt-file receipt-file :inotifywait (or inotifywait "inotifywait") :dry (boolean dry)}))

(defn log [& parts]
  (binding [*out* *err*]
    (println (str (java.time.Instant/now)) "codex-context-watch" (str/join " " parts))))

(defn parse-row [line]
  (try (json/parse-string line true) (catch Exception _ nil)))

(defn context-size [line]
  (let [payload (:payload (parse-row line))]
    (when (= "token_count" (:type payload))
      (get-in payload [:info :last_token_usage :input_tokens]))))

(defn identity-part [{:keys [type payload]}]
  (cond
    (= type "session_meta")
    {:address (second (re-find #"(?m)^Metaflow: (\{ (?:Psyche|Mind|Field) \w+ \w+ \})$"
                               (get-in payload [:base_instructions :text] "")))
     :started-at (:timestamp payload)}
    (and (= type "response_item") (= "message" (:type payload)) (= "developer" (:role payload)))
    (if-let [id (some #(second (re-matches #"Flow: ([0-9a-f]{6})" (get % :text "")))
                     (:content payload))]
      {:flow-id id} {})
    :else {}))

(defn rollout-identity [path]
  (with-open [reader (clojure.java.io/reader (str path))]
    (reduce (fn [identity line]
              (let [identity (merge identity (identity-part (parse-row line)))]
                (if (:flow-id identity) (reduced identity) identity)))
            {} (line-seq reader))))

(defn rollout-paths [directory]
  (if (fs/directory? directory)
    (map str (fs/glob directory "**/rollout-*.jsonl")) []))

(defn current-flow [address]
  (let [result (process/shell {:out :string :err :string :continue true} "herdr" "agent" "list")]
    (if-not (zero? (:exit result))
      (do (log "Herdr unavailable for" address (str/trim (:err result))) nil)
      (some (fn [agent]
            (let [title (:terminal_title_stripped agent)
                  [_ aspect topic model id] (when title
                                              (re-matches #"\{ (\w+) (\w+) (\w+) ([0-9a-f]{6}) \}" title))]
              (when (= address (str "{ " aspect " " topic " " model " }")) id)))
          (get-in (json/parse-string (:out result) true) [:result :agents])))))

(defn find-rollout [homes address flow-id]
  (some (fn [path]
          (let [identity (rollout-identity path)]
            (when (and (= address (:address identity)) (= flow-id (:flow-id identity))) path)))
        (sort-by #(str (fs/file-name %)) #(compare %2 %1)
                 (mapcat #(rollout-paths (fs/path % "sessions")) homes))))

(defn order-text [{:keys [address flow-id]} tokens]
  (str "Your context has reached " tokens " tokens. Write your handover now at flows/" flow-id
       "/handover.md: " "a map of the code for the flow that follows: the repositories, branches and files the work lives in, where each part of it sits, the types, traits and functions that carry it and how they connect, how it is built and tested, and where the work stands in that code with the next change to make. "
       "Then ask for your own refresh: flow Refresh." address))

(defn hard-order-text [{:keys [address flow-id]} tokens]
  (str "Your context has reached " tokens " tokens and your handover is still owed. Write your "
       "handover now at flows/" flow-id "/handover.md, or lose everything at the refresh. "
       "It is " "a map of the code for the flow that follows: the repositories, branches and files the work lives in, where each part of it sits, the types, traits and functions that carry it and how they connect, how it is built and tested, and where the work stands in that code with the next change to make. "
       "Then ask for your own refresh: flow Refresh." address))

(defn send-datom [{:keys [address]} priority text]
  (str "Send.{ " address " " priority ".Order.«" text "» }"))

(defn admission [output]
  (or (when-let [[_ request cursor state]
                 (re-matches #"SendAccepted\.\{ ([0-9a-z]{4}) (\d+) (.*) \}" output)]
        {:request request :cursor cursor :state state})
      (when-let [[_ request] (re-matches #"Failed\.Admitted\.\{ ([0-9a-z]{4}) .* \}" output)]
        {:request request :state "Uncertain"})))

(defn terminal-state [state]
  (cond
    (#{"Delivered" "Read"} state) :delivered
    (or (#{"Withdrawn" "Archived"} state) (str/starts-with? state "Failed.")) :failed))

(defn delivery-state
  "The newest state of this request in one visible Flow observation."
  [request line]
  (when (re-find #"^(?:SendsObserved|SendChanged)\.\{" line)
    (let [rows (re-seq (re-pattern
                        (str "\\{ " request " (\\d+) (?:Sender\\.\\{ [^}]+ \\}|[^ ]+) "
                             "\\{ [^}]+ \\} (.*?) \\}(?= |$)")) line)]
      (if-let [[_ _ state] (last (sort-by #(Long/parseLong (second %)) rows))]
        state))))

(defn observe-delivery! [{:keys [request cursor]}]
  (let [query (str "ObserveSends." (if cursor (str "Some." cursor) "None"))
        observer (process/process ["flow" query] {:err :inherit})]
    (try
      (with-open [reader (clojure.java.io/reader (:out observer))]
        (or (some (fn [line]
                    (let [state (delivery-state request line)]
                      (when (and state (terminal-state state)) state)))
                  (line-seq reader))
            (throw (ex-info "Flow delivery stream ended before the receipt" {:request request}))))
      (finally (process/destroy-tree observer)))))

(defn save-receipts! [{:keys [receipts receipt-file]} key receipt]
  (locking receipts
    (let [next (assoc @receipts key receipt)]
      (when receipt-file
        (let [path (fs/absolutize receipt-file)
              _ (fs/create-dirs (fs/parent path))
              temporary (fs/create-temp-file {:dir (fs/parent path) :prefix "receipt-"})]
          (try
            (spit (str temporary) (pr-str next))
            (fs/move temporary path {:replace-existing true :atomic-move true})
            (finally (fs/delete-if-exists temporary)))))
      (reset! receipts next))))

(defn record-receipt! [configuration key receipt state]
  (save-receipts! configuration key (assoc receipt :state state))
  (log (:address configuration) "flow" (first key) (second key)
       "request" (:request receipt) "input tokens" (:tokens receipt) "Flow reports" state))

(defn follow-receipt! [configuration key receipt]
  (if (terminal-state (:state receipt))
    (record-receipt! configuration key receipt (:state receipt))
    (future
      (try
        (record-receipt! configuration key receipt (observe-delivery! receipt))
        (catch Exception e
          ((:receipt-failure configuration) e))))))

(defn send! [configuration priority text tokens]
  (let [datom (send-datom configuration priority text)]
    (if (:dry configuration)
      (do (println "DRY would send:" (str "flow '" datom "'"))
          {:state "Delivered"})
      (let [result (process/shell {:out :string :err :string :continue true} "flow" datom)
            output (str/trim (:out result))]
        (log (:address configuration) "flow" (:flow-id configuration) priority "send at" tokens
             "input tokens, flow exit" (:exit result) output (str/trim (:err result)))
        (some-> (admission output) (assoc :tokens tokens))))))

(defn thresholds [{:keys [size margin]}]
  [{:name :first :at size :priority "MiddleAbrupt" :text order-text}
   {:name :second :at (+ size margin) :priority "HardAbrupt" :text hard-order-text}])

(defn react
  "Admit eligible reminders; a pending request suppresses a duplicate, and only a
  Delivered or Read receipt counts as a delivered reminder."
  [{:keys [flow-id dry receipts address] :as configuration} dry-fired tokens]
  (reduce (fn [sent {:keys [name at priority text]}]
            (let [key [flow-id name]]
              (if (and (>= tokens at) (not (if dry (sent name) (get @receipts key)))
                       (or dry (= flow-id (current-flow address))))
                (if-let [receipt (send! configuration priority (text configuration tokens) tokens)]
                  (if dry
                    (conj sent name)
                    (do (save-receipts! configuration key receipt)
                        (follow-receipt! configuration key receipt)
                        sent))
                  sent)
                sent)))
          dry-fired
          (thresholds configuration)))

;; Directory events and incremental complete lines

(defn complete-lines [path offset consume state]
  (with-open [file (RandomAccessFile. (str path) "r")]
    (.seek file (if (< (.length file) offset) 0 offset))
    (loop [state state offset (.getFilePointer file)]
      (if-let [line (.readLine file)]
        (let [end (.getFilePointer file)]
          (.seek file (dec end))
          (if (= 10 (.read file))
            (recur (consume state (String. (.getBytes line "ISO-8859-1") "UTF-8")) end)
            [state offset]))
        [state offset]))))

(defn select-rollout [configuration state path identity]
  (let [{:keys [address flow-id started-at]} identity
        current (:current state)
        same-flow (= flow-id (:flow-id current))
        newer (pos? (compare (or started-at "") (or (:started-at current) "")))
        initial? (or (nil? (:initial-flow state))
                     (= flow-id (:initial-flow state))
                     (not (neg? (compare (or started-at "") (:waiting-since state)))))]
    (if (and initial? (= address (:address configuration)) flow-id
             (or (nil? current)
                 (and same-flow (or (= path (:path current)) newer))
                 (and newer (not (contains? (:seen state) flow-id)))))
      (do
        (when (not= path (:path current)) (log "following" flow-id path))
        (-> state (dissoc :initial-flow :waiting-since)
            (assoc :current (assoc identity :path path)) (update :seen conj flow-id)))
      state)))

(defn consume-line [configuration path state line]
  (let [row (parse-row line)
        identity (merge (get-in state [:files path :identity]) (identity-part row))
        state (-> state
                  (assoc-in [:files path :identity] identity)
                  (update-in [:files path :lines] (fnil inc 0)))
        state (select-rollout configuration state path identity)
        payload (:payload row)
        tokens (when (= "token_count" (:type payload))
                 (get-in payload [:info :last_token_usage :input_tokens]))]
    (if (and (= path (get-in state [:current :path])) (number? tokens))
      (let [flow-id (:flow-id identity)]
        (when (:dry configuration)
          (println "DRY reading:" tokens "tokens, flow" flow-id "line" (get-in state [:files path :lines])))
        (assoc state :reading [flow-id tokens]))
      state)))

(defn apply-reading [configuration state]
  (if-let [[flow-id tokens] (:reading state)]
    (-> (update-in state [:fired flow-id]
                   #(react (assoc configuration :flow-id flow-id) (or % #{}) tokens))
        (dissoc :reading))
    state))

(defn consume-path [configuration state path]
  (if (and (fs/regular-file? path) (re-matches #"rollout-.*\.jsonl" (str (fs/file-name path))))
    (let [[state offset] (complete-lines path (get-in state [:files path :offset] 0)
                                         #(consume-line configuration path %1 %2) state)]
      (apply-reading configuration (assoc-in state [:files path :offset] offset)))
    state))

(defn consume-event [configuration state path]
  (reduce #(consume-path configuration %1 %2) state
          (if (fs/directory? path) (sort (rollout-paths path)) [path])))

(defn directory-events [configuration queue]
  (let [directories (mapv #(str (fs/path % "sessions")) (:homes configuration))
        _ (doseq [directory directories] (fs/create-dirs directory))
        watcher (apply process/process {:extra-env {"LC_ALL" "C"}}
                       (:inotifywait configuration)
                       "--monitor" "--recursive" "--event" "create,modify,moved_to,close_write"
                       "--format" "%w%f" directories)]
    (future
      (try
        (with-open [reader (clojure.java.io/reader (:out watcher))]
          (doseq [path (line-seq reader)] (.put queue [:path path])))
        (finally (.put queue [:ended]))))
    (future
      (with-open [reader (clojure.java.io/reader (:err watcher))]
        (doseq [line (line-seq reader)]
          (if (= line "Watches established.") (.put queue [:ready])
              (log "inotify:" line)))))
    {:process watcher :queue queue}))

(defn initial-rollout [configuration state queue]
  ;; Install watches before discovery; retain every event during setup/scan.
  (let [pending (loop [paths []]
                  (let [[event path] (.take queue)]
                    (case event
                      :ready paths
                      :ended (fail "inotify ended before watches were established")
                      :receipt-failed (throw path)
                      :path (recur (conj paths path)))))
        waiting-since (str (java.time.Instant/now))
        flow-id (when-not (:rollout configuration) (current-flow (:address configuration)))
        path (or (:rollout configuration)
                 (when flow-id (find-rollout (:homes configuration) (:address configuration) flow-id)))]
    (when (:rollout configuration)
      (when-not (fs/regular-file? path) (fail (str "rollout does not exist: " path)))
      (when-not (= (:address configuration) (:address (rollout-identity path)))
        (fail "the selected rollout names a different address")))
    (let [state (if (:current state) state
                    (assoc state :initial-flow flow-id :waiting-since waiting-since))
          state (if path (consume-path configuration state path)
                    (do (log "waiting for rollout of" (:address configuration) "current flow" flow-id) state))]
      (reduce #(consume-event configuration %1 %2) state pending))))

(defn watch [configuration]
  (let [queue (LinkedBlockingQueue.)
        receipt-file (:receipt-file configuration)
        receipts (atom (if (and receipt-file (fs/exists? receipt-file))
                         (edn/read-string (slurp receipt-file)) {}))
        configuration (assoc configuration :receipts receipts
                             :receipt-failure #(.put queue [:receipt-failed %]))
        running (atom nil)
        hook (Thread. #(do (log "exit: SIGTERM")
                           (when-let [child @running] (process/destroy-tree child))))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (when-not (:dry configuration)
        (doseq [[key receipt] @receipts :when (not (terminal-state (:state receipt)))]
          (follow-receipt! configuration key receipt)))
      (loop [state {:files {} :seen #{} :fired {}}]
        (let [{:keys [process queue]} (directory-events configuration queue)
              _ (reset! running process)
              state (initial-rollout configuration state queue)
              state (loop [state state]
                      (let [[event path] (.take queue)]
                        (case event
                          :path (recur (consume-event configuration state path))
                          :ended state
                          :receipt-failed (throw path)
                          (recur state))))]
          ;; A child ending is an event too. Reinstall its watches and discover
          ;; the current rollout again, retaining all per-flow send accounting.
          (log "inotify ended; reinstalling directory watches")
          (process/destroy-tree process)
          (recur state)))
      (finally
        (when-let [child @running] (process/destroy-tree child))
        (.removeShutdownHook (Runtime/getRuntime) hook)))))

(defn -main [& arguments]
  (watch (configuration arguments)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
