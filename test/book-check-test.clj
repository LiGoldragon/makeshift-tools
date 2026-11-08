;; Tests for book-check.clj. Run: bb test/book-check-test.clj
;; Ported from book-check.test.mjs.
(ns book-check-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [babashka.fs :as fs]))

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path root "book-check.clj")))
(def program (str (fs/path root "book-check.clj")))
(def check book-check/check-book)
(defn reasons
  ([source] (mapv :reason (check source)))
  ([source opts] (mapv :reason (check source opts))))
(defn rep [s n] (apply str (repeat n s)))
(defn same! [a b] (when-not (= a b) (throw (AssertionError. (str a " != " b)))))
(defn throwing [& _] (throw (Exception. "unavailable")))

(def good "# A book\n## Proposal 1: psyche-belief › vision/example.md\n+ Use the supported route.\n")
(def after-title (str/join "\n" (rest (str/split good #"\n"))))
(def after-title* (str after-title "\n"))

(deftest heading-carries-the-location
  (is (= [] (check good)))
  (is (= [] (check "### Proposal 2: The Library · signal-chronos › ethos/library.ethos, a new file\n+ Added.\n")))
  (is (= [] (check "## Proposal 3: chronos › ethos/memory.ethos, a new file\n+ Added.\n")))
  (is (= [] (check "### Proposal 4: Move · psyche-belief › vision/old.md, and psyche-belief › vision/new.md\n+ Moved.\n")))
  (is (= [] (check "### Proposal 5: Move · psyche-belief › vision/old.md, and psyche-belief › vision/new.md, a new file\n+ Moved.\n")))
  (is (= [] (check "### Proposal 6: Move · psyche-belief › vision/old.md, and psyche-belief › vision/new.md, a deleted file\n+ Moved.\n"))))

(deftest proposal-heading-location-pair-is-exact
  (doseq [heading ["### Proposal 1: Move · psyche-belief › vision/old.md, psyche-belief › vision/new.md"
                   "### Proposal 1: Move · psyche-belief › vision/old.md, and bad path"
                   "### Proposal 1: Move · psyche-belief › vision/old.md, and psyche-belief › vision/new.md, and psyche-belief › vision/third.md"
                   "### Proposal 1: Move · psyche-belief › vision/old.md, a new file, and psyche-belief › vision/new.md"]]
    (is (some #{"proposal-heading-without-location"}
              (reasons (str heading "\n+ Changed.\n"))) heading)))

(deftest heading-without-a-location-refuses
  (is (= ["proposal-heading-without-location" "missing-proposal" "text-outside-proposal-without-module"]
         (reasons "## Proposal 1: The Library\n+ Added.\n")))
  (is (= ["proposal-heading-without-location" "missing-proposal" "text-outside-proposal-without-module"]
         (reasons "## Proposal\n+ Added.\n")))
  (is (= ["proposal-heading-without-location" "text-outside-proposal-without-module"]
         (reasons (str good "## Proposal 2: psyche-belief skills/x.md\n+ Added.\n")))))

(deftest file-line-in-body-refuses
  (is (= ["file-line-in-body"] (reasons (str/replace good "+ Use" "File: skills/example.md\n+ Use"))))
  (is (= ["file-line-in-body"] (reasons (str good "```text\nFile: example.md\n+ New.\n```\n"))))
  (is (= [] (check (str good "```rust\nFile: literal code\n```\n"))))
  (is (= [] (check (str good "<!-- File: hidden.md -->\n")))))

(deftest located-proposal-with-a-change
  (is (= ["missing-proposal" "text-outside-proposal-without-module" "text-outside-proposal-without-module"]
         (reasons "# A book\nText.\n+ Added.\n")))
  (is (= ["no-change-in-proposal" "context-module-unavailable"]
         (reasons (str good "## Proposal 2: flows › a/b.md\nOnly prose.\n"))))
  (is (= [] (check (str good "## Proposal 2: flows › a/b.md\n**Archive** the record.\n"))))
  (is (= [] (check (str good "## Proposal 2: flows › a/b.md\n**Destroy** a sentence.\n"))))
  (is (= [] (check "## Proposal 1: chronos › src/lib.rs\n```rust\n+ fn f() {}\n```\n")))
  (is (= ["no-change-in-proposal" "context-module-unavailable" "heading-outside-proposal-without-module" "text-outside-proposal-without-module"]
         (reasons "## Proposal 1: flows › a.md\nText.\n## Elsewhere\n+ Added.\n"))))

(deftest heading-inside-code-or-comment-opens-no-proposal
  (is (= ["text-outside-proposal-without-module" "missing-proposal"]
         (reasons "```text\n## Proposal 1: a › b.md\n+ x\n```\n")))
  (is (= ["missing-proposal"] (reasons "<!--\n## Proposal 1: a › b.md\n+ x\n-->\n"))))

(deftest code-and-comment-width-do-not-block-publication
  (is (= [] (check (str good "```text\n" (rep "p" 100) "\n```\n"))))
  (is (= [] (check (str good "~~~clojure\n" (rep "x" 160) "\n~~~\n"))))
  (is (= [] (check (str good "```rust\n" (rep "x" 200) "\n```\n"))))
  (is (= [] (check (str good "```clojure\n- " (rep "x" 190) "\n+ " (rep "y" 180) "\n```\n"))))
  (is (= [] (check (str "## Proposal 1: Wide comment · psyche-belief › vision/example.md\n\n"
                         "::: comment\n" (rep "reason " 40) "\n:::\n\n+ Changed.\n")))))

(deftest mermaid-change-signs-and-width
  (let [fence (fn [body] (str good "```mermaid\n" body "\n```\n"))]
    (is (= [] (check (fence (str "+ flowchart LR\n+     a[\"" (rep "x" 70) "\"] --> b")))))
    (is (= [] (check (fence (str "- flowchart LR\n-     a[\"" (rep "x" 70) "\"] --> b")))))
    (is (= ["mixed-mermaid-change"] (reasons (fence "+ flowchart LR\n-     a --> b"))))
    (is (= ["mixed-mermaid-change"] (reasons (fence "+ flowchart LR\n\n+     a --> b")))
        "a bare blank is unmarked; write the sign alone for an intentional blank")
    (is (= [] (check (fence (str "flowchart LR\n    a[\"" (rep "x" 170) "\"] --> b"))))
        "an unmarked whole Mermaid fence keeps its authored width")))

(deftest ethos-whole-documents-and-marked-fragments-are-distinct
  (let [fence (fn [body] (str good "```ethos\n" body "\n```\n"))]
    (is (= [] (reasons (fence "  Library\n  []\n- [ Old.Integer ]\n+ [ New.Integer ]\n\n  []\n  []")))
        "added, removed, context and bare blank lines form one coherent fragment")
    (is (= ["malformed-ethos-change-fragment"]
           (filterv #{"malformed-ethos-change-fragment"}
                    (reasons (fence "+ Library\n[]"))))
        "a root-at-zero line cannot be mixed into a signed fragment")
    (is (= ["ethos-change-changes-nothing"]
           (filterv #{"ethos-change-changes-nothing"}
                    (reasons (fence "  Library\n  []"))))
        "context alone is named as a change that changes nothing")
    (is (empty? (filterv #{"malformed-ethos-change-fragment" "ethos-change-changes-nothing"}
                         (reasons (fence "Library\n[]\n[]\n[]"))))
        "a column-zero root remains a whole Ethos document")
    (is (empty? (filterv #{"malformed-ethos-change-fragment" "ethos-change-changes-nothing"}
                         (reasons (fence "\n"))))
        "an empty or all-blank fence never becomes a fragment")))

(deftest shorter-fence-does-not-close-code
  (is (= "unclosed-code-fence" (:reason (last (check (str good "````rust\n```\n")))))))

(deftest quotes-refuse-outside-code
  (is (= "quote-block-in-book" (:reason (first (check (str good "> his words\n"))))))
  (is (= [] (check (str good "```rust\n> code\n```\n")))))

(deftest executable-succeeds-only-for-valid-source
  (let [dir (fs/create-temp-dir {:prefix "book-check-"})
        file (str (fs/path dir "book.md"))
        run (fn [& args] (apply sh "bb" program args))]
    (try
      (spit file good)
      (let [r (run file)]
        (is (= 0 (:exit r))) (is (= "" (:err r)))
        (is (true? (get (cheshire.core/parse-string (:out r)) "checked")))
        (is (= good (slurp file))))
      (spit file "## Proposal 1: a › b.md\nFile: b.md\n+ Added.\n")
      (let [r (run file)] (is (= 1 (:exit r))) (is (= "" (:out r))))
      (let [r (run file file)] (is (= 2 (:exit r))) (is (= "" (:out r))))
      (finally (fs/delete-tree dir)))))

(deftest historical-books
  (let [read #(slurp (str (fs/path root "test/fixtures/books" %)))]
    (is (seq (check (read "02-chronos-golden-example-2.md"))))
    (is (seq (check (read "50-lean-flows.md"))))
    (is (some #{"file-line-in-body"} (reasons (read "01-chronos-golden-example.md"))))))

(deftest outside-proposals-only-whole-current-modules-pass
  (let [body "## The rule\n\nUse the actual module.\n\n> Literal source quotation.\n"
        read-module (fn [repo file]
                      (same! "field-belief" repo) (same! "compensation/example.md" file)
                      (str "---\ndescription: A fixture.\ndependencies: []\n---\n\n" body))
        heading "## field-belief › compensation/example.md\n\n"
        proposal "## Proposal 1: psyche-belief › vision/example.md\n+ A proposal.\n"
        opts {:read-module read-module}]
    (is (= [] (check (str "# A book\n" heading body "\n" proposal) opts)))
    (is (= [] (check (str heading "```markdown\n" (str/trim body) "\n```\n\n" proposal) opts)))
    (is (= "context-module-differs" (:reason (first (check (str heading "The rule means something else.\n\n" proposal) opts)))))
    (is (= "context-module-differs" (:reason (first (check (str heading body "\nMy own conclusion.\n\n" proposal) opts)))))
    (is (= "context-module-unavailable" (:reason (first (check (str heading body proposal) {:read-module throwing})))))
    (is (seq (check (str "## tools › brief.md\nProse.\n" proposal) opts)))
    (is (seq (check (str "## field-belief › ../secret.md\nProse.\n" proposal) opts)))
    (is (some #(= "heading-outside-proposal-without-module" %)
              (reasons (str "# A book\nA paraphrase.\n## What vision says\nMore prose.\n" proposal))))))

(deftest module-view-headings-use-level-two
  (let [module "Module line.\n\n### Nested › section\n\nMore module text.\n"
        opts {:read-module (fn [& _] module)}
        proposal "## Proposal 1: repo › x.md\n+ Add a line.\n"]
    (is (= [] (check (str "# A book\n\n## repo › x.md\n\n" module "\n" proposal) opts)))
    (doseq [level ["#" "###" "####" "######"]]
      (is (some #{"heading-outside-proposal-without-module"}
                (reasons (str "# A book\n\n" level " repo › x.md\n\n" module "\n" proposal) opts)) level))
    (is (some #{"heading-outside-proposal-without-module"}
              (reasons "# repo › x.md\n\n## Proposal 1: repo › x.md\n+ Add a line.\n"))
        "a level-one module path is not a book title")))

(deftest markdown-in-a-plain-code-block
  (let [proposal "## Proposal 1: psyche-belief › vision/example.md\n+ A proposal.\n"
        md "## Rule\n\nText.\n"]
    (doseq [label ["" "text" "txt" "prose"]]
      (is (some #{"markdown-in-code-block"} (reasons (str proposal "```" label "\n" md "```\n"))) label))
    (is (not (some #{"markdown-in-code-block"} (reasons (str proposal "```markdown\n" md "```\n")))))
    (is (not (some #{"markdown-in-code-block"} (reasons (str proposal "~~~~md\n" md "~~~~\n")))))
    (is (not (some #{"markdown-in-code-block"} (reasons (str proposal "```sh\n# a comment\n```\n")))))
    (is (not (some #{"markdown-in-code-block"} (reasons (str proposal "```text\n+ ## Rule\n```\n"))))
        "a change is not a code block")))

(deftest a-view-of-any-file-matches-it-to-the-character
  (let [file "(Types\n  chronos)\n\n## not a heading\n"
        read-module (fn [repo p]
                      (same! "chronos" repo) (same! ".ethos/data/chronos.ethos" p)
                      file)
        opts {:read-module read-module}
        proposal "## Proposal 1: chronos › .ethos/data/chronos.ethos\n- (Types\n"
        heading "## chronos › .ethos/data/chronos.ethos\n\n"]
    (is (= [] (check (str "# B\n" heading "```ethos\n" (str/trimr file) "\n```\n\n" proposal) opts)))
    (is (= [] (check (str "# B\n" heading "````\n" (str/trimr file) "\n````\n\n" proposal) opts)))
    (is (= "context-module-differs"
           (:reason (first (check (str "# B\n" heading "```ethos\n" (str/trimr (str/replace file "chronos" "kronos")) "\n```\n\n" proposal) opts)))))
    (is (= "context-module-unavailable"
           (:reason (first (check (str "# B\n" heading "```ethos\n" (str/trimr file) "\n```\n\n" proposal) {:read-module throwing})))))
    (is (seq (check (str "# B\n## chronos › ../x.ethos\n```ethos\nx\n```\n" proposal) opts)))
    (is (seq (check (str "# B\n## chronos › /etc/passwd\n```ethos\nx\n```\n" proposal) opts)))
    (is (seq (check (str "# B\n## psyche-belief › src/x.rs\n```rust\nx\n```\n" proposal) opts))
        "context repositories hold only authored modules")))

(deftest removal-only-or-deleted-file-passes
  (is (= [] (reasons "# A book\n## Proposal 1: chronos › a.ethos\n- Old line.\n")))
  (is (= [] (reasons "# A book\n## Proposal 1: chronos › a.ethos, a deleted file\nIt goes.\n")))
  (is (some #{"no-change-in-proposal"} (reasons "# A book\n## Proposal 1: chronos › a.ethos\nText only.\n"))))

(deftest unlabelled-diagram-fence-outside-a-section
  (let [fig "```\n┌───┐   draws   ┌───┐\n│ a │ ───────▶ │ b │\n└───┘          └───┘\n```\n"
        ascii "```\n+---+\n| a |\n+---+\n  |\n  v\n+---+\n| b |\n+---+\n```\n"]
    (is (= [] (reasons (str "# A book\n" fig after-title*))))
    (is (= [] (reasons (str "# A book\n" ascii after-title*))))
    (is (= ["text-outside-proposal-without-module"]
           (reasons (str "# A book\n```\nJust some prose here.\n```\n" after-title*))))))

(def located "### Proposal 1: Replace · psyche-belief › vision/book.md\n")

(deftest comment-comes-first-once
  (is (= [] (check (str located "::: comment\nWhy it is proposed.\n:::\n\n- Old.\n+ New.\n"))))
  (is (= ["comment-after-change"] (reasons (str located "- Old.\n+ New.\n\n::: comment\nToo late.\n:::\n"))))
  (is (= ["second-comment-in-proposal"] (reasons (str located "::: comment\nOne.\n:::\n\n::: comment\nTwo.\n:::\n\n+ New.\n"))))
  (is (= ["comment-outside-proposal" "missing-proposal"] (reasons "::: comment\nLoose.\n:::\n")))
  (is (= ["comment-container-required"] (reasons (str located "**Comment** Legacy.\n\n+ New.\n")))))

(deftest each-alternative-is-its-own-proposal
  (is (= [] (check (str located "- Old.\n+ One.\n\n"
                        "### Proposal 2: Replace, another way · psyche-belief › vision/book.md\n\n- Old.\n+ Two.\n"))))
  (is (= [] (check (str located "::: comment\nA lone proposal.\n:::\n\n- Old.\n+ One.\n")))))

(deftest proposal-prose-is-authenticated-as-one-module-paragraph
  (let [module (str "---\ndescription: fixture\ndependencies: []\n---\n\n"
                    "First paragraph wraps\nacross two lines.\n\nSecond paragraph.\n")
        opts {:read-module (fn [repo path]
                             (same! "psyche-belief" repo)
                             (same! "vision/book.md" path)
                             module)}]
    (is (= [] (check (str located "First paragraph wraps across two lines.\n\n+ New.\n") opts))
        "layout whitespace alone is normalized")
    (is (= ["proposal-module-text-absent"]
           (reasons (str located "First paragraph wraps Second paragraph.\n\n+ New.\n") opts))
        "paragraphs are not assembled across their boundary")
    (is (= ["proposal-module-text-absent"]
           (reasons (str located "First paragraph wraps across two lines. Extra.\n\n+ New.\n") opts))
        "the paragraph is not accepted as a substring")
    (is (= [] (check (str located "::: comment\nAuthor explanation.\n:::\n\n+ New.\n") opts)))))

(deftest shown-is-an-exact-contiguous-current-module-excerpt
  (let [module "First line.\nSecond line.\n\n- A real list item.\nLast line.\n"
        opts {:read-module (fn [repo path]
                             (same! "psyche-belief" repo)
                             (same! "vision/book.md" path)
                             module)}
        source (fn [body] (str located "::: shown\npsyche-belief › vision/book.md\n" body "\n:::\n\n+ New.\n"))]
    (is (= [] (check (source "First line.\nSecond line.") opts)))
    (is (= [] (check (source "- A real list item.\nLast line.") opts))
        "an exact module list is current text, not a coloured change")
    (is (= ["shown-module-text-absent"] (reasons (source "First line.\nInvented.") opts)))
    (is (= ["shown-module-text-absent"] (reasons (source "+ Current change.") opts)))
    (is (= ["shown-location-invalid"]
           (reasons (str located "::: shown\nnot a location\nFirst line.\n:::\n\n+ New.\n") opts)))
    (is (= ["shown-excerpt-empty"]
           (reasons (str located "::: shown\npsyche-belief › vision/book.md\n:::\n\n+ New.\n") opts)))))

(def sig "Created by Opus, helped by Sonnet (the checking).\n")

(deftest the-presentation-kind
  (let [proposal "### Proposal 1: Replace · psyche-belief › vision/book.md\n\n- Old.\n+ New.\n"]
    (doseq [kind ["" " Proposal" " Review" " Account"]]
      (is (= [] (check (str "Presentation.{ «T»" kind " }\n\n# T\n\n" proposal))) kind))
    (is (= ["presentation-kind-unknown"] (reasons (str "Presentation.{ «T» Essay }\n\n# T\n\n" proposal))))
    (is (= ["missing-proposal"] (reasons "Presentation.{ «T» Review }\n\n# T\n\n")) "a review shows landed changes")))

(deftest an-account-holds-prose-and-headings
  (let [account "Presentation.{ «T» Account }\n\n# T\n\nCreated by Fable.\n\n## The gist\n\nIt happened so.\n\n### What followed\n\nMore prose.\n"]
    (is (= [] (check account)) "no missing-proposal either")
    (is (= [] (check (str account "\n### Proposal 1: Replace · psyche-belief › vision/book.md\n\n- Old.\n+ New.\n"))))
    (is (= ["no-change-in-proposal" "proposal-module-text-absent"]
           (reasons (str account "\n### Proposal 1: Replace · psyche-belief › vision/book.md\n\nOnly prose.\n")))
        "a proposal inside an account is checked as a proposal")
    (is (= ["missing-proposal" "text-outside-proposal-without-module"]
           (reasons "# T\n\nProse in a proposal book.\n")))
    (is (= ["missing-proposal" "text-outside-proposal-without-module"]
           (reasons "Presentation.{ «T» Review }\n\n# T\n\nProse in a review.\n")))))

(deftest an-account-accepts-code-fences
  (let [account "Presentation.{ «T» Account }\n\n# T\n\nCreated by Fable.\n\n## The gist\n\n"]
    (is (= [] (check (str account "```rust\nfn f() {}\n```\n"))) "labelled code in an account")
    (is (= [] (check (str account "```clojure\n(+ 1 2)\n```\n\nProse after.\n"))))
    (is (= ["missing-proposal" "text-outside-proposal-without-module"]
           (reasons "Presentation.{ «T» Review }\n\n# T\n\n```rust\nfn f() {}\n```\n"))
        "other kinds are unchanged")))

(deftest the-signature-line-is-accepted-for-every-kind
  (doseq [kind ["" " Review" " Account"]]
    (is (= [] (check (str "Presentation.{ «T»" kind " }\n\n# T\n\n" sig "\n" after-title*)))
        (str "signature under kind" kind)))
  (is (= [] (check (str "# T\n\nCreated by Opus,\nhelped by Sonnet.\n\n" after-title*))) "a wrapped signature")
  (is (= ["text-outside-proposal-without-module"]
         (reasons (str "# T\n\n" sig "\nOther prose.\n\n" after-title*)))
      "only the paragraph that opens Created by"))

(deftest mermaid-figures
  (let [mermaid (str "```mermaid\nflowchart LR\n    a[\"x\"] -- \"to\" --> b[\"b\"]\n```\n")
        long-mermaid (str "```mermaid\nflowchart LR\n    a[\"" (rep "x" 80) "\"] -- \"to\" --> b[\"b\"]\n```\n")]
    (is (= [] (reasons (str "# T\n\n" mermaid "\n" after-title*))) "a context figure outside a proposal")
    (is (= [] (reasons (str good "\n" mermaid))) "Mermaid figure")
    (is (= [] (reasons (str located "- Old.\n+ New.\n\n" (str/replace mermaid #"(?m)^" "+ ")))))
    (is (= [] (reasons (str "### Proposal 1: A · psyche-belief › vision/book.md\n\n"
                            (str/replace mermaid #"(?m)^(.)" "+ $1")))) "a signed opener is a fence and its change")
    (is (= [] (reasons (str good long-mermaid))) "wide Mermaid")
    (is (= [] (reasons (str good "```rust\n" (rep "x" 180) "\n```\n")))
        "wide code publishes")
    (is (= [] (reasons (str good "+ ```rust\n+ " (rep "x" 180) "\n+ ```\n")))
        "wide code nested in a diff fence publishes")))

(deftest proposal-fences-name-their-language
  (is (= ["fence-language-required"]
         (reasons "### Proposal 1: Code · repo › x.md\n+ Added.\n\n```\nplain text\n```\n")))
  (is (= ["fence-language-required"]
         (reasons "### Proposal 1: Code · repo › x.md\n+ Added.\n\n```\n+ added\n```\n")))
  (is (= ["fence-language-required"]
         (reasons "### Proposal 1: Code · repo › x.md\n+ Added.\n\n```\n- removed\n```\n")))
  (is (= []
         (reasons "### Proposal 1: Code · repo › x.md\n+ Added.\n\n```text\nplain text\n```\n"))))

(let [{:keys [fail error]} (run-tests 'book-check-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
