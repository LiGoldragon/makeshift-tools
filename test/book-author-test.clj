(ns book-author-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str] [babashka.fs :as fs]
            [babashka.process :as process]))
(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str root "/book-tools.clj"))
(load-file (str root "/book-build.clj"))
(def modules {["mind-belief" "vision/first/0.md"]
              "---\ndescription: \"First module.\"\n---\n# First\n\nThe old anchor is here.\n\nA repeated line.\n\nA repeated line.\n"
              ["mind-belief" "vision/second/0.md"]
              "---\ndescription: 'Second module.'\n---\n# Second\n\nThe old anchor is here.\n\n## Inner heading\n\nSecond ending.\n"
              ["repo" "src/code.rs"] "fn main() {}\n"})
(def first-module "mind-belief › vision/first/0.md")
(def second-module "mind-belief › vision/second/0.md")
(defn reader [repository path] (or (modules [repository path]) (throw (ex-info "missing fixture" {}))))
(defn source [] (book-tools/start-source "Draft" [first-module second-module]))
(defn checked [source] (book-check/check-book source {:read-module reader}))
(defn render [source] (binding [book-build/*highlight* false] (book-build/build source "")))

(deftest start-is-a-parsed-proposal-draft-with-authenticated-views
  (with-redefs [book-check/read-context-module reader]
    (let [draft (source)]
      (is (str/starts-with? draft "Presentation.{ «Draft» Proposal }"))
      (is (= ["missing-proposal"] (mapv :reason (checked draft))))
      (is (= 2 (count (re-seq #"::: description" draft))))
      (is (str/includes? draft "First module."))
      (is (str/includes? draft "Second module."))
      (is (str/includes? draft "## Inner heading"))
      (is (str/includes? (render draft) "class=\"module diff\"")))
    (is (= ["missing-proposal"]
           (mapv :reason (checked (book-tools/start-source "Code" ["repo › src/code.rs"])))))
    (is (thrown-with-msg? Exception #"Repeated module" (book-tools/start-source "D" [first-module first-module])))
    (is (thrown? Exception (book-tools/start-source "D»" [first-module])))
    (is (thrown? Exception (book-tools/start-source "D" ["mind-belief › ../secret.md"])))))

(deftest both-edits-keep-the-authenticated-module-and-other-views
  (with-redefs [book-check/read-context-module reader]
    (doseq [[mode new] [[:after "New paragraph.\n\nA second paragraph."]
                       [:replace "new anchor"]]]
      (let [draft (source)
            edited (book-tools/edit-source draft first-module "old anchor" mode new)
            second-start (str/index-of draft (str "## " second-module))]
        (is (= [] (checked edited)))
        (is (str/includes? edited (subs draft second-start)) "other module stays byte-identical")
        (is (str/includes? edited "Presentation.{ «Draft» Proposal }"))
        (is (str/includes? (render edited) (if (= mode :replace) "<ins>" "class=\"add\"")))
        (when (= mode :replace)
          (is (str/includes? edited "- The old anchor is here."))
          (is (str/includes? edited "+ The new anchor is here.")))))))

(deftest anchors-refuse-by-name-within-the-selected-view
  (with-redefs [book-check/read-context-module reader]
    (doseq [mode [:after :replace]]
      (is (thrown-with-msg? Exception #"Anchor \"absent\" occurs 0" (book-tools/edit-source (source) first-module "absent" mode "new")))
      (is (thrown-with-msg? Exception #"Anchor \"A repeated line.\" occurs 2" (book-tools/edit-source (source) first-module "A repeated line." mode "new")))
      (is (thrown-with-msg? Exception #"Module view.*occurs 0" (book-tools/edit-source (source) "repo › absent.md" "anchor" mode "new"))))))

(deftest command-refusals-preserve-the-source-and-stdin-is-the-change
  (let [directory (fs/create-temp-dir {:prefix "book-author-test-"})
        path (str (fs/path directory "source.md"))]
    (try
      (with-redefs [book-check/read-context-module reader]
        (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
          (is (zero? (book-tools/main ["start" path "Draft" first-module second-module])))
          (let [original (slurp path)]
            (is (= 1 (binding [*in* (java.io.StringReader. "New.")]
                       (book-tools/main ["edit" path first-module "absent" "--after"]))))
            (is (= original (slurp path)))
            (is (= 1 (binding [*in* (java.io.StringReader. "New.")]
                       (book-tools/main ["edit" path first-module "A repeated line." "--replace-old" "repeated" "--replace-new" "changed"]))))
            (is (= original (slurp path))))
          (let [original (slurp path)]
            (is (= 1 (binding [*in* (java.io.StringReader. "old anchor")]
                       (book-tools/main ["edit" path first-module "old anchor" "--replace-old" "old" "--replace-new" "old"]))))
            (is (= original (slurp path))))
          (is (zero? (binding [*in* (java.io.StringReader. "Inserted from stdin.\n")]
                       (book-tools/main ["edit" path first-module "old anchor" "--after"]))))
          (is (= [] (checked (slurp path))))
          (is (str/includes? (slurp path) "+ Inserted from stdin."))
          (is (= 1 (book-tools/main ["start" path "Overwrite" first-module])))
))
      (finally (fs/delete-tree directory)))))

(deftest replacement-arguments-are-scoped-and-refusals-are-atomic
  (let [directory (fs/create-temp-dir {:prefix "book-author-test-"})
        path (str (fs/path directory "source.md"))]
    (try
      (with-redefs [book-check/read-context-module reader]
        (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
          (is (zero? (book-tools/main ["start" path "Draft" first-module second-module])))
          (let [original (slurp path)]
            (doseq [options [["--replace-old" "absent" "--replace-new" "new"]
                             ["--replace-old" "" "--replace-new" "new"]
                             ["--replace-old" "old"]
                             ["--after" "extra"]]]
              (is (= 1 (book-tools/main (into ["edit" path first-module "old anchor"] options))))
              (is (= original (slurp path)))))
          (is (zero? (book-tools/main ["edit" path first-module "old anchor" "--replace-old" "old" "--replace-new" "new"])))
          (is (= [] (checked (slurp path))))
          (is (str/includes? (slurp path) "+ The new anchor is here."))
          (is (str/includes? (slurp path) "The old anchor is here.") "other module is unchanged")
          (let [original (slurp path)]
            (is (= 1 (book-tools/main ["edit" path first-module "new anchor" "--replace-old" "new" "--replace-new" "later"])))
            (is (= original (slurp path)))) ))
      (finally (fs/delete-tree directory)))))

(deftest line-endings-and-code-anchors-retain-the-original-module
  (doseq [fence ["```" "```rust" "~~~~rust"]
          mode [:after :replace]]
    (let [module (str "---\ndescription: Code in Markdown.\n---\n# H\n\nalpha\r\nbeta\r\n"
                      fence "\r\ninside\r\n" (re-find #"[`~]+" fence) "\r\n")
          read-module (fn [& _] module)]
      (with-redefs [book-check/read-context-module read-module]
        (let [draft (book-tools/start-source "Code" ["repo › a.md"])
              edited (book-tools/edit-source draft "repo › a.md" "inside" mode "changed\nline")]
          (is (= ["missing-proposal"] (mapv :reason (book-check/check-book draft {:read-module read-module}))))
          (is (= [] (book-check/check-book edited {:read-module read-module})))
          (is (str/includes? (render edited) "changed"))
          (is (str/includes? edited "- inside")))))))

(deftest installer-exposes-all-commands-from-a-path-with-spaces
  (let [directory (fs/create-temp-dir {:prefix "book install "})
        bin (str (fs/path directory "bin directory"))
        run (fn [& args] @(process/process (vec args) {:out :string :err :string}))]
    (try
      (is (zero? (:exit (run "python3" (str root "/install-book-tools") bin "--repository" root))))
      (doseq [command ["book-tools" "book-check" "book-build"]]
        (is (fs/executable? (fs/path bin command))))
      (let [result (run (str (fs/path bin "book-tools")))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "Usage: book-tools")))
      (finally (fs/delete-tree directory)))))

(let [{:keys [fail error]} (run-tests 'book-author-test)] (System/exit (if (zero? (+ fail error)) 0 1)))
