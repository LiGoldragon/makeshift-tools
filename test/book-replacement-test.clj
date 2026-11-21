(ns book-replacement-test
  (:require [clojure.test :refer [deftest is run-tests]] [babashka.fs :as fs]
            [clojure.string :as str]))
(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str root "/book-build.clj"))
(load-file (str root "/test/cdp.clj"))
(def source "Presentation.{ «Replacement» Review }\n\n# Replacement\n\n### Proposal 1: Description · repo › a.md\n\n::: description\n- A module is being designed or judged.\n+ A module.\n:::\n\n- Entirely unrelated old passage.\n+ Fresh new wording.\n")
(deftest prose-replacements-have-separate-margins-even-with-no-common-words
  (let [html (book-build/build source "")]
    (is (= 2 (count (re-seq #"class=\"del replacement-old\"" html))))
    (is (str/includes? html "A module<del> is being designed or judged</del>."))
    (is (str/includes? html "<div class=\"del replacement-old\"><p><del>Entirely unrelated old passage</del>.</p></div>"))
    (is (str/includes? html "<div class=\"add replacement-new\"><p><ins>Fresh new wording</ins>.</p></div>"))
    (is (not (str/includes? html "class=\"del\"")))
    (is (not (str/includes? html "class=\"add\"")))
    (is (= [] (book-check/check-built-page html)))))
(deftest prose-replacement-retains-shared-words-on-both-sides
  (let [html (book-build/text-panels ["- The old words remain." "+ The new words remain."] true)]
    (is (str/includes? html "<div class=\"del replacement-old\"><p>The <del>old</del> words remain.</p></div>"))
    (is (str/includes? html "<div class=\"add replacement-new\"><p>The <ins>new</ins> words remain.</p></div>"))
    (is (= [] (book-check/check-built-page html)))))

(deftest every-body-kind-keeps-complete-old-and-new-sides
  (doseq [[old new old-tag new-tag]
          [[[:p "The old rule."] [:p "The new rule."] "<p>The <del>old</del> rule.</p>" "<p>The <ins>new</ins> rule.</p>"]
           [[:heading "## The old rule"] [:heading "## The new rule"] "<h4>The <del>old</del> rule</h4>" "<h4>The <ins>new</ins> rule</h4>"]
           [[:list [["-" "The old rule"]]] [:list [["-" "The new rule"]]] "<ul><li>The <del>old</del> rule</li></ul>" "<ul><li>The <ins>new</ins> rule</li></ul>"]]]
    (let [html (book-build/paired-body old new)]
      (is (= (str "<div class=\"del replacement-old\">" old-tag "</div>"
                  "<div class=\"add replacement-new\">" new-tag "</div>") html))
      (is (= [] (book-check/check-built-page html)))))
  (let [[old new] (book-build/inline-sides "The <old>& rule" "The <new>& rule")]
    (is (= "The &lt;<del>old</del>&gt;&amp; rule" old))
    (is (= "The &lt;<ins>new</ins>&gt;&amp; rule" new))))

(deftest checker-names-the-duplicated-pair
  (let [removed "The old words remain."
        added "The new words remain."
        html (str "<div class=\"del\"><div><p>" removed "</p></div></div>\n"
                  "<div class=\"add\"><p>" added "</p></div>")
        errors (book-check/check-built-page html)]
    (is (= [{:line 1 :reason "replaced-line-printed-twice" :removed removed :added added}] errors)))
  (is (= [] (book-check/check-built-page "<div class=\"del\">Old unrelated words.</div><div class=\"add\">Fresh text.</div>")))
  (is (= [] (book-check/check-built-page "<div class=\"del\">The same words.</div><p>Separate passage.</p><div class=\"add\">The same words.</div>")))
  (is (= [] (book-check/check-built-page "<div class=\"del\">A much longer sentence with retained words.</div><div class=\"add\">retained words.</div>")))
  (is (= [] (book-check/check-built-page "<div class=\"del\">The living skims it.</div><div class=\"add\">The living skims it, and the table row says so in full, at length, here.</div>"))))
(deftest phone-replacements-have-both-coloured-margins-and-changed-words
  (let [binary (cdp/chromium-binary) dir (fs/create-temp-dir {:prefix "book-replacement-"})
        file (fs/path dir "page.html")]
    (try
      (is binary "Chromium required")
      (when binary
        (spit (str file) (book-build/build (str source "\n- - The old list item.\n+ - The new list item.\n\n  | Name | Value |\n  |---|---|\n- | same | old |\n+ | same | new |\n\n```rust\n- let old = 1;\n+ let new = 1;\n```\n") (slurp (str root "/book-code.html"))))
        (cdp/with-page binary
          (fn [request session events]
            (request "Emulation.setDeviceMetricsOverride" {"width" 390 "height" 900 "deviceScaleFactor" 1 "mobile" true} session)
            (doseq [scheme ["dark" "light"]]
              (request "Emulation.setEmulatedMedia" {"features" [{"name" "prefers-color-scheme" "value" scheme}]} session)
              (cdp/navigate request session events (str "file://" file))
              (let [m (cdp/evaluate request session "(() => { const p=document.querySelector('.proposal > .diff .replacement-old');const n=document.querySelector('.proposal > .diff .replacement-new');const d=p.querySelector('del');const i=n.querySelector('ins');return {oldMargin:getComputedStyle(p).borderLeftColor,newMargin:getComputedStyle(n).borderLeftColor,oldWords:p.textContent,newWords:n.textContent,width:innerWidth,page:document.documentElement.scrollWidth,passages:document.querySelectorAll('.description p').length,strike:getComputedStyle(d).textDecorationLine,deleted:getComputedStyle(d).color,inserted:getComputedStyle(i).color,margin:getComputedStyle(p).borderLeftWidth,code:document.querySelectorAll('.code-diff .replacement-old, .code-diff .replacement-new').length,rows:document.querySelectorAll('tbody tr').length,items:document.querySelectorAll('.replacement-old li, .replacement-new li').length,syntax:document.querySelectorAll('.code-diff [class^=ts-]').length,codeMargin:getComputedStyle(document.querySelector('.ln-del')).boxShadow,codeRemoved:getComputedStyle(document.querySelector('.ln-del del span') || document.querySelector('.ln-del del')).color};})()")]
                (is (= "Entirely unrelated old passage." (m "oldWords")))
                (is (= "Fresh new wording." (m "newWords")))
                (is (not= (m "oldMargin") (m "newMargin")))
                (is (= 2 (m "passages")))
                (is (<= (m "page") (m "width")))
                (is (= "line-through" (m "strike")))
                (is (not= (m "deleted") (m "inserted")))
                (is (= "4px" (m "margin")))
                (is (= 2 (m "code")))
                (is (= 2 (m "rows")))
                (is (= 2 (m "items")))
                (is (pos? (m "syntax")))
                (is (not= "none" (m "codeMargin")))
                (is (= (m "deleted") (m "codeRemoved"))))))))
      (finally (fs/delete-tree dir)))))

(deftest changed-code-has-two-highlighted-lines
  (let [old "let x = \"<old>&\";" new "let x = \"<new>&\";"
        old-h "<span class=\"ts-keyword\">let</span> x = <span class=\"ts-string\">&quot;&lt;old&gt;&amp;&quot;</span>;"
        new-h "<span class=\"ts-keyword\">let</span> x = <span class=\"ts-string\">&quot;&lt;new&gt;&amp;&quot;</span>;"
        sides (book-build/marked-code old new old-h new-h)
        html (str/join sides)]
    (is (str/includes? html "<span class=\"ts-keyword\">let</span>"))
    (is (str/includes? html "<del><span class=\"ts-string\">old</span></del>"))
    (is (str/includes? html "<ins><span class=\"ts-string\">new</span></ins>"))
    (is (= (count (re-seq #"<span " html)) (count (re-seq #"</span>" html)))))
  (binding [book-build/*highlight* false]
    (let [html (book-build/signed-code-html "rust" ["context" "- let x = 1;" "- old unrelated" "+ let x = 2;" "+ fresh words" "+ extra" "tail"])]
      (is (= 2 (count (re-seq #"ln ln-del replacement-old" html))))
      (is (= 3 (count (re-seq #"ln ln-add" html))))
      (is (not (str/includes? html "ln ln-replace")))
      (is (str/includes? html "let x = <del>1</del>;"))
      (is (str/includes? html "<del>old unrelated</del>")))))
(deftest list-and-table-replacements-keep-both-sides
  (let [html (book-build/paired-body [:list [["-" "An old item"] ["-" "Removed"]]]
                                    [:list [["-" "An improved item"]]])]
    (is (= 2 (count (re-seq #"<ul>" html))))
    (is (str/includes? html "An <del>old</del> item"))
    (is (str/includes? html "<li><del>Removed</del></li>")))
  (doseq [source ["  | name | value |\n  |---|---|\n- | same | old |\n+ | same | new |"
                  "- | name | value |\n- |---|---|\n- | same | old |\n+ | name | value |\n+ |---|---|\n+ | same | new |"]]
    (let [html (book-build/text-panels (str/split-lines source))]
      (is (= 1 (count (re-seq #"<table>" html))))
      (is (str/includes? html "<td>same</td><td><del>old</del></td></tr><tr class=\"row-add replacement-new\"><td>same</td><td><ins>new</ins></td>"))
      (is (not (re-find #"class=\"(?:del|add|row-del|row-add)\"" html))))))
(deftest checker-names-code-and-table-pairs
  (doseq [[open close] [["span class=\"ln ln-" "span"] ["tr class=\"row-" "tr"]]]
    (let [html (str "<" open "del\">The old words remain.</" close ">"
                    "<" open "add\">The new words remain.</" close ">")
          e (first (book-check/check-built-page html))]
      (is (= "replaced-line-printed-twice" (:reason e)))
      (is (= "The old words remain." (:removed e)))
      (is (= "The new words remain." (:added e))))))
(deftest checker-compares-entire-adjacent-runs
  (let [line #(str "<span class=\"ln ln-" %1 "\">" %2 "</span>")
        html (str (line "del" "First old words.") (line "del" "Second old words.")
                  (line "add" "First new words.") (line "add" "Second new words."))
        errors (book-check/check-built-page html)]
    (is (= 1 (count errors)))
    (is (= "First old words. Second old words." (:removed (first errors))))
    (is (= "First new words. Second new words." (:added (first errors))))))

(def recovered-removed "- The Flow Nexus holds a flow's state as the last of its transitions, each recorded with the event that caused it, for every harness alike.")
(def recovered-added "+ The Flow Nexus holds a flow's state as the last of its transitions, each recorded with its cause, for every harness alike. When the Flow Nexus subscribes, the harness's Nexus answers with every session as it is observed now.")
(defn proposal-source [& body]
  (str "Presentation.{ «Blank» Review }\n\n# Blank\n\n### Proposal 1: Recovered · repo › a.md\n\n"
       (str/join "\n" body) "\n"))
(deftest a-blank-line-between-removed-and-added-paragraphs-keeps-their-word-marks
  (let [together (book-build/build (proposal-source recovered-removed recovered-added) "")
        apart (book-build/build (proposal-source recovered-removed "" recovered-added) "")]
    (is (= together apart))
    (is (str/includes? apart "class=\"del replacement-old\""))
    (is (str/includes? apart "<del>the event that caused it</del>"))
    (is (str/includes? apart "<ins>"))
    (is (not (str/includes? apart "class=\"del\"")))
    (is (not (str/includes? apart "class=\"add\"")))
    (is (= [] (book-check/check-built-page apart)))))
(deftest runs-of-paragraphs-pair-in-order-across-blank-lines
  (let [html (book-build/build (proposal-source "- The old first rule." "" "- The old second rule."
                                                "" "+ The new first rule." "" "+ The new second rule.") "")]
    (is (str/includes? html "<p>The <del>old</del> first rule.</p>"))
    (is (str/includes? html "<p>The <ins>new</ins> second rule.</p>"))
    (is (= 4 (count (re-seq #"replacement-(?:old|new)" html))))))
(deftest a-removed-and-an-added-fence-stay-two-figures-across-a-blank-line
  (let [fence (fn [sign body] [(str sign " ```mermaid") (str sign " " body) (str sign " ```")])
        source (proposal-source (str/join "\n" (fence "-" "flowchart TB; a-->b")) ""
                                (str/join "\n" (fence "+" "flowchart TB; a-->c")))
        fences (filter #(= :fence (first %)) (book-build/blocks source))]
    (is (= 2 (count fences)))
    (is (every? #(every? (fn [l] (str/starts-with? l (if (some (fn [x] (str/includes? x "a-->b")) (nth % 2)) "-" "+")))
                         (nth % 2))
                fences))))
(let [{:keys [fail error]} (run-tests 'book-replacement-test)] (System/exit (if (zero? (+ fail error)) 0 1)))
