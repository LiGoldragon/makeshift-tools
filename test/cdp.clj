;; Headless chromium driven over the Chrome DevTools Protocol on a WebSocket
;; (java.net.http), shared by the browser tests. Load with load-file.
(ns cdp
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket$Listener)
           (java.util.concurrent CompletableFuture TimeUnit)))

(defn connect
  "Open a WebSocket to url. Returns {:send f :pending atom :events atom :socket ws}."
  [url]
  (let [pending (atom {}) events (atom {}) buffer (StringBuilder.)
        opened (CompletableFuture.)
        handle (fn [text]
                 (let [message (json/parse-string text)]
                   (if-let [id (get message "id")]
                     (when-let [p (get @pending id)]
                       (swap! pending dissoc id)
                       (deliver p (if (contains? message "error")
                                    {:error (get message "error")}
                                    {:result (get message "result")})))
                     (let [key (str (get message "sessionId") ":" (get message "method"))]
                       (when-let [p (get @events key)]
                         (swap! events dissoc key)
                         (deliver p (get message "params")))))))
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ws 1) (.complete opened true))
                   (onText [_ ws data last?]
                     (.append buffer (str data))
                     (when last? (let [t (str buffer)] (.setLength buffer 0) (handle t)))
                     (.request ws 1)
                     nil)
                   (onClose [_ ws status reason] nil)
                   (onError [_ ws error] (.completeExceptionally opened error)))
        socket (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient)) (URI/create url) listener))
        _ (.get opened 10 TimeUnit/SECONDS)
        counter (atom 0)]
    {:socket socket :pending pending :events events
     :request (fn [method params session-id]
                (let [id (swap! counter inc)
                      p (promise)]
                  (swap! pending assoc id p)
                  (.join (.sendText socket
                                    (json/generate-string (cond-> {"id" id "method" method "params" params}
                                                            session-id (assoc "sessionId" session-id)))
                                    true))
                  (let [r (deref p 30000 {:error "no reply in 30 seconds"})]
                    (if (:error r)
                      (throw (ex-info (json/generate-string (:error r)) {}))
                      (:result r)))))}))

(defn chromium-binary []
  (or (System/getenv "CHROMIUM")
      (some-> (fs/which "chromium") str)))

(defn launch [profile config cache directory binary]
  (process/process [binary "--headless" "--no-sandbox" "--disable-gpu" "--disable-dev-shm-usage"
                    "--no-first-run" "--no-default-browser-check" "--disable-background-networking"
                    "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost"
                    "--remote-debugging-address=127.0.0.1" "--remote-debugging-port=0"
                    (str "--user-data-dir=" profile) "about:blank"]
                   {:out :ignore :err :string
                    :extra-env {"HOME" (str directory) "XDG_CONFIG_HOME" (str config) "XDG_CACHE_HOME" (str cache)}}))

(defn endpoint [profile browser]
  (let [file (fs/path profile "DevToolsActivePort")
        deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (let [[port route] (when (fs/exists? file) (str/split-lines (str/trim (slurp (str file)))))]
        (cond (and port route) (str "ws://127.0.0.1:" port route)
              (not (.isAlive ^Process (:proc browser))) (throw (ex-info (str "Chromium exited: " (:exit @browser)) {}))
              (> (System/currentTimeMillis) deadline) (throw (ex-info "no DevToolsActivePort in 60 seconds" {}))
              :else (do (Thread/sleep 50) (recur)))))))

(defn with-page
  "Start chromium, open one page target and call (f request session events);
  the browser is stopped after."
  [binary f]
  (let [directory (fs/create-temp-dir {:prefix "book-browser-"})
        profile (fs/create-dirs (fs/path directory "profile"))
        config (fs/create-dirs (fs/path directory "config"))
        cache (fs/create-dirs (fs/path directory "cache"))
        browser (launch profile config cache directory binary)
        connection (volatile! nil)]
    (try
      (let [conn (connect (endpoint profile browser))
            _ (vreset! connection conn)
            request (:request conn)
            {target "targetId"} (request "Target.createTarget" {"url" "about:blank"} nil)
            {session "sessionId"} (request "Target.attachToTarget" {"targetId" target "flatten" true} nil)]
        (request "Page.enable" {} session)
        (f request session (:events conn)))
      (finally
        (some-> @connection :socket (.abort))
        (.destroyForcibly ^Process (:proc browser))
        (fs/delete-tree directory)))))

(defn navigate
  "Load url in the session and wait for its load event."
  [request session events url]
  (let [loaded (promise)]
    (swap! events assoc (str session ":Page.loadEventFired") loaded)
    (request "Page.navigate" {"url" url} session)
    (deref loaded 30000 nil)))

(defn evaluate
  "The value of a page expression, awaited when it is a promise."
  [request session expression]
  (let [r (request "Runtime.evaluate" {"awaitPromise" true "returnByValue" true "expression" expression} session)]
    (when (get r "exceptionDetails")
      (throw (ex-info (str "page script failed: " (json/generate-string (get r "exceptionDetails"))) {})))
    (get-in r ["result" "value"])))
