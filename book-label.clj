;; book-label.clj gives a presentation source its page label, `V7 · title`.
;; Run: bb book-label.clj SOURCE.md [--labels LABELS.datom]
(ns book-label
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))

(def default-labels-path "/home/li/primary/flows/book/labels.datom")
(def labels-environment-variable "BOOK_LABELS")

(def label-pattern #"^([A-Z])(\d+) · ")
(def presentation-pattern #"^Presentation\.\{ «([^»]*)»(?: (\w+))? \}$")
(def proposal-heading-pattern #"^#{3,4} Proposal \d+: .* · ([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\s,›·]+)\s*$")
(def new-module-heading-pattern #"^## ([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\s,›·]+), a new file\s*$")
(def move-end-pattern #"^(?:from|to): ([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\s,›·]+)\s*$")

;; The configuration: one datom, the labels with their next numbers and the
;; modules that make a proposal a vision one.
;;   { Labels [ { V VisionProposal 1 } ... ] VisionModules { psyche-belief [ spirit intent vision ] } }
(def label-entry-pattern #"\{\s*([A-Z])\s+(\w+)\s+(\d+)\s*\}")
(def vision-modules-pattern #"VisionModules\s*\{\s*(\S+)\s*\[([^\]]*)\]\s*\}")

(defn parse-config [text]
  (let [[_ repository directories] (re-find vision-modules-pattern text)]
    {:labels (vec (for [[_ letter kind next] (re-seq label-entry-pattern text)]
                    {:letter letter :kind kind :next (parse-long next)}))
     :vision-repository repository
     :vision-directories (vec (str/split (str/trim (or directories "")) #"\s+"))}))

(defn source-lines [source] (vec (str/split source #"\n" -1)))

(defn outside-fences
  "The lines of a source with the lines of code fences replaced by nil."
  [lines]
  (second
   (reduce (fn [[fence out] l]
             (let [open (second (re-find #"^ {0,3}(`{3,}|~{3,})" l))]
               (cond
                 (and fence open (str/starts-with? open fence)) [nil (conj out nil)]
                 fence [fence (conj out nil)]
                 open [open (conj out nil)]
                 :else [nil (conj out l)])))
           [nil []] lines)))

(defn presentation [lines]
  (some (fn [l] (when-let [m (re-find presentation-pattern l)]
                  {:title (m 1) :kind (or (m 2) "Proposal")}))
        (take-while #(not (re-find #"^#" %)) (remove str/blank? lines))))

(defn changed-modules
  "The modules a source's proposals change: each proposal heading's, each new
  module's, each move's two ends."
  [lines]
  (->> (outside-fences lines)
       (keep (fn [l] (when l
                       (when-let [m (or (re-find proposal-heading-pattern l)
                                        (re-find new-module-heading-pattern l)
                                        (re-find move-end-pattern l))]
                         [(m 1) (m 2)]))))
       distinct vec))

(defn vision-module? [config [repository path]]
  (and (= repository (:vision-repository config))
       (some #(str/starts-with? path (str % "/")) (:vision-directories config))))

(defn page-kind
  "VisionProposal, KnowledgeProposal, Account or Review."
  [source config]
  (let [lines (source-lines source)
        kind (:kind (presentation lines))]
    (case kind
      "Account" "Account"
      "Review" "Review"
      (let [modules (changed-modules lines)]
        (if (and (seq modules) (some #(vision-module? config %) modules))
          "VisionProposal"
          "KnowledgeProposal")))))

(defn existing-label
  "The label a source already carries in its head title or its H1, or nil."
  [lines]
  (let [h1 (some #(when (and % (str/starts-with? % "# ")) (subs % 2)) (outside-fences lines))]
    (or (some->> (:title (presentation lines)) (re-find label-pattern) (#(str (% 1) (% 2))))
        (some->> h1 (re-find label-pattern) (#(str (% 1) (% 2)))))))

(defn- titled [label title]
  (if (re-find label-pattern title) title (str label " · " title)))

(defn- relabel-lines [lines label]
  (let [fences (outside-fences lines)
        h1 (first (keep-indexed (fn [i l] (when (and l (str/starts-with? l "# ")) i)) fences))
        head (first (keep-indexed (fn [i l] (when (re-find presentation-pattern l) i)) lines))]
    (cond-> lines
      head (update head (fn [l] (str/replace-first l #"«([^»]*)»" (fn [[_ t]] (str "«" (titled label t) "»")))))
      h1 (update h1 (fn [l] (str "# " (titled label (subs l 2))))))))

(defn label-source
  "Label a source: its head title and its H1, or the H1 alone of a source with
  no Presentation line, which is a Proposal. Returns {:label \"V7\" :source text :config text :new? bool}.
  A source that carries a label keeps it, takes no number, and leaves the
  configuration as it was."
  [source config-text]
  (let [config (parse-config config-text)
        lines (source-lines source)]
    (when-not (or (presentation lines) (some #(and % (str/starts-with? % "# ")) (outside-fences lines)))
      (throw (ex-info "the source has no title to label" {})))
    (if-let [label (existing-label lines)]
      {:label label :source (str/join "\n" (relabel-lines lines label)) :config config-text :new? false}
      (let [kind (page-kind source config)
            entry (first (filter #(= kind (:kind %)) (:labels config)))]
        (when-not entry
          (throw (ex-info (str "the configuration has no label for " kind) {})))
        (let [label (str (:letter entry) (:next entry))]
          {:label label
           :source (str/join "\n" (relabel-lines lines label))
           :config (str/replace config-text
                                (re-pattern (str "\\{\\s*" (:letter entry) "\\s+" kind "\\s+" (:next entry) "\\s*\\}"))
                                (str "{ " (:letter entry) " " kind " " (inc (:next entry)) " }"))
           :new? true})))))

(defn label-files!
  "Label the source file, counting in the configuration file under its lock."
  [source-path labels-path]
  (with-open [file (java.io.RandomAccessFile. (str labels-path) "rw")
              lock (.lock (.getChannel file))]
    (let [config-text (slurp (str labels-path) :encoding "UTF-8")
          result (label-source (slurp (str source-path) :encoding "UTF-8") config-text)]
      (when (:new? result)
        (spit (str labels-path) (:config result) :encoding "UTF-8"))
      (spit (str source-path) (:source result) :encoding "UTF-8")
      result)))

(defn -main [& args]
  (let [args (vec args)
        li (.indexOf args "--labels")
        labels-path (cond (>= li 0) (get args (inc li))
                          (System/getenv labels-environment-variable) (System/getenv labels-environment-variable)
                          :else default-labels-path)
        source-path (first (remove #{"--labels" labels-path} args))]
    (when-not source-path
      (binding [*out* *err*] (println "usage: bb book-label.clj SOURCE.md [--labels LABELS.datom]"))
      (System/exit 2))
    (try (println (:label (label-files! source-path labels-path)))
         (catch Exception e
           (binding [*out* *err*] (println (str "book-label: " (ex-message e))))
           (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
