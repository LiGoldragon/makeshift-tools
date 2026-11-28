(ns book-proposal-png-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def tools (fs/parent (fs/parent (fs/absolutize *file*))))
(load-file (str (fs/path tools "book-build.clj")))

(def first-png "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQMgkDAAD4AJ3MaiF4AAAAAElFTkSuQmCC")
(def second-png "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4MKEBAAR0AgGa7/GqAAAAAElFTkSuQmCC")
(def module-path "vision/figures/0.md")
(def repository "psyche-belief")
(def revision (apply str (repeat 40 "1")))
(def front-matter "---\ndescription: Figures as drawn.\ndependencies: []\n---\n\n")

(defn- write-text! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn- write-png! [directory name encoded]
  (fs/write-bytes (fs/path directory name) (.decode (java.util.Base64/getDecoder) encoded)))

(defn- write-proposal! [directory kind state body]
  (write-text! (fs/path directory "manifest.datom")
               (book-proposal/manifest-text
                {:title "DrawnFigures.1" :kind kind :creator "Sol" :drawer "Luna"
                 :entries [(cond-> {:state state :repository repository :path module-path}
                             (= :changed state) (assoc :revision revision))]}))
  (write-text! (fs/path directory repository module-path) (str front-matter body)))

(defn- cli [name & args]
  @(process/process (into ["bb" (str (fs/path tools (str name ".clj")))] (map str args))
                    {:out :string :err :string
                     :extra-env {"BOOK_NOTICE_CONFIGURATION"
                                 (str (fs/path tools "test/refusal-notice-off.datom"))}}))

(deftest an-undrawn-manifest-is-reported-but-can-be-published
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :new "Opening.\n")
    (let [manifest (fs/path directory "manifest.datom")
          out (fs/path directory "book.html")
          undrawn (str/replace (slurp (str manifest)) " Some.Luna" " None")]
      (write-text! manifest undrawn)
      (is (str/includes? (:out (cli "book-tools" "source" directory)) "Created by Sol.\n"))
      (is (= 0 (:exit (cli "book-tools" "figures" directory (fs/path directory "figures")))))
      (let [check (cli "book-check" directory)
            build (cli "book-build" directory out)]
        (is (= 1 (:exit check)))
        (is (= 0 (:exit build)) (:err build))
        (doseq [result [check build]]
          (is (str/includes? (:err result) "manifest-without-drawer")))
        (is (fs/exists? out)))
      (is (= 1 (:exit (cli "book-tools" "drawer" directory "Nobody"))))
      (is (= undrawn (slurp (str manifest))))
      (is (= 0 (:exit (cli "book-tools" "drawer" directory "Terra"))))
      (is (= 0 (:exit (cli "book-build" directory out))))
      (is (= 0 (:exit (cli "book-check" directory out))))
      (let [html (slurp (str out))]
        (is (str/includes? html "Created by Sol. Drawn by Terra."))
        (doseq [[from to reason] [["Drawn by Terra." "Drawn by Haiku." "publication-signature-mismatch"]
                                  ["{ Vision figures 0 }" "psyche-belief › vision/figures/0.md" "publication-module-headings-mismatch"]
                                  ["<title>DrawnFigures.1" "<title>Other.1" "publication-title-mismatch"]]]
          (write-text! out (str/replace html from to))
          (is (str/includes? (:err (cli "book-check" directory out)) reason))))
      (write-text! manifest (str/replace (slurp (str manifest)) "DrawnFigures.1" "Drawn figures"))
      (is (str/includes? (:err (cli "book-check" directory)) "manifest-presentation-name-invalid")))))

