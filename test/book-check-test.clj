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

(deftest built-page-headings-stay-in-their-context
  (is (= [] (book-check/check-built-page
             (str "<h1 class=\"book-title\">Book</h1>\n"
                  "<article class=\"module diff\">\n"
                  "<h3 class=\"module-title\">The module</h3>\n"
                  "</article>")))
      "a module's authenticated level-one heading is legitimate module structure")
  (is (= [{:line 3 :reason "book-heading-inside-module-view" :element "h1.book-title"}]
         (book-check/check-built-page
          (str "<h1 class=\"book-title\">Book</h1>\n"
               "<article class=\"module diff\">\n"
               "<h1 class=\"book-title\">Stray</h1>\n"
               "</article>")))
      "a document head inside a module is refused at its exact line"))

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

(deftest a-module-view-with-cross-referencing-ethos-fences-is-authenticated-as-source
  (let [body (str "A request.\n\n```ethos\nSendableRequest.[ Done.{ RequestId String } ]\n"
                  "RequestId.String\n```\n\nThe delivered request.\n\n"
                  "```ethos\nDeliveredRequest.[ Order.{ RequestId String } ]\n```\n")
        read-module (fn [repo p]
                      (same! "psyche-belief" repo) (same! "vision/flow-nexus.md" p)
                      (str "---\ndescription: A fixture.\ndependencies: []\n---\n\n" body))
        opts {:read-module read-module}
        heading "## psyche-belief › vision/flow-nexus.md\n\n"
        proposal "## Proposal 1: psyche-belief › vision/example.md\n+ A proposal.\n"]
    (is (= [] (check (str "# A book\n\n" heading body "\n" proposal) opts))
        "the second fence uses a type only the first declares")
    (is (= "context-module-differs"
           (:reason (first (check (str "# A book\n\n" heading (str/replace body "RequestId.String" "RequestId.Integer")
                                       "\n" proposal) opts))))
        "a view that differs from the module is refused")))

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

;; A proposal in place: inside a module view, at the place its added lines land.
(def in-place-module
  "---\ndescription: A fixture.\n---\n\n## The rule\n\nFirst line.\n\nSecond line.\n\n- a bullet\n")
(def in-place-opts
  {:read-module (fn [repo file] (same! "psyche-belief" repo) (same! "vision/rule.md" file) in-place-module)})
(defn in-place-book [& view]
  (str "# A book\n\n## psyche-belief › vision/rule.md\n\n" (apply str view)))
(def in-place-p1
  (str "#### Proposal 1: Added · psyche-belief › vision/rule.md\n\n::: comment\nWhy.\n:::\n\n"
       "+ Added line.\n\n```mermaid\n+ flowchart TB\n+     a --> b\n```\n\n"))

