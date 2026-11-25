;; Run: INOTIFYWAIT=/path/to/inotifywait bb test/codex-context-watch-test.clj
(ns codex-context-watch-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def tool (str (fs/path (fs/parent (fs/parent (fs/absolutize *file*))) "codex-context-watch.clj")))
(load-file tool)
(alias 'watch 'codex-context-watch)
(def address "{ Mind fixture Sol }")
(def config (watch/configuration [address "300000" "--margin" "50000" "--dry"]))
(def empty-state {:files {} :seen #{} :fired {}})
(defn row [payload] (json/generate-string payload))
(defn metadata [address time]
  (row {:type "session_meta" :payload {:timestamp time :base_instructions {:text (str "Other instructions\nMetaflow: " address "\nModel: example\n")}}}))
(defn flow-row [id]
  (row {:type "response_item" :payload {:type "message" :role "developer" :content [{:type "input_text" :text (str "Flow: " id)}]}}))
(defn tokens-row [n]
  (row {:type "event_msg" :payload {:type "token_count" :info {:last_token_usage {:input_tokens n}}}}))
(defn recording [address id time tokens]
  (str/join "\n" (concat [(metadata address time) (flow-row id)] (map tokens-row tokens) [""])))
(defn consume [state path content]
  (reduce #(watch/apply-reading config (watch/consume-line config path %1 %2)) state (str/split-lines content)))

(deftest arguments-and-readings
  (is (= 50000 (:margin config)))
  (is (= 300000 (:size config)))
  (doseq [args [[address "-1"] [address "bad"] [address "3" "--home"]
                [address "3" "--idle-seconds" "1"] [address "id" "3"]]]
    (is (thrown? Exception (watch/configuration args))))
  (is (= 350000 (watch/context-size (tokens-row 350000))))
  (is (nil? (watch/context-size "broken token_count")))
  (is (nil? (watch/context-size (row {:payload {:type "token_count" :info nil}})))))

(deftest only-header-identity
  (is (= {:flow-id "abcdef"} (watch/identity-part (watch/parse-row (flow-row "abcdef")))))
  (doseq [role ["user" "assistant" "tool"]]
    (is (= {} (watch/identity-part {:type "response_item" :payload {:type "message" :role role
                   :content [{:text "Flow: abcdef"}]}}))))
  (is (= {} (watch/identity-part {:type "response_item" :payload {:type "message" :role "developer"
                                 :content [{:text "An example: Flow: abcdef"}]}}))))

(deftest thresholds-follow-flows-and-preserve-once-accounting
  (let [sends (atom [])]
    (with-redefs [watch/send! (fn [cfg priority _ tokens] (swap! sends conj [(:flow-id cfg) priority tokens]) {:state "Delivered"})]
      (let [a (consume empty-state "a" (recording address "aaaaaa" "2026-11-18T20:00:00Z" [299999 300000 349999 350000 400000]))
            b (consume a "b" (recording address "bbbbbb" "2026-11-18T20:01:00Z" [350000 360000]))
            old (consume b "a" (tokens-row 450000))
            foreign (consume old "other" (recording "{ Mind other Sol }" "cccccc" "2026-11-18T20:02:00Z" [400000]))
            resumed (consume foreign "b-resumed" (recording address "bbbbbb" "2026-11-18T20:03:00Z" [400000]))
            old-reopened (consume resumed "a-resumed" (recording address "aaaaaa" "2026-11-18T20:04:00Z" [400000]))]
        (is (= [["aaaaaa" "MiddleAbrupt" 300000] ["aaaaaa" "HardAbrupt" 350000]
                ["bbbbbb" "MiddleAbrupt" 350000] ["bbbbbb" "HardAbrupt" 350000]] @sends))
        (is (= "b-resumed" (get-in old-reopened [:current :path])))
        (is (= #{:first :second} (get-in old-reopened [:fired "aaaaaa"])))))))

(deftest thresholds-follow-the-effective-codex-window
  (let [configuration (watch/configuration [address "300000" "--margin" "50000" "--model-window" "475000" "--dry"])
        limits (fn [window] (mapv :at (watch/thresholds (watch/effective-policy configuration window))))
        sample (fn [tokens window]
                 (row {:payload {:type "token_count" :info {:last_token_usage {:input_tokens tokens}
                                                            :model_context_window window}}}))]
    (is (= [300000 350000] (limits 475000)))
    (is (= [300000 350000] (limits 872000)))
    (is (= [300000 350000] (limits nil)))
    (is (= [163200 190400] (limits 258400)))
    (is (thrown? Exception (watch/configuration [address "300000" "--model-window" "258400"])))
    (let [state (reduce #(watch/consume-line configuration "a" %1 %2) empty-state
                        [(metadata address "2026-11-18T20:00:00Z") (flow-row "aaaaaa")
                         (sample 163199 258400) (sample 163200 nil)])
          result (atom nil)
          output (with-out-str (reset! result (watch/apply-reading configuration state)))]
      (is (= ["aaaaaa" 163200 258400] (:reading state)))
      (is (= #{:first} (get-in @result [:fired "aaaaaa"])))
      (is (str/includes? output "MiddleAbrupt.Order"))
      (is (not (str/includes? output "HardAbrupt.Order"))))))

(deftest flow-admission-is-not-a-delivery
  (is (nil? (watch/admission "SendRefused.Unknown.Address")))
  (is (= {:request "abcd" :cursor "200" :state "Queued"}
         (watch/admission "SendAccepted.{ abcd 200 Queued }")))
  (is (= {:request "abcd" :state "Uncertain"}
         (watch/admission "Failed.Admitted.{ abcd ReceiptUncertain }")))
  (is (= "Retryable.Kernel"
         (watch/delivery-state "abcd"
          "SendsObserved.{ store 203 [ { abcd 201 Sender.{ Mind core Sol } { Mind fixture Sol } Queued } { abcd 202 Sender.{ Mind core Sol } { Mind fixture Sol } Retryable.Kernel } { dcba 203 Sender.{ Mind core Sol } { Mind fixture Sol } Delivered } ] }")))
  (is (nil? (watch/delivery-state "abcd"
             "SendChanged.{ dcba 204 Sender.{ Mind core Sol } { Mind fixture Sol } Delivered }")))
  (is (= "Read"
         (watch/delivery-state "abcd"
          "SendChanged.{ abcd 204 Sender.{ Mind core Sol } { Mind fixture Sol } Read }")))
  (is (every? #(nil? (watch/terminal-state %)) ["Queued" "Attempting" "Uncertain" "Retryable.Kernel"]))
  (is (= :delivered (watch/terminal-state "Read")))
  (is (= :failed (watch/terminal-state "Failed.Taken"))))

(deftest pending-reminders-are-durable-and-not-duplicated
  (fs/with-temp-dir [directory {}]
    (let [path (str (fs/path directory "receipts.edn"))
          calls (atom [])
          released (promise)
          followed (promise)
          entry ["aaaaaa" :first]
          config (assoc config :dry false :flow-id (first entry) :receipt-file path
                        :receipts (atom {}) :receipt-failure #(deliver followed %))]
      (with-redefs [watch/current-flow (constantly "aaaaaa")
                    watch/send! (fn [_ _ _ tokens]
                                  (swap! calls conj tokens)
                                  {:request "abcd" :cursor "1" :state "Queued" :tokens tokens})
                    watch/observe-delivery! (fn [_] @released)
                    watch/log (fn [& args] (when (= "Delivered" (last args)) (deliver followed :done)))]
        (watch/react config #{} 300000)
        (is (= "Queued" (get-in @(:receipts config) [entry :state])))
        (let [restored (clojure.edn/read-string (slurp path))]
          (watch/react (assoc config :receipts (atom restored)) #{} 300010))
        (is (= [300000] @calls))
        (deliver released "Delivered")
        (is (= :done (deref followed 2000 :timeout)))
        (is (= "Delivered" (get-in (clojure.edn/read-string (slurp path)) [entry :state])))))))

(deftest complete-lines-and-partial-utf8
  (fs/with-temp-dir [directory {}]
    (let [path (str (fs/path directory "rollout-split.jsonl"))
          content (str (recording address "aaaaaa" "2026-11-18T20:00:00Z" [])
                       (tokens-row 350000))]
      (spit path content)
      (let [state (watch/consume-path config empty-state path)]
        (is (= 2 (get-in state [:files path :lines])))
        (is (empty? (:fired state)))
        (spit path "\n" :append true)
        (let [output (atom nil)
              printed (with-out-str (reset! output (watch/consume-path config state path)))]
          (is (= 2 (count (re-seq #"DRY would send:" printed))))
          (is (= @output (watch/consume-path config @output path)))
          (spit path (str (row {:payload {:text "śāstra"}}) "\n"))
          (is (= (fs/size path) (get-in (watch/consume-path config @output path) [:files path :offset]))))))))

(defn await-line [queue predicate seen]
  (loop []
    (let [line (.poll queue 15 TimeUnit/SECONDS)]
      (when-not line (throw (ex-info "timed out waiting for watcher output" {:seen @seen})))
      (swap! seen conj line)
      (if (predicate line) line (recur)))))

(deftest inotify-refresh-integration
  (let [inotify (or (System/getenv "INOTIFYWAIT") (some-> (fs/which "inotifywait") str))]
    (is (some? inotify) "Install inotify-tools or set INOTIFYWAIT for the integration test")
    (when inotify
      (fs/with-temp-dir [directory {}]
        (let [home (str directory)
              sessions (fs/create-dirs (fs/path directory "sessions" "today"))
              initial (str (fs/path sessions "rollout-initial.jsonl"))
              _ (spit initial (recording address "aaaaaa" "2026-11-18T20:00:00Z" [350000 350001]))
              child (process/process "bb" tool address "300000" initial "--margin" "50000" "--home" home
                                     "--inotifywait" inotify "--dry")
              queue (LinkedBlockingQueue.)
              seen (atom [])
              reader (future
                       (with-open [r (clojure.java.io/reader (:out child))]
                         (doseq [line (line-seq r)] (.put queue line))))]
          (try
            (await-line queue #(str/includes? % "DRY reading: 350001") seen)
            ;; A fresh nested date directory and an incomplete identity arrive
            ;; after startup; appending its newline must wake discovery.
            (let [nested (fs/create-dirs (fs/path directory "sessions" "tomorrow" "hour"))
                  next-file (str (fs/path nested "rollout-next.jsonl"))]
              (spit next-file (str (metadata address "2026-11-18T20:01:00Z") "\n" (flow-row "bbbbbb")))
              (spit next-file (str "\n" (tokens-row 350000) "\n" (tokens-row 350002) "\n") :append true)
              (await-line queue #(str/includes? % "DRY reading: 350002") seen)
              (spit initial (str (tokens-row 450000) "\n") :append true)
              (spit next-file (str (tokens-row 350003) "\n") :append true)
              (await-line queue #(str/includes? % "DRY reading: 350003") seen)
              (is (= 4 (count (filter #(str/includes? % "DRY would send:") @seen))))
              (is (= 2 (count (filter #(str/includes? % "HardAbrupt.Order") @seen))))
              (is (not-any? #(str/includes? % "DRY reading: 450000") @seen))
              (is (.isAlive (:proc child))))
            (finally (process/destroy-tree child) (future-cancel reader))))))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
(shutdown-agents)
