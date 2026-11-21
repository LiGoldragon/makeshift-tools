;; Tests for book-label.clj. Run: bb test/book-label-test.clj
(ns book-label-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def tools-dir (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path tools-dir "book-label.clj")))
(load-file (str (fs/path tools-dir "book-check.clj")))

(def config "{ Revisions [ { Panel 2 } { Purification 3 } ] }\n")
(def empty-config "{ Revisions [] }\n")

(defn proposal [title]
  (str "Presentation.{ «" title "» Proposal }\n\n# " title "\n\nCreated by Fable.\n\n"
       "### Proposal 1: Part · psyche-belief › vision/a.md\n\n::: comment\nWhy.\n:::\n\n+ Added.\n"))

(defn label [source options & [cfg]] (book-label/label-source source (or cfg config) options))
(defn refused? [source options & [cfg]]
  (try (label source options cfg) false (catch clojure.lang.ExceptionInfo _ true)))

(deftest the-name-is-required
  (is (refused? (proposal "A title") {}))
  (is (refused? (proposal "A title") {:revise true}))
  (testing "an old-form label counts as none"
    (is (refused? (proposal "V7 · A title") {}))))

(deftest the-name-is-pascal-case
  (doseq [bad ["panel" "Two words" "Panel_One" "PANEL" "Panel.1" "" "1Panel"]]
    (is (refused? (proposal "A title") {:name bad}) bad))
  (doseq [good ["Panel" "Purification" "BookLabel" "Panel2"]]
    (is (not (refused? (proposal "A title") {:name good})) good)))

(deftest the-first-revision-is-one
  (let [result (label (proposal "A title") {:name "Account"})]
    (is (= "Account.1" (:label result)))
    (is (str/starts-with? (:source result) "Presentation.{ «Account.1 · A title» Proposal }\n\n# Account.1 · A title\n")))
  (is (= "Panel.1" (:label (label (proposal "A title") {:name "Panel"} empty-config)))))

(deftest a-known-name-takes-one-more-than-its-highest
  (is (= "Panel.3" (:label (label (proposal "A title") {:name "Panel"}))))
  (is (= "Purification.4" (:label (label (proposal "A title") {:name "Purification"})))))

(deftest counts-are-recorded
  (let [first-page (label (proposal "A title") {:name "Account"})]
    (is (:new? first-page))
    (is (= [["Panel" 2] ["Purification" 3] ["Account" 1]] (book-label/parse-config (:config first-page))))
    (is (= "Account.2" (:label (label (proposal "A title") {:name "Account"} (:config first-page)))))
    (is (= "{ Revisions [ { Panel 3 } { Purification 3 } ] }\n"
           (:config (label (proposal "A title") {:name "Panel"}))))
    (is (= "{ Revisions [ { Panel 1 } ] }\n"
           (:config (label (proposal "A title") {:name "Panel"} empty-config))))))

(deftest a-rebuild-keeps-the-label
  (let [labelled (:source (label (proposal "A title") {:name "Panel"}))
        again (label labelled {})
        with-name (label labelled {:name "Other"})]
    (is (= "Panel.3" (:label again)))
    (is (= labelled (:source again)))
    (is (= config (:config again)) "no number is taken")
    (is (not (:new? again)))
    (is (= "Panel.3" (:label with-name)) "a name given to a labelled source changes nothing"))
  (testing "a label on the head alone is carried to the H1"
    (let [half (str/replace (proposal "A title") "«A title»" "«Panel.9 · A title»")
          result (label half {})]
      (is (= "Panel.9" (:label result)))
      (is (str/includes? (:source result) "# Panel.9 · A title"))
      (is (= config (:config result))))))

(deftest revise-raises-the-label
  (let [labelled (:source (label (proposal "A title") {:name "Panel"}))
        revised (label labelled {:revise true})]
    (is (= "Panel.4" (:label revised)))
    (is (str/starts-with? (:source revised) "Presentation.{ «Panel.4 · A title» Proposal }\n\n# Panel.4 · A title\n"))
    (is (= [["Panel" 4] ["Purification" 3]] (book-label/parse-config (:config revised))))
    (is (:new? revised))
    (is (= "Panel.5" (:label (label (:source revised) {:revise true} (:config revised)))))))

(deftest an-old-form-label-is-replaced
  (let [result (label (proposal "V7 · A title") {:name "Panel"})]
    (is (= "Panel.3" (:label result)))
    (is (str/starts-with? (:source result) "Presentation.{ «Panel.3 · A title» Proposal }\n\n# Panel.3 · A title\n")))
  (testing "a title that only begins like a label is labelled"
    (is (str/includes? (:source (label (proposal "V7 title") {:name "Panel"})) "# Panel.3 · V7 title"))))

(deftest a-source-without-presentation
  (testing "is labelled in its H1 alone"
    (let [result (label "# Title\n\n### Proposal 1: P · psyche-belief › vision/a.md\n" {:name "Panel"})]
      (is (= "Panel.3" (:label result)))
      (is (str/starts-with? (:source result) "# Panel.3 · Title\n"))))
  (testing "with no title is refused"
    (is (refused? "Text.\n" {:name "Panel"}))))

(deftest the-checker-accepts-a-labelled-title
  (let [source (proposal "A title")
        labelled (:source (label source {:name "Panel"}))]
    (is (= (mapv :reason (book-check/check-book source))
           (mapv :reason (book-check/check-book labelled))))))

(deftest files
  (let [dir (str (fs/create-temp-dir))
        source (str (fs/path dir "s.md"))
        labels (str (fs/path dir "labels.datom"))]
    (spit labels config)
    (spit source (proposal "A title"))
    (is (= "Panel.3" (:label (book-label/label-files! source labels {:name "Panel"}))))
    (is (= "Panel.3" (:label (book-label/label-files! source labels {}))))
    (is (= "Panel.4" (:label (book-label/label-files! source labels {:revise true}))))
    (is (= "{ Revisions [ { Panel 4 } { Purification 3 } ] }\n" (slurp labels)))
    (is (str/includes? (slurp source) "# Panel.4 · A title"))))

(deftest a-proposal-directory-manifest-takes-the-label-in-its-title
  (let [manifest (str "Manifest.{ «A title» Proposal\n[ Changed.{ «psyche-belief» «vision/a/0.md» «" (apply str (repeat 40 "a")) "» } ] }\n")
        labelled (label manifest {:name "Panel"})]
    (is (= "Panel.3" (:label labelled)))
    (is (str/starts-with? (:source labelled) "Manifest.{ «Panel.3 · A title» Proposal\n"))
    (is (= (subs manifest (count "Manifest.{ «A title» Proposal")) (subs (:source labelled) (count "Manifest.{ «Panel.3 · A title» Proposal"))) "the rest is untouched")
    (is (= "Panel.3" (:label (label (:source labelled) {}))) "it keeps its label")))

(let [{:keys [fail error]} (run-tests 'book-label-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
