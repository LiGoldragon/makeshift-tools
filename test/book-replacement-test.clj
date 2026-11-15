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
  (is (seq (book-check/check-built-page "<div class=\"del\">A much longer sentence with retained words.</div><div class=\"add\">retained words.</div>"))))
(deftest phone-replacement-has-inline-colours-strike-and-one-margin
  (let [binary (cdp/chromium-binary) dir (fs/create-temp-dir {:prefix "book-replacement-"})
        file (fs/path dir "page.html")]
    (try
      (is binary "Chromium required")
      (when binary
        (spit (str file) (book-build/build source (slurp (str root "/book-code.html"))))
        (cdp/with-page binary
          (fn [request session events]
            (request "Emulation.setDeviceMetricsOverride" {"width" 390 "height" 900 "deviceScaleFactor" 1 "mobile" true} session)
            (doseq [scheme ["dark" "light"]]
              (request "Emulation.setEmulatedMedia" {"features" [{"name" "prefers-color-scheme" "value" scheme}]} session)
              (cdp/navigate request session events (str "file://" file))
              (let [m (cdp/evaluate request session "(() => { const p=document.querySelector('.replacement');const d=p.querySelector('del');const i=document.querySelector('ins');return {width:innerWidth,page:document.documentElement.scrollWidth,passages:document.querySelectorAll('.description p').length,strike:getComputedStyle(d).textDecorationLine,deleted:getComputedStyle(d).color,inserted:getComputedStyle(i).color,margin:getComputedStyle(p).borderLeftWidth};})()")]
                (is (= 1 (m "passages")))
                (is (<= (m "page") (m "width")))
                (is (= "line-through" (m "strike")))
                (is (not= (m "deleted") (m "inserted")))
                (is (= "4px" (m "margin"))))))))
      (finally (fs/delete-tree dir)))))
(let [{:keys [fail error]} (run-tests 'book-replacement-test)] (System/exit (if (zero? (+ fail error)) 0 1)))
