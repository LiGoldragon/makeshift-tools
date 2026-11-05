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
  (is (= ["no-change-in-proposal"] (reasons (str good "## Proposal 2: flows › a/b.md\nOnly prose.\n"))))
  (is (= [] (check (str good "## Proposal 2: flows › a/b.md\n**Archive** the record.\n"))))
  (is (= [] (check (str good "## Proposal 2: flows › a/b.md\n**Destroy** a sentence.\n"))))
  (is (= [] (check "## Proposal 1: chronos › src/lib.rs\n```rust\n+ fn f() {}\n```\n")))
  (is (= ["no-change-in-proposal" "heading-outside-proposal-without-module" "text-outside-proposal-without-module"]
         (reasons "## Proposal 1: flows › a.md\nText.\n## Elsewhere\n+ Added.\n"))))

(deftest heading-inside-code-or-comment-opens-no-proposal
  (is (= ["text-outside-proposal-without-module" "missing-proposal"]
         (reasons "```text\n## Proposal 1: a › b.md\n+ x\n```\n")))
  (is (= ["missing-proposal"] (reasons "<!--\n## Proposal 1: a › b.md\n+ x\n-->\n"))))

(deftest code-width
  (is (= [] (check (str good "```text\n" (rep "p" 100) "\n```\n"))))
  (is (= "code-over-52-columns" (:reason (first (check (str good "~~~clojure\n" (rep "x" 53) "\n~~~\n"))))))
  (is (= [] (check (str good "```rust\n" (rep "x" 52) "\n```\n"))))
  (is (= [] (check (str good "```clojure\n- " (rep "x" 90) "\n+ " (rep "y" 50) "\n```\n"))))
  (is (= "code-over-52-columns" (:reason (first (check (str good "```clojure\n+ " (rep "y" 51) "\n```\n")))))))

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
    (is (= [] (check (read "02-chronos-golden-example-2.md"))))
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
  (is (= [] (check (str located "**Comment** Why it is proposed.\n\n- Old.\n+ New.\n"))))
  (is (= ["comment-after-change"] (reasons (str located "- Old.\n+ New.\n\n**Comment** Too late.\n"))))
  (is (= ["second-comment-in-proposal"] (reasons (str located "**Comment** One.\n\n**Comment** Two.\n\n+ New.\n"))))
  (is (= ["text-outside-proposal-without-module"] (reasons (str "**Comment** Loose.\n" located "+ New.\n")))))

(deftest each-alternative-is-its-own-proposal
  (is (= [] (check (str located "- Old.\n+ One.\n\n"
                        "### Proposal 2: Replace, another way · psyche-belief › vision/book.md\n\n- Old.\n+ Two.\n"))))
  (is (= [] (check (str located "**Comment** A lone proposal.\n\n- Old.\n+ One.\n")))))

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
    (is (= ["no-change-in-proposal"]
           (reasons (str account "\n### Proposal 1: Replace · psyche-belief › vision/book.md\n\nOnly prose.\n")))
        "a proposal inside an account is checked as a proposal")
    (is (= ["missing-proposal" "text-outside-proposal-without-module"]
           (reasons "# T\n\nProse in a proposal book.\n")))
    (is (= ["missing-proposal" "text-outside-proposal-without-module"]
           (reasons "Presentation.{ «T» Review }\n\n# T\n\nProse in a review.\n")))))

(deftest the-signature-line-is-accepted-for-every-kind
  (doseq [kind ["" " Review" " Account"]]
    (is (= [] (check (str "Presentation.{ «T»" kind " }\n\n# T\n\n" sig "\n" after-title*)))
        (str "signature under kind" kind)))
  (is (= [] (check (str "# T\n\nCreated by Opus,\nhelped by Sonnet.\n\n" after-title*))) "a wrapped signature")
  (is (= ["text-outside-proposal-without-module"]
         (reasons (str "# T\n\n" sig "\nOther prose.\n\n" after-title*)))
      "only the paragraph that opens Created by"))

(deftest mermaid-figures
  (let [mermaid (str "```mermaid\nflowchart LR\n    a[\"" (rep "x" 80) "\"] -- \"to\" --> b[\"b\"]\n```\n")]
    (is (= [] (reasons (str "# T\n\n" mermaid "\n" after-title*))) "a context figure outside a proposal")
    (is (= [] (reasons (str good "\n" mermaid))) "no 52-column limit on mermaid lines")
    (is (= [] (reasons (str located "- Old.\n+ New.\n\n" (str/replace mermaid #"(?m)^" "+ ")))))
    (is (= [] (reasons (str "### Proposal 1: A · psyche-belief › vision/book.md\n\n"
                            (str/replace mermaid #"(?m)^(.)" "+ $1")))) "a signed opener is a fence and its change")
    (is (= ["code-over-52-columns"]
           (reasons (str good "```rust\n" (rep "x" 80) "\n```\n"))) "other code keeps the limit")
    (is (= ["code-over-52-columns"]
           (reasons (str good "+ ```rust\n+ " (rep "x" 80) "\n+ ```\n"))) "a signed fence is checked like an unsigned one")))

(let [{:keys [fail error]} (run-tests 'book-check-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
