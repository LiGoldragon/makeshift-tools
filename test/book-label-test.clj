;; Tests for book-label.clj. Run: bb test/book-label-test.clj
(ns book-label-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def tools-dir (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path tools-dir "book-label.clj")))
(load-file (str (fs/path tools-dir "book-check.clj")))

(def config
  "{ Labels
  [ { V VisionProposal 1 }
    { K KnowledgeProposal 4 }
    { A Account 1 }
    { R Review 1 } ]
  VisionModules { psyche-belief [ spirit intent vision ] } }
")

(defn proposal [kind & modules]
  (str "Presentation.{ «A title» " kind " }\n\n# A title\n\nCreated by Fable.\n\n"
       (str/join "\n"
                 (map-indexed (fn [i [repository path]]
                                (str "### Proposal " (inc i) ": Part · " repository " › " path
                                     "\n\n::: comment\nWhy.\n:::\n\n+ Added.\n"))
                              modules))))

(defn kind-of [source] (book-label/page-kind source (book-label/parse-config config)))

(deftest letter-decision
  (testing "a proposal into spirit, intent or vision modules of psyche-belief is a vision one"
    (is (= "VisionProposal" (kind-of (proposal "Proposal" ["psyche-belief" "vision/book/0.md"]))))
    (is (= "VisionProposal" (kind-of (proposal "Proposal" ["psyche-belief" "spirit/core/0.md"] ["psyche-belief" "intent/energy/0.md"])))))
  (testing "any other module makes it a knowledge proposal"
    (is (= "KnowledgeProposal" (kind-of (proposal "Proposal" ["psyche-belief" "knowledge/book/0.md"]))))
    (is (= "KnowledgeProposal" (kind-of (proposal "Proposal" ["other-repo" "vision/x.md"]))))
    (is (= "KnowledgeProposal" (kind-of (proposal "Proposal" ["psyche-belief" "vision/a.md"] ["psyche-belief" "mind/b.md"])))))
  (testing "a Presentation naming no kind is a Proposal"
    (is (= "VisionProposal" (kind-of (str/replace (proposal "Proposal" ["psyche-belief" "vision/a.md"]) " Proposal }" " }")))))
  (testing "an account and a review are their own"
    (is (= "Account" (kind-of (proposal "Account"))))
    (is (= "Review" (kind-of (proposal "Review" ["psyche-belief" "vision/a.md"])))))
  (testing "a move's two ends and a new module count as changed modules"
    (let [source (str "Presentation.{ «T» Proposal }\n\n# T\n\n## psyche-belief › vision/a.md\n\n"
                      "#### Proposal 1: P · psyche-belief › vision/a.md\n\n::: move proposed\n"
                      "from: psyche-belief › vision/a.md\nto: psyche-belief › knowledge/b.md\n\nText.\n:::\n")]
      (is (= "KnowledgeProposal" (kind-of source))))
    (is (= [["psyche-belief" "intent/new/0.md"]]
           (book-label/changed-modules (book-label/source-lines "## psyche-belief › intent/new/0.md, a new file\n")))))
  (testing "a heading in a code fence is no module"
    (is (= [] (book-label/changed-modules
               (book-label/source-lines "```text\n### Proposal 1: P · psyche-belief › vision/a.md\n```\n"))))))

(deftest counting
  (let [source (proposal "Proposal" ["psyche-belief" "knowledge/book/0.md"])
        first-page (book-label/label-source source config)]
    (is (= "K4" (:label first-page)))
    (is (str/starts-with? (:source first-page) "Presentation.{ «K4 · A title» Proposal }\n\n# K4 · A title\n"))
    (is (str/includes? (:config first-page) "{ K KnowledgeProposal 5 }"))
    (is (str/includes? (:config first-page) "{ V VisionProposal 1 }") "the other counts stay")
    (let [second-page (book-label/label-source source (:config first-page))]
      (is (= "K5" (:label second-page))))
    (is (= "V1" (:label (book-label/label-source (proposal "Proposal" ["psyche-belief" "vision/a.md"]) config))))
    (is (= "A1" (:label (book-label/label-source (proposal "Account") config))))
    (is (= "R1" (:label (book-label/label-source (proposal "Review" ["psyche-belief" "vision/a.md"]) config))))))

(deftest existing-label-is-kept
  (let [labelled (:source (book-label/label-source (proposal "Proposal" ["psyche-belief" "knowledge/book/0.md"]) config))
        again (book-label/label-source labelled config)]
    (is (= "K4" (:label again)))
    (is (= labelled (:source again)))
    (is (= config (:config again)) "no number is taken")
    (is (not (:new? again))))
  (testing "a label on the head alone is carried to the H1"
    (let [half (str/replace (proposal "Proposal" ["psyche-belief" "vision/a.md"]) "«A title»" "«V9 · A title»")
          result (book-label/label-source half config)]
      (is (= "V9" (:label result)))
      (is (str/includes? (:source result) "# V9 · A title"))
      (is (= config (:config result)))))
  (testing "a title that only begins like a label is labelled"
    (is (= "V1" (:label (book-label/label-source
                         (str/replace (proposal "Proposal" ["psyche-belief" "vision/a.md"]) "A title" "V7 title") config))))))

(deftest a-source-without-presentation
  (testing "is a proposal, labelled in its H1 alone"
    (let [result (book-label/label-source "# Title\n\n### Proposal 1: P · psyche-belief › vision/a.md\n" config)]
      (is (= "V1" (:label result)))
      (is (str/starts-with? (:source result) "# V1 · Title\n"))))
  (testing "with no title is refused"
    (is (thrown? clojure.lang.ExceptionInfo (book-label/label-source "Text.\n" config)))))

(deftest the-checker-accepts-a-labelled-title
  (let [source (proposal "Proposal" ["psyche-belief" "vision/a.md"])
        labelled (:source (book-label/label-source source config))]
    (is (= (mapv :reason (book-check/check-book source))
           (mapv :reason (book-check/check-book labelled))))))

(deftest files
  (let [dir (str (fs/create-temp-dir))
        source (str (fs/path dir "s.md"))
        labels (str (fs/path dir "labels.datom"))]
    (spit labels config)
    (spit source (proposal "Proposal" ["psyche-belief" "vision/a.md"]))
    (is (= "V1" (:label (book-label/label-files! source labels))))
    (is (= "V1" (:label (book-label/label-files! source labels))))
    (is (str/includes? (slurp labels) "{ V VisionProposal 2 }"))
    (is (str/includes? (slurp source) "# V1 · A title"))))

(let [{:keys [fail error]} (run-tests 'book-label-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