(deftest two-images-in-the-new-module-margin
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :new
                     (str "Opening.\n\n![Marks & \"shapes\"](marks.png)\n\n"
                          "Comparison.\n\n![Colours](colours.png)\n\nEnding.\n"))
    (write-png! directory "marks.png" first-png)
    (write-png! directory "colours.png" second-png)
    (let [out (fs/path directory "book.html")
          checker (cli "book-check" directory)
          builder (cli "book-build" directory out)]
      (is (zero? (:exit checker)) (:err checker))
      (is (zero? (:exit builder)) (:err builder))
      (when (fs/exists? out)
        (let [html (slurp (str out))
              article (re-find #"(?s)<article class=\"module diff\">.*?</article>" html)
              sequence ["{ Vision figures 0 }</code></h2></header>"
                        "<div class=\"add\"><p>Figures as drawn.</p>"
                        "<div class=\"add\"><p>Opening.</p>"
                        "<div class=\"add\"><img"
                        "alt=\"Marks &amp; &quot;shapes&quot;\""
                        (str "data:image/png;base64," first-png)
                        "<div class=\"add\"><p>Comparison.</p>"
                        "<div class=\"add\"><img"
                        "alt=\"Colours\""
                        (str "data:image/png;base64," second-png)
                        "<div class=\"add\"><p>Ending.</p>"]
              positions (rest (reductions #(or (str/index-of (or article "") %2 (inc %1)) -1) -1 sequence))]
          (is (= 2 (count (re-seq #"<img " html))))
          (is (every? #(<= 0 %) positions))
          (is (apply < positions))
          (is (not (str/includes? html "](marks.png)"))))))))

(deftest the-checker-refuses-artifact-false-passes
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :new
                     "Opening.\n\n![Marks](marks.png)\n\nComparison.\n\n![Colours](colours.png)\n")
    (write-png! directory "marks.png" first-png)
    (write-png! directory "colours.png" second-png)
    (let [out (fs/path directory "book.html")
          built (cli "book-build" directory out)
          _ (is (zero? (:exit built)) (:err built))
          correct (cli "book-check" directory out)
          _ (is (zero? (:exit correct)) (:err correct))
          html (slurp (str out))
          [marks colours] (re-seq #"<img [^>]*>" html)]
      (doseq [[variant altered reason]
              [["literal-links"
                (-> html (str/replace marks "<p>![Marks](marks.png)</p>")
                         (str/replace colours "<p>![Colours](colours.png)</p>"))
                "proposal-artifact-image-count-mismatch"]
               ["reversed"
                (str/replace html #"<img [^>]*>" {marks colours colours marks})
                "proposal-artifact-image-content-or-order-mismatch"]
               ["wrong-bytes"
                (str/replace html first-png second-png)
                "proposal-artifact-image-content-or-order-mismatch"]
               ["unmargined"
                (str/replace html (str "<div class=\"add\">" marks) (str "<div class=\"ctx\">" marks))
                "proposal-artifact-image-placement-mismatch"]
               ["wrong-position"
                (str/replace html (re-pattern (str "(<div class=\"add\"><p>Opening[.]</p></div>)(<div class=\"add\">"
                                                   (java.util.regex.Pattern/quote marks) "</div>)")) "$2$1")
                "proposal-artifact-image-placement-mismatch"]]]
        (testing variant
          (let [path (fs/path directory (str variant ".html"))]
            (is (not= html altered))
            (write-text! path altered)
            (let [refusal (cli "book-check" directory path)]
              (is (= 1 (:exit refusal)) (:err refusal))
              (is (str/includes? (:err refusal) reason) (:err refusal)))))))))

(deftest a-renderer-regression-cannot-erase-the-checkers-expectations
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :new "Opening.\n\n![Marks](marks.png)\n\nEnding.\n")
    (write-png! directory "marks.png" first-png)
    (let [{:keys [images slots]} (#'book-build/probe-directory directory directory nil)
          good (#'book-build/build-directory directory directory nil "")
          broken (binding [book-build/*proposal-image-html* (constantly "")]
                   (#'book-build/build-directory directory directory nil ""))]
      (is (= [{:slot "0" :body-line 3 :module "psyche-belief › vision/figures/0.md"}]
             (mapv #(select-keys % [:slot :body-line :module]) images)))
      (is (not (str/includes? slots "data:image/png")))
      (is (not (str/includes? broken "<img")))
      (is (= [{:reason "proposal-artifact-image-count-mismatch" :expected 1 :actual 0}]
             (book-check/check-proposal-artifact images slots broken)))
      (is (= "proposal-artifact-source-position-unavailable"
             (-> (book-check/check-proposal-artifact images "" good) first :reason))))))

(deftest changed-module-keeps-image-in-place
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :changed "Before.\n\n![New figure](figure.png)\n\nAfter.\n")
    (write-png! directory "figure.png" first-png)
    (with-redefs [book-proposal/text-at (fn [_ repo rev path]
                                        (is (= [repository revision module-path] [repo rev path]))
                                        (str front-matter "Before.\n\nAfter.\n"))]
      (let [html (#'book-build/build-directory directory directory nil "")
            {:keys [images slots]} (#'book-build/probe-directory directory directory nil)]
        (is (empty? (book-check/check-proposal-artifact images slots html)))
        (is (re-find #"(?s)Before\.</p>\s*<section class=\"proposal in-place\">\s*<div class=\"diff\"><div class=\"add\"><img.*?</section>\s*<p>After\.</p>" html))))))

(deftest image-replacement-keeps-both-git-and-copy-lines-in-their-margins
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :changed
                     "Before.\n\n![New figure](new.png)\n\nAfter.\n")
    (write-png! directory "old.png" first-png)
    (write-png! directory "new.png" second-png)
    (with-redefs [book-proposal/text-at (fn [_ repo rev path]
                                        (is (= [repository revision module-path] [repo rev path]))
                                        (str front-matter "Before.\n\n![Old figure](old.png)\n\nAfter.\n"))]
      (let [html (#'book-build/build-directory directory directory nil "")
            {:keys [images slots]} (#'book-build/probe-directory directory directory nil)
            check (fn [artifact] (mapv :reason (book-check/check-proposal-artifact images slots artifact)))
            [old-image new-image] (re-seq #"<img [^>]*>" html)]
        (is (= [[:del 3 "old.png"] [:add 3 "new.png"]]
               (mapv (juxt :sign :body-line :filename) images)))
        (is (re-find #"(?s)Before[.]</p>\s*<section class=\"proposal in-place\">\s*<div class=\"diff\"><div class=\"del\"><img [^>]+></div><div class=\"add\"><img [^>]+></div></div>\s*</section>\s*<p>After[.]</p>" html))
        (is (str/includes? old-image (str "data:image/png;base64," first-png)))
        (is (str/includes? new-image (str "data:image/png;base64," second-png)))
        (is (empty? (check html)))
        (is (= ["proposal-artifact-image-count-mismatch"] (check (str/replace html old-image ""))))
        (is (= ["proposal-artifact-image-content-or-order-mismatch"]
               (check (str/replace html first-png second-png))))
        (is (= ["proposal-artifact-image-placement-mismatch"]
               (check (str/replace html (str "<div class=\"del\">" old-image)
                                   (str "<div class=\"add\">" old-image)))))
        (is (= ["proposal-artifact-image-placement-mismatch"]
               (check (str/replace html (str "<div class=\"add\">" new-image)
                                   (str "<div class=\"del\">" new-image))))))
      (fs/delete (fs/path directory "old.png"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"proposal-image-file-unavailable: old.png"
                            (#'book-build/build-directory directory directory nil ""))))))

(deftest revised-new-module-compares-source-and-assets-across-snapshots
  (fs/with-temp-dir [before {}]
    (fs/with-temp-dir [after {}]
      (let [opening "## First\n\n![First](first.png)\n\n## Middle\n\n"
            ending "\n\n## Last\n\n![Last](last.png)\n"
            prior {:directory (str before)}
            location (str repository " › " module-path)]
        (write-proposal! before "Proposal" :new
                         (str opening "![Middle](middle.png)\n\nOld prose." ending))
        (write-proposal! after "Proposal" :new
                         (str opening "![Middle](middle.png)\n\nNew prose." ending))
        (doseq [directory [before after] name ["first.png" "last.png"]]
          (write-png! directory name first-png))
        (write-png! before "middle.png" first-png)
        (write-png! after "middle.png" second-png)
        (with-redefs-fn {#'book-proposal/prior-store-directory :directory
                        #'book-proposal/text-at (fn [& _] (throw (ex-info "Git base read" {})))}
          (fn []
            (let [{:keys [images slots]} (#'book-build/probe-directory after after prior)
                  source (book-proposal/directory-source after after prior)
                  html (#'book-build/build-directory after after prior "")
                  reasons (fn [artifact] (mapv :reason (book-check/check-proposal-artifact images slots artifact)))
                  middle-add (second (re-find #"(<div class=\"add\"><img [^>]*alt=\"Middle\"[^>]*></div>)" html))]
              (is (str/includes? source (str "## " location ", a revised new file")))
              (is (str/includes? source "- ![Middle](middle.png)\n+ ![Middle](middle.png)"))
              (is (str/includes? source "- Old prose.\n+ New prose."))
              (is (= [[:keep "First"] [:del "Middle"] [:add "Middle"] [:keep "Last"]]
                     (mapv (juxt :sign :alt-text) images)))
              (is (str/includes? middle-add second-png))
              (is (str/includes? (or (re-find #"<div class=\"del\"><img [^>]*alt=\"Middle\"[^>]*>" html) "")
                                 first-png))
              (is (str/includes? html "{ Vision figures 0 }</code></h2></header>"))
              (is (empty? (reasons html)))
              (is (= ["proposal-artifact-image-placement-mismatch"]
                     (reasons (str/replace html middle-add (str/replace middle-add "class=\"add\"" "class=\"ctx\""))))))
            (write-proposal! after "Proposal" :changed "Still another module state.
")
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prior-proposal-new-module-identity-mismatch"
                                  (book-proposal/directory-page after after prior)))
            (write-proposal! after "Proposal" :new
                             (str opening "![Middle](middle.png)\n\nNew prose." ending))
            (fs/delete (fs/path before "middle.png"))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"proposal-image-file-unavailable: middle.png"
                                  (book-proposal/directory-page after after prior)))))))))

(deftest unsupported-assets-and-placements-refuse-in-both-cli-commands
  (doseq [[kind body reason put-file]
          [["Proposal" "![Picture](missing.png)" "proposal-image-file-unavailable" nil]
           ["Proposal" "![Picture](bad.png)" "proposal-image-invalid-png" "iVBORw0KGgo="]
           ["Proposal" "![Picture](picture.jpg)" "proposal-image-root-png-required" nil]
           ["Proposal" "![Picture](sub/figure.png)" "proposal-image-root-png-required" nil]
           ["Proposal" "![Picture](https://example.org/figure.png)" "proposal-image-root-png-required" nil]
           ["Proposal" "In a paragraph ![Picture](figure.png)." "proposal-image-placement-unsupported" nil]
           ["Proposal" "```text\n![Picture](figure.png)\n```" "proposal-image-placement-unsupported" nil]
           ["Review" "![Picture](figure.png)" "proposal-image-placement-unsupported" nil]]]
    (testing body
      (fs/with-temp-dir [directory {}]
        (write-proposal! directory kind :new (str body "\n"))
        (when put-file (write-png! directory "bad.png" put-file))
        (let [out (fs/path directory "book.html")
              results [(cli "book-check" directory) (cli "book-build" directory out)]]
          (doseq [result results]
            (is (= 1 (:exit result)) (:err result))
            (is (str/includes? (:err result) reason) (:err result)))
          (is (not (fs/exists? out))))))))

(deftest projected-image-line-alone-is-not-a-publication-source
  (fs/with-temp-dir [directory {}]
    (let [file (fs/path directory "book.md")
          out (fs/path directory "book.html")]
      (write-text! file (str "# Drawn figures\n\n## psyche-belief › " module-path ", a new file\n\n"
                             "::: description\n+ Figures as drawn.\n:::\n\n+ ![Picture](figure.png)\n"))
      (write-png! directory "figure.png" first-png)
      (doseq [result [(cli "book-check" file) (cli "book-build" file out)]]
        (is (= 1 (:exit result)) (:err result))
        (is (str/includes? (:err result) "proposal-image-directory-required") (:err result)))
      (is (not (fs/exists? out))))))

(deftest signed-png-keeps-signed-svg-in-the-same-module
  (fs/with-temp-dir [directory {}]
    (write-proposal! directory "Proposal" :new
                     "![PNG drawing](painting.png)\n\n![SVG drawing](diagram.svg)\n")
    (write-png! directory "painting.png" first-png)
    (write-text! (fs/path directory "diagram.svg")
                 "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\"/></svg>")
    (let [out (fs/path directory "book.html")
          built (cli "book-build" directory out)]
      (is (zero? (:exit built)) (:err built))
      (when (fs/exists? out)
        (let [html (slurp (str out))
              checked (cli "book-check" directory out)]
          (is (str/includes? html (str "data:image/png;base64," first-png)))
          (is (re-find #"figmark fig-add[^>]*><img[^>]*alt=\"SVG drawing\"[^>]*src=\"data:image/svg\+xml;base64," html))
          (is (zero? (:exit checked)) (:err checked)))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
