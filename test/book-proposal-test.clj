;; Tests for book-proposal.clj: the manifest, copy, replace and land, and the reading of a
;; proposal directory into the page the builder shows. Run: bb test/book-proposal-test.clj
(ns book-proposal-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as process]))

(def tools-directory (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path tools-directory "book-build.clj")))

(defn- sh [directory & arguments]
  (let [result @(process/process (into ["git" "-C" (str directory)
                                        "-c" "user.name=Test" "-c" "user.email=test@example.org"
                                        "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main"]
                                       arguments)
                                 {:out :string :err :string :in ""})]
    (when-not (zero? (:exit result)) (throw (ex-info (str "git failed: " (:err result)) {:arguments arguments})))
    (:out result)))

(defn- commit! [root repository files message]
  (let [directory (fs/path root repository)]
    (doseq [[path text] files]
      (fs/create-dirs (fs/parent (fs/path directory path)))
      (spit (str (fs/path directory path)) text))
    (sh directory "add" "-A")
    (sh directory "commit" "-q" "-m" message)
    (sh directory "push" "-q" "origin" "HEAD:main")))

(defn- make-root
  "A temporary directory holding a checkout of each context repository, with a bare
  remote, the files committed and pushed to main."
  [repositories]
  (let [root (str (fs/create-temp-dir {:prefix "book-proposal-test-"}))]
    (doseq [repository book-proposal/context-repositories]
      (let [remote (str (fs/path root (str repository ".git")))]
        (sh root "init" "-q" "--bare" remote)
        (sh root "clone" "-q" remote (str (fs/path root repository)))
        (commit! root repository (get repositories repository {".keep" ""}) "first")))
    root))

