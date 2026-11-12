;; Tests for book-build.clj. Run: bb test/book-build-test.clj
;; The two books in test/fixtures/books are rebuilt with the working-tree book-code.html
;; and compared byte for byte with their published html. The fixture covers
;; Destroy/Archive records, a figure, rulings and lists; its expected page was
;; produced by the former Python builder with the tail TAIL, all but its figure,
;; which is checked by its parts.
(ns book-build-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [clojure.java.shell]
            [babashka.fs :as fs]))

(def tools-dir (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def fixtures-dir (str (fs/path tools-dir "test" "fixtures")))
(load-file (str (fs/path tools-dir "book-build.clj")))
(def root tools-dir)
(def code (book-build/read-text (fs/path tools-dir "book-code.html")))

;; These books predate the ethos check; their rendering is compared with ethos-zero
;; told to accept every block. That the check refuses their ethos is tested below.
(defn rebuilt [book]
  (let [dir (fs/path fixtures-dir "books")]
    (binding [book-build/*ethos-check* (constantly nil)]
      (book-build/build (book-build/read-text (fs/path dir (str book ".md"))) code))))

;; The published html of these two books predates the one-text diff, so it is
;; no longer compared byte for byte: no removed panel may stay directly before
;; an added panel, and every ruling card is still built.
(defn no-twice-read? [html]
  (not (re-find #"</del></div><div class=\"add\">" html)))

(deftest belief-modules-book
  (let [html (rebuilt "09-context-modules")]
    (is (no-twice-read? html))
    (is (re-find #"<del>" html))
    (is (re-find #"<ins>" html))))

(deftest messaging-book
  (is (no-twice-read? (rebuilt "10-messaging-between-flows"))))

(defn change [md]
  (book-build/text-panels (str/split-lines md)))

(deftest one-word-changed
  (is (= "<div class=\"diff\"><p>The <del>old</del><ins>new</ins> plan holds, for now.</p></div>"
         (change "- The old plan holds, for now.\n+ The new plan holds, for now."))))

(deftest phrase-inserted
  (is (= "<div class=\"diff\"><p>Keep the <ins>whole new </ins>plan.</p></div>"
         (change "- Keep the plan.\n+ Keep the whole new plan."))))

(deftest line-wholly-added
  (is (= "<div class=\"diff\"><div class=\"ctx\"><p>Kept.</p></div><div class=\"add\"><p>Brand new line.</p></div></div>"
         (change "  Kept.\n+ Brand new line."))))

(deftest line-wholly-removed
  (is (= "<div class=\"diff\"><div class=\"del\"><p>Gone line.</p></div><div class=\"ctx\"><p>Kept.</p></div></div>"
         (change "- Gone line.\n  Kept."))))

(deftest two-lines-paired-with-two
  (is (= "<div class=\"diff\"><p>first <del>old</del><ins>new</ins> line second <del>old</del><ins>new</ins> line</p></div>"
         (change "- first old line\n- second old line\n+ first new line\n+ second new line"))))

(deftest signed-code-html-three-cases
  (binding [book-build/*highlight* false]
    (is (= "<pre class=\"code-add\"><code class=\"language-rust\">one &lt;\n  two</code></pre>"
           (book-build/signed-code-html "rust" ["+ one <" "+   two"])) "wholly added")
    (is (= "<pre class=\"code-del\"><code class=\"language-rust\">one\ntwo</code></pre>"
           (book-build/signed-code-html "rust" ["- one" "- two"])) "wholly removed")
    (is (= (str "<pre class=\"code-diff\"><code class=\"language-rust\">"
                "<span class=\"ln\">x\n</span><span class=\"ln ln-del\">  (a b)\n</span>"
                "<span class=\"ln ln-add\">  (a z)\n</span><span class=\"ln\">(c)\n</span></code></pre>")
           (book-build/signed-code-html "rust" ["  x" "-   (a b)" "+   (a z)" "  (c)"])) "mixed")
    (is (nil? (book-build/signed-code-html "rust" ["plain"])) "no sign, no change")))

(deftest highlight-on-marks-rust-keywords
  (if-not (zero? (:exit (clojure.java.shell/sh "sh" "-c" "command -v tree-sitter")))
    (println "tree-sitter not on PATH; highlight test skipped")
    (binding [book-build/*highlight* true]
      (is (str/includes? (book-build/code-html "rust" ["fn main() {}"]) "<span class=\"ts-keyword\">fn</span>")))))

(def fixture-md
  "# Fixture & <book>\n\n<!-- hidden comment -->\nPresentation.{ Book Fixture }\n\n## Crystallization\n\nAn opening paragraph with `inline <code>` and\na second line & more.\n\n### Proposal 1: Figures & `records`\n\nFile: `skills/vision-x/SKILL.md`, (builds on `vision-y`)\n\n```text\n  ---\n  name: vision-x\n  ---\n  ## A heading kept as text\n  Unchanged context line\n- Removed line one\n- removed line two\n+ Added line with `code`\n+ # added heading\n```\n\n```text\n+-----------------------+\n| Flow                  |\n| with \"two\" lines      |\n+-----------------------+\n           |\n           | sends it's\n           | message\n           v\n+-----------------------+\n| Recipient             |\n+-----------------------+\n     |\n     | reads\n     v\n+-----------------------+\n| Log                   |\n+-----------------------+\n```\n\n**Destroy** the record `flows/aa/vision/x.md` below.\n\nFile: `flows/aa/vision/x.md`\n\n```text\n- A working instruction logged as vision.\n```\n\n**Archive**\n\n```text\n- An archived line.\n```\n\n**Destroy** with nothing following.\n\n```js\nconst x = 1 < 2 && \"y\";\n```\n\nRuling, one number per line:\n\n1. Approve.\n2. Reject:\n\n```text\n+ inside the ruling card\n```\n\nAfter the card.\n\n### Proposal 2: Second\n\nGroup heading\n3. Third choice\ncontinued here.\n4. Fourth choice\n\nRuling, one number per line:\n\n3. Yes\n4. No\n\n```python\nprint(\"tail\")\n```\n\nTrailing paragraph.\n")

(def fixture-expected
  "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Fixture &amp; &lt;book&gt;</title>\n<h1 class=\"book-title\">Fixture &amp; &lt;book&gt;</h1>\n<h2>Crystallization</h2>\n<p>An opening paragraph with <code>inline &lt;code&gt;</code> and a second line &amp; more.</p>\n<article class=\"proposal\"><header><span class=\"pnum\">1</span><h2>Figures &amp; <code>records</code></h2></header>\n<p class=\"target\"><code>skills/vision-x/SKILL.md</code></p><p>builds on <code>vision-y</code></p>\n<div class=\"diff\"><div class=\"ctx\"><div class=\"fm\"><div class=\"fm-label\">Front matter</div><dl><dt>Name</dt><dd>vision-x</dd></dl></div></div><div class=\"ctx\"><h4>A heading kept as text</h4></div><div class=\"ctx\"><p>Unchanged context line</p></div><p><del>Removed</del><ins>Added</ins> line <del>one removed line two</del><ins>with <code>code</code></ins></p><div class=\"add\"><h3>added heading</h3></div></div>\n<div class=\"figure\">FIGURE</div>\n<div class=\"record destroy\"><p>the record <code>flows/aa/vision/x.md</code> below.</p>\n<p class=\"target\"><code>flows/aa/vision/x.md</code></p>\n<div class=\"del\"><p>A working instruction logged as vision.</p></div></div>\n<div class=\"record archive\">\n<div class=\"del\"><p>An archived line.</p></div></div>\n<div class=\"record destroy\"><p>with nothing following.</p>\n<pre><code class=\"language-javascript\">const x = 1 &lt; 2 &amp;&amp; \"y\";</code></pre>\n<section class=\"rulings\"><ol><li value=\"1\"><p>Ruling, one number per line:</p><ol class=\"choices\" start=\"1\"><li>Approve.</li><li>Reject:</li></ol><div class=\"add\"><p>inside the ruling card</p></div></div><p>After the card.</p></li></ol></section>\n</article>\n<article class=\"proposal\"><header><span class=\"pnum\">2</span><h2>Second</h2></header>\n<p>Group heading</p>\n<ol class=\"choices\" start=\"3\" style=\"counter-reset:choice 2\"><li>Third choice continued here.</li><li>Fourth choice</li></ol>\n<section class=\"rulings\"><ol><li value=\"2\"><p>Ruling, one number per line:</p><ol class=\"choices\" start=\"3\" style=\"counter-reset:choice 2\"><li>Yes</li><li>No</li></ol></li></ol></section><pre><code class=\"language-python\">print(\"tail\")</code></pre><p>Trailing paragraph.</p>\n</article>\nTAIL\n")

(def illustration
  (str "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
       "viewBox=\"0 0 360 520\" width=\"360\" height=\"520\" onload=\"evil()\">"
       "<script>alert(1)</script><a href=\"https://evil.example/x\"><rect id=\"keep\" width=\"10\" height=\"10\" onclick=\"evil()\"/></a>"
       "<use xlink:href=\"http://evil.example/y.svg#a\"/><use href=\"#keep\"/>"
       "<image href=\"data-not-allowed\"/><foreignObject><p>x</p></foreignObject><text>One</text><text>sends</text><text>Two</text>"
       "<line x1=\"5\" y1=\"5\" x2=\"5\" y2=\"50\" marker-end=\"url(#head)\"/></svg>"))

(defn with-figures [files f]
  (let [dir (fs/create-temp-dir)]
    (try (doseq [[n body] files] (spit (str (fs/path dir n)) body))
         (binding [book-build/*figures-dir* (str dir)] (f))
         (finally (fs/delete-tree dir)))))

(defn faithful
  "An illustrator's SVG carrying these texts and this many arrows (marker-end)."
  [arrows & texts]
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 239 100\"><g id=\"DRAWN\">"
       (apply str (map #(str "<text>" (book-build/esc %) "</text>") texts))
       (apply str (repeat arrows "<line x1=\"0\" y1=\"0\" x2=\"0\" y2=\"9\" marker-end=\"url(#h)\"/>"))
       "</g></svg>"))

;; The figures of the sketches below, each keeping every label and arrow.
(def fixture-fig (faithful 2 "Flow" "with \"two\" lines" "sends it's" "message" "Recipient" "reads" "Log"))
(def one-two (faithful 1 "One" "sends" "Two"))
(def red-blue (faithful 1 "Red" "Blue"))
(def a-to-b (faithful 1 "a" "to" "b"))
(def box-a (faithful 0 "a"))

(deftest fixture-records-and-figure
  (let [page (with-figures {"fig-1.svg" fixture-fig} #(book-build/build fixture-md "TAIL\n"))
        fig (re-find #"(?s)<div class=\"figure\"[^>]*>.*?</svg></div>" page)]
    (is (str/includes? page (str "<h1 class=\"book-title\">Fixture &amp; &lt;book&gt;</h1>\n" book-build/colour-key "\n"))
        "the key follows the title")
    (is (= fixture-expected (-> page (str/replace fig "<div class=\"figure\">FIGURE</div>")
                                (str/replace (str book-build/colour-key "\n") ""))))
    (is (str/includes? fig "id=\"f1-DRAWN\"") "the figure is the supplied fig-1.svg, its ids its own")
    (is (not (str/includes? page "+-----")) "the sketch is not drawn")))

(deftest fixture-output-has-no-trailing-whitespace
  (is (nil? (re-find #"(?m)[ \t]+$" (with-figures {"fig-1.svg" fixture-fig} #(book-build/build fixture-md "TAIL\n")))))
  (is (nil? (re-find #"(?m)[ \t]+$"
                     (book-build/build "```clj\nx\n- a\n-\n+ a\n+\n```\n" "")))))

(deftest table-in-a-proposal-is-a-table
  (let [html (book-build/build (str "### Proposal 1: x\n\n| a | b |\n|:---|---:|\n| 1 | 2 |\n\n"
                                    "```text\n  | c | d |\n  |---|---|\n  | 3 | 4 |\n- Gone line.\n```\n") "")]
    (is (= 2 (count (re-seq #"<table>" html))))
    (is (str/includes? html "<th style=\"text-align:right\">b</th>"))
    (is (str/includes? html "<td>3</td><td>4</td>"))
    (is (not (re-find #"-{3}" html)) "no separator row drawn as text")
    (is (= 1 (count (re-seq #"class=\"del\"" html))))
    (is (str/includes? html "<p>Gone line.</p>") "a line opening with - and a space is still removed")))

(deftest a-table-is-read-across-its-signs
  ;; kept header and separator, then kept, removed and added rows: one table
  (let [md (str "  | type | kind |\n  |---|---|\n  | kept | `A` |\n- | gone | `B` |\n- | also gone | C |\n+ | new | D |\n")
        html (change md)]
    (is (= 1 (count (re-seq #"<table>" html))) "one table")
    (is (str/includes? html "<thead><tr><th>type</th><th>kind</th></tr></thead>"))
    (is (str/includes? html "<tr><td>kept</td><td><code>A</code></td></tr>"))
    (is (str/includes? html "<tr class=\"row-del\"><td>gone</td><td><code>B</code></td></tr>"))
    (is (str/includes? html "<tr class=\"row-del\"><td>also gone</td><td>C</td></tr>"))
    (is (str/includes? html "<tr class=\"row-add\"><td>new</td><td>D</td></tr>"))
    (is (= 2 (count (re-seq #"<tr class=\"row-del\">" html))) "each removed row is its own struck row")
    (is (not (str/includes? html "<p>")) "no paragraph of pipes")
    (is (not (re-find #"-{3}" html)) "no separator row drawn as text"))
  (let [html (change "- | a | b |\n- |---|---|\n- | 1 | 2 |")]
    (is (not (str/includes? html "<tr class=\"row-")) "a table of one sign stays as it was read")))

(deftest empty-line-in-text-fence
  (is (= "<div class=\"diff\"><div class=\"add\"><p>a</p></div><div class=\"add\"><p>b</p></div></div>\n"
         (book-build/build "```\n+ a\n\n+ b\n```\n" ""))))

(deftest bare-markers-equal-marker-space
  (is (= (book-build/signed-code-html "text" ["x" "- a" "-  " "- b" "+ a" "+ " "+ b"])
         (book-build/signed-code-html "text" ["x" "- a" "-" "- b" "+ a" "+" "+ b"])))
  (is (= (change "- a\n- \n+ b\n+ ") (change "- a\n-\n+ b\n+"))))

(deftest new-module-renders-as-web
  (let [md (str "### Proposal 1: m\n\nFile: `a/b.md`, new\n\n+ ---\n+ description: Does a thing.\n+ dependencies: [x, y]\n+ ---\n+\n"
                "+ ## Head\n+\n+ Para with `code` & more.\n+\n+ - one\n+ - two\n+\n+ ```rust\n+ let a = 1;\n+\n+ - kept < code\n+ ```\n\n"
                "- ---\n- description: Old.\n- ---\n- Old body.\n")
        html (book-build/build md "")]
    (is (str/includes? html "<div class=\"add\"><div class=\"fm\"><div class=\"fm-label\">Front matter</div><dl><dt>Description</dt><dd>Does a thing.</dd><dt>Dependencies</dt><dd>x, y</dd></dl></div></div>"))
    (is (str/includes? html "<div class=\"add\"><h4>Head</h4></div>"))
    (is (str/includes? html "<p>Para with <code>code</code> &amp; more.</p>"))
    (is (str/includes? html "<ul><li>one</li><li>two</li></ul>"))
    (is (str/includes? html "<pre><code class=\"language-rust\">let a = 1;\n\n- kept &lt; code</code></pre>") "fence stays code, blank line kept")
    (is (str/includes? html "<div class=\"del\"><div class=\"fm\">") "removed module is rendered and marked")
    (is (not (str/includes? html "---")) "no YAML syntax shown")
    (is (not (str/includes? html "<div class=\"textblock\">")))))

(deftest unsigned-fence-is-code
  (let [ethos (str "; The kind of a subflow: aspect, role and level.\nLibrary\n[]                    ; imports: intrinsics only\n"
                   "[ Aspect.[ Psyche     ; thinking, psyche records\n           Mind       ; documentation, tools,\n"
                   "                      ; code changes, audit review\n           Field ]    ; debugging, system audit,\n"
                   "                      ; system search, deploy\n  Role.[ Explorer     ; locate and read\n"
                   "         Researcher ] ; the web\n  Level.[ Secondary\n          Tertiary\n          Quaternary ]\n"
                   "  Kind.{ Aspect\n         Role\n         Level } ]\n[]                    ; kinds\n[]                    ; associations")
        md (fn [file] (str "### Proposal 1: The Kind in Ethos\n\nFile: " file "\n\n```\n" ethos "\n```\n\nAfter.\n"))
        esc* (book-build/esc ethos)]
    (doseq [[file lang] [["Curriculum/kinds.ethos, new" "ethos"] ["`Curriculum/kinds.ethos`, new" "ethos"] ["a/b.md, new" "text"]]
            :let [html (book-build/build (md file) "")]]
      (is (str/includes? html (str "<pre><code class=\"language-" lang "\">" esc* "</code></pre>")) file)
      (is (not (str/includes? html "<div class=\"diff\">")))
      (is (not (str/includes? html "<div class=\"ctx\">"))))))

(deftest bullet-list-after-heading
  (let [html (book-build/build "## Why\n\n- **one** is first.\n- **two** is second,\n  continued.\n\nAfter.\n" "")]
    (is (str/includes? html "<ul><li><strong>one</strong> is first.</li><li><strong>two</strong> is second, continued.</li></ul>"))
    (is (not (str/includes? html "**"))))
  (let [html (book-build/build "### Proposal 1: T\n\nFile: a.md\n\n- old\n+ new\n" "")]
    (is (not (str/includes? html "<ul>")))))

(def sketch-md
  (str "```text\n+-------+\n| One   |\n+-------+\n    |\n    | sends\n    v\n+-------+\n| Two   |\n+-------+\n```\n\n"
       "```text\n+-------+\n| Red   |\n+-------+\n    |\n    v\n+-------+\n| Blue  |\n+-------+\n```\n"))

(deftest illustration-replaces-the-nth-figure
  (let [html (with-figures {"fig-1.svg" illustration "fig-2.svg" red-blue} #(book-build/build sketch-md ""))
        figs (re-seq #"(?s)<div class=\"figure\"[^>]*>.*?</div>" html)]
    (is (= 2 (count figs)))
    (is (str/includes? (first figs) "url(#head)") "figure 1 is the illustration")
    (is (not (str/includes? html "| One")) "the sketch is not shown")
    (is (str/includes? (first figs) "width=\"100%\"") "scaled to the container")
    (is (str/includes? (first figs) "viewBox=\"0 0 360 520\""))
    (is (str/includes? (first figs) "padding-bottom:144.44%") "the container holds the aspect height")
    (is (str/includes? (second figs) "DRAWN") "figure 2 is fig-2.svg")))

(deftest illustration-is-sanitized
  (let [html (with-figures {"fig-1.svg" illustration} #(book-build/build (first (str/split sketch-md #"\n\n")) ""))
        fig (re-find #"(?s)<div class=\"figure\"[^>]*>.*?</div>" html)]
    (is (not (re-find #"(?i)<script|alert|onload|onclick|evil|foreignObject|<\?xml" fig)))
    (is (not (re-find #"https?://[^\"]*evil" fig)))
    (is (str/includes? fig "xmlns=\"http://www.w3.org/2000/svg\"") "the namespace stays")
    (is (str/includes? fig "<use href=\"#f1-keep\"/>") "an internal reference stays, to the figure's own id")
    (is (str/includes? fig "id=\"f1-keep\""))))

(defn refusal
  "The message of the refusal, or nil when the build succeeds."
  [md]
  (try (book-build/build md "") nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn stderr-of
  "What the thunk writes to stderr."
  [f]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w] (f))
    (str w)))

(deftest diagram-without-figure-is-left-out
  (let [err (stderr-of #(with-figures {} (fn [] (book-build/build sketch-md ""))))]
    (is (= (str "book-build: figure fig-1.svg not found; diagram 1 left out\n"
                "book-build: figure fig-2.svg not found; diagram 2 left out\n") err)
        "one line for each figure"))
  (let [html (with-figures {} #(book-build/build sketch-md ""))]
    (is (string? html) "the build succeeds")
    (is (not (str/includes? html "<pre")) "no raw sketch in its place"))
  (is (str/includes? (stderr-of #(with-figures {"fig-2.svg" red-blue} (fn [] (book-build/build sketch-md "")))) "fig-1.svg"))
  (is (str/includes? (stderr-of #(with-figures {"fig-1.svg" one-two} (fn [] (book-build/build sketch-md "")))) "fig-2.svg"))
  (is (= "" (stderr-of #(with-figures {"fig-1.svg" one-two "fig-2.svg" red-blue} (fn [] (book-build/build sketch-md "")))))
      "figures found: nothing on stderr")
  (is (str/includes? (stderr-of #(book-build/build sketch-md "")) "fig-1.svg") "no --figures directory")
  (is (nil? (refusal "### Proposal 1: x\n\n```\n+-------+\n| One   |\n+-------+\n    |\n    v\n+-------+\n| Two   |\n+-------+\n```\n")))
  (is (nil? (refusal "```figure\n{:nodes [{:id 1 :label \"a\"} {:id 2 :label \"b\"}] :edges [{:from 1 :to 2}]}\n```\n"))))

(deftest unboxed-arrow-chain-is-a-diagram
  (let [chain "```\n Ethos\n   | specifies\n   v\n Nexus\n   | then\n   v\n Flow\n```\n"]
    (is (str/includes? (stderr-of #(refusal chain)) "diagram 1") "an unlabelled fence outside a proposal is a diagram")
    (let [html (with-figures {"fig-1.svg" (faithful 2 "Ethos" "specifies" "Nexus" "then" "Flow")} #(book-build/build chain ""))]
      (is (str/includes? html "DRAWN") "it is replaced by fig-1.svg")
      (is (not (str/includes? html "<pre")) "the sketch is not shown as code")))
  (is (nil? (binding [book-build/*ethos-check* (constantly nil)]
               (refusal "```ethos\n Ethos\n   | specifies\n   v\n Nexus\n```\n")))
      "a labelled fence stays code")
  (is (nil? (refusal "### Proposal 1: x\n\n```\n Ethos\n   |\n   v\n Nexus\n```\n")) "inside a proposal an unboxed fence stays code"))

(deftest the-builder-renders-a-figure-as-given
  (let [one-sends-two (first (str/split sketch-md #"\n\n"))
        render (fn [svg] (with-figures {"fig-1.svg" svg} #(book-build/build one-sends-two "")))]
    (doseq [svg [(faithful 0 "One" "sends" "Two") (faithful 1 "One" "Two") (faithful 1 "One" "sends" "Two" "Three")
                 (faithful 2 "One" "sends" "Two")]]
      (is (str/includes? (render svg) "DRAWN") "a figure that lost or added a word or an arrow is still shown"))
    (is (= "" (stderr-of #(render (faithful 0 "One")))) "nothing is said of it")
    (is (str/includes? (stderr-of #(with-figures {"fig-1.svg" "not an svg"} (fn [] (book-build/build one-sends-two ""))))
                       "fig-1.svg left out")
        "a file that is no svg is left out, one line naming it")))

(deftest cli-renders-what-it-is-given
  (let [dir (fs/create-temp-dir)
        source (str "### Proposal 1: Figures · repo › book.md\n\n+ Added.\n\n" sketch-md)
        src (str (fs/path dir "b.md")) out (str (fs/path dir "b.html"))
        bb #(apply clojure.java.shell/sh "bb" (str (fs/path tools-dir "book-build.clj")) %&)]
    (try (spit src source)
         (let [r (bb src out)]
           (is (= 0 (:exit r)) (:err r))
           (is (= 2 (count (str/split-lines (:err r)))) "one line for each of the two missing figures")
           (is (str/starts-with? (:err r) "book-build: figure fig-1.svg not found"))
           (is (fs/exists? out) "the page is written"))
         (let [fd (fs/create-dirs (fs/path dir "figs"))]
           (spit (str (fs/path fd "fig-1.svg")) one-two) (spit (str (fs/path fd "fig-2.svg")) red-blue)
           (let [r (bb src out "--figures" (str fd))]
             (is (= 0 (:exit r)) (:err r))
             (is (= "" (:err r)))
             (is (str/includes? (slurp out) "DRAWN"))))
         (let [r (bb (str (fs/path dir "missing.md")) out)]
           (is (= 1 (:exit r)) "an unreadable source is an error")
           (is (str/starts-with? (:err r) "book-build: ")))
         (let [r (bb src (str (fs/path dir "no-such-dir" "b.html")))]
           (is (= 1 (:exit r)) "an unwritable page is an error")
           (is (str/starts-with? (:err r) "book-build: ")))
         (finally (fs/delete-tree dir)))))

(deftest code-and-signed-fences-need-no-figure
  (is (nil? (refusal "### Proposal 1: x\n\n```\nlet a = 1;\nlet b = 2;\n```\n")))
  (is (nil? (refusal "### Proposal 1: x\n\n```rust\nlet a = 1;\n```\n")))
  (is (nil? (refusal "### Proposal 1: x\n\n```text\n- +-------+\n- | One   |\n- +-------+\n- |\n- v\n- +-------+\n- | Two   |\n- +-------+\n+ plain\n```\n"))
      "a proposal's signed lines are code, not a diagram"))

(deftest section-proposal-says-where-it-lands
  (let [html (book-build/build (str "## Proposal 2: add to the vision of `books`\n\n+ One line.\n\nRuling: land, amend, or reject.\n\n"
                                    "## Technical part\n\n- a note\n") "")]
    (is (str/includes? html (str "<article class=\"proposal\"><header><span class=\"pnum\">2</span>"
                                 "<h2 class=\"target\"><code style=\"font-size:.95rem\">add to the vision of books</code></h2></header>")))
    (is (str/includes? html "<p>Ruling: land, amend, or reject.</p>\n</article>\n<h2>Technical part</h2>\n<ul><li>a note</li></ul>")
        "the next section closes the proposal")))

(deftest section-proposal-with-a-file-line
  (let [html (book-build/build "## Proposal 1: no noise\n\nFile: the vision of books\n\n+ Plain.\n" "")]
    (is (str/includes? html "<span class=\"pnum\">1</span><h2>no noise</h2></header>"))
    (is (str/includes? html "<p class=\"target\"><code>the vision of books</code></p>")))
  (let [html (book-build/build "## Proposal 1: T\n\nFile: `a/b.md`\n\n+ Plain.\n" "")]
    (is (str/includes? html "<h2>T</h2>"))
    (is (str/includes? html "<p class=\"target\"><code>a/b.md</code></p>"))))

(deftest signed-lines-of-one-paragraph-are-one-panel
  (let [html (book-build/build "## Proposal 1: x\n\n+ First added.\n+ Second added.\n+ - item one\n+ - item two\n" "")]
    (is (str/includes? html (str "<div class=\"diff\"><div class=\"add\"><p>First added. Second added.</p></div>"
                                 "<div class=\"add\"><ul><li>item one</li><li>item two</li></ul></div></div>"))
        "running text of one sign is one paragraph; a list after it is its own"))
  (let [html (book-build/build "## Proposal 1: x\n\n- Gone one.\n-\n- Gone two.\n" "")]
    (is (str/includes? html "<div class=\"del\"><p>Gone one.</p></div><div class=\"del\"><p>Gone two.</p></div>")
        "a removed blank line parts two removed paragraphs"))
  (let [html (book-build/build "## Proposal 1: x\n\n- old one\n-\n- old two\n+ new one\n+\n+ new two\n+\n+ three\n" "")]
    (is (str/includes? html (str "<p><del>old</del><ins>new</ins> one</p><p><del>old</del><ins>new</ins> two</p>"
                                 "<div class=\"add\"><p>three</p></div>"))
        "removed paragraphs pair in order with the added paragraphs after them"))
  (is (= "<div class=\"diff\"><p>first <del>old</del><ins>new</ins> line second <del>old</del><ins>new</ins> line</p></div>"
         (change "- first old line\n- second old line\n+ first new line\n+ second new line"))
      "inside a fence, lines still flow as one paragraph"))

(deftest a-wrapped-removed-sentence-is-one-box
  (let [html (book-build/build (str "## Proposal 1: x\n\n"
                                    "- A claim must be relayed as a claim; a thing is verified only by a\n"
                                    "- witness.\n") "")]
    (is (str/includes? html (str "<div class=\"diff\"><div class=\"del\"><p>A claim must be relayed as a claim;"
                                 " a thing is verified only by a witness.</p></div></div>")))
    (is (= 1 (count (re-seq #"class=\"del\"" html))) "one box, never one per physical line"))
  (let [html (book-build/build (str "## Proposal 1: x\n\n"
                                    "  - **Rulings**: each time the living decides,\n"
                                    "    approves or refuses something. Give the id\n"
                                    "-   when the flow logs it. Put the ruling\n"
                                    "-   in your own words.\n"
                                    "+   when the flow logs it.\n") "")]
    (is (str/includes? html "<ul><li><strong>Rulings</strong>: each time the living decides, approves or refuses something. Give the id</li></ul>")
        "a list item's wrapped continuation stays in its item")
    (is (str/includes? html "<p>when the flow logs it.<del> Put the ruling in your own words.</del></p>"))))

(deftest a-replaced-paragraph-is-one-marked-change
  (let [html (book-build/build (str "## Proposal 1: x\n\n"
                                    "  Every word comes from the transcript, the records or\n"
                                    "- the page; nothing is invented. A report is marked as reported, not as\n"
                                    "- seen.\n"
                                    "+ the page; nothing is invented.\n") "")]
    (is (str/includes? html (str "<div class=\"diff\"><div class=\"ctx\"><p>Every word comes from the transcript, the records or</p></div>"
                                 "<p>the page; nothing is invented.<del> A report is marked as reported, not as seen.</del></p></div>"))
        "the whole removed text is marked against the whole added text")
    (is (not (str/includes? html "<div class=\"del\">")) "no removed line is left in a box of its own")
    (is (not (re-find #"class=\"ctx\"><p>[^<]*<del>" html)) "no strike inside a kept paragraph"))
  (let [html (book-build/build (str "## Proposal 1: x\n\n"
                                    "- One old sentence here.\n- Another old sentence there.\n"
                                    "+ One new sentence here.\n+ Another sentence there.\n") "")]
    (is (str/includes? html "<p>One <del>old</del><ins>new</ins> sentence here. Another <del>old </del>sentence there.</p>"))))

(deftest kept-lines-in-a-proposal-are-the-module-around-the-change
  (let [html (book-build/build (str "### Proposal 1: x · a › b.md\n\n- Gone.\n\n"
                                    "#### psyche-belief › intent/main-flow.md\n\n"
                                    "  A claim is relayed as a claim.\n  Keep them separate.\n") "")]
    (is (str/includes? html (str "<h4 class=\"part\"><code>psyche-belief › intent/main-flow.md</code></h4>\n"
                                 "<div class=\"diff\"><div class=\"ctx\"><p>A claim is relayed as a claim. Keep them separate.</p></div></div>")))
    (is (not (str/includes? html "</h4>\n<p>A claim")) "never a plain paragraph")))

(deftest a-prose-change-with-unchanged-lines-is-a-change
  (let [html (book-build/build (str "## Proposal 1: x\n\n"
                                    "  A kept vision line.\n+\n"
                                    "+ First added line.\n+ Second added line.\n") "")]
    (is (str/includes? html (str "<div class=\"diff\"><div class=\"ctx\"><p>A kept vision line.</p></div>"
                                 "<div class=\"add\"><p>First added line. Second added line.</p></div></div>")))
    (is (not (str/includes? html "+ First"))))
  (let [html (book-build/build "## Proposal 1: x\n\n  Kept line.\n- Gone line.\n" "")]
    (is (str/includes? html (str "<div class=\"ctx\"><p>Kept line.</p></div>"
                                 "<div class=\"del\"><p>Gone line.</p></div>"))))
  (let [html (book-build/build "## Proposal 1: x\n\n  - kept item\n+ - added item\n" "")]
    (is (str/includes? html "<div class=\"add\"><ul><li>added item</li></ul></div>")))
  (let [html (book-build/build "## Section\n\n- an item\n  its continuation\n" "")]
    (is (not (str/includes? html "class=\"diff\""))
        "a list item's indented continuation is not a change")))

(deftest every-change-in-a-proposal-is-coloured
  (let [html (book-build/build "## Proposal 1: x\n\n- Gone line.\n  Kept line.\n" "")]
    (is (str/includes? html (str "<div class=\"diff\"><div class=\"del\"><p>Gone line.</p></div>"
                                 "<div class=\"ctx\"><p>Kept line.</p></div></div>"))
        "a removal followed directly by an unchanged line is red, then unchanged")
    (is (not (str/includes? html "<p>- Gone"))))
  (let [html (book-build/build "## Proposal 1: x\n\nFile: `skills/a/SKILL.md`\n- Gone line.\n  Kept line.\n" "")]
    (is (str/includes? html (str "<p class=\"target\"><code>skills/a/SKILL.md</code></p>\n"
                                 "<div class=\"diff\"><div class=\"del\"><p>Gone line.</p></div>"
                                 "<div class=\"ctx\"><p>Kept line.</p></div></div>"))
        "a File line may head its change without a blank line"))
  (let [html (book-build/build "## Proposal 1: x\n\n::: comment\nWhy.\n:::\n\n- Gone line.\n  Kept line.\n" "")]
    (is (str/includes? html "<div class=\"del\"><p>Gone line.</p></div><div class=\"ctx\"><p>Kept line.</p></div>")
        "a comment may head its change without a blank line"))
  (let [html (book-build/build "## Proposal 2: x\n\nNot yet parseable:\n- Gone line.\n+ New line.\n" "")]
    (is (str/includes? html "<del>Gone</del><ins>New</ins> line.") "prose glued to change lines is rendered, not refused")
    (is (str/includes? html "Not yet parseable:")))
  (let [html (book-build/build "## Proposal 1: x\n\n- Gone line.\nRuling: keep it.\n" "")]
    (is (str/includes? html "<p>Gone line.</p>") "an unsigned line after change lines is rendered, not refused")
    (is (str/includes? html "Ruling: keep it.")))
  (let [html (book-build/build "## Section\n\n- an item\n  its continuation\n" "")]
    (is (str/includes? html "<li>an item its continuation</li>")
        "outside a proposal a list stays a list"))
  (let [html (book-build/build "## Proposal 1: x\n\n+ Added.\n\n## Section\n\n- an item\n- another\n" "")]
    (is (str/includes? html "<ul><li>an item</li><li>another</li></ul>")
        "a level-two heading ends the proposal; a list after it is a list")))

(deftest exactly-two-spaces-keep-a-diff-paragraph-open
  (let [html (book-build/build (str "## Proposal 1: x\n\n"
                                    "  ## Kept heading\n"
                                    "  \n"
                                    "+ Added line.\n") "")]
    (is (str/includes? html (str "<div class=\"diff\"><div class=\"ctx\"><h4>Kept heading</h4></div>"
                                 "<div class=\"add\"><p>Added line.</p></div></div>")))
    (is (not (str/includes? html "<p>## Kept heading</p>")))))

(deftest title-from-presentation-datom
  (let [html (book-build/build "<!-- c -->\nPresentation.{ «Drawing ideas, 2nd edition» }\n\n# Drawing ideas\n" "")]
    (is (str/includes? html "<title>Drawing ideas, 2nd edition</title>"))
    (is (str/includes? html "<h1 class=\"book-title\">Drawing ideas</h1>"))
    (is (not (str/includes? html "Presentation.{"))))
  (is (str/includes? (book-build/build "# Plain\n" "") "<title>Plain</title>")))

(deftest signed-lines-inside-a-code-fence-stay-code
  (let [html (book-build/build "## Proposal 1: x\n\n```text\n  ```rust\n  fn a() {}\n+ ## x\n- old\n  ```\n```\n" "")]
    (is (str/includes? html (str "<pre class=\"code-diff\"><code class=\"language-rust\"><span class=\"ln\">fn a() {}\n</span>"
                                 "<span class=\"ln ln-add\">## x\n</span><span class=\"ln ln-del\">old\n</span></code></pre>")))
    (is (not (str/includes? html "<h4>x</h4>")) "+ ## x is a code line, not a heading")))

(deftest proposal-heading-without-a-blank-line-opens-a-proposal
  (let [html (book-build/build "### Proposal 1: a\n\nsome text\n## Proposal 2: b\nmore\n" "")]
    (is (str/includes? html "<p>some text</p>\n</article>\n<article class=\"proposal\"><header><span class=\"pnum\">2</span>"))
    (is (not (str/includes? html "## Proposal 2")))))

;; ------------------------------------------------------------- ethos
;; ethos-zero never stops a build: it only tags how a page renders.

(def accepted-ethos "Library\n[]\n[ Point.{ X.Integer Y.Integer } ]\n[]\n[]")

(deftest marked-ethos-is-a-highlighted-fragment-not-a-whole-document
  (let [calls (atom [])
        check (fn [text] (swap! calls conj text) nil)
        render (fn [body]
                 (binding [book-build/*enforce-ethos* true
                           book-build/*ethos-check* check
                           book-build/*highlight* true]
                   (book-build/build (str "### Proposal 1: Ethos · repo › x.ethos\n\n```ethos\n"
                                           body "\n```\n") "")))
        fragment "  Library\n  []\n- [ Old.Integer ]\n+ [ New.Integer ]\n\n  []\n  []"]
    (let [html (render fragment)]
      (is (empty? @calls) "a marked fragment never reaches the whole-document checker")
      (is (str/includes? html "class=\"language-ethos\""))
      (is (str/includes? html "ts-keyword") "the stripped sides are highlighted as Ethos")
      (is (str/includes? html "ln-del"))
      (is (str/includes? html "ln-add")))
    (reset! calls [])
    (render accepted-ethos)
    (is (= [accepted-ethos] @calls) "a root at column zero remains a whole Ethos document")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ethos rejected"
          (binding [book-build/*enforce-ethos* true
                    book-build/*ethos-check* (constantly "Rejected.invalid")]
            (book-build/build (str "### Proposal 1: Ethos · repo › x.ethos\n\n"
                                    "```ethos\nLibrary\ninvalid\n```\n") ""))))
    (reset! calls [])
    (render "\n")
    (is (= ["\n"] @calls) "an empty fence is not a fragment and reaches the whole checker")
    (is (= :context-only (book-build/ethos-fence-kind ["  Library" "  []"])))
    (is (= :malformed-fragment (book-build/ethos-fence-kind ["+ Library" "[]"])))
    (let [datom (binding [book-build/*enforce-ethos* true
                          book-build/*ethos-check* (fn [& _] (throw (Exception. "Ethos checker called")))]
                  (book-build/build (str "### Proposal 1: Datom · repo › x.datom\n\n"
                                          "```datom\n+ Lock.{ 1 «name» }\n```\n") ""))]
      (is (str/includes? datom "class=\"language-datom\""))
      (is (str/includes? datom "class=\"code-add\"")))))

;; A module view is the module as it stands, authenticated as source by book-check; its
;; fences are not whole Ethos documents, so one may use a type another fence declares.
(deftest a-module-view-fences-are-not-checked-as-whole-ethos-documents
  (let [calls (atom [])
        refuse-lone-use (fn [text] (swap! calls conj text)
                          (when-not (str/includes? text "RequestId.String") "Rejected.Undeclared.RequestId"))
        module-view (str "# B\n\n## psyche-belief \u203a vision/flow-nexus.md\n\n"
                         "A request.\n\n```ethos\nSendableRequest.[ Done.{ RequestId String } ]\nRequestId.String\n```\n\n"
                         "The delivered request.\n\n```ethos\nDeliveredRequest.[ Order.{ RequestId String } ]\n```\n")
        build (fn [md] (binding [book-build/*enforce-ethos* true
                                 book-build/*ethos-check* refuse-lone-use
                                 book-build/*highlight* true]
                         (book-build/build md "")))
        html (build (str module-view "\n### Proposal 1: x \u00b7 psyche-belief \u203a vision/flow-nexus.md\n\n+ Added.\n"))]
    (is (empty? @calls) "no fence of a module view reaches the whole-document checker")
    (is (= 2 (count (re-seq #"<code class=\"language-ethos\">" html))) "the fences keep Ethos highlighting")
    (is (str/includes? html "ts-"))
    (testing "a proposed fence keeps its check"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ethos rejected"
            (build (str module-view "\n### Proposal 1: x \u00b7 psyche-belief \u203a vision/flow-nexus.md\n\n"
                        "```ethos\nDeliveredRequest.[ Order.{ RequestId String } ]\n```\n"))))
      (is (= 1 (count @calls))))))

;; A fence of bare declarations is a fragment of an implied Library.
(deftest bare-declarations-are-checked-as-a-fragment-of-a-library
  (let [gate book-build/ethos-gate-text]
    (is (= "Library [] [ Depth.Integer Outline.{ Title.Text Vector<Outline> } ] [] []"
           (gate "Depth.Integer\nOutline.{ Title.Text Vector<Outline> }"))
        "a fragment of types")
    (is (= (str "Library [] [ Depth.Integer Outline.{ Title.Text Vector<Outline> } ] "
                "[ Structural.[ outline.{ [ Depth ] [ Vector<Outline> ] } ] ] []")
           (gate (str "Depth.Integer\nOutline.{ Title.Text Vector<Outline> }\n"
                      "Structural.[ outline.{ [ Depth ] [ Vector<Outline> ] } ]")))
        "types and traits mixed in one fence")
    (is (= "Library [] [] [ Deep.[ depth.[ Integer ] ] Structural.[ outline.{ [ Deep ] [ Vector<Outline> ] } ] ] []"
           (gate (str "Deep.[ depth.[ Integer ] ]                  ; the quality\n"
                      "Structural.[ outline.{ [ Deep ] [ Vector<Outline> ] } ]")))
        "comments are dropped; a trait body opens with a lowercase head")
    (is (= "Library [] [] [ Structural.[ outline.{ [ Depth ] [ Vector<Outline> ] } ] ] []"
           (gate "; unchanged\nStructural.[ outline.{ [ Depth ] [ Vector<Outline> ] } ]")))
    (is (= "Library [] [ Kind.[ A B ] Note.{ «a ; b» } ] [] []"
           (gate "Kind.[ A B ]\nNote.{ «a ; b» }"))
        "an enumeration is a type; a ; inside guillemets is not a comment")
    (doseq [rooted [accepted-ethos "Signal\n[]\n[]" "; note\nLibrary\n[]\n[]\n[]\n[]" "\n" ""]]
      (is (= rooted (gate rooted)) "a rooted or empty fence is checked whole"))
    (let [calls (atom [])
          render (fn [body]
                   (binding [book-build/*enforce-ethos* true
                             book-build/*ethos-check* (fn [text] (swap! calls conj text) nil)]
                     (book-build/build (str "# B\n\n```ethos\n" body "\n```\n") "")))]
      (render "Depth.Integer")
      (render accepted-ethos)
      (is (= ["Library [] [ Depth.Integer ] [] []" accepted-ethos] @calls)))))

(defn- fake-checker
  "An executable that refuses every file, naming itself in the refusal."
  [dir name]
  (let [f (str (fs/path dir name))]
    (spit f (str "#!/bin/sh\necho \"Rejected.{ ${1#Check.} { 1 1 } " name " }\"\nexit 1\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(deftest ethos-zero-refusal-does-not-stop-the-build
  (let [dir (fs/create-temp-dir)
        refusing (fake-checker dir "Refuser")]
    (try
      (binding [book-build/*ethos-zero* refusing book-build/*ethos-zero-real* refusing]
        (doseq [md ["# B\n\n```ethos\nPoint.{ X.Integer\n```\n"
                    "# B\n\n```ethos\nPoint.{ X.Integer }\n```\n"
                    "### Proposal 1: x\n\nFile: `r/x.ethos`\n\n```text\n  Library\n+ [ New ]\n```\n"
                    "# B\n\n```ethos\nLibrary\n- [ Old ]\n+ [ New ]\n```\n"
                    "# B\n\n```ethos\nA.{\n```\n\n```ethos\nB.[\n```\n"]]
          (is (string? (book-build/build md "")) md))
        (is (str/includes? (book-build/build (str "# B\n\n```ethos\n" accepted-ethos "\n```\n") "")
                           "<code class=\"language-ethos\">Library")))
      (binding [book-build/*ethos-zero* "/no/such/ethos-zero" book-build/*ethos-zero-real* "/no/such/ethos-zero"]
        (is (string? (book-build/build "# B\n\n## chronos \u203a x.ethos\n\n```ethos\nA\n```\n" ""))
            "an ethos-zero that cannot be had does not stop it either"))
      (finally (fs/delete-tree dir)))))

(deftest the-old-messaging-book-is-rendered
  (let [md (book-build/read-text (fs/path fixtures-dir "books/10-messaging-between-flows.md"))]
    (is (string? (binding [book-build/*ethos-check* (constantly "Rejected.1:1")] (book-build/build md ""))))))

(deftest cli-builds-against-a-refusing-ethos-zero
  (let [dir (fs/create-temp-dir)
        src (str (fs/path dir "b.md")) out (str (fs/path dir "b.html"))
        fake (fake-checker dir "Refuser")
        run #(apply clojure.java.shell/sh "bb" (str (fs/path tools-dir "book-build.clj")) src out "--figures" (str dir) %&)]
    (try (spit src (str "### Proposal 1: x · repo › x.md\n\n+ Added.\n\n```ethos\n" accepted-ethos "\n```\n\n```figure\n+------+\n| a    |\n+------+\n   | to\n   v\n+------+\n| b    |\n+------+\n```\n"))
         (spit (str (fs/path dir "fig-1.svg")) a-to-b)
         (let [r (run "--ethos-zero" fake "--ethos-zero-real" fake)]
          (is (= 1 (:exit r)) (:err r))
          (is (str/starts-with? (:err r) "book-build: ethos rejected:"))
          (is (not (fs/exists? out))))
         (finally (fs/delete-tree dir)))))

(deftest an-unlabelled-fence-is-code-only-inside-a-module-view
  (let [render (fn [md] (binding [book-build/*ethos-check* (fn [t] (when-not (str/starts-with? t "Library") "no"))]
                          (book-build/build md "")))
        in-module (fn [fence] (str "# B\n\n## mind-belief \u203a knowledge/x.md\n\n" fence))
        datom "```\nLaunch.{ { Psyche Primary } \u00abDraft\u00bb } ; one\nSucceeded\nStarted.7\n```\n"
        sketch (str "```\n+----------------+\n| a book         |\n+-------+--------+\n        | holds\n        v\n+-------+--------+\n| a section      |\n+----------------+\n```\n")
        left-out (fn [md] (str/includes? (stderr-of #(render md)) "left out"))]
    (is (str/includes? (render (in-module "```\nLibrary\n[]\n```\n")) "language-ethos")
        "in a module view, an unlabelled fence ethos-zero accepts is ethos")
    (is (str/includes? (render (in-module datom)) "language-datom")
        "in a module view, an unlabelled fence that reads as a datom is a datom")
    (is (left-out (str "# B\n\n" datom))
        "outside a module view an unlabelled fence is a diagram")
    (is (left-out "# B\n\n```\nLibrary\n[]\n```\n"))
    (is (left-out (in-module sketch))
        "a sketch with edges is a diagram, in a module view too")
    (is (left-out (str "# B\n\n" sketch)))
    (is (left-out (in-module "```\nsome { unbalanced\n```\n")))))

(deftest sketch-one-of-book-09-is-a-diagram
  (let [src (slurp (str (fs/path fixtures-dir "what-a-book-is.md")))]
    (is (str/includes? (stderr-of #(binding [book-build/*ethos-check* (constantly nil)] (book-build/build src "")))
                       "diagram 1 left out"))))

(deftest a-module-view-is-one-framed-sheet
  (let [html (binding [book-build/*ethos-check* (constantly nil)]
               (book-build/build (str "# B\n\n## mind-belief › knowledge/x.md\n\nIntro.\n\n## Its own heading\n\nBody.\n\n### Deeper\n\nMore.\n\n"
                                      "### Proposal 1: Land it\n\nProse.\n\n## repo › other.md\n\nAnother.\n") ""))]
    (is (str/includes? html "<article class=\"module diff\"><header><h2 class=\"target\"><code style=\"font-size:.95rem\">mind-belief › knowledge/x.md</code></h2></header>"))
    (is (str/includes? html "<h3>Its own heading</h3>") "the module's headings are demoted")
    (is (str/includes? html "<h4>Deeper</h4>"))
    (is (= 2 (count (re-seq #"<article class=\"module diff\">" html))))
    (is (= 1 (count (re-seq #"<article class=\"proposal\">" html))))
    (is (< (str/index-of html "Intro.") (str/index-of html "</article>") (str/index-of html "<article class=\"proposal\">"))
        "a proposal closes the module sheet")
    (is (= (count (re-seq #"<article" html)) (count (re-seq #"</article>" html))))))

(deftest markdown-is-rendered-as-text-never-a-pre
  (let [build #(binding [book-build/*ethos-check* (constantly nil)] (book-build/build % ""))
        body "## Rule\n\nA paragraph with `code`.\n\n- one\n- two\n"
        shows-text? (fn [html]
                      (and (str/includes? html "<h3>Rule</h3>") (str/includes? html "<li>one</li>")
                           (str/includes? html "<p>A paragraph with <code>code</code>.</p>")
                           (not (str/includes? html "<pre"))))]
    (doseq [open ["```markdown" "```md" "````markdown" "~~~md" "~~~~markdown"]
            :let [close (str/replace open #"[a-z]+$" "")]]
      (is (shows-text? (build (str "# B\n\n" open "\n" body close "\n")))
          (str "a " open " fence is text")))
    (is (shows-text? (build (str "# B\n\n## mind-belief › knowledge/x.md\n\n```text\n" body "```\n")))
        "a module view of a .md module, written in a text fence, is text")
    (is (shows-text? (build (str "# B\n\n## mind-belief › knowledge/x.md\n\n~~~~markdown\n" body "~~~~\n")))
        "a module view in a tilde fence is text")
    (let [html (build (str "# B\n\n## mind-belief › knowledge/x.md\n\n````markdown\n" body "\n```rust\nfn a() {}\n```\n````\n"))]
      (is (str/includes? html "<h3>Rule</h3>"))
      (is (= 1 (count (re-seq #"<pre" html))) "the module's own code stays code")
      (is (str/includes? html "language-rust")))))

(deftest a-code-file-view-is-a-framed-sheet-of-code-never-parsed
  (let [file "(Types\n  chronos)\n- not a change\n"
        src (str "# B\n\n## chronos \u203a .ethos/data/chronos.ethos\n\n```ethos\n" file "```\n\n### Proposal 1: x\n\nProse.\n")
        build (fn [check] (binding [book-build/*ethos-check* check] (book-build/build src "")))
        refused (build (constantly "Rejected.1:1"))
        read (build (constantly nil))]
    (is (str/includes? refused "<article class=\"module diff\"><header><h2 class=\"target\"><code style=\"font-size:.95rem\">chronos \u203a .ethos/data/chronos.ethos</code></h2></header>"))
    (is (str/includes? refused "<pre><code class=\"language-ethos\">(Types\n  chronos)\n- not a change</code></pre>")
        "the file is code in its language, a line opening with - is no change")
    (is (str/includes? refused "not read by current Ethos") "a file no ethos-zero reads is tagged")
    (is (not (str/includes? read "not read by current Ethos")))
    (is (= 1 (count (re-seq #"<article class=\"proposal\">" refused))))
    (is (str/includes? (binding [book-build/*ethos-check* (constantly nil)]
                         (book-build/build "# B\n\n## tools \u203a x.mjs\n\n```\nconst a = 1;\n```\n" ""))
                       "language-javascript") "an unlabelled fence takes the file's language")))

(deftest no-built-page-holds-an-undrawn-drawing
  (let [render (fn [md] (binding [book-build/*ethos-check* (constantly nil)] (book-build/build md "")))
        box "+------+\n| a    |\n+------+\n   | to\n   v\n+------+\n| b    |\n+------+\n"]
    (doseq [md [(str "# B\n\n### Proposal 1: t\n\nFile: a.md\n\n```\n" box "```\n")
                (str "# B\n\n### Proposal 1: t\n\nFile: a.md\n\n```text\n" box "```\n")
                (str "# B\n\n```txt\n" box "```\n")
                (str "# B\n\n```\n+------+\n| a    |\n+------+\n```\n")
                (str "# B\n\n## mind-belief \u203a knowledge/x.md\n\n```text\n" box "```\n")]]
      (let [err (java.io.StringWriter.)
            html (binding [*err* err] (render md))]
        (is (str/includes? (str err) "left out") md)
        (is (not (re-find #"\+---" html)) "the sketch is not shown raw")))
    (is (not (re-find #"\+---" (render "# B\n\n### Proposal 1: t\n\nFile: a.md\n\n```text\nplain words\n```\n"))))))

(deftest a-diagram-in-a-module-view-is-drawn-in-document-order
  (let [box "+------+\n| a    |\n+------+\n   | to\n   v\n+------+\n| b    |\n+------+\n"
        glyph "┌───┐\n│ a │\n└───┘\n"
        render #(binding [book-build/*ethos-check* (constantly nil)] (book-build/build % ""))]
    (doseq [[md figs] [[(str "# B\n\n## mind-belief \u203a knowledge/x.md\n\n````markdown\nProse.\n\n```\n" box "```\n\nMore.\n\n```\n" glyph "```\n````\n")
                        {"fig-1.svg" a-to-b "fig-2.svg" box-a}]
                       [(str "# B\n\n## tools \u203a x.mjs\n\n```\n" glyph "```\n") {"fig-1.svg" box-a}]]]
      (is (str/includes? (stderr-of #(render md)) "diagram 1 left out"))
      (let [html (with-figures figs #(render md))]
        (is (not (re-find #"<pre><code[^>]*>(?:[^<]*)[+\u250c]" html)) "no raw drawing")
        (is (str/includes? html "figure"))))
    (is (str/includes? (stderr-of #(with-figures {"fig-1.svg" a-to-b}
            (fn [] (render (str "# B\n\n## mind-belief \u203a knowledge/x.md\n\n````markdown\n```\n" box "```\n\n```\n" glyph "```\n````\n")))))
                       "diagram 2 left out"))))

(defn- built [md] (binding [book-build/*ethos-check* (constantly nil)] (book-build/build md "")))

(def ^:private head "# B\n\n### Proposal 1: Replace · psyche-belief › vision/book.md\n\n")

(deftest an-author-comment-is-a-labelled-box-before-the-proposal-it-explains
  (let [html (built (str head "::: comment\nProposed because the rule repeats.\n:::\n\n- Old.\n+ New.\n"))]
    (is (str/includes? html (str "<aside class=\"comment\" aria-label=\"Why\">"
                                 "<div class=\"comment-label\">Why</div>"
                                 "<div class=\"comment-body\"><p>Proposed because the rule repeats.</p></div></aside>")))
    (is (< (str/index-of html "class=\"comment\"") (str/index-of html "class=\"diff\"")) "the comment comes before the lines")
    (is (< (str/index-of html "class=\"proposal\"") (str/index-of html "class=\"comment\"")) "inside its proposal"))
  (is (str/includes? (built (str head "::: comment\nUses `code` and **bold**.\n:::\n\n+ New.\n"))
                     "<p>Uses <code>code</code> and <strong>bold</strong>.</p>"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside proposal"
        (built "# B\n\n::: comment\nLoose.\n:::\n"))))

(deftest comment-and-move-containers-are-distinct
  (let [html (built (str head
                         "::: comment\nThe passage moves as one.\n:::\n\n"
                         "::: move proposed\nfrom: vision/old.md\nto: vision/new.md\n\nMoved passage.\n:::\n"))]
    (is (str/includes? html "<div class=\"comment-body\"><p>The passage moves as one.</p></div>"))
    (is (str/includes? html "<section class=\"move move-proposed\">"))
    (is (= 1 (count (re-seq #"The passage moves as one\." html))))))

(deftest the-comment-has-its-own-colour-light-and-dark
  (let [vars (re-seq #"--comment-(?:bg|edge|fg):(#[0-9a-f]{6})" code)
        colours (set (map second vars))]
    (is (= 9 (count vars)) "three comment colours in light, dark, and the dark theme")
    (is (str/includes? code ".comment{border:1px solid var(--comment-edge);border-left-width:4px"))
    (is (str/includes? code "margin:18px 0 20px"))
    (is (str/includes? code ".comment-label{"))
    (doseq [c ["#2f8a46" "#c2372b" "#5cc47a" "#f07a6e" "#1c5f8a" "#7cc0ee"]]
      (is (not (colours c)) (str "not the green, red or module colour " c)))))

(deftest a-shown-container-is-a-source-headed-fourth-colour-module-excerpt
  (let [html (built (str head "::: shown\npsyche-belief › vision/book.md\nLibrary\n[]\n:::\n\n+ New.\n"))]
    (is (str/includes? html (str "<section class=\"shown\"><header><span class=\"mlabel\">as it stands</span>"
                                 "<code>psyche-belief › vision/book.md</code></header>")))
    (is (str/includes? html "</header><div class=\"markdown\"><p>Library []</p></div></section>")
        "a Markdown module's excerpt is prose, rendered as text that wraps")
    (is (not (re-find #"<section class=\"shown\">.*<pre" html)) "no code block for prose")
    (is (not (re-find #"shown[^>]*(?:add|del|move)|<(?:ins|del)>Library" html)))
    (is (str/includes? code ".shown{border:1px solid var(--shown-edge);border-left-width:5px"))
    (doseq [other ["var(--add-edge)" "var(--del-edge)" "var(--moved-edge)"]]
      (is (not (str/includes? (second (re-find #"(?s)(\.shown\{[^}]+)" code)) other))))))

(deftest a-shown-ethos-excerpt-is-highlighted-but-not-whole-file-checked
  (let [source (str head "::: shown\npsyche-belief › vision/example.ethos\nRecord.{ String\n:::\n\n+ New.\n")
        html (binding [book-build/*ethos-check* (fn [& _] (throw (Exception. "whole checker called")))
                       book-build/*highlight* false]
               (book-build/build source ""))]
    (is (str/includes? html "<code class=\"language-ethos\">Record.{ String</code>"))))

(deftest signed-figures-show-green-or-red
  (let [fig (fn [sign] (str head "```text\n" sign " +---+   +---+\n" sign " | a |-->| b |\n" sign " +---+   +---+\n```\n"))
        unsigned (str head "```text\n+---+   +---+\n| a |-->| b |\n+---+   +---+\n```\n")]
    (with-figures {"fig-1.svg" (faithful 1 "a" "b")}
      #(let [add (built (fig "+")) del (built (fig "-")) plain (built unsigned)]
         (is (re-find #"<div class=\"diff\"><div class=\"figmark fig-add\"><div class=\"figure\"" add))
         (is (not (re-find #"<ins>|<del>" add)))
         (is (re-find #"<div class=\"diff\"><div class=\"figmark fig-del\"><div class=\"figure\"" del))
         (is (not (re-find #"class=\"(add|del)\"" plain)) "an unsigned figure renders as before")
         (is (str/includes? plain "<div class=\"figure\""))))))

(deftest uniformly-signed-mermaid-renders-one-svg-in-its-change-margin
  (let [source (fn [sign] (str head "```mermaid\n"
                               sign " flowchart LR\n"
                               sign "     a[\"a\"] -- \"to\" --> b[\"b\"]\n```\n"))]
    (let [html (with-figures {"fig-1.svg" a-to-b} #(built (source "+")))]
      (is (str/includes? html "<div class=\"figmark fig-add\"><div class=\"figure\""))
      (is (= 1 (count (re-seq #"<svg" html))) "the stripped Mermaid source becomes one actual SVG")
      (is (not (str/includes? html "+ flowchart")) "the source marker is absent only from renderer input"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mixed Mermaid change"
          (built (str head "```mermaid\n+ flowchart LR\n-     a --> b\n```\n"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mixed Mermaid change"
          (built (str head "```mermaid\n+ flowchart LR\n\n+     a --> b\n```\n"))))))

(deftest a-removed-mermaid-fence-is-its-source-as-code
  (let [body "- flowchart LR\n-     a[\"a\"] -- \"to\" --> b[\"b\"]"
        source "flowchart LR\n    a[\"a\"] -- \"to\" --&gt; b[\"b\"]</code></pre>"]
    (doseq [[src margin]
            [[(str head "```mermaid\n" body "\n```\n") "<pre class=\"code-del\">"]
             [(str head "```text\n" body "\n```\n") "<pre class=\"code-del\">"]
             [(str head "- ```mermaid\n" body "\n- ```\n") "<div class=\"del\"><pre>"]]]
      (let [html (built src)]   ; no figures directory: nothing is drawn, nothing is refused
        (is (str/includes? html (str margin "<code class=\"language-mermaid\">" source)) src)
        (is (not (str/includes? html "<svg")))
        (is (not (str/includes? html "figmark")))))))

(deftest only-a-drawn-mermaid-fence-takes-a-figure-number
  ;; a removed fence, however it is written, is code and takes no number: the
  ;; added fence after it is fig-1, the only figure the page asks for
  (let [removed "- flowchart TB\n-     r[\"gone\"] --> q[\"end\"]\n"
        added "+ flowchart TB\n+     a[\"new\"] --> q[\"end\"]\n"
        pages [(str head "```mermaid\n" removed "```\n\n```mermaid\n" added "```\n")
               (str head "```text\n" removed "```\n\n```mermaid\n" added "```\n")
               (str head "- ```mermaid\n" removed "- ```\n\n+ ```mermaid\n" added "+ ```\n")]]
    (doseq [src pages]
      (with-figures {"fig-1.svg" (faithful 1 "new")}
        #(let [html (built src)]
           (is (= 1 (count (re-seq #"class=\"figure\"" html))) src)
           (is (str/includes? html ">new<") "the added fence is fig-1")
           (is (str/includes? html "r[\"gone\"] --&gt;") "the removed fence is its source"))))
    (with-figures {}
      #(is (= "figure fig-1.svg not found; Mermaid fence 1 unrendered" (refusal (first pages)))
           "the missing figure asked for is the added fence's fig-1"))))

(deftest signed-code-fences-show-as-code
  (let [fence (fn [lang sign body] (str head "```" lang "\n" (str/join "\n" (map #(str sign " " %) body)) "\n```\n"))
        ethos ["Library" "[]" "[ Lock.{ Vector<String> } ]" "[]" "[]"]
        datom ["Lock.{ 1 «name» }"]]
    (binding [book-build/*ethos-check* (constantly nil)]
      (doseq [sign ["+" "-"]]
        (let [e (built (fence "" sign ethos)) d (built (fence "" sign datom)) l (built (fence "rust" sign ["let a = 1;"]))]
          (is (str/includes? e "<code class=\"language-ethos\">Library\n[]"))
          (is (not (str/includes? e "data-diff")))
          (is (str/includes? e (str "<pre class=\"" (if (= sign "+") "code-add" "code-del") "\">")))
          (is (str/includes? d "language-datom"))
          (is (str/includes? l "<code class=\"language-rust\">let a = 1;</code>"))))
      (is (str/includes? (built (fence "datom" "+" datom)) "language-datom") "a label is kept")
      (is (str/includes? (built (str head "```rust\n- let a = 1;\n+ let a = 2;\n```\n")) "<span class=\"ln ln-del\">")
          "removed then added stays a diff"))))

(deftest move-containers-render-once-and-share-figures
  (let [src (str "::: move proposed\nfrom: vision/<old>.md\nto: vision/new.md\n\n"
                 "# Inner\n\nMoved <body>.\n\n```mermaid\nA --> B\n```\n\n:::\n\n"
                 "```mermaid\nC --> D\n```\n")
        svg (faithful 2 "A" "moves" "B" "then" "C")
        html (with-figures {"fig-1.svg" svg "fig-2.svg" svg}
               #(book-build/build src ""))]
    (is (str/includes? html "<section class=\"move move-proposed\">") )
    (is (str/includes? html "From vision/&lt;old&gt;.md → To vision/new.md"))
    (is (str/includes? html ">Proposed</span>"))
    (is (= 1 (count (re-seq #"Moved &lt;body&gt;\." html))))
    (is (not (str/includes? html "<meta name=\"viewport\"")))
    (is (not (str/includes? html "<title>Inner</title>")))
    (is (= 2 (count (re-seq #"class=\"figure\"" html))) "move and adjacent figure share numbering")))

(deftest a-missing-move-mermaid-figure-refuses-the-build
  (let [src "::: move landed\nfrom: a\nto: b\n\n```mermaid\nA --> B\n```\n:::"
        message (refusal src)]
    (is (= "figure fig-1.svg not found; Mermaid fence 1 unrendered" message)))
  (with-figures {"fig-1.svg" "not svg"}
    #(is (str/starts-with?
           (refusal "::: move landed\nfrom: a\nto: b\n\n```mermaid\nA --> B\n```\n:::")
           "figure fig-1.svg unrendered:"))))

(deftest move-container-refusals-and-fenced-delimiter
  (doseq [op ["::: move" "::: move bogus"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source line 1"
          (book-build/build (str op "\nfrom: a\nto: b\n\nbody\n:::") ""))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source line 1"
        (book-build/build "::: move proposed\nfrom:   \nto: b\n\nbody\n:::" "")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source line 1"
        (book-build/build "::: move proposed\nfrom: a\nto:   \n\nbody\n:::" "")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source line 1"
        (book-build/build "::: move proposed\nfrom: a\nto: b\n\nbody" "")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested move container"
        (book-build/build "::: move proposed\nfrom: a\nto: b\n\n::: move landed\nfrom: c\nto: d\n\nbody\n:::\n:::" "")))
  (doseq [fence ["```rust\n::: move bogus\n```" "~~~rust\n::: move bogus\n~~~" "````rust\n::: move bogus\n````"]]
    (is (str/includes? (book-build/build (str "::: move proposed\nfrom: a\nto: b\n\n" fence "\n:::") "") "::: move bogus"))))

(deftest fences-under-the-addition-margin
  ;; a fence whose every line, the fence marks included, stands under "+ "
  (let [src (str head "+ ```mermaid\n+ flowchart LR\n+     a[\"a\"] -- \"to\" --> b[\"b\"]\n+ ```\n+\n"
                 "+ ```rust\n+ let a = 1;\n+ ```\n")
        html (with-figures {"fig-1.svg" a-to-b} #(built src))]
    (is (= 1 (count (re-seq #"<div class=\"figmark fig-add\"><div class=\"figure\"" html)))
        "the mermaid fence is a figure in the addition colour")
    (is (not (str/includes? html "flowchart LR")) "no mermaid source is shown")
    (is (str/includes? html "<div class=\"add\"><pre><code class=\"language-rust\">let a = 1;</code>")
        "the code fence is code in the addition colour, its margin stripped")))

(def mermaid-margin-src
  (str "# B\n\n"
       "```mermaid\nflowchart TB\n    p[plain] --> q[end]\n```\n\n"
       "### Proposal 1: Figures · psyche-belief › vision/book.md\n\n"
       "  ```mermaid\n  flowchart TB\n      c[context] --> q[end]\n  ```\n\n"
       "+ ```mermaid\n+ flowchart TB\n+     a[added] --> q[end]\n+ ```\n\n"
       "- ```mermaid\n- flowchart TB\n-     r[removed] --> q[end]\n- ```\n"))

(deftest every-mermaid-margin-keeps-document-order-and-colour
  (let [figures {"fig-1.svg" (faithful 1 "plain")
                 "fig-2.svg" (faithful 1 "context")
                 "fig-3.svg" (faithful 1 "added")}
        html (str/replace (with-figures figures #(built mermaid-margin-src)) book-build/colour-key "")]
    (is (= 3 (count (re-seq #"class=\"figure\"" html))) "a removed fence is not drawn")
    (is (apply < (map #(str/index-of html %) [">plain<" ">context<" ">added<" "r[removed]"]))
        "fig-N.svg follows source order across every margin")
    (is (re-find #"class=\"ctx\"><div class=\"figure\"" html) "two-space context keeps its margin")
    (is (re-find #"class=\"figmark fig-add\"><div class=\"figure\"" html) "added keeps green margin")
    (is (not (str/includes? html "fig-del")) "a removed fence is no figure")
    (is (re-find #"<div class=\"del\"><pre><code class=\"language-mermaid\">flowchart TB\n    r\[removed\]" html)
        "removed is its source as code under the red margin")
    (is (= 1 (count (re-seq #"flowchart TB" html))) "no other Mermaid fence is left as code")))

(deftest an-unrendered-mermaid-names-the-ordered-figure
  (with-figures {"fig-1.svg" (faithful 1 "plain")
                 "fig-2.svg" (faithful 1 "context")}
    #(is (= "figure fig-3.svg not found; Mermaid fence 3 unrendered"
            (refusal mermaid-margin-src))))
  (with-figures {"fig-1.svg" (faithful 1 "plain")
                 "fig-2.svg" (faithful 1 "context")
                 "fig-3.svg" "not an svg"}
    #(is (str/starts-with? (refusal mermaid-margin-src) "figure fig-3.svg unrendered:"))))

(deftest cli-refuses-an-unrendered-mermaid-without-writing-the-page
  (let [dir (fs/create-temp-dir)
        src (str (fs/path dir "b.md"))
        out (str (fs/path dir "b.html"))]
    (try
      (spit src "### Proposal 1: x · repo › x.md\n\n+ Added.\n\n```mermaid\nflowchart TB\n    a --> b\n```\n")
      (let [r (clojure.java.shell/sh "bb" (str (fs/path tools-dir "book-build.clj")) src out
                                     "--figures" (str dir))]
        (is (= 1 (:exit r)))
        (is (= "book-build: figure fig-1.svg not found; Mermaid fence 1 unrendered\n" (:err r)))
        (is (not (fs/exists? out))))
      (finally (fs/delete-tree dir)))))

(deftest prose-fence-renders-as-text
  ;; one list, book-fence-languages.json, read by book-build.clj
  (let [langs (cheshire.core/parse-string (slurp (str (fs/path tools-dir "book-fence-languages.json"))))]
    (is (= (set (langs "text")) book-build/text-languages))
    (is (= (set (langs "markdown")) book-build/markdown-languages))))

(deftest alternatives-are-proposals-of-their-own
  (is (not (re-find #"\.alt-|\.alternatives?\{" code)) "no alternatives styles in the page code")
  (is (not (str/includes? (built (str head "**Alternative 1**\n\n- Old.\n+ New.\n")) "class=\"alternatives\""))))

(deftest destroy-and-archive-show-the-deleted-module-whole-under-the-red-margin
  (is (not (re-find #"destroy-edge|archive-edge|--destroy|--archive" code)) "no violet or amber record colours")
  (is (str/includes? code ".record{border-left:4px solid var(--del-edge)"))
  (is (str/includes? code "content:\"\\2715  Destroy\""))
  (let [html (built (str head "**Archive**\n\n```text\n- Whole line.\n```\n"))]
    (is (str/includes? html "<div class=\"record archive\">"))
    (is (str/includes? html "<div class=\"del\"><p>Whole line.</p></div>"))))

(def ^:private review-src
  "Presentation.{ «A review» Review }\n\n# A review\n\nCreated by Opus, helped by Sonnet (the checking).\n\n### Proposal 1: Landed · psyche-belief › vision/book.md\n\n- Old.\n+ New.\n")

(deftest the-kind-is-data-in-the-presentation-datom
  (is (= "Proposal" (book-build/presentation-kind "Presentation.{ «T» }\n\n# T\n")) "absent means Proposal")
  (is (= "Proposal" (book-build/presentation-kind "# T\n")))
  (is (= "Proposal" (book-build/presentation-kind "Presentation.{ «T» Proposal }\n")))
  (is (= "Review" (book-build/presentation-kind "Presentation.{ «T» Review }\n")))
  (is (= "Account" (book-build/presentation-kind "Presentation.{ «T» Account }\n")))
  (is (= "T" (book-build/presentation-title "Presentation.{ «T» Review }\n")))
  (let [review (built review-src)
        proposal (built (str/replace review-src " Review }" " Proposal }"))
        plain (built (str/replace review-src " Review }" " }"))]
    (is (str/includes? review "<meta name=\"book-kind\" content=\"review\">") "a review says so")
    (is (not (str/includes? proposal "book-kind")) "a proposal shows the light shade")
    (is (= proposal plain))
    (is (str/includes? review "<title>A review</title>")))
  (is (str/includes? code "--add-landed-edge") "landed edge colours for add and del")
  (is (str/includes? code "--del-landed-edge"))
  (is (re-find #":root:has\(meta\[name=\"book-kind\"\]\[content=\"review\"\]\)\{--add-edge:var\(--add-landed-edge\);--del-edge:var\(--del-landed-edge\)\}" code)))

(deftest the-signature-line-is-the-subtitle
  (doseq [kind ["Proposal" "Review" "Account"]]
    (let [html (built (str/replace review-src "Review" kind))]
      (is (str/includes? html "<p class=\"book-subtitle\">Created by Opus, helped by Sonnet (the checking).</p>") kind))))

(deftest an-account-builds-with-its-prose-and-headings
  (let [html (built "Presentation.{ «An account» Account }\n\n# An account\n\nCreated by Fable.\n\n## The gist\n\nIt happened so.\n")]
    (is (str/includes? html "<h2>The gist</h2>"))
    (is (str/includes? html "<p>It happened so.</p>"))))

(deftest an-account-shows-a-code-fence-as-code
  (let [html (built "Presentation.{ «An account» Account }\n\n# An account\n\nCreated by Fable.\n\n## The gist\n\n```rust\nfn f() {}\n```\n")]
    (is (str/includes? html "<code class=\"language-rust\">"))))

(deftest a-context-mermaid-fence-is-a-figure
  (let [html (with-figures {"fig-1.svg" a-to-b}
               #(built "# B\n\n```mermaid\nflowchart LR\n    a[\"a\"] -- \"to\" --> b[\"b\"]\n```\n"))]
    (is (= 1 (count (re-seq #"class=\"figure\"" html))))
    (is (not (str/includes? html "flowchart LR")))))

(def ^:private written-colour
  ;; a colour a figure was drawn with, left in an attribute or a declaration
  #"(?i)(?:fill|stroke|color|stop-color|flood-color)\s*(?:=\s*[\"']|:\s*)\s*(?:#[0-9a-f]{3,8}|rgba?\(|hsla?\(|black|white|gr[ae]y|red|blue|green|silver|navy)")

(deftest every-figure-colour-is-the-page-theme
  (doseq [f ["white-boxes.svg" "black-current-colour.svg"]
          :let [el (book-build/illustration-el (slurp (str (fs/path fixtures-dir "figures" f))) "f1-")]]
    (is (not (re-find written-colour el)) (str f ": no colour of its own is left"))
    (is (re-find #"<svg [^>]*style=\"[^\"]*color:var\(--fg\)" el) (str f ": currentColor is the page text colour")))
  (let [el (book-build/illustration-el (slurp (str (fs/path fixtures-dir "figures" "white-boxes.svg"))))]
    (is (str/includes? el "<g stroke-width=\"1.5\" style=\"fill:var(--chip);stroke:var(--fg)\">") "white boxes take the surface, their borders the ink")
    (is (str/includes? el "<path d=\"M0,0 L10,5 L0,10 z\" style=\"fill:var(--fg)\"/>") "the arrowhead is ink")
    (is (str/includes? el "<g font-size=\"16\" text-anchor=\"middle\" style=\"fill:var(--fg)\">") "labels are ink"))
  (let [svg (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
                 "<style>rect{fill:#fafafa;stroke:rgb(40,40,40)} text.x{fill:hsl(0,0%,10%) !important}</style>"
                 "<rect fill=\"black\" width=\"9\" height=\"9\"/><text fill=\"#ffffff\">on black</text>"
                 "<rect fill=\"#e8f0ff\" stroke=\"#c33\" width=\"9\" height=\"9\"/><text style=\"fill:#777\">grey</text>"
                 "<line stroke=\"none\" fill=\"url(#g)\"/><path fill=\"none\" stroke=\"currentColor\"/><text>unpainted</text></svg>")
        el (book-build/illustration-el svg)]
    (is (not (re-find written-colour el)) "no colour of its own is left")
    (is (str/includes? el "rect{fill:var(--chip);stroke:var(--fg)}") "a style sheet's light fill is surface, its stroke ink")
    (is (str/includes? el "text.x{fill:var(--fg) !important}") "a style sheet's text is ink")
    (is (str/includes? el "<rect width=\"9\" height=\"9\" style=\"fill:var(--fg)\"/><text style=\"fill:var(--chip)\">on black</text>")
        "a dark box is ink and the white text drawn on it is surface")
    (is (str/includes? el "style=\"fill:var(--chip);stroke:var(--fg)\"") "a tinted box is surface, a red border ink")
    (is (str/includes? el "<text style=\"fill:var(--fg)\">grey</text>") "grey text is ink")
    (is (str/includes? el "<line stroke=\"none\" fill=\"url(#g)\"/>") "none and a paint server are kept")
    (is (str/includes? el "<text>unpainted</text>") "unpainted text inherits the root's ink")
    (is (re-find #"<svg [^>]*style=\"color:var\(--fg\);fill:var\(--fg\);" el) "the root's ink is the page text colour")))

(deftest every-figure-on-a-page-has-its-own-ids
  (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 9 9\"><marker id=\"a\"/><line marker-end=\"url(#a)\"/></svg>"
        src (str "# B\n\n```mermaid\nflowchart TB\n  a --> b\n```\n\n```mermaid\nflowchart TB\n  c --> d\n```\n")
        html (with-figures {"fig-1.svg" svg "fig-2.svg" svg} #(book-build/build src ""))]
    (is (str/includes? html "<marker id=\"f1-a\"/><line marker-end=\"url(#f1-a)\"/>"))
    (is (str/includes? html "<marker id=\"f2-a\"/><line marker-end=\"url(#f2-a)\"/>"))))

(deftest a-prose-excerpt-is-text-and-a-code-excerpt-is-code
  (let [shown (fn [path body] (built (str head "::: shown\npsyche-belief › " path "\n" body "\n:::\n\n+ New.\n")))]
    (is (str/includes? (shown "vision/a.md" "One line\nwrapped here.") "<div class=\"markdown\"><p>One line wrapped here.</p></div>"))
    (is (str/includes? (shown "notes/a.txt" "One line\n  kept.") "<div class=\"textblock\">One line\n  kept.</div>"))
    (is (str/includes? (shown "src/a.rs" "let a = 1;") "<pre><code class=\"language-rust\">let a = 1;</code></pre>"))))

(deftest a-presentation-with-change-marks-opens-with-the-colour-key
  (let [html (built (str head "+ New.\n"))]
    (is (= 1 (count (re-seq #"class=\"key\"" html))))
    (is (str/includes? html (str "<h1 class=\"book-title\">B</h1>\n" book-build/colour-key)) "the key follows the title"))
  (let [html (built "Presentation.{ «B» Proposal }\n\n# B\n\nCreated by a test.\n\n### Proposal 1: T · psyche-belief › vision/book.md\n\n+ New.\n")]
    (is (str/includes? html (str "<p class=\"book-subtitle\">Created by a test.</p>\n" book-build/colour-key)) "and the signature line"))
  (let [html (built (str head "::: move proposed\nfrom: vision/old.md\nto: vision/new.md\n\nMoved.\n:::\n"))]
    (is (= 1 (count (re-seq #"class=\"key\"" html))) "a moved passage's own build adds no second key"))
  (is (not (str/includes? (book-build/build "# B\n\n## psyche-belief › vision/book.md\n\nAs it stands.\n" "") "class=\"key\""))
      "a page that changes nothing has no key")
  (is (not (str/includes? (book-build/build "+ New.\n" "") "class=\"key\"")) "a fragment without a title has no key")
  (doseq [sw ["sw-add{background:var(--add-proposed-edge)}" "sw-add-landed{background:var(--add-landed-edge)}"
              "sw-del{background:var(--del-proposed-edge)}" "sw-del-landed{background:var(--del-landed-edge)}"
              "sw-move{background:var(--move-proposed-edge)}" "sw-move-landed{background:var(--move-landed-edge)}"
              "sw-shown{background:var(--shown-edge)}" "sw-module{background:var(--accent)}"]]
    (is (str/includes? code sw) (str "the swatch is the page colour: " sw)))
  (is (= 3 (count (re-seq #"--add-proposed-edge:#" code))) "the proposed green holds in a review, whose marks are landed"))

;; A proposal in place: its heading, comment and added lines inside the module's sheet,
;; between the module lines around the place it lands.
(deftest a-proposal-in-place-stands-inside-the-module-sheet
  (let [md (str "# B\n\n## psyche-belief \u203a vision/rule.md\n\nFirst line.\n\n"
                "#### Proposal 1: Added \u00b7 psyche-belief \u203a vision/rule.md\n\n::: comment\nWhy.\n:::\n\n"
                "+ Added line.\n\nSecond line.\n\n"
                "#### Proposal 2: Last \u00b7 psyche-belief \u203a vision/rule.md\n\n+ At the end.\n")
        html (book-build/build md "")
        at #(str/index-of html %)]
    (is (= 1 (count (re-seq #"<article class=\"module diff\">" html))) "one module sheet")
    (is (str/includes? html "<section class=\"proposal in-place\"><header><span class=\"pnum\">1</span><h3>Added</h3></header>"))
    (is (< (at "First line.") (at "pnum\">1<") (at "comment-label") (at "class=\"add\"><p>Added line.") (at "Second line.")
           (at "pnum\">2<") (at "At the end.") (at "</article>"))
        "the proposal sits between the module lines it lands among, and the last one inside the sheet")
    (is (= 2 (count (re-seq #"</section>" html))) "each proposal in place is closed")
    (is (not (str/includes? html "<article class=\"proposal\">")) "no proposal sheet of its own")))

(deftest a-module-title-remains-a-module-title
  (let [html (book-build/build
              (str "Presentation.{ «The book» Proposal }\n\n# The book\n\n"
                   "## repo › rule.md\n\n# The module\n\n"
                   "#### Proposal 1: Add · repo › rule.md\n\n+ Added.\n")
              "")
        module-at (str/index-of html "<article class=\"module diff\">")
        module-end (str/index-of html "</article>" module-at)
        module-html (subs html module-at (+ module-end (count "</article>")))]
    (is (str/includes? module-html "<h3 class=\"module-title\">The module</h3>")
        "an authenticated level-one module heading remains visible as module structure")
    (is (not (re-find #"<(?:meta|title|h1 class=\"book-title\")" module-html))
        "document chrome occurs only outside module views")
    (is (= [{:line 2 :reason "book-heading-inside-module-view" :element "h1.book-title"}]
           (book-check/check-built-page
            "<article class=\"module diff\">\n<h1 class=\"book-title\">Stray</h1>\n</article>")))))

(deftest an-in-place-proposal-renders-every-change-form-across-module-views
  (let [md (str "# B\n\n## repo › old.md\n\n## Old\n\n"
                "#### Proposal 7: Remove · repo › old.md\n\n- Removed line.\n-\n"
                "#### Proposal 7: Replace · repo › old.md\n\n"
                "- The old words stay.\n-\n+ The new words stay.\n+\n"
                "#### Proposal 7: Move · repo › old.md, and repo › new.md\n\n"
                "::: move proposed\nfrom: repo › old.md\nto: repo › new.md\n\nMoved line.\n:::\n\n"
                "## repo › new.md\n\n## New\n\n"
                "#### Proposal 7: Add · repo › new.md\n\n+ Added line.\n")
        html (book-build/build md "")]
    (is (= 2 (count (re-seq #"<article class=\"module diff\">" html)))
        "one numbered proposal can span two whole module views")
    (is (str/includes? html "<div class=\"del\"><p>Removed line.</p></div>")
        "a removed line has the red removal margin")
    (is (str/includes? html "<p>The <del>old</del><ins>new</ins> words stay.</p>")
        "a replacement is one line and marks only its changed words")
    (is (= 1 (count (re-seq #"class=\"move move-proposed\"" html)))
        "a moved line is shown once in the third colour")
    (is (str/includes? html "<h2>From repo › old.md → To repo › new.md</h2>")
        "the move names the module it leaves and the module it enters")
    (is (str/includes? html "<div class=\"add\"><p>Added line.</p></div>"))))

(deftest a-partial-current-line-move-is-once-and-inside-the-module
  (let [moved "Field reaches Psyche through Mind."
        md (str "# B\n\n## repo › old.md\n\n"
                "#### Proposal 6: Routes · repo › old.md, and repo › new.md\n\n"
                "- ## Routes\n-\n- The route definition leaves.\n"
                "::: move proposed\nfrom: repo › old.md\nto: repo › new.md\n\n"
                moved "\n:::\n-\nAfter.\n\n"
                "## repo › new.md\n\n## Destination\n")
        html (book-build/build md "")
        module-open (str/index-of html "<article class=\"module diff\">")
        proposal-open (str/index-of html "<section class=\"proposal in-place\">")
        moved-at (str/index-of html moved)
        module-close (str/index-of html "</article>" module-open)]
    (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote moved)) html)))
        "the moved text is absent from unchanged context and appears once")
    (is (< module-open proposal-open moved-at module-close)
        "the move is inside its whole source module view")
    (is (str/includes? html "<div class=\"del\"><h4>Routes</h4></div>")
        "a proposal at the start of a module still renders its removal margin")
    (is (str/includes? html "<h2>From repo › old.md → To repo › new.md</h2>"))))

(deftest a-heading-opening-a-block-in-a-module-view-keeps-the-lines-below-it
  (let [html (book-build/build (str "# B\n\n## psyche-belief \u203a vision/x.md\n\n"
                                    "## The vocabulary\nA metaflow is members.\nIt has routes.\n") "")]
    (is (str/includes? html "<h3>The vocabulary</h3>"))
    (is (str/includes? html "<p>A metaflow is members. It has routes.</p>")
        "the lines below the heading render as their own block")))

(deftest a-questions-section-is-prose-under-its-heading
  (let [section (fn [html] (subs html (str/index-of html "<h2>Questions</h2>")))
        proposal "# B\n\n### Proposal 1: Added \u00b7 psyche-belief \u203a vision/rule.md\n\n+ Added line.\n"]
    (testing "after a proposal sheet"
      (let [html (book-build/build (str proposal "\n## Questions\n\n1. Is it so?\n2. Or else?\n") "")]
        (is (str/includes? html "</article>\n<h2>Questions</h2>") "outside the proposal sheet")
        (is (str/includes? (section html) "<ol class=\"choices\" start=\"1\"><li>Is it so?</li><li>Or else?</li></ol>"))
        (is (not (re-find #"class=\"(?:diff|add|del|ctx|move)" (section html))) "no change margin")))
    (testing "the heading needs no blank line before it, and prose stays prose"
      (let [html (book-build/build (str proposal "## Questions\n\nIs it so?\n\nOr else?\n") "")]
        (is (str/includes? html "</article>\n<h2>Questions</h2>\n<p>Is it so?</p>\n<p>Or else?</p>"))
        (is (not (str/includes? html "<h4>Questions</h4>")))))
    (testing "after a proposal in place, outside the module sheet"
      (let [html (book-build/build (str "# B\n\n## psyche-belief \u203a vision/x.md\n\nFirst line.\n\n"
                                        "#### Proposal 1: Added \u00b7 psyche-belief \u203a vision/x.md\n\n+ Added.\n\n"
                                        "## Questions\n\n1. One?\n") "")]
        (is (str/includes? html "</section>\n</article>\n<h2>Questions</h2>"))
        (is (not (re-find #"class=\"(?:diff|add|del|ctx|move)" (section html))))))))

(let [{:keys [fail error]} (binding [book-build/*highlight* false] (run-tests 'book-build-test))]
  (System/exit (if (zero? (+ fail error)) 0 1)))
