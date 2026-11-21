;; book-label.clj gives a presentation source its page label, `Panel.1 · title`:
;; a Pascal-case name that says the subject, a dot, and the page's revision
;; counting from 1.
;; Run: bb book-label.clj SOURCE.md|DIRECTORY [--name Name] [--revise] [--labels LABELS.datom]
(ns book-label
  (:require [clojure.string :as str]))

(def default-labels-path "/home/li/primary/flows/book/labels.datom")
(def labels-environment-variable "BOOK_LABELS")

(def pascal-case-pattern #"[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)*")
(def label-pattern #"^([A-Z][A-Za-z0-9]*)\.(\d+) · ")
(def old-label-pattern #"^[A-Z]\d+ · ")
(def presentation-pattern #"^Presentation\.\{ «([^»]*)»(?: (\w+))? \}$")
;; A proposal directory's manifest holds the title on its first line.
(def manifest-pattern #"^Manifest\.\{ «([^»]*)»(?: (\w+))?$")
(def manifest-file "manifest.datom")

;; The configuration: one datom, the highest revision published per name.
;;   { Revisions [ { Panel 2 } { Purification 3 } ] }
(def revision-entry-pattern #"\{\s*([A-Z][A-Za-z0-9]*)\s+(\d+)\s*\}")

(defn pascal-case? [name] (boolean (and name (re-matches pascal-case-pattern name))))

(defn parse-config
  "The revisions of a configuration, an ordered vector of [name revision]."
  [text]
  (vec (for [[_ name revision] (re-seq revision-entry-pattern text)]
         [name (parse-long revision)])))

(defn config-text [revisions]
  (str "{ Revisions ["
       (if (empty? revisions) "" (str " " (str/join " " (map (fn [[n r]] (str "{ " n " " r " }")) revisions)) " "))
       "] }\n"))

(defn recorded-revision [revisions name]
  (or (some (fn [[n r]] (when (= n name) r)) revisions) 0))

(defn record-revision
  "The revisions with the name's highest revision at least `revision`."
  [revisions name revision]
  (if (some #(= name (first %)) revisions)
    (mapv (fn [[n r]] (if (= n name) [n (max r revision)] [n r])) revisions)
    (conj revisions [name revision])))

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
  (some (fn [l] (when-let [m (or (re-find presentation-pattern l) (re-find manifest-pattern l))]
                  {:title (m 1) :kind (or (m 2) "Proposal")}))
        (take-while #(not (re-find #"^#" %)) (remove str/blank? lines))))

(defn existing-label
  "The label of the new form a source carries in its head title or its H1, as
  {:name :revision}, or nil. An old-form label (`V7 · `) is no label."
  [lines]
  (let [h1 (some #(when (and % (str/starts-with? % "# ")) (subs % 2)) (outside-fences lines))]
    (some->> (or (some->> (:title (presentation lines)) (re-find label-pattern))
                 (some->> h1 (re-find label-pattern)))
             ((fn [m] {:name (m 1) :revision (parse-long (m 2))})))))

(defn label-text [{:keys [name revision]}] (str name "." revision))

(defn- titled [label title]
  (str label " · " (-> title (str/replace-first label-pattern "") (str/replace-first old-label-pattern ""))))

(defn- relabel-lines [lines label]
  (let [fences (outside-fences lines)
        h1 (first (keep-indexed (fn [i l] (when (and l (str/starts-with? l "# ")) i)) fences))
        head (first (keep-indexed (fn [i l] (when (or (re-find presentation-pattern l) (re-find manifest-pattern l)) i)) lines))]
    (cond-> lines
      head (update head (fn [l] (str/replace-first l #"«([^»]*)»" (fn [[_ t]] (str "«" (titled label t) "»")))))
      h1 (update h1 (fn [l] (str "# " (titled label (subs l 2))))))))

(defn label-source
  "Label a source: its head title and its H1, or the H1 alone of a source with
  no Presentation line. Returns {:label \"Panel.1\" :source text :config text :new? bool}.
  options: {:name \"Panel\" :revise bool}.
  A source that carries a Name.N label keeps it and leaves the configuration as
  it was; with :revise its revision is raised by one and recorded. A source
  with no label of the new form takes :name, one more than the highest revision
  recorded for it, or 1."
  [source config-text-in {:keys [name revise]}]
  (let [revisions (parse-config config-text-in)
        lines (source-lines source)
        existing (existing-label lines)]
    (when-not (or (presentation lines) (some #(and % (str/starts-with? % "# ")) (outside-fences lines)))
      (throw (ex-info "the source has no title to label" {})))
    (let [labelled (fn [label recorded]
                     {:label (label-text label)
                      :source (str/join "\n" (relabel-lines lines (label-text label)))
                      :config (if recorded (config-text (record-revision revisions (:name label) (:revision label))) config-text-in)
                      :new? (boolean recorded)})]
      (cond
        (and existing (not revise)) (labelled existing false)
        existing (labelled (update existing :revision inc) true)
        :else
        (do
          (when-not name
            (throw (ex-info "the source carries no label of the form Name.N; give --name Name" {})))
          (when-not (pascal-case? name)
            (throw (ex-info (str "the name " name " is not Pascal case") {})))
          (labelled {:name name :revision (inc (recorded-revision revisions name))} true))))))

(defn label-files!
  "Label the source file, or the manifest of a proposal directory, counting in the
  configuration file under its lock."
  [source-path labels-path options]
  (let [source-path (if (.isDirectory (java.io.File. (str source-path)))
                      (str source-path "/" manifest-file)
                      source-path)]
  (with-open [file (java.io.RandomAccessFile. (str labels-path) "rw")
              lock (.lock (.getChannel file))]
    (let [text (slurp (str labels-path) :encoding "UTF-8")
          result (label-source (slurp (str source-path) :encoding "UTF-8") text options)]
      (when (:new? result)
        (spit (str labels-path) (:config result) :encoding "UTF-8"))
      (spit (str source-path) (:source result) :encoding "UTF-8")
      result))))

(defn- option [args flag]
  (let [i (.indexOf args flag)] (when (>= i 0) (get args (inc i)))))

(defn -main [& args]
  (let [args (vec args)
        labels-path (or (option args "--labels") (System/getenv labels-environment-variable) default-labels-path)
        name (option args "--name")
        revise (boolean (some #{"--revise"} args))
        source-path (first (remove (set (concat ["--labels" "--name" "--revise"] (keep #(option args %) ["--labels" "--name"]))) args))]
    (when-not source-path
      (binding [*out* *err*] (println "usage: bb book-label.clj SOURCE.md|DIRECTORY [--name Name] [--revise] [--labels LABELS.datom]"))
      (System/exit 2))
    (try (println (:label (label-files! source-path labels-path {:name name :revise revise})))
         (catch Exception e
           (binding [*out* *err*] (println (str "book-label: " (ex-message e))))
           (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