(deftest a-proposal-stands-in-place-inside-its-module-view
  (testing "the view without its in-place proposals is the module as it stands"
    (is (= [] (check (in-place-book "## The rule\n\nFirst line.\n\n" in-place-p1 "Second line.\n\n- a bullet\n") in-place-opts)))
    (is (= [] (check (in-place-book "## The rule\n\nFirst line.\n\nSecond line.\n\n- a bullet\n\n" in-place-p1) in-place-opts))
        "at the module's end")
    (is (= [] (check (in-place-book "## The rule\n\n" in-place-p1
                                    "#### Proposal 2: psyche-belief › vision/rule.md\n\n+ Another.\n\n"
                                    "First line.\n\nSecond line.\n\n- a bullet\n") in-place-opts))
        "two proposals at one place, the second without a comment"))
  (testing "the unmarked lines are authenticated"
    (is (= ["context-module-differs"]
           (reasons (in-place-book "## The rule\n\nFirst line.\n\n" in-place-p1 "Second line, changed.\n\n- a bullet\n") in-place-opts)))
    (is (= ["context-module-differs"]
           (reasons (in-place-book "## The rule\n\nFirst line.\n" in-place-p1 "Second line.\n\n- a bullet\n") in-place-opts))
        "the heading stands after a blank line, as a paragraph boundary"))
  (testing "its form"
    (is (= ["in-place-proposal-elsewhere"]
           (reasons (in-place-book "## The rule\n\n#### Proposal 1: X · psyche-belief › vision/other.md\n\n+ A.\n\n"
                                   "First line.\n\nSecond line.\n\n- a bullet\n") in-place-opts)))
    (is (= ["no-change-in-proposal"]
           (reasons (in-place-book "## The rule\n\n#### Proposal 1: X · psyche-belief › vision/rule.md\n\n::: comment\nWhy.\n:::\n\n"
                                   "First line.\n\nSecond line.\n\n- a bullet\n") in-place-opts)))
    (is (= ["comment-after-change"]
           (reasons (in-place-book "## The rule\n\n#### Proposal 1: X · psyche-belief › vision/rule.md\n\n+ A.\n\n::: comment\nWhy.\n:::\n\n"
                                   "First line.\n\nSecond line.\n\n- a bullet\n") in-place-opts)))
    (is (= ["mixed-mermaid-change"]
           (reasons (in-place-book "## The rule\n\n#### Proposal 1: X · psyche-belief › vision/rule.md\n\n```mermaid\n+ flowchart TB\n\n+     a --> b\n```\n\n"
                                   "First line.\n\nSecond line.\n\n- a bullet\n") in-place-opts)))
    (is (some #{"in-place-proposal-outside-module-view"}
              (reasons "# A book\n\n#### Proposal 1: X · psyche-belief › vision/rule.md\n\n+ A.\n")))))

(deftest in-place-removal-replacement-move-and-many-module-views
  (let [modules {"old.md" "## Old\n\nThe old words stay.\n\nMoved line.\n"
                 "new.md" "## New\n"}
        opts {:read-module (fn [repo path]
                             (same! "repo" repo)
                             (get modules path))}
        source (str "# B\n\n## repo › old.md\n\n## Old\n\n"
                    "#### Proposal 7: Replace · repo › old.md\n\n"
                    "- The old words stay.\n-\n+ The new words stay.\n+\n"
                    "#### Proposal 7: Move · repo › old.md, and repo › new.md\n\n"
                    "::: move proposed\nfrom: repo › old.md\nto: repo › new.md\n\nMoved line.\n:::\n\n"
                    "## repo › new.md\n\n## New\n\n"
                    "#### Proposal 7: Add · repo › new.md\n\n+ Added at the destination.\n")]
    (is (= [] (check source opts))
        "one proposal number can carry edits in two authenticated module views")
    (is (some #{"malformed-in-place-move"}
              (reasons (str/replace source "to: repo › new.md" "to: repo › elsewhere.md") opts))
        "a move heading, source view and from/to locations must agree")))

(deftest an-in-place-move-can-leave-the-end-of-a-current-line
  (let [module (str "## Routes\n\n"
                    "The route definition leaves. Field reaches Psyche through Mind.\n\n"
                    "After.\n")
        opts {:read-module (fn [_ _] module)}
        source (str "# B\n\n## repo › old.md\n\n"
                    "#### Proposal 6: Routes · repo › old.md, and repo › new.md\n\n"
                    "- ## Routes\n-\n- The route definition leaves.\n"
                    "::: move proposed\nfrom: repo › old.md\nto: repo › new.md\n\n"
                    "Field reaches Psyche through Mind.\n:::\n"
                    "-\nAfter.\n")]
    (is (= [] (check source opts))
        "adjacency says the moved text followed the removed text on one current source line")
    (is (some #{"context-module-differs"}
              (reasons (str/replace source "- The route definition leaves.\n:::"
                                    "- The route definition leaves.\n\n:::") opts))
        "a blank before the move says it was a whole source line")))

;; The closing Questions section of a Proposal book: plain prose after the last proposal, to the end.
(def with-comment-proposal
  (str located "::: comment\nWhy it is proposed.\n:::\n\n- Old.\n+ New.\n"))