(defn- page [source]
  (binding [book-build/*ethos-check* (constantly nil)] (book-build/build source "")))

(def module-a
  (str "---\ndescription: The first module.\ndependencies: []\n---\n\n"
       "# First\n\n## Rule\n\nThe rule is old and it stands in this module.\n\n"
       "A passage long enough to be seen as one that moved to another module.\n\n"
       "## Kept\n\nThis stays as it is.\n\n- a bullet\n- another bullet\n"))

(def module-b
  (str "---\ndescription: The second module.\ndependencies: []\n---\n\n"
       "# Second\n\n## Home\n\nThe home of the second module.\n"))

(def repositories {"psyche-belief" {"vision/first/0.md" module-a "vision/second/0.md" module-b}
                   "mind-belief" {"knowledge/third/0.md" "---\ndescription: Third.\ndependencies: []\n---\n\n# Third\n\nBody.\n"}})

(defn- write-text [path text] (spit path text) path)
(defn- copy-file [directory repository path] (str (fs/path directory repository path)))

(deftest the-manifest-is-one-small-datom
  (let [manifest {:title "A title" :kind "Review" :creator "Luna"
                  :entries [{:state :changed :repository "psyche-belief" :path "vision/a/0.md" :revision (apply str (repeat 40 "a"))}
                            {:state :new :repository "mind-belief" :path "knowledge/b/0.md"}
                            {:state :removed :repository "psyche-belief" :path "vision/c/0.md" :revision (apply str (repeat 40 "b"))}]}]
    (is (= manifest (book-proposal/read-manifest (book-proposal/manifest-text manifest))) "it reads back as written")
    (is (str/starts-with? (book-proposal/manifest-text manifest) "Manifest.{ «A title» Review Luna\n"))
    (doseq [bad ["" "Manifest.{ «T» Essay Luna [ ] }" "Manifest.{ «T» Proposal Luna [ Changed.{ «r» «p» } ] }"
                 "Manifest.{ «T» Proposal Luna [ New.{ «r» «p» «x» } ] }" "Other.{ «T» Proposal Luna [ ] }"
                 "Manifest.{ «T» Proposal [ ] }" "Manifest.{ «T» Proposal Nobody [ ] }"]]
      (is (thrown? Exception (book-proposal/read-manifest bad)) bad))))

(deftest copy-takes-a-module-by-its-name-and-records-its-revision
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        copied (book-proposal/copy! directory ["vision:first:0" "knowledge:third:0"] {:title "T" :creator "Sonnet"} root)
        manifest (book-proposal/read-directory-manifest directory)
        revision (str/trim (sh (fs/path root "psyche-belief") "rev-parse" "HEAD"))]
    (is (= 2 (count copied)))
    (is (= module-a (slurp (copy-file directory "psyche-belief" "vision/first/0.md"))) "the module as it stands on main")
    (is (= "Body." (str/trim (last (str/split-lines (slurp (copy-file directory "mind-belief" "knowledge/third/0.md")))))) "found in its own repository")
    (is (= [:changed :changed] (map :state (:entries manifest))))
    (is (= revision (:revision (first (:entries manifest)))) "the revision it came from")
    (is (= "T" (:title manifest)))
    (is (thrown-with-msg? Exception #"already holds" (book-proposal/copy! directory ["vision:first:0"] {} root)) "a module once")
    (is (thrown-with-msg? Exception #"stands in none" (book-proposal/copy! directory ["vision:nothing:0"] {} root)))
    (is (thrown-with-msg? Exception #"Not a module name" (book-proposal/copy! directory ["nonsense"] {} root)))
    (is (thrown-with-msg? Exception #"needs --title" (book-proposal/copy! (str (fs/path root "other")) ["vision:first:0"] {:creator "Sonnet"} root)))
    (is (thrown-with-msg? Exception #"needs --creator" (book-proposal/copy! (str (fs/path root "other")) ["vision:first:0"] {:title "T"} root)))
    (is (thrown-with-msg? Exception #"Creator is one of" (book-proposal/copy! (str (fs/path root "other")) ["vision:first:0"] {:title "T" :creator "Nobody"} root)))
    (testing "a module that stands in two repositories is refused"
      (commit! root "mind-belief" {"vision/first/0.md" module-a} "same module")
      (is (thrown-with-msg? Exception #"several repositories"
                            (book-proposal/copy! (str (fs/path root "again")) ["vision:first:0"] {:title "T" :creator "Sonnet"} root))))))

(deftest replace-is-exact-and-unique
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        _ (book-proposal/copy! directory ["vision:first:0"] {:title "T" :creator "Sonnet"} root)
        file (copy-file directory "psyche-belief" "vision/first/0.md")]
    (book-proposal/replace! file "The rule is old" "The rule is new")
    (is (str/includes? (slurp file) "The rule is new and it stands"))
    (is (thrown-with-msg? Exception #"absent" (book-proposal/replace! file "The rule is old" "x")))
    (is (thrown-with-msg? Exception #"occurs 2 times" (book-proposal/replace! file "bullet" "x")) "twice is refused")
    (is (str/includes? (slurp file) "- a bullet") "a refusal changes nothing")
    (book-proposal/replace! file "# First\n\n## Rule" "# First\n\n## Rules")
    (is (str/includes? (slurp file) "## Rules") "old and new may span lines")
    (let [stray (write-text (str (fs/path root "stray.md")) "text")]
      (is (thrown-with-msg? Exception #"not in a proposal directory" (book-proposal/replace! stray "t" "u"))))
    (is (thrown-with-msg? Exception #"not a copy" (book-proposal/replace! (write-text (str (fs/path directory "loose.md")) "text") "t" "u")))))

(deftest land-applies-the-difference-to-the-module-as-it-stands
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        _ (book-proposal/copy! directory ["vision:first:0"] {:title "T" :creator "Sonnet"} root)
        file (copy-file directory "psyche-belief" "vision/first/0.md")
        target (str (fs/path root "psyche-belief" "vision/first/0.md"))]
    (book-proposal/replace! file "The rule is old" "The rule is new")
    (testing "a module that has not moved lands as the copy itself"
      (is (= ["written psyche-belief › vision/first/0.md"] (book-proposal/land! directory root)))
      (is (= (slurp file) (slurp target))))
    (sh (fs/path root "psyche-belief") "checkout" "-q" "--" ".")
    (testing "a module that moved elsewhere takes the difference where it applies"
      (commit! root "psyche-belief" {"vision/first/0.md" (str/replace module-a "This stays as it is." "This stays, and more.")} "moved")
      (book-proposal/land! directory root)
      (let [landed (slurp target)]
        (is (str/includes? landed "The rule is new") "the copy's change applied")
        (is (str/includes? landed "This stays, and more.") "the other change kept")))
    (sh (fs/path root "psyche-belief") "checkout" "-q" "--" ".")
    (testing "a part that does not apply is refused back, and nothing is written"
      (commit! root "psyche-belief" {"vision/first/0.md" (str/replace module-a "The rule is old" "The rule differs")} "conflict")
      (let [before (slurp target)]
        (is (thrown-with-msg? Exception #"does not apply" (book-proposal/land! directory root)))
        (is (= before (slurp target)))))))

(deftest land-new-and-removed-modules
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        _ (book-proposal/copy! directory ["vision:first:0"] {:title "T" :creator "Sonnet"} root)
        _ (book-proposal/new! directory "psyche-belief" "vision:fresh:0" {} root)
        _ (book-proposal/remove! directory "vision:second:0" {} root)
        fresh (copy-file directory "psyche-belief" "vision/fresh/0.md")]
    (write-text fresh "---\ndescription: Fresh.\n---\n\n# Fresh\n\nNew text.\n")
    (is (= [:changed :new :removed] (map :state (:entries (book-proposal/read-directory-manifest directory)))))
    (is (= [] (book-proposal/check-directory directory root)))
    (let [lines (book-proposal/land! directory root)]
      (is (= ["unchanged psyche-belief › vision/first/0.md" "written psyche-belief › vision/fresh/0.md"
              "removed psyche-belief › vision/second/0.md"] lines)))
    (is (str/includes? (slurp (str (fs/path root "psyche-belief" "vision/fresh/0.md"))) "New text."))
    (is (not (fs/exists? (fs/path root "psyche-belief" "vision/second/0.md"))))
    (testing "a new module that already stands is refused"
      (commit! root "psyche-belief" {"vision/fresh/0.md" "x"} "now it stands")
      (is (thrown-with-msg? Exception #"already stands" (book-proposal/land! directory root))))))

(deftest a-directory-is-checked
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))]
    (book-proposal/copy! directory ["vision:first:0"] {:title "T" :creator "Sonnet"} root)
    (is (= [] (book-proposal/check-directory directory root)))
    (fs/delete (copy-file directory "psyche-belief" "vision/first/0.md"))
    (is (= ["copy-missing"] (map :reason (book-proposal/check-directory directory root))))
    (book-proposal/new! directory "psyche-belief" "vision:fresh:0" {} root)
    (is (= #{"copy-missing" "new-module-empty"} (set (map :reason (book-proposal/check-directory directory root)))))
    (is (= ["manifest-invalid"] (map :reason (book-proposal/check-directory (str (fs/path root "none")) root))))))

(defn- built [directory root]
  (page (book-proposal/directory-source directory root)))

(deftest a-module-shows-whole-under-its-name-with-every-change-in-place
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        _ (book-proposal/copy! directory ["vision:first:0"] {:title "A page" :creator "Sonnet"} root)
        file (copy-file directory "psyche-belief" "vision/first/0.md")
        _ (book-proposal/replace! file "The rule is old and it stands" "The rule is new and it stands")
        _ (book-proposal/replace! file "- a bullet\n" "- a bullet\n- an added bullet\n")
        _ (book-proposal/replace! file "description: The first module." "description: The first module, changed.")
        html (built directory root)]
    (is (str/includes? html "<h2 class=\"target\"><code style=\"font-size:.95rem\">vision:first:0</code></h2>") "under its name")
    (is (str/includes? html "<title>A page</title>"))
    (is (str/includes? html "<p>The rule is <del>old</del><ins>new</ins> and it stands in this module.</p>") "the replaced line, read once")
    (is (str/includes? html "<div class=\"add\"><ul><li>an added bullet</li></ul></div>") "an added line")
    (is (str/includes? html "The first module<ins>, changed</ins>.") "a changed description")
    (is (str/includes? html "This stays as it is.") "unchanged lines as they stand")
    (is (not (re-find #"(?m)^[+-] " html)) "no sign is shown")
    (is (= 1 (count (re-seq #"<article class=\"module diff\">" html))) "one sheet")))

(deftest lines-that-leave-one-copy-and-stand-in-another-show-as-moved-once
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        passage "A passage long enough to be seen as one that moved to another module."
        _ (book-proposal/copy! directory ["vision:first:0" "vision:second:0"] {:title "T" :creator "Sonnet"} root)
        _ (book-proposal/replace! (copy-file directory "psyche-belief" "vision/first/0.md") (str passage "\n\n") "")
        _ (book-proposal/replace! (copy-file directory "psyche-belief" "vision/second/0.md")
                                  "The home of the second module.\n"
                                  (str "The home of the second module.\n\n" passage "\n"))
        html (built directory root)]
    (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote passage)) html))) "the passage reads once")
    (is (= 1 (count (re-seq #"class=\"move move-proposed\"" html))) "under the move margin")
    (is (= 1 (count (re-seq #"move-placeholder" html))) "a placeholder where it left")
    (is (str/includes? html "<h2>From psyche-belief › vision/first/0.md</h2>") "headed by the module it left")
    (is (not (str/includes? html "<div class=\"del\"><p>A passage long")) "not also removed")
    (is (not (str/includes? html "<div class=\"add\"><p>A passage long")) "nor added")))

(deftest a-new-module-shows-whole-as-added-and-a-removed-one-whole-as-removed
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        _ (book-proposal/new! directory "psyche-belief" "vision:fresh:0" {:title "T" :creator "Sonnet"} root)
        _ (write-text (copy-file directory "psyche-belief" "vision/fresh/0.md")
                      "---\ndescription: Fresh one.\ndependencies: [vision:first:0]\n---\n\n# Fresh\n\nNew text.\n\n```text\ncode\n```\n")
        _ (book-proposal/remove! directory "vision:second:0" {} root)
        html (built directory root)]
    (is (str/includes? html "vision:fresh:0</code></h2><span class=\"module-state\">New</span>"))
    (is (str/includes? html "<div class=\"add\"><p>New text.</p></div>"))
    (is (str/includes? html "<div class=\"add\"><p>Fresh one.</p></div>") "its description, added")
    (is (str/includes? html "<code>vision:first:0</code>") "its dependencies")
    (is (str/includes? html "<div class=\"del\"><p>The home of the second module.</p></div>") "the removed module's text")
    (is (str/includes? html "<div class=\"del\"><p>The second module.</p></div>") "its description, removed")))

(deftest a-changed-fence-and-the-lines-after-a-change-stay-in-the-module
  (let [module (str "# Code\n\nIntro line.\n\n```text\nfirst\nsecond\nthird\n```\n\n- listed\n- also listed\n")
        root (make-root {"psyche-belief" {"vision/code/0.md" module}})
        directory (str (fs/path root "proposal"))
        _ (book-proposal/copy! directory ["vision:code:0"] {:title "T" :creator "Sonnet"} root)
        file (copy-file directory "psyche-belief" "vision/code/0.md")
        _ (book-proposal/replace! file "second" "SECOND")
        _ (book-proposal/replace! file "Intro line." "Intro line changed.")
        html (built directory root)]
    (is (str/includes? html "SECOND") "the changed line of a fence")
    (is (str/includes? html "first") "its unchanged lines")
    (is (str/includes? html "<ul><li>listed</li><li>also listed</li></ul>") "the list after the change is the module's, not a change")))

(deftest a-review-shows-the-changes-as-landed
  (let [root (make-root repositories)
        directory (str (fs/path root "proposal"))
        _ (book-proposal/copy! directory ["vision:first:0"] {:title "T" :creator "Sonnet" :kind "Review"} root)
        _ (book-proposal/replace! (copy-file directory "psyche-belief" "vision/first/0.md") "The rule is old" "The rule is new")
        source (book-proposal/directory-source directory root)]
    (is (str/starts-with? source "Presentation.{ «T» Review }"))
    (is (str/includes? source "# T\n\nCreated by Sonnet.\n\n") "the creator stands under the title")
    (book-proposal/copy! (str (fs/path root "second")) ["vision:first:0"] {:title "T" :creator "Sonnet"} root)
    (book-proposal/remove! (str (fs/path root "second")) "vision:second:0" {:creator "Haiku"} root)
    (is (= "Haiku" (:creator (book-proposal/read-directory-manifest (str (fs/path root "second"))))) "the creator is changed again")
    (is (str/includes? (page source) "<meta name=\"book-kind\" content=\"review\">"))))

(let [{:keys [fail error]} (run-tests 'book-proposal-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
