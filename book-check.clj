;; Structural check of a book source: `bb book-check.clj /abs/SOURCE.md`.
;; Ported from book-check.mjs. check-book: source text -> vector of
;; {:line n :reason "..."} errors, sorted by line. This checks source
;; structure, not the merits of a proposed crystallization.
(ns book-check
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; A proposal names its location in its heading: "Proposal N: <repo> › <path>",
;; optionally after a title and " · ", optionally ending ", a new file" or ", a deleted file".
(def located #"^Proposal \d+: (?:.+ · )?[^\s›·]+ › [^\s,]+(?:, a (?:new|deleted) file)?\z")
;; A removal is a change: removed lines, or the whole file deleted.
(defn change? [line]
  (boolean (or (re-find #"^[+-] .*\S" line)
               (re-find #"^\*\*(?:Archive|Destroy)\*\*" line))))
;; The three context repositories hold only their authored modules; any other
;; repository of the living holds any file, named by a relative path.
(def context-repository #"^(?:psyche|mind|field)-belief\z")
(def context-module #"^(?:spirit|intent|vision|knowledge|compensation|inquiry|role|raw)/[A-Za-z0-9][A-Za-z0-9_.-]*\.md\z")
(def repository-name #"^[A-Za-z0-9][A-Za-z0-9_.-]*\z")
(def file-path #"^(?!/)(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\z|^(?!/)(?:[A-Za-z0-9_.-]+/)*\.[A-Za-z0-9_-][A-Za-z0-9_.-]*\z")

(defn valid-location? [repository relative-path]
  (boolean
   (and (re-find repository-name repository)
        (if (re-find context-repository repository)
          (re-find context-module relative-path)
          (and (re-find file-path relative-path)
               (not-any? #(or (= % ".") (= % "..")) (str/split relative-path #"/" -1)))))))

(def module-location #"^([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\s,›·]+)\z")
;; The renderer recognizes module views only as level-two headings, and
;; proposal headings only at levels two and three.
(def next-section #"^ {0,3}(?:##\s+(?:Proposal\b|[A-Za-z0-9][A-Za-z0-9_.-]* › [^\s,›·]+\s*\z)|###\s+Proposal\b)")

(defn read-context-module
  "A file of one of the living's repositories as origin main has it; a context
  repository gives only its authored modules, never a generated skill."
  [repository relative-path]
  (when-not (valid-location? repository relative-path)
    (throw (ex-info "module-location-invalid" {})))
  (let [root (str "/git/github.com/LiGoldragon/" repository)
        git (fn [& args]
              (let [r (deref (process/process (into ["git" "-C" root] args)
                                              {:out :string :err :string :in ""})
                             15000 ::timeout)]
                (when (or (= r ::timeout) (not (zero? (:exit r))))
                  (throw (ex-info "git failed" {:args args})))
                (:out r)))
        ;; Qualify main now; an unavailable revision refuses instead of using a stale copy.
        revision (first (str/split (str/trim (git "ls-remote" "origin" "refs/heads/main")) #"\s+"))]
    (when (or (not (re-find #"^[a-f0-9]{40}\z" (or revision "")))
              (not (re-find #"^100(?:644|755) blob " (git "ls-tree" revision "--" relative-path))))
      (throw (ex-info "module-source-unavailable" {})))
    (git "show" (str revision ":" relative-path))))

(def ^:private sign-prefix "(?:[+-] |  )?")

(defn- fence-close-re [ch size]
  (re-pattern (str "^" sign-prefix " {0,3}" (java.util.regex.Pattern/quote ch) "{" size ",}\\s*\\z")))

(defn- diagram-fence?
  "An unlabelled fence whose body draws boxes or arrows; prose is not a diagram."
  [lines open [_ fence-str]]
  (let [close (fence-close-re (subs fence-str 0 1) (count fence-str))
        body (take-while #(not (re-find close %)) (drop (inc open) lines))]
    (boolean (re-find #"(?m)[─-╿]|\+-{2,}\+|^\s*[|v^]\s*$|-->|<--" (str/join "\n" body)))))

(defn- module-body [text]
  (-> text (str/replace "\r\n" "\n") (str/replace-first #"\A---\n(?s:.*?)\n---\n" "") str/trim))

(defn- code-points [s] (.codePointCount ^String s 0 (count s)))

(defn check-book
  "Source text -> vector of {:line n :reason \"...\"}. Option :read-module is
  (fn [repository path]) -> file text; it throws when the module is unavailable."
  ([source] (check-book source {}))
  ([source {:keys [read-module] :or {read-module read-context-module}}]
   (let [lines (vec (str/split (str/replace source "\r\n" "\n") #"\n" -1))
         st (atom {:errors [] :fence nil :section nil :in-comment false
                   :proposals 0 :title false :presentation false :kind "Proposal" :signature nil :shown #{}})
         err! (fn [line reason] (swap! st update :errors conj {:line line :reason reason}))
         section #(:section @st)
         set-section! (fn [f] (swap! st update :section f))
         mark-changed! (fn [] (set-section! #(assoc % :changed true)))
         finish! (fn []
                   (let [s (section)]
                     (when (and s (not (:changed s)) (not (:deleted s)))
                       (err! (:line s) "no-change-in-proposal"))
                     (swap! st assoc :section nil)))
         body! (fn [line number]
                 ;; The location lives in the heading; a File: line in the body is the retired form.
                 (when (re-find #"^File:" line) (err! number "file-line-in-body"))
                 (when (and (section) (re-find #"^\*\*Comment\*\*" line))
                   ;; The machine's comment comes first: before every change and alternative of its proposal, once.
                   (let [s (section)]
                     (cond (:comment s) (err! number "second-comment-in-proposal")
                           (:changed s) (err! number "comment-after-change"))
                     (set-section! #(assoc % :comment true))))
                 (when (and (section) (change? line)) (mark-changed!)))
         handle-module-view!
         (fn [i number heading-text [_ repo path]]
           (let [end (loop [end (inc i) literal nil]
                       (if (>= end (count lines))
                         end
                         (let [text (lines end)]
                           (if literal
                             (recur (inc end)
                                    (if (re-find (fence-close-re (:char literal) (:size literal)) text) nil literal))
                             (if (or (re-find next-section text) (re-find #"^<!-- to-the-living:end -->\z" text))
                               end
                               (let [open (re-find #"^ {0,3}(`{3,}|~{3,})" text)]
                                 (recur (inc end)
                                        (when open {:char (subs (second open) 0 1) :size (count (second open))}))))))))
                 raw (str/join "\n" (subvec lines (inc i) end))
                 shown (str/trim raw)
                 markdown (str/ends-with? path ".md")
                 ;; A markdown module may sit in a markdown or text fence; any other file is the
                 ;; content of its one fence, in its language, kept to the character.
                 literal (if markdown
                           (re-find #"^(`{3,}|~{3,})(?:markdown|md|text)\s*\n((?s:.*))\n\1\z" shown)
                           (re-find #"^(`{3,}|~{3,})[^\n`]*\n((?s:.*))\n\1\z" (str/trim raw)))
                 shown (if literal (nth literal 2) shown)]
             (try
               (let [file (str/replace (read-module repo path) "\r\n" "\n")]
                 (when (not= shown (if markdown (module-body file) (str/replace file #"\n+\z" "")))
                   (err! number "context-module-differs")))
               (catch Throwable _ (err! number "context-module-unavailable")))
             (swap! st update :shown into (range (inc i) end))))]
     (doseq [i (range (count lines))]
       (let [line (lines i)
             number (inc i)
             {:keys [fence in-comment shown]} @st]
         (cond
           (contains? shown i) nil

           fence
           (cond
             (re-find (fence-close-re (:char fence) (:size fence)) line)
             ;; Markdown is never shown in a code block: an unsigned plain-text fence
             ;; is built as <pre>, so Markdown headings in it would show as code.
             (do (when (and (:plain fence)
                            (not-any? change? (:lines fence))
                            (not-any? #(re-find #"^- \S" %) (:lines fence))
                            (some #(re-find #"^#{1,6} \S" %) (:lines fence)))
                   (err! (:line fence) "markdown-in-code-block"))
                 (swap! st assoc :fence nil))

             (:code fence)
             (do (swap! st update-in [:fence :lines] conj line)
                 ;; A removed line is quoted as it is; the width limit binds proposed code only.
                 (when (and (not= "mermaid" (:language fence)) (not (str/starts-with? line "- "))
                            (> (code-points line) 52))
                   (err! number "code-over-52-columns"))
                 (when (and (section) (re-find #"^[+-] .*\S" line)) (mark-changed!)))

             :else
             (do (swap! st update-in [:fence :lines] conj line)
                 (body! line number)))

           in-comment
           (when (str/includes? line "-->") (swap! st assoc :in-comment false))

           (str/starts-with? (str/triml line) "<!--")
           (swap! st assoc :in-comment (not (str/includes? line "-->")))

           :else
           (let [opening (re-find (re-pattern (str "^(" sign-prefix ") {0,3}(`{3,}|~{3,})\\s*([^ ]*)")) line)
                 opening (when opening [(nth opening 0) (nth opening 2) (nth opening 3) (nth opening 1)])]
             (if opening
               (let [language (str/lower-case (nth opening 2))]
                 ;; Outside a section a fence is allowed only as a diagram: an unlabelled
                 ;; sketch or a labelled mermaid figure (a context figure).
                 (when (and (not (section))
                            (not (or (= language "mermaid")
                                     (and (= language "") (diagram-fence? lines i opening)))))
                   (err! number "text-outside-proposal-without-module"))
                 ;; A signed opener is a change line, as the fence it opens is read.
                 (when (and (section) (re-find #"^[+-] " (nth opening 3))) (mark-changed!))
                 (swap! st assoc :fence
                        {:char (subs (second opening) 0 1) :size (count (second opening)) :line number
                         :language language
                         :code (not (#{"text" "prose" "markdown" "md"} language))
                         :plain (boolean (#{"" "text" "txt" "prose"} language))
                         :lines []}))
               ;; Inside a proposal, a line prefixed "  ", "+ " or "- " is module content, never book structure.
               (let [heading (when-not (and (section) (re-find #"^(?:  |\+ |- )" line))
                               (re-find #"^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*\z" line))]
                 (if heading
                   (let [level (count (nth heading 1)) text (nth heading 2)]
                     (when (and (section) (<= level (:level (section)))) (finish!))
                     (cond
                       (re-find #"(?i)^Proposal\b" text)
                       (do (finish!)
                           (if (re-find located text)
                             (swap! st #(-> % (update :proposals inc)
                                            (assoc :section {:line number :level level :changed false
                                                             :deleted (str/ends-with? text ", a deleted file")
                                                             :comment false})))
                             (err! number "proposal-heading-without-location")))

                       (not (section))
                       (let [account? (= "Account" (:kind @st))
                             location (when (= level 2) (re-find module-location text))]
                         (cond
                           (and location (valid-location? (nth location 1) (nth location 2)))
                           (handle-module-view! i number text location)

                           (and (= level 1) (not (re-find module-location text))
                                (not (:title @st)) (zero? (:proposals @st)) (empty? (:shown @st)))
                           (swap! st assoc :title true)

                           ;; An account holds headings of its own.
                           account? nil

                           :else (err! number "heading-outside-proposal-without-module")))))
                   (do
                     (when (and (not (section)) (str/blank? line) (= :open (:signature @st)))
                       (swap! st assoc :signature :done))
                     (when (and (not (section)) (not (str/blank? line)))
                       (cond
                         (and (not (:presentation @st)) (not (:title @st)) (zero? (:proposals @st))
                              (empty? (:shown @st)) (re-find #"^Presentation\.\{ «[^»]*»(?: \w+)? \}\z" line))
                         (let [kind (second (re-find #"»(?: (\w+))? \}\z" line))]
                           (swap! st assoc :presentation true :kind (or kind "Proposal"))
                           (when-not (#{"Proposal" "Review" "Account"} (or kind "Proposal"))
                             (err! number "presentation-kind-unknown")))

                         ;; The signature line: the paragraph under the title naming the creating
                         ;; model and each helper's help.
                         (or (= :open (:signature @st))
                             (and (:title @st) (nil? (:signature @st)) (zero? (:proposals @st))
                                  (empty? (:shown @st)) (str/starts-with? line "Created by")))
                         (swap! st assoc :signature :open)

                         ;; An account holds prose of its own.
                         (= "Account" (:kind @st)) nil

                         :else (err! number "text-outside-proposal-without-module")))
                     (when (re-find #"^ {0,3}>" line) (err! number "quote-block-in-book"))
                     (body! line number)))))))))
     (finish!)
     (when (and (zero? (:proposals @st)) (not= "Account" (:kind @st))) (err! 1 "missing-proposal"))
     (when-let [f (:fence @st)] (err! (:line f) "unclosed-code-fence"))
     (vec (sort-by :line (:errors @st))))))

(defn main [args]
  (cond
    (or (not= 1 (count args)) (not (fs/absolute? (first args))))
    (do (binding [*out* *err*]
          (println (json/generate-string {:errors [{:reason "one-absolute-source-path-required"}]})))
        2)
    :else
    (let [source (try (slurp (first args)) (catch Exception _ nil))]
      (if (nil? source)
        (do (binding [*out* *err*]
              (println (json/generate-string {:errors [{:reason "source-unreadable"}]})))
            2)
        (let [errors (check-book source)]
          (if (seq errors)
            (do (binding [*out* *err*] (println (json/generate-string {:errors errors}))) 1)
            (do (println (json/generate-string {:checked true :source (first args)})) 0)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (main *command-line-args*)))
