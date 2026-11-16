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
(let [{:keys [fail error]} (run-tests 'book-validator-test)] (System/exit (if (zero? (+ fail error)) 0 1)))
