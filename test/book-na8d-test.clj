(ns book-na8d-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def tools-dir (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path tools-dir "book-build.clj")))

(defn with-figures [figures f]
  (let [dir (fs/create-temp-dir)]
    (try
      (doseq [[name body] figures] (spit (str (fs/path dir name)) body))
      (binding [book-build/*figures-dir* (str dir)
                book-build/*ethos-check* (constantly nil)]
        (f))
      (finally (fs/delete-tree dir)))))

(def svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><text>figure</text></svg>")

(deftest fenced-ethos-keeps-drain-vector-literal
  (let [html (binding [book-build/*ethos-check* (constantly nil)]
               (book-build/build
                "# Fixture\n\n## psyche-belief › vision/x.md\n\n```ethos\ndrain![ ⟦String⟧ ]\n```\n"
                ""))]
    (is (and (str/includes? html "drain") (str/includes? html "⟦String⟧")))))

(deftest missing-mermaid-uses-explicit-fenced-code-fallback
  ;; A missing numbered figure must not make the fallback code path disappear:
  ;; a fenced text rendering remains visible and does not request fig-1.svg.
  (let [html (binding [book-build/*ethos-check* (constantly nil)]
               (book-build/build "# Fixture\n\n```text\nflowchart LR\n    a --> b\n```\n" ""))]
    (is (str/includes? html "flowchart LR"))
    (is (not (str/includes? html "fig-1.svg")))))

(deftest mixed-mermaid-replacement-preserves-both-sides
  (let [source (str "# Fixture\n\n### Proposal 1: Figure · repo › x.md\n\n"
                    "```mermaid\n"
                    "  flowchart LR\n"
                    "-     old[old] --> end[end]\n"
                    "+     new[new] --> end[end]\n"
                    "```\n")
        html (with-figures {"fig-1.svg" svg "fig-2.svg" svg}
               #(book-build/build source ""))]
    (is (= 2 (count (re-seq #"class=\"figure\"" html))))
    (is (str/includes? html "figmark fig-del"))
    (is (str/includes? html "figmark fig-add"))))

(deftest plain-source-publishes-without-module-or-figure
  (let [html (binding [book-build/*ethos-check* (constantly nil)]
               (book-build/build "# Plain\n\nA plain source paragraph.\n" ""))]
    (is (str/includes? html "A plain source paragraph."))
    (is (not (str/includes? html "class=\"figure\"")))))

(deftest plain-source-is-an-explicit-publisher-mode
  (let [source "# Plain\n\nAn ordinary page with no proposal wrapper.\n\n## A section\n\nMore prose.\n"]
    (is (= [] (book-check/check-book source {:allow-plain true})))
    ;; The authoring checker remains strict unless the publisher opts in.
    (is (some #(= "missing-proposal" (:reason %))
              (book-check/check-book source)))
    ;; A real proposal marker is never reclassified as a plain page.
    (is (seq (book-check/check-book "# Plain\n\n+ changed\n"
                                    {:allow-plain true})))))

(deftest composite-fixture-keeps-all-four-visible
  (let [source (str "# Composite\n\n"
                    "Plain source.\n\n"
                    "## psyche-belief › vision/x.md\n\n"
                    "```ethos\ndrain![ ⟦String⟧ ]\n```\n\n"
                    "```text\nflowchart LR\n    fallback --> visible\n```\n\n"
                    "### Proposal 1: Figure · repo › x.md\n\n"
                    "```mermaid\n  flowchart LR\n-     old[old] --> end[end]\n+     new[new] --> end[end]\n```\n")
        html (with-figures {"fig-1.svg" svg "fig-2.svg" svg}
               #(book-build/build source ""))]
    (doseq [needle ["Plain source." "drain" "⟦String⟧" "flowchart LR" "figmark fig-del" "figmark fig-add"]]
      (is (str/includes? html needle) needle))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (if (zero? (:fail (run-tests 'book-na8d-test))) 0 1)))
