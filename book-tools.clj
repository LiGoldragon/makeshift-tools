#!/usr/bin/env bb
;; Book authoring uses the same module reader and grammar as checking/building.
(ns book-tools
  (:require [babashka.fs :as fs] [clojure.string :as str]))
(load-file (str (fs/path (fs/parent (fs/absolutize *file*)) "book-check.clj")))
(load-file (str (fs/path (fs/parent (fs/absolutize *file*)) "book-proposal.clj")))

(defn refuse [message] (throw (ex-info message {})))
(defn location [text]
  (let [[_ repository path] (re-matches book-check/module-location text)]
    (when-not (and repository (book-check/valid-location? repository path))
      (refuse (str "Invalid module location: " text)))
    [repository path]))
(defn lines-of [text] (vec (str/split text #"\n" -1)))
(defn checked [path text]
  (when-let [errors (seq (book-check/check-book text {:source-path path}))]
    (refuse (str "Book check refused: " (pr-str errors))))
  text)
(defn write-source [path text validate]
  ;; Validation finishes before the existing source is touched.
  (validate text)
  (let [target (fs/absolutize path)
        temporary (fs/create-temp-file {:dir (fs/parent target) :prefix ".book-author-"})]
    (try
      (spit (str temporary) text)
      (fs/move temporary target {:replace-existing true :atomic-move true})
      (finally (fs/delete-if-exists temporary)))))
(defn clean-title [title]
  (when (or (str/blank? title) (re-find #"[\r\n«»]" title))
    (refuse "Title must be one nonempty line without guillemets."))
  title)
(defn start-source [title modules]
  (clean-title title)
  (when-not (seq modules) (refuse "Start requires at least one module."))
  (when-not (= (count modules) (count (distinct modules))) (refuse "Repeated module view."))
  (str "Presentation.{ «" title "» Proposal }\n\n# " title "\n\n"
       (str/join "\n"
         (for [spec modules
               :let [[repository path] (location spec)
                     text (str/replace (book-check/read-context-module repository path) "\r\n" "\n")
                     markdown (str/ends-with? path ".md")
                     description (when markdown (book-check/module-description text))
                     body (if markdown
                            (str/trim (str/replace-first text #"\A---\n(?s:.*?)\n---(?:\n|\z)" ""))
                            (str/replace text #"\n+\z" ""))]]
           (str "## " spec "\n\n"
                (when description (str "::: description\n" description "\n:::\n\n"))
                ;; A fence keeps module headings distinct from book structure.
                (let [fence (apply str (repeat (max 3 (inc (reduce max 0 (map count (re-seq #"`+" body))))) "`"))]
                  (str fence (if markdown "markdown" (last (str/split path #"\."))) "\n" body "\n" fence "\n")))))))

(defn module-views [lines]
  (for [i (book-check/unfenced-indices lines 0 (count lines))
        :let [match (re-matches #"## (.+)" (lines i))
              spec (second match)]
        :when (and spec (re-matches book-check/module-location spec))]
    {:module spec :heading i :end (book-check/module-view-end lines i)}))
(defn occurrences [text anchor]
  (when (empty? anchor) (refuse "Anchor is empty."))
  (loop [from 0 found []]
    (let [at (str/index-of text anchor from)]
      (if at (recur (inc at) (conj found at)) found))))
(defn unique-position [text anchor module]
  (let [found (occurrences text anchor)]
    (when-not (= 1 (count found))
      (refuse (str "Anchor " (pr-str anchor) " occurs " (count found) " times in " module ".")))
    (first found)))
(defn next-number [text]
  (inc (reduce max 0 (map #(parse-long (second %)) (re-seq #"(?m)^#{2,4} Proposal (\d+)" text)))))
(defn signed [sign text]
  (str/join "\n" (map #(if (empty? %) sign (str sign " " %)) (str/split text #"\n" -1))))
(defn enclosing-fence [text at anchor-end]
  (loop [lines (str/split text #"\n" -1) offset 0 opened nil]
    (when-let [line (first lines)]
      (let [fence (re-find #"^ {0,3}(`{3,}|~{3,})(.*)$" line)
            close? (and opened fence (str/blank? (nth fence 2))
                        (= (first (:marker opened)) (first (second fence)))
                        (>= (count (second fence)) (count (:marker opened))))]
        (cond
          (and close? (<= (:start opened) at) (<= anchor-end (+ offset (count line))))
          {:start (:start opened) :end (+ offset (count line))}
          close? (recur (rest lines) (+ offset (count line) 1) nil)
          (and (nil? opened) fence) (recur (rest lines) (+ offset (count line) 1)
                                                  {:start offset :marker (second fence)})
          :else (recur (rest lines) (+ offset (count line) 1) opened))))))

(defn edit-source [source spec anchor mode replacement]
  (location spec)
  (let [lines (lines-of source)
        views (filter #(= spec (:module %)) (module-views lines))]
    (when-not (= 1 (count views)) (refuse (str "Module view " spec " occurs " (count views) " times.")))
    (let [{:keys [heading end]} (first views)
          view (str/join "\n" (subvec lines (inc heading) end))
          _ (unique-position view anchor spec)
          ;; Start fences the current module. Edit unwraps a Markdown view so
          ;; its Proposal heading is book structure, not literal module content.
          head (first (filter #(not (str/blank? (lines %))) (range (inc heading) end)))
          body-start (if (and head (= "::: description" (lines head)))
                       (inc (first (filter #(= ":::" (lines %)) (range (inc head) end))))
                       (inc heading))
          content-start (first (filter #(not (str/blank? (lines %))) (range body-start end)))
          opener (when content-start (re-matches #"(`{3,})(?:markdown|md|text)" (lines content-start)))
          content-end (when opener (last (filter #(= (first (rest opener)) (lines %)) (range (inc content-start) end))))
          editable (if content-end
                     (vec (concat (subvec lines (inc heading) content-start)
                                  (subvec lines (inc content-start) content-end)
                                  (subvec lines (inc content-end) end)))
                     (subvec lines (inc heading) end))
          view (str/join "\n" editable)
          at (unique-position view anchor spec)
          _ (when-not (str/ends-with? spec ".md") (refuse "Edit in place currently accepts Markdown module views."))
          _ (when (str/blank? replacement) (refuse "Edit text is empty."))
          _ (when (and (= mode :replace) (= replacement anchor)) (refuse "Replacement changes nothing."))
          anchor-line (count (str/split (subs view 0 at) #"\n" -1))
          _ (when (some #(and (<= (:heading %) anchor-line) (> (:end %) anchor-line))
                         (vals (book-check/source-in-place-proposals (str "## " spec "\n" view))))
              (refuse (str "Anchor " (pr-str anchor) " is already inside a proposal in " spec ".")))
          line-start (inc (or (str/last-index-of view "\n" at) -1))
          anchor-end (+ at (count anchor))
          line-end (or (str/index-of view "\n" anchor-end) (count view))
          fence (enclosing-fence view at anchor-end)
          scope-start (or (:start fence) line-start)
          scope-end (or (:end fence) line-end)
          old (subs view scope-start scope-end)
          proposed (str "#### Proposal " (next-number source) ": Edit · " spec "\n\n")
          suffix (subs view scope-end)
          separator (or (re-find #"^\n+" suffix) "")
          ;; Keep the original paragraph breaks on the authenticated side.
          tail (str (apply str (repeat (max 0 (dec (count separator))) "\n"))
                    (subs suffix (count separator)))
          new-body (case mode
                        :replace (str (subs old 0 (- at scope-start)) replacement
                                      (subs old (- anchor-end scope-start)))
                        :after (when fence (str (subs view scope-start line-end) "\n" replacement
                                                (subs view line-end scope-end))))
          changed (if (and (= mode :after) (nil? fence))
                    (str (subs view 0 line-end) (if (empty? separator) "\n" separator) proposed (signed "+" replacement) "\n" (subs suffix (count separator)))
                    (str (subs view 0 scope-start) proposed (signed "-" old) "\n"
                         (signed "+" new-body) "\n" tail))]
      (str/join "\n" (concat (subvec lines 0 (inc heading)) [changed] (subvec lines end))))))

(defn replace-source [source spec anchor old new]
  (when (empty? old) (refuse "Replacement old text is empty."))
  (let [lines (lines-of source)
        views (filter #(= spec (:module %)) (module-views lines))]
    (when-not (= 1 (count views)) (refuse (str "Module view " spec " occurs " (count views) " times.")))
    (let [{:keys [heading end]} (first views)
          view (str/join "\n" (subvec lines (inc heading) end))
          at (unique-position view anchor spec)
          begin (inc (or (str/last-index-of view "\n" at) -1))
          end (or (str/index-of view "\n" (+ at (count anchor))) (count view))
          anchored-line (subs view begin end)]
      (unique-position anchored-line old spec)
      (edit-source source spec anchored-line :replace (str/replace-first anchored-line old new)))))

(def usage
  (str "Usage: book-tools copy DIRECTORY MODULE... [--title TITLE] [--creator MODEL] [--kind Proposal|Review] [--root DIRECTORY]\n"
       "       book-tools new DIRECTORY REPOSITORY MODULE [--title TITLE] [--creator MODEL] [--kind Proposal|Review] [--root DIRECTORY]\n"
       "       book-tools remove DIRECTORY MODULE [--title TITLE] [--creator MODEL] [--kind Proposal|Review] [--root DIRECTORY]\n"
       "       book-tools creator DIRECTORY MODEL      (names the creator of a directory already made)\n"
       "       book-tools replace COPY OLD NEW      (OLD and NEW are files; - is standard input)\n"
       "       book-tools land DIRECTORY [--root DIRECTORY]\n"
       "       book-tools source DIRECTORY [--root DIRECTORY]      (prints the book source the builder reads)\n"
       "       book-tools start SOURCE TITLE MODULE... | edit SOURCE MODULE ANCHOR --after | --replace-old OLD --replace-new NEW"))

(defn split-options
  "The arguments with every `--name value` pair taken out: [arguments {:name value}]."
  [arguments]
  (loop [arguments arguments rest-arguments [] options {}]
    (if-let [argument (first arguments)]
      (if (str/starts-with? argument "--")
        (if (second arguments)
          (recur (drop 2 arguments) rest-arguments (assoc options (keyword (subs argument 2)) (second arguments)))
          (refuse (str argument " needs a value.")))
        (recur (rest arguments) (conj rest-arguments argument) options))
      [rest-arguments options])))

(defn read-replacement-text
  "The text of a file, or of standard input for -, without the one newline that ends it."
  [path]
  (let [text (if (= path "-") (slurp *in*) (slurp path :encoding "UTF-8"))]
    (if (str/ends-with? text "\n") (subs text 0 (dec (count text))) text)))

(defn proposal-command [command arguments]
  (let [[arguments options] (split-options arguments)
        root (or (:root options) book-proposal/repository-root)]
    (case command
      "copy" (let [[directory & modules] arguments]
               (doseq [path (book-proposal/copy! directory modules options root)] (println "Copied:" path)))
      "new" (let [[directory repository module] arguments]
              (when-not (and directory repository module) (refuse usage))
              (println "Begun:" (book-proposal/new! directory repository module options root)))
      "remove" (let [[directory module] arguments]
                 (when-not (and directory module) (refuse usage))
                 (println "Removed:" (book-proposal/remove! directory module options root)))
      "creator" (let [[directory creator] arguments]
                  (when-not (and directory creator) (refuse usage))
                  (println "Creator:" (book-proposal/set-creator! directory creator)))
      "replace" (let [[copy old new] arguments]
                  (when-not (and copy old new) (refuse usage))
                  (when (= "-" old new) (refuse "Only one of OLD and NEW may be standard input."))
                  (println "Replaced in:" (book-proposal/replace! copy (read-replacement-text old) (read-replacement-text new))))
      "source" (let [[directory] arguments]
                 (when-not directory (refuse usage))
                 (print (book-proposal/directory-source directory root)))
      "land" (let [[target] arguments
                   directory (if (fs/directory? target) target (str (fs/parent (fs/absolutize target))))]
               (doseq [line (book-proposal/land! directory root)] (println line))))))

(defn main [args]
  (try
    (let [[command path & more] args]
      (when-not path (refuse usage))
      (if (#{"copy" "new" "remove" "creator" "replace" "land" "source"} command)
        (do (proposal-command command (rest args)) 0)
      (do
      (case command
        "start" (do (when (fs/exists? path) (refuse (str "Source already exists: " path)))
                    (write-source path (start-source (first more) (rest more))
                      (fn [text]
                        (let [errors (remove #(= "missing-proposal" (:reason %)) (book-check/check-book text {:source-path path}))]
                          (when (seq errors) (refuse (str "Draft parse refused: " (pr-str errors))))))))
        "edit" (let [[spec anchor & options] more
                     source (slurp path)
                     edited (cond
                              (= options ["--after"])
                              (edit-source source spec anchor :after (str/replace (slurp *in*) #"\n\z" ""))
                              (and (= 4 (count options))
                                   (= "--replace-old" (first options))
                                   (= "--replace-new" (nth options 2)))
                              (replace-source source spec anchor (second options) (nth options 3))
                              :else (refuse "Edit requires MODULE ANCHOR --after or --replace-old OLD --replace-new NEW."))]
                 (write-source path edited (partial checked path)))
        (refuse (str "Unknown command: " command)))
      (println (str "Written: " path)) 0)))
    (catch Exception e (binding [*out* *err*] (println (ex-message e))) 1)))
(when (= *file* (System/getProperty "babashka.file")) (System/exit (main *command-line-args*)))
