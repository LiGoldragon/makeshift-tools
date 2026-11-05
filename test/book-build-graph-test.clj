;; Tests for reading an ASCII sketch into a graph in book-build.clj.
;; Run: bb test/book-build-graph-test.clj
(ns book-build-graph-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]))

(def tools-dir (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path tools-dir "book-build.clj")))

(defn sketch [s] (str/split-lines s))
(defn graph [s] (book-build/ascii-graph (sketch s)))
(defn edges [g] (set (map (juxt :from :to :verb) (:edges g))))
(defn labels [g] (into {} (map (juxt :id :label) (:nodes g))))

(def chain
  "+-----------+
| asks      |
+-----------+
     | sends to
     v
+-----------+
| answers   |
+-----------+")

(def fork
  "      +-----------+
      | guard     |
      +-----------+
            |
     +------+-------+
     | under        | over
     v              v
+---------+    +---------+
| refuses |    | compacts|
+---------+    +---------+")

(def join
  "+--------+   +--------+
| psyche |   | mind   |
+--------+   +--------+
     |            |
     | reads      | writes
     v            v
   +----------------+
   | field          |
   +----------------+")

(def diamond
  "      +---------+
      | comment |
      +---------+
        |     |
  rules a     asks
        v     v
+--------+  +---------+
| vision |  | inquiry |
+--------+  +---------+
     \\          /
      \\ lands  /
       v      v
     +----------+
     | module   |
     +----------+")

(deftest ascii-to-edn-chain
  (is (= {:nodes [{:id "n1" :label "asks"} {:id "n2" :label "answers"}]
          :edges [{:from "n1" :to "n2" :verb "sends to"}]}
         (graph chain))))

(deftest ascii-to-edn-fork
  (let [g (graph fork) l (labels g)]
    (is (= {"n1" "guard" "n2" "refuses" "n3" "compacts"} l))
    (is (= #{["n1" "n2" "under"] ["n1" "n3" "over"]} (edges g)))))

(deftest ascii-to-edn-join
  (let [g (graph join)]
    (is (= {"n1" "psyche" "n2" "mind" "n3" "field"} (labels g)))
    (is (= #{["n1" "n3" "reads"] ["n2" "n3" "writes"]} (edges g)))))

(deftest ascii-to-edn-diamond
  (let [g (graph diamond)]
    (is (= {"n1" "comment" "n2" "vision" "n3" "inquiry" "n4" "module"} (labels g)))
    (is (= #{["n1" "n2" "rules a"] ["n1" "n3" "asks"] ["n2" "n4" "lands"] ["n3" "n4" "lands"]}
           (edges g))
        "a verb between two lines that meet is the verb of both")))

(deftest ascii-to-edn-bare-text-and-side-arrow
  (is (= {:nodes [{:id "n1" :label "the living speaks" :bare true} {:id "n2" :label "proposal"}]
          :edges [{:from "n1" :to "n2"}]}
         (graph " the living speaks
        |
        v
 +--------------+
 | proposal     |
 +--------------+")))
  (is (= #{["n1" "n2" nil]}
         (edges (graph "+----------+     +----------+
| proposal |---->| you rule |
+----------+     +----------+")))))

(deftest ascii-to-edn-box-drawing-glyphs
  (is (= {:nodes [{:id "n1" :label "Asleep"} {:id "n2" :label "Awake"}]
          :edges [{:from "n1" :to "n2" :verb "is woken"}]}
         (graph "┌────────┐
│ Asleep │
└───┬────┘
    │ is woken
    ▼
┌────────┐
│ Awake  │
└────────┘"))))

(deftest sketch-read-in-part-is-not-drawn
  (is (nil? (graph "+----------+
| outer    |
| +------+ |
| | in   | |
| +------+ |
+----------+
     |
     v
+----------+
| next     |
+----------+")) "a box inside a box")
  (is (nil? (graph "+----------+
| proposal |---->| you rule |
+----------+")) "an arrow to no box"))

(deftest table-is-not-a-figure
  (is (false? (book-build/diagram? ["+---+---+" "| a | b |" "+---+---+"]))))

(let [{:keys [fail error]} (run-tests 'book-build-graph-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
