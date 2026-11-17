(ns book-replacement-test
  (:require [clojure.test :refer [deftest is run-tests]] [babashka.fs :as fs]
            [clojure.string :as str]))
(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str root "/book-build.clj"))
(load-file (str root "/test/cdp.clj"))
(def source "Presentation.{ «Replacement» Review }\n\n# Replacement\n\n### Proposal 1: Description · repo › a.md\n\n::: description\n- A module is being designed or judged.\n+ A module.\n:::\n\n- Entirely unrelated old passage.\n+ Fresh new wording.\n")
(deftest replacements-are-one-passage-even-with-no-common-words
  (let [html (book-build/build source "")]
    (is (= 1 (count (re-seq #"class=\"replacement\"" html))))
    (is (str/includes? html "A module<del> is being designed or judged</del>."))
    (is (str/includes? html "<del>Entirely unrelated old passage</del><ins>Fresh new wording</ins>."))
    (is (not (str/includes? html "class=\"del\"")))
    (is (not (str/includes? html "class=\"add\"")))
    (is (= [] (book-check/check-built-page html)))))
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
(deftest phone-replacement-has-inline-colours-strike-and-one-margin
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
              (let [m (cdp/evaluate request session "(() => { const p=document.querySelector('.replacement');const d=p.querySelector('del');const i=document.querySelector('ins');return {width:innerWidth,page:document.documentElement.scrollWidth,passages:document.querySelectorAll('.description p').length,strike:getComputedStyle(d).textDecorationLine,deleted:getComputedStyle(d).color,inserted:getComputedStyle(i).color,margin:getComputedStyle(p).borderLeftWidth,code:document.querySelectorAll('.ln-replace').length,rows:document.querySelectorAll('tbody tr').length,items:document.querySelectorAll('.replacement li').length,syntax:document.querySelectorAll('.ln-replace [class^=ts-]').length,codeMargin:getComputedStyle(document.querySelector('.ln-replace')).boxShadow,codeRemoved:getComputedStyle(document.querySelector('.ln-replace del span') || document.querySelector('.ln-replace del')).color};})()")]
                (is (= 1 (m "passages")))
                (is (<= (m "page") (m "width")))
                (is (= "line-through" (m "strike")))
                (is (not= (m "deleted") (m "inserted")))
                (is (= "4px" (m "margin")))
                (is (= 1 (m "code")))
                (is (= 1 (m "rows")))
                (is (= 1 (m "items")))
                (is (pos? (m "syntax")))
                (is (not= "none" (m "codeMargin")))
                (is (= (m "deleted") (m "codeRemoved"))))))))
      (finally (fs/delete-tree dir)))))

(deftest changed-code-is-one-highlighted-line
  (let [old "let x = \"<old>&\";" new "let x = \"<new>&\";"
        old-h "<span class=\"ts-keyword\">let</span> x = <span class=\"ts-string\">&quot;&lt;old&gt;&amp;&quot;</span>;"
        new-h "<span class=\"ts-keyword\">let</span> x = <span class=\"ts-string\">&quot;&lt;new&gt;&amp;&quot;</span>;"
        html (book-build/marked-code old new old-h new-h)]
    (is (str/includes? html "<span class=\"ts-keyword\">let</span>"))
    (is (str/includes? html "<del><span class=\"ts-string\">old</span></del>"))
    (is (str/includes? html "<ins><span class=\"ts-string\">new</span></ins>"))
    (is (= (count (re-seq #"<span " html)) (count (re-seq #"</span>" html)))))
  (binding [book-build/*highlight* false]
    (let [html (book-build/signed-code-html "rust" ["context" "- let x = 1;" "- old unrelated" "+ let x = 2;" "+ fresh words" "+ extra" "tail"])]
      (is (= 2 (count (re-seq #"ln ln-replace" html))))
      (is (= 1 (count (re-seq #"ln ln-add" html))))
      (is (not (str/includes? html "ln ln-del")))
      (is (str/includes? html "let x = <del>1</del><ins>2</ins>;"))
      (is (str/includes? html "<del>old unrelated</del><ins>fresh words</ins>")))))
(deftest list-and-table-replacements-read-once
  (let [html (book-build/paired-body [:list [["-" "An old item"] ["-" "Removed"]]]
                                    [:list [["-" "An improved item"]]])]
    (is (= 1 (count (re-seq #"<ul>" html))))
    (is (str/includes? html "An <del>old</del><ins>improved</ins> item"))
    (is (str/includes? html "<li><del>Removed</del></li>")))
  (doseq [source ["  | name | value |\n  |---|---|\n- | same | old |\n+ | same | new |"
                  "- | name | value |\n- |---|---|\n- | same | old |\n+ | name | value |\n+ |---|---|\n+ | same | new |"]]
    (let [html (book-build/text-panels (str/split-lines source))]
      (is (= 1 (count (re-seq #"<table>" html))))
      (is (str/includes? html "<td>same</td><td><del>old</del><ins>new</ins></td>"))
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
(let [{:keys [fail error]} (run-tests 'book-replacement-test)] (System/exit (if (zero? (+ fail error)) 0 1)))