(def questions "\n## Questions\n\n1. Is it so?\n2. Or else?\n")

(deftest a-questions-section-closes-a-proposal-book
  (testing "accepted after a proposal that has a comment, with no second-comment error"
    (is (= [] (check (str with-comment-proposal questions))))
    (is (= [] (check (str with-comment-proposal "## Questions\nIs it so?\n"))) "no blank line before the heading, prose only")
    (is (= [] (check (str with-comment-proposal "\n## Proposal 2: Replace again · psyche-belief › vision/book.md\n\n- Old.\n+ Newer.\n"
                          questions)))
        "after the last of several proposals"))
  (testing "after a proposal in place"
    (is (= [] (check (in-place-book "## The rule\n\nFirst line.\n\n" in-place-p1 "Second line.\n\n- a bullet\n\n## Questions\n\n1. Is it so?\n")
                     in-place-opts))))
  (testing "a book without the section stays as it is"
    (is (= [] (check with-comment-proposal))))
  (testing "heading and place"
    (is (= ["missing-proposal" "questions-without-proposal"]
           (reasons "# A book\n\n## Questions\n\n1. Is it so?\n"))
        "a Questions section follows a proposal")
    (is (some #{"heading-outside-proposal-without-module"}
              (reasons (str with-comment-proposal "\n### Questions\n\n1. Is it so?\n")))
        "only the exact level-two heading is the section")
    (is (some #{"heading-outside-proposal-without-module"}
              (reasons (str with-comment-proposal "\n## Questions for you\n\n1. Is it so?\n")))))
  (testing "nothing else follows or sits in it"
    (let [refused (fn [more] (reasons (str with-comment-proposal questions more)))]
      (is (= ["heading-after-questions"] (refused "\n## Another\n")))
      (is (= ["heading-after-questions"] (refused "\n### Another\n")))
      (is (= ["heading-after-questions"] (refused "\n## Questions\n\n1. Again?\n")))
      (is (= "proposal-after-questions"
             (first (refused "\n### Proposal 3: Late · psyche-belief › vision/book.md\n\n- Old.\n+ Late.\n")))
          "a proposal after it names its own error first")
      (is (= ["comment-in-questions"] (refused "\n::: comment\nWhy.\n:::\n")))
      (is (= ["shown-in-questions"] (refused "\n::: shown\npsyche-belief › vision/book.md\nText.\n:::\n")))
      (is (= ["move-in-questions"] (refused "\n::: move proposed\nfrom: a › b.md\nto: c › d.md\n\nText.\n:::\n")))
      (is (= ["change-in-questions"] (refused "\n+ An added line.\n")))
      (is (= ["change-in-questions"] (refused "\n- A removed line.\n")))
      (is (= ["change-in-questions"] (refused "\n+\n")))
      (is (= ["code-fence-in-questions"] (refused "\n```text\ncode\n```\n")))))
  (testing "Review and Account books keep their own forms"
    (is (some #{"heading-outside-proposal-without-module"}
              (reasons (str "Presentation.{ «A review» Review }\n\n# A review\n\n" (subs with-comment-proposal 0) questions))))
    (is (= [] (check (str "Presentation.{ «An account» Account }\n\n# An account\n\n## Questions\n\nProse.\n"))))))

(deftest executable-checks-the-questions-form
  (let [file (str (fs/create-temp-file {:suffix ".md"}))]
    (spit file (str "# A book\n\n### Proposal 1: Replace · psyche-belief › vision/book.md\n\n::: comment\nWhy.\n:::\n\n- Old.\n+ New.\n"
                    "\n## Questions\n\n1. Is it so?\n\n## Another\n"))
    (let [{:keys [exit err]} (sh "bb" program file)]
      (is (= 1 exit))
      (is (str/includes? err "heading-after-questions")))))

(let [{:keys [fail error]} (run-tests 'book-check-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
