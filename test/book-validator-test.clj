(ns book-validator-test
 (:require [clojure.test :refer [deftest is run-tests]] [babashka.fs :as fs]))
(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str root "/book-build.clj"))
(deftest configured-publication-validator-accepts-all-five-roots
 (is (= book-build/configured-ethos-zero book-build/*ethos-zero* book-build/*ethos-zero-real*))
 (doseq [text ["Library [] [ Name.String ] [] []"
               "Signal [] [ First.Integer Second.String ] [ FirstDone.Integer SecondDone.String ] []"
               "Operation [] [ Act.String ] [ Acted.Integer ] []"
               "Memory [] [ Record.{ String Integer } ]"
               "Emission [] [ Command.String ] [ Outcome.[ Done Refused.Integer ] ] []"]]
  (is (nil? (book-build/ethos-zero-check book-build/configured-ethos-zero text)) text))
 (is (some? (book-build/ethos-zero-check book-build/configured-ethos-zero "Emission [] [ First.String Second.String ] [ Outcome.[ Done Refused ] ] []"))
     "recognizing a root does not bypass its outcome-pairing rules"))
(def emission "Emission\n[]\n[ Command.String ]\n[ Outcome.[ Done Refused.Integer ] ]\n[]")
(def invalid-emission "Emission\n[]\n[ First.String Second.String ]\n[ Outcome.[ Done Refused ] ]\n[]")
(defn forms [body]
 (let [fence (str "```ethos\n" body "\n```\n")
       signed #(clojure.string/join "\n" (map (fn [l] (str %1 " " l)) (clojure.string/split-lines %2)))]
  [fence (signed "+" fence)
   (signed "+" (clojure.string/replace fence "```ethos" "```"))
   (str "```ethos\n" (signed "+" body) "\n```\n")
   (str "```diff\n" (signed "+" fence) "\n```\n")
   (str "::: move proposed\nfrom: repo › a.md\nto: repo › b.md\n\n" fence ":::\n")]))
(deftest every-rendering-path-checks-ethos
 (binding [book-build/*enforce-ethos* true book-build/*highlight* false]
  (doseq [form (forms emission)]
   (is (clojure.string/includes? (book-build/build (str "### Proposal 1: Added · repo › a.md\n\n" form) "") "language-ethos")))
  (doseq [form (forms invalid-emission)]
   (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ethos rejected:.*Unpaired" (book-build/build (str "### Proposal 1: Added · repo › a.md\n\n" form) ""))))))
(deftest mixed-signed-code-checks-only-the-new-standing-text
 (let [calls (atom [])]
  (binding [book-build/*enforce-ethos* true book-build/*highlight* false
            book-build/*ethos-check* (fn [text] (swap! calls conj text) nil)]
   (book-build/signed-code-html "ethos" ["  Library" "  []" "- [ Old.String ]" "+ [ New.String ]" "  []" "  []"]))
  (is (= ["Library\n[]\n[ New.String ]\n[]\n[]"] @calls))))
(let [{:keys [fail error]} (run-tests 'book-validator-test)] (System/exit (if (zero? (+ fail error)) 0 1)))
