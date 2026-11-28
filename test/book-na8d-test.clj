(ns book-na8d-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

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

(defn- build-plain-file [source]
  (let [dir (fs/create-temp-dir)
        src (str (fs/path dir "source.md"))
        out (str (fs/path dir "page.html"))]
    (try
      (spit src source)
      (let [result (shell/sh "bb" (str (fs/path tools-dir "book-build.clj")) src out)]
        (assoc result :html (when (fs/regular-file? out) (slurp out))))
      (finally (fs/delete-tree dir)))))

(deftest plain-source-renders-without-hiding-check-findings
  ;; The reported proposal had exactly this combination: a bare front matter
  ;; fence with a blank line, ordinary headings, a table and notes in bullets.
  (let [source (str "# Thread management, as proposed\n\nAn ordinary page.\n\n---\n\n"
                    "```\n---\ndescription: The starting of threads.\n\nvisibility: Listed\n---\n```\n\n"
                    "## The kinds of thread\n\n*New.*\n\n"
                    "| Kind of thread | Started with |\n|---|---|\n| Every thread | Skills |\n\n"
                    "#### Beside the proposal\n\n- A note.\n+ Another note.\n")
        {:keys [exit err html]} (build-plain-file source)]
    (is (= 0 exit) err)
    (doseq [finding ["missing-proposal" "text-outside-proposal-without-module"]]
      (is (str/includes? err finding) finding)
      (is (not (str/includes? html finding)) finding))
    (doseq [rendered ["<h1 class=\"book-title\">Thread management, as proposed</h1>"
                      "<h2>The kinds of thread</h2>" "<h4>Beside the proposal</h4>" "<hr>" "<em>New.</em>"
                      "<pre><code class=\"language-text\">---\ndescription: The starting of threads.\n\nvisibility: Listed\n---</code></pre>"
                      "<table><thead><tr><th>Kind of thread</th><th>Started with</th></tr>"
                      "<tr><td>Every thread</td><td>Skills</td></tr>"
                      "<ul><li>A note.</li><li>Another note.</li></ul>"]]
      (is (str/includes? html rendered) rendered))
    (is (not (str/includes? err "fig-1.svg")))))

(deftest plain-source-preserves-leading-front-matter-and-fence-bodies
  (let [source (str "---\ndescription: Draft <not markup>\n\nvisibility: Listed\n---\n"
                    "# Plain\n\n"
                    "````markdown\n```\n\n## Proposal 7: Example only\n::: shown\n```\n````\n\n"
                    "Closing prose.\n")
        {:keys [exit err html]} (build-plain-file source)]
    (is (= 0 exit) err)
    (is (str/includes? html "---\ndescription: Draft &lt;not markup&gt;\n\nvisibility: Listed\n---"))
    (is (str/includes? html ">```\n\n## Proposal 7: Example only\n::: shown\n```</code>"))
    (is (str/includes? html "<p>Closing prose.</p>"))))

(deftest plain-account-keeps-its-presentation-and-signature
  (let [source (str "Presentation.{ Notes.1 Account }\n\n"
                    "# A draft title\n\nCreated by Sol. Drawn by Luna.\n\n"
                    "## Proposal headings in an account\n\nA paragraph.\n")
        {:keys [exit err html]} (build-plain-file source)]
    (is (= 0 exit) err)
    (is (str/includes? html "<title>Notes.1</title>"))
    (is (str/includes? html "<meta name=\"book-kind\" content=\"account\">"))
    (is (str/includes? html "<h1 class=\"book-title\">Notes.1</h1>"))
    (is (str/includes? html "<p class=\"book-subtitle\">Created by Sol. Drawn by Luna.</p>"))
    (is (str/includes? html "<h2>Proposal headings in an account</h2>"))))

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
  (let [{:keys [fail error]} (run-tests 'book-na8d-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
