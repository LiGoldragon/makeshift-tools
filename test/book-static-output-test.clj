;; Static-output regressions for book-build.clj.
;; Run: bb test/book-static-output-test.clj
(ns book-static-output-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def tools-dir (fs/parent (fs/parent (fs/absolutize *file*))))
(load-file (str (fs/path tools-dir "book-build.clj")))

(deftest every-signed-code-fence-is-static-html
  (is (= "<pre class=\"code-add\"><code class=\"language-rust\">one\ntwo</code></pre>"
         (book-build/signed-code-html "rust" ["+ one" "+ two"]))
      "an added-only fence is one green-margin fence")
  (is (= "<pre class=\"code-del\"><code class=\"language-rust\">one\ntwo</code></pre>"
         (book-build/signed-code-html "rust" ["- one" "- two"]))
      "a removed-only fence is one red-margin fence")
  (is (= (str "<pre class=\"code-diff\"><code class=\"language-rust\">"
              "<span class=\"ln ln-del\">let old = 1;\n</span><span class=\"ln ln-add\">let new = 1;\n</span></code></pre>")
         (book-build/signed-code-html "rust" ["- let old = 1;" "+ let new = 1;"]))
      "a mixed fence interleaves its lines, each carrying its sign class")
  (doseq [[name source mark]
          [["added" "+ let one = 1;\n+ let two = 2;" "<pre class=\"code-add\">"]
           ["removed" "- let one = 1;\n- let two = 2;" "<pre class=\"code-del\">"]
           ["mixed" "- let old = 1;\n+ let new = 1;" "<span class=\"ln ln-del\">let old = 1;"]]]
    (let [html (book-build/build (str "```rust\n" source "\n```\n") "")]
      (is (str/includes? html mark) (str name " fence is marked in built HTML"))
      (is (not (re-find #"(?m)^[+-] " html)) (str name " fence leaves no sign for page JavaScript")))))

(deftest page-form-controls-are-structural
  (let [without (book-build/build
                 (str "# Book\n\n"
                      "## psyche-belief › vision/book.md\n\n"
                      "Why: the module keeps the rendering rule.\n\n"
                      "The module stands whole.\n") "")]
    (is (not (str/includes? without "class=\"pnum\""))
        "a module-change page emits no numbered Proposal badge")
    (is (not (str/includes? without "class=\"comment\""))
        "a module-change page emits no comment aside")
    (is (not (str/includes? without ">Why:")) "the source marker is not emitted as a label")
    (is (str/includes? without "<p>the module keeps the rendering rule.</p>")
        "the reason remains as ordinary prose"))
  (let [with (book-build/build
              (str "## Proposal 7: change a module\n\n"
                   "::: comment\nThe reason is wanted here.\n:::\n\n"
                   "+ Added.\n") "")]
    (is (str/includes? with "<span class=\"pnum\">7</span>")
        "a numbered Proposal form includes its badge")
    (is (str/includes? with (str "<aside class=\"comment\" aria-label=\"Why\">"
                                 "<div class=\"comment-label\">Why</div>"
                                 "<div class=\"comment-body\">"))
        "an explicit comment container includes its own visibly labelled box")))

(deftest whole-module-inline-proposal-marks
  (let [html (book-build/build
              (str "# Book\n\n"
                   "## psyche-belief › vision/book.md\n\n"
                   "A line is [+added+] here.\n\n"
                   "A word is [-removed-] here.\n\n"
                   "[~moved from vision/old.md: The passage appears here once.~]\n") "")]
    (is (str/includes? html "<article class=\"module diff\">")
        "the module is the proposal and supplies the change context")
    (is (not (str/includes? html "class=\"pnum\"")) "the module proposal has no badge")
    (is (not (str/includes? html "as it stands")) "the module proposal has no state heading")
    (is (str/includes? html "A line is <ins>added</ins> here.") "added words stay in place")
    (is (str/includes? html "A word is <del>removed</del> here.") "removed words stay in place")
    (is (str/includes? html "<span class=\"moved-origin\">from vision/old.md:</span>")
        "a moved passage names its origin")
    (is (= 1 (count (re-seq #"The passage appears here once\." html)))
        "a moved passage is shown once at its destination")
    (is (not (re-find #"\[(?:\+|-|~moved)" html)) "authoring marks do not reach built HTML")))

(deftest proposal-mark-theme-contract
  (let [css (slurp (str (fs/path tools-dir "book-code.html")))]
    (is (str/includes? css "--moved-bg:#e4e9fb") "light theme declares the moved colour")
    (is (str/includes? css "--moved-bg:#1f2747") "dark theme declares the moved colour")
    (is (str/includes? css ".diff del,pre del") "removed words use the inline prose rule")
    (is (str/includes? css "text-decoration:none") "removed words are never struck through")
    (is (str/includes? css "font:17px/1.6 var(--f-prose)") "module prose keeps ordinary body type")))

(deftest move-state-theme-contract
  (let [css (slurp (str (fs/path tools-dir "book-code.html")))
        move-rules (map second (re-seq #"(?s)\.move(?![A-Za-z0-9_-])[^\{]*\{([^}]*)\}" css))
        regression-css (str/replace css "padding:4px 0 4px 12px" "padding:4px 0 4px 12px;background:#fff;color:red")
        regression-rules (map second (re-seq #"(?s)\.move(?![A-Za-z0-9_-])[^\{]*\{([^}]*)\}" regression-css))]
    (is (seq move-rules) "move declarations are present")
    (is (str/includes? css ".move{border-left:4px solid var(--move-edge)") "move is a single left margin border")
    (is (str/includes? css ".move-proposed{--move-edge:var(--move-proposed-edge)}") "proposed move edge")
    (is (str/includes? css ".move-landed{--move-edge:var(--move-landed-edge)}") "landed move edge")
    (is (str/includes? css "--move-proposed-edge:#6b7ed6") "light proposed edge")
    (is (str/includes? css "--move-landed-edge:#304aa8") "light landed edge")
    (is (str/includes? css "--move-proposed-edge:#9aaeff") "dark proposed edge")
    (is (str/includes? css "--move-landed-edge:#7e95f5") "dark landed edge")
    (is (not (str/includes? css ".move{border:2px")) "move has no full surround")
    (is (every? #(not (re-find #"(?m)(?:^|;)\s*(?:background|color)\s*:" %)) move-rules)
        "move rules do not recolour their body")
    (is (not (every? #(not (re-find #"(?m)(?:^|;)\s*(?:background|color)\s*:" %)) regression-rules))
        "regression witness detects recolour declarations after padding")
    (is (not (str/includes? css "--move-bg:")) "move has no background variable")
    (is (not (str/includes? css "--move-fg:")) "move has no foreground variable")
    (is (not (str/includes? css ".move h2")) "move heading keeps ordinary colour")
    (is (not (str/includes? css ".move>p")) "move descendants keep ordinary colours")
    (is (str/includes? css ".ts-keyword,.ts-type,.ts-constructor{color:var(--hl-type)}") "code token colours remain internal")
    (is (str/includes? css ".figure{overflow-x:auto") "figure rendering remains unchanged")))

(let [{:keys [fail error]} (binding [book-build/*highlight* false] (run-tests 'book-static-output-test))]
  (when (pos? (+ fail error)) (System/exit 1)))
