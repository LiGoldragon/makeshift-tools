#!/usr/bin/env bb
;; Build a book page from its Markdown source.
;;
;; usage: bb book-build.clj SOURCE.md OUT.html [--code book-code.html] [--figures DIR] [--ethos-zero REF] [--ethos-zero-real REF]
;;
;; The source is a book in the to-the-living form: a title, a
;; Crystallization heading, proposals (### Proposal N: title), File:
;; lines, fenced text as panels signed in the margin, fenced code highlighted by tree-sitter as code
;; blocks, and numbered rulings. An ASCII box-and-arrow diagram in an unlabelled
;; fence, and every unlabelled fence outside a proposal, boxed or not, is a
;; diagram; it is never drawn: the n-th diagram is shown as fig-n.svg from the
;; --figures directory. Every Mermaid fence requires its ordered figure file;
;; a missing or unreadable one stops the build and names that file. Other
;; diagram forms keep their existing optional-figure behavior. A failure to
;; read or write a file is also an error (stderr line, exit 1). A paragraph opening with
;; **Destroy** or **Archive** wraps the deleted module that follows, shown
;; whole, in a record panel under the red margin with that mark. Inside a proposal,
;; a ::: comment container is the author's reason, in its own box, before the
;; module text it explains. Each alternative is its own proposal. The first line may be a
;; Presentation datom, Presentation.{ «title» Kind }, Kind being Proposal
;; (the default when absent), Review or Account: a Review shows its added and
;; removed lines in the dark shade. A paragraph opening "Created by" under the
;; title is the signature line, shown as the subtitle. The page code (book-code.html) is
;; appended whole. ethos-zero (the one --ethos-zero-real names, else the pinned
;; main) only decides how an unlabelled module-view fence and an Ethos file are
;; tagged; its refusal never stops a build. --ethos-zero names the one that reads
;; proposed code (a flake
;; reference such as github:LiGoldragon/ethos-zero/ethos-proposals carrying
;; proposed syntax, a store path, or a binary), else the pinned main.
(ns book-build
  (:require [clojure.string :as str]
            [clojure.edn]
            [babashka.fs :as fs]))

(def box-font "IBM Plex Sans Condensed, Arial Narrow, system-ui, sans-serif")
(def label-font "JetBrains Mono, ui-monospace, DejaVu Sans Mono, monospace")
(def langs {"js" "javascript"})
(def script-dir (str (fs/parent (fs/absolutize *file*))))

;; ------------------------------------------------------------- text helpers

(def ^:private ws "(?U)[\\s\\x1c-\\x1f]")
(def ^:private ws-left (re-pattern (str "^" ws "+")))
(def ^:private ws-right (re-pattern (str ws "+$")))

(defn rstrip [s] (str/replace s ws-right ""))
(defn strip-left [s] (str/replace s ws-left ""))
(defn strip [s] (-> s (str/replace ws-left "") rstrip))
(defn blank? [s] (= "" (strip s)))

(defn cp-count [^String s] (.codePointCount s 0 (.length s)))

(defn esc [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn esc-attr [s]
  (-> (esc s) (str/replace "\"" "&quot;") (str/replace "'" "&#x27;")))

(defn num [x]
  (let [x (/ (Math/rint (* 100 (double x))) 100)]
    (if (== x (Math/floor x)) (str (long x)) (str x))))

(defn inline
  "Escape text; render proposal marks, `code`, links and **bold**. Proposal
  marks are [+added+], [-removed-], and [~moved from <module>: text~]."
  [text]
  (let [m (re-matcher #"`([^`]+)`" text)
        sb (StringBuilder.)
        ;; the url is escaped by esc; the pattern excludes quotes, so it stays inside href
        esc (fn [t] (-> (esc t)
                        (str/replace #"\[([^\]]+)\]\((https?://[^\s)\"']+)\)"
                                     (fn [[_ label url]] (str "<a href=\"" url "\">" label "</a>")))
                        (str/replace #"\*\*(?=\S)(.+?)(?<=\S)\*\*" "<strong>$1</strong>")
                        (str/replace #"\[~moved from ([^:\]]+):\s*(.+?)~\]"
                                     "<span class=\"moved\"><span class=\"moved-origin\">from $1:</span> $2</span>")
                        (str/replace #"\[-(.+?)-\]" "<del>$1</del>")
                        (str/replace #"\[\+(.+?)\+\]" "<ins>$1</ins>")))]
    (loop [pos 0]
      (if (.find m)
        (do (.append sb (esc (subs text pos (.start m))))
            (.append sb (str "<code>" (esc (.group m 1)) "</code>"))
            (recur (.end m)))
        (do (.append sb (esc (subs text pos)))
            (str sb))))))

(defn join-lines [lines]
  (str/join " " (keep #(let [t (strip %)] (when (not= "" t) t)) lines)))

;; ------------------------------------------------------------ graph figures
;;
;; Any box-and-arrow sketch is read cell by cell into an EDN graph,
;;   {:nodes [{:id "n1" :label "his comment"} ...]
;;    :edges [{:from "n1" :to "n2" :verb "rules a line"} ...]}
;; a node may also carry :note (free text beside its box) or :bare true (an
;; unboxed text that a line starts or ends at). A ```figure fence may give the
;; graph directly as EDN. The graph is only read, to tell a
;; diagram from code; the build never draws it. A diagram's figure is the
;; illustrator's fig-N.svg.

(def ^:private box-drawing
  (merge (zipmap (map str "┌┐└┘├┤┬┴┼╭╮╰╯") (repeat "+"))
         {"─" "-" "│" "|" "▼" "v" "▲" "^" "▶" ">" "◀" "<" "►" ">" "◄" "<"}))

(defn char-grid
  "Lines as rows of code points; box-drawing glyphs read as their ASCII."
  [lines]
  (mapv #(mapv (fn [ch] (box-drawing ch ch)) (re-seq #"(?s)." (rstrip %))) lines))

(defn at [g r c] (or (get-in g [r c]) " "))

(def ^:private edge-chars #{"-" "+" "v" "^"})

(defn- box-at
  "The box whose top-left corner is at r c, or nil."
  [g r c]
  (when (and (= "+" (at g r c)) (#{"|" "+"} (at g (inc r) c)))
    (some (fn [c2]
            (when (and (= "+" (at g r c2)) (#{"|" "+"} (at g (inc r) c2))
                       (not= "+" (at g (inc r) c) (at g (inc r) c2)))
              (loop [r2 (+ r 2)]
                (let [a (at g r2 c) b (at g r2 c2)]
                  (cond
                    (and (= "+" a) (= "+" b) (every? #(edge-chars (at g r2 %)) (range c (inc c2))))
                    {:r0 r :c0 c :r1 r2 :c1 c2}
                    (and (#{"|" "+"} a) (#{"|" "+"} b)) (recur (inc r2))
                    :else nil)))))
          (take-while #(edge-chars (at g r %)) (range (inc c) (count (get g r)))))))

(defn- find-boxes [g]
  (reduce (fn [boxes [r c]]
            (if (some #(and (<= (:r0 %) r (:r1 %)) (<= (:c0 %) c (:c1 %))) boxes)
              boxes
              (if-let [b (box-at g r c)] (conj boxes b) boxes)))
          [] (for [r (range (count g)) c (range (count (g r)))] [r c])))

(defn- box-text [g {:keys [r0 c0 r1 c1]}]
  (->> (range (inc r0) r1)
       (map (fn [r] (strip (apply str (map #(at g r %) (range (inc c0) c1))))))
       (remove #(= "" %))
       (str/join "\n")))

(defn- owned-cells
  "Cell -> box index; an arrowhead in a box border stays a connector."
  [g boxes]
  (into {}
        (for [[i {:keys [r0 c0 r1 c1]}] (map-indexed vector boxes)
              r (range r0 (inc r1)) c (range c0 (inc c1))
              :let [ch (at g r c)]
              :when (not (or (and (= r r0) (< c0 c c1) (= ch "v"))
                             (and (= r r1) (< c0 c c1) (= ch "^"))
                             (and (= c c0) (< r0 r r1) (= ch ">"))
                             (and (= c c1) (< r0 r r1) (= ch "<"))))]
          [[r c] i])))

(defn- letter? [s] (boolean (re-matches #"(?U)[\p{L}\p{N}]" s)))

(def ^:private horizontal #{"-" "+" "<" ">"})

(defn- conn-char
  "The connector at r c, or nil. A dash ending its run over or under a
  vertical line is a corner."
  [g owned r c]
  (when-not (owned [r c])
    (let [ch (at g r c) l (at g r (dec c)) rt (at g r (inc c))
          side? #(or (horizontal (at g r %)) (owned [r %]))]
      (case ch
        ("|" "+") ch
        "-" (when (or (horizontal l) (horizontal rt) (owned [r (dec c)]) (owned [r (inc c)]))
              (if (and (not (and (horizontal l) (horizontal rt)))
                       (not (and (side? (dec c)) (side? (inc c))))
                       (or (#{"|" "v" "^"} (at g (dec r) c)) (#{"|" "v" "^"} (at g (inc r) c))))
                "+" "-"))
        ("v" "^" "\\" "/") (when-not (or (letter? l) (letter? rt)) ch)
        "<" (when (= "-" rt) ch)
        ">" (when (= "-" l) ch)
        nil))))

(def ^:private dirs
  {"|" [[-1 0] [1 0]] "-" [[0 -1] [0 1]] "+" [[-1 0] [1 0] [0 -1] [0 1]]
   "v" [[-1 0] [1 0]] "^" [[-1 0] [1 0]] ">" [[0 -1] [0 1]] "<" [[0 -1] [0 1]]
   "\\" [[-1 -1] [1 1]] "/" [[-1 1] [1 -1]]})

(def ^:private points {"v" [1 0] "^" [-1 0] ">" [0 1] "<" [0 -1]})

(defn- step [[r c] [dr dc] k] [(+ r (* k dr)) (+ c (* k dc))])
(defn- neg [[dr dc]] [(- dr) (- dc)])
(defn- diag? [[dr dc]] (and (not= 0 dr) (not= 0 dc)))
(defn- has? [ch d] (some #(= d %) (dirs ch)))

(defn- metric [[r1 c1] [r2 c2]]
  (let [dr (* 2 (- r1 r2)) dc (- c1 c2)] (+ (* dr dr) (* dc dc))))

(defn- min-metric [as bs]
  (reduce min Long/MAX_VALUE (for [a as b bs] (metric a b))))

(defn- label-blocks
  "Runs of text outside boxes and connectors, two spaces apart, gathered
  into blocks of runs starting in one column on consecutive rows."
  [g owned conn]
  (let [text? (fn [r c] (and (not (owned [r c])) (not (conn [r c])) (not= " " (at g r c))))
        runs (for [r (range (count g))
                   run (loop [c 0 runs [] cur nil]
                         (if (>= c (count (g r)))
                           (cond-> runs cur (conj cur))
                           (cond
                             (text? r c) (recur (inc c) runs (if cur (assoc cur 1 c) [c c]))
                             (and cur (= " " (at g r c)) (text? r (inc c)) (not (conn [r c])))
                             (recur (inc c) runs cur)
                             :else (recur (inc c) (cond-> runs cur (conj cur)) nil))))]
               {:r r :c0 (first run) :c1 (second run)
                :text (apply str (map #(at g r %) (range (first run) (inc (second run)))))})]
    (mapv (fn [rs] {:runs rs
                    :text (str/join "\n" (map :text rs))
                    :cells (set (for [{:keys [r c0 c1]} rs c (range c0 (inc c1))] [r c]))})
          (reduce (fn [blocks run]
                    (if-let [k (first (keep-indexed
                                       (fn [k rs]
                                         (let [p (peek rs)]
                                           (when (and (= (:r p) (dec (:r run)))
                                                      (<= (Math/abs (- (:c0 p) (:c0 run))) 1))
                                             k)))
                                       blocks))]
                      (update blocks k conj run)
                      (conj blocks [run])))
                  [] runs))))

(defn ascii-graph
  "The EDN graph of an ASCII sketch; nil when it holds no box, nests a box,
  or has a line joining no two nodes."
  [lines]
  (let [g (char-grid lines)
        boxes (find-boxes g)]
    (when (seq boxes)
      (let [owned (owned-cells g boxes)
            conn (into {} (for [r (range (count g)) c (range (count (g r)))
                                :let [ch (conn-char g owned r c)] :when ch]
                            [[r c] ch]))
            blocks (label-blocks g owned conn)
            block-of (into {} (for [[i b] (map-indexed vector blocks) p (:cells b)] [p i]))
            ;; links, attachments to boxes, and loose ends of every connector
            scan (for [[p ch] conn d (dirs ch)
                       :let [q (step p d 1)]]
                   (cond
                     (owned q) [:attach p {:node [:box (owned q)] :cell p :arrow? (= d (points ch))}]
                     (and (conn q) (or (has? (conn q) (neg d)) (diag? d))) [:link p q]
                     (conn q) nil
                     :else
                     (let [far (when (#{"|" "v" "^" "\\" "/"} ch)
                                 (some (fn [k]
                                         (let [s (step p d k)]
                                           (cond
                                             (owned s) [:attach p {:node [:box (owned s)] :cell p
                                                                   :arrow? (= d (points ch))}]
                                             (and (conn s) (has? (conn s) (neg d))) [:link p s]
                                             :else nil)))
                                       [2 3]))]
                       (or far
                           (let [b (when-not (or (diag? d) (= ch "+"))
                                     (some #(block-of (step p d %)) [1 2]))]
                             (if b
                               [:attach p {:node [:text b] :cell p :arrow? (= d (points ch))}]
                               [:loose p d]))))))
            scan (remove nil? scan)
            adj (reduce (fn [m [kind p q]]
                          (if (= kind :link) (-> m (update p (fnil conj #{}) q) (update q (fnil conj #{}) p)) m))
                        {} scan)
            comp-of (reduce (fn [m p]
                              (if (m p)
                                m
                                (loop [m (assoc m p p) todo [p]]
                                  (if-let [x (peek todo)]
                                    (let [nx (remove m (adj x))]
                                      (recur (reduce #(assoc %1 %2 p) m nx) (into (pop todo) nx)))
                                    m))))
                            {} (sort (keys conn)))
            attaches (group-by (comp comp-of :cell) (keep (fn [[k _ a]] (when (= k :attach) a)) scan))
            loose (group-by (comp comp-of second) (filter #(= :loose (first %)) scan))
            cells-of (group-by comp-of (keys conn))
            targeted? (fn [k] (some :arrow? (attaches k)))
            ;; a line that starts at a box and stops short of any joins the
            ;; nearest arrowed line it runs toward
            merged (reduce
                    (fn [m k]
                      (if (or (targeted? k) (empty? (attaches k)) (< 1 (count (distinct (map :node (attaches k))))))
                        m
                        (let [ends (loose k)
                              cands (for [[_ p d] ends
                                          j (keys cells-of) :when (and (not= j k) (targeted? j))
                                          q (cells-of j)
                                          :let [[dr dc] (map - q p)]
                                          :when (and (>= (* dr (first d)) 0) (>= (* dc (second d)) 0))]
                                      [(metric p q) j])]
                          (if (seq cands) (assoc m k (second (apply min-key first cands))) m))))
                    {} (keys cells-of))
            root (fn [k] (get merged k k))
            comps (group-by root (keys cells-of))
            text-nodes (set (keep (fn [[k _ a]] (when (and (= k :attach) (= :text (first (:node a))))
                                                  (second (:node a))))
                                  scan))
            edges-of
            (into {}
                  (for [[k ks] comps
                        :let [ats (mapcat attaches ks)
                              tg (distinct (map :node (filter :arrow? ats)))
                              src (remove (set tg) (distinct (map :node ats)))
                              [src tg] (if (seq tg)
                                         [src tg]
                                         (let [ordered (distinct (map :node (sort-by :cell ats)))]
                                           [(take 1 ordered) (rest ordered)]))
                              cells-for (fn [n] (set (map :cell (filter #(= n (:node %)) ats))))]
                        :when (and (seq tg) (seq src))]
                    [k (vec (for [s src t tg]
                              {:from s :to t
                               :keys (into (if (< 1 (count src)) (cells-for s) #{})
                                           (when (< 1 (count tg)) (cells-for t)))}))]))
            conn-cells (keys conn)
            near? (fn near?
                    ([b] (near? b conn-cells))
                    ([b cells] (some (fn [[r c]] (some (fn [[r2 c2]] (and (<= (Math/abs (- r r2)) 1)
                                                                          (<= (Math/abs (- c c2)) 3)))
                                                       cells))
                                     (:cells b))))
            labels (for [[i b] (map-indexed vector blocks) :when (not (text-nodes i))] [i b])
            ;; a verb is on the line just left of it, else its nearest line,
            ;; or on every line it touches when those lines join at one node
            verb-blocks (for [[i b] labels
                              :when (near? b)
                              :let [left (some (fn [{:keys [r c0]}]
                                                 (some #(when (conn [r %]) [r %]) [(dec c0) (- c0 2)]))
                                               (:runs b))
                                    k (root (comp-of (or left (apply min-key #(min-metric (:cells b) [%]) conn-cells))))
                                    touching (distinct (filter edges-of (map (comp root comp-of) (filter #(near? b [%]) conn-cells))))
                                    es (mapcat edges-of touching)
                                    meet? (and (< 1 (count touching)) (apply = (map :to es)))]
                              k (if meet? touching (when (edges-of k) [k]))]
                          [k i (cond-> b left (assoc :left left))])
            note-blocks (remove (set (map second verb-blocks)) (map first labels))
            ;; verbs: one label on a line is the verb of all its edges
            verbs (reduce
                   (fn [m [k bs]]
                     (let [es (edges-of k)]
                       (if (or (= 1 (count es)) (= 1 (count bs)))
                         (reduce (fn [m e] (assoc m [k e] (str/join " " (map #(str/replace (:text (nth % 2)) "\n" " ") bs))))
                                 m es)
                         (reduce (fn [m [_ _ b]]
                                   (let [e (apply min-key #(min-metric (if-let [l (:left b)] [l] (:cells b)) (:keys %)) es)
                                         t (str/replace (:text b) "\n" " ")]
                                     (update m [k e] #(if % (str % " " t) t))))
                                 m (sort-by (fn [[_ _ b]] (first (sort (:cells b)))) bs)))))
                   {} (group-by first verb-blocks))
            node-keys (concat (map #(vector :box %) (range (count boxes)))
                              (map #(vector :text %) (sort text-nodes)))
            pos (fn [[kind i]] (if (= kind :box)
                                 [(:r0 (boxes i)) (:c0 (boxes i))]
                                 (first (sort (:cells (blocks i))))))
            node-keys (sort-by pos node-keys)
            ids (zipmap node-keys (map #(str "n" (inc %)) (range)))
            box-cells (fn [i] (let [{:keys [r0 c0 r1 c1]} (boxes i)]
                                (for [r [r0 r1] c (range c0 (inc c1))] [r c])))
            notes (group-by (fn [i] (apply min-key
                                           #(min-metric (:cells (blocks i)) (box-cells %))
                                           (range (count boxes))))
                            note-blocks)
            ;; a note standing wholly above its box's top row titles the box
            above? (fn [bi i] (< (apply max (map first (:cells (blocks i)))) (:r0 (boxes bi))))]
        ;; a sketch read only in part is not drawn: a box inside a box, or
        ;; a line that joins no two nodes
        (when-not (or (some #(re-find #"[+|]-{3,}|-{3,}[+|]" (box-text g %)) boxes)
                      (some (fn [[k ks]] (and (not (edges-of k)) (< 1 (count (mapcat cells-of ks)))))
                            comps))
        ;; the arrowheads drawn: lines merged into one head are one arrow
        (with-meta
        {:nodes (vec (for [[kind i :as nk] node-keys]
                       (cond-> {:id (ids nk)
                                :label (if (= kind :box) (box-text g (boxes i)) (:text (blocks i)))}
                         (= kind :text) (assoc :bare true)
                         (and (= kind :box) (seq (filter #(above? i %) (notes i))))
                         (assoc :title (str/join "\n" (map #(:text (blocks %)) (sort-by #(pos [:text %]) (filter #(above? i %) (notes i))))))
                         (and (= kind :box) (seq (remove #(above? i %) (notes i))))
                         (assoc :note (str/join "\n" (map #(:text (blocks %)) (sort-by #(pos [:text %]) (remove #(above? i %) (notes i)))))))))
         :edges (vec (for [[k es] (sort-by key edges-of) e es
                           :let [v (verbs [k e])]]
                       (cond-> {:from (ids (:from e)) :to (ids (:to e))}
                         v (assoc :verb v))))}
         {:arrowheads (count (distinct (for [[k es] edges-of e es] [k (:to e)])))}))))))

(defn read-figure
  "A ```figure fence: an EDN graph, or an ASCII sketch."
  [lines]
  (let [src (str/join "\n" lines)
        g (if (str/starts-with? (strip src) "{")
            (clojure.edn/read-string src)
            (ascii-graph lines))
        ids (set (map :id (:nodes g)))]
    (when-not (seq (:nodes g))
      (throw (ex-info (str "figure has no node: " (pr-str src)) {})))
    (doseq [{:keys [from to]} (:edges g)]
      (when-not (and (ids from) (ids to))
        (throw (ex-info (str "figure edge names no node: " (pr-str [from to])) {}))))
    g))

(defn sanitize-svg
  "An illustrator's SVG made safe to inline: no script, foreignObject or
  event attribute, no external reference; scaled to the width of its container."
  [svg]
  (let [svg (-> svg
                (str/replace #"(?s)<\?xml.*?\?>|<!DOCTYPE.*?>|<!--.*?-->" "")
                (str/replace #"(?is)<script\b.*?</script\s*>|<script\b[^>]*/>" "")
                (str/replace #"(?is)<foreignObject\b.*?</foreignObject\s*>" "")
                (str/replace #"(?i)\s+on[a-z]+\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+)" "")
                (str/replace #"(?i)\s+(?:xlink:)?href\s*=\s*(\"(?!#)[^\"]*\"|'(?!#)[^']*')" "")
                (str/replace #"(?i)url\(\s*(['\"]?)(?!#)[^)]*\)" "none")
                (str/replace #"(?i)javascript:" "")
                strip)
        [_ tag] (re-find #"(?s)^(<svg\b[^>]*>)" svg)
        _ (when-not tag (throw (ex-info "illustration is not an svg" {})))
        attr (fn [a] (some-> (re-find (re-pattern (str "(?i)\\s" a "\\s*=\\s*\"([^\"]*)\"")) tag) second))
        vb (or (attr "viewBox")
               (let [w (attr "width") h (attr "height")]
                 (when (and w h (re-matches #"[\d.]+(px)?" w) (re-matches #"[\d.]+(px)?" h))
                   (str "0 0 " (str/replace w "px" "") " " (str/replace h "px" "")))))
        tag' (-> tag
                 (str/replace #"(?i)\s(width|height|style|viewBox)\s*=\s*(\"[^\"]*\"|'[^']*')" "")
                 (str/replace #"^<svg" (str "<svg viewBox=\"" vb "\" width=\"100%\" style=\"display:block;position:absolute;left:0;top:0;width:100%;height:100%\"")))]
    (str tag' (subs svg (count tag)))))

(defn illustration-el
  "The figure container of an illustrator's SVG, held to its aspect ratio by padding, which
  a grid row cannot collapse."
  [raw]
  (let [svg (sanitize-svg raw)
        [w h] (map #(Double/parseDouble %) (take-last 2 (re-seq #"[\d.]+" (second (re-find #"viewBox=\"([^\"]*)\"" svg)))))]
    (str "<div class=\"figure\" style=\"position:relative;height:0;padding-bottom:" (num (* 100.0 (/ h w)))
         "%;overflow:hidden\">" svg "</div>")))

(def ^:dynamic *figures-dir* nil)

(def ^:dynamic *shared-figure-counter* nil)
(def ^:dynamic *move-context* false)

(def ^:dynamic *module-fig* nil)

(defn diagram?
  "The lines are an ASCII diagram: a sketch that holds at least one edge."
  [lines]
  (boolean (seq (:edges (try (ascii-graph lines) (catch Exception _ nil))))))

(defn drawing?
  "The lines are an ASCII drawing: a diagram, or any box border or box-drawing glyph."
  [lines]
  (boolean (or (diagram? lines)
               (some #(re-find #"\+-{2,}\+|[\u2500-\u257f]" %) lines))))

;; -------------------------------------------------------------- text fences

;; ------------------------------------------------------------------ tables

(defn table-sep?
  "A table's separator row: dashes between pipes, a colon marking alignment."
  [l]
  (boolean (and (str/includes? l "|")
                (re-matches #"(?U)\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*" l))))

(defn table-at?
  "Line i of ls opens a table: a row with pipes over a separator row."
  [ls i]
  (and (< (inc i) (count ls)) (str/includes? (ls i) "|") (table-sep? (ls (inc i)))))

(defn table-end
  "The index past the last row of the table opening at i."
  [ls i]
  (loop [j (+ i 2)] (if (and (< j (count ls)) (str/includes? (ls j) "|")) (recur (inc j)) j)))

(defn- cells [l]
  (let [t (strip l)
        t (if (str/starts-with? t "|") (subs t 1) t)
        t (if (and (str/ends-with? t "|") (not (str/ends-with? t "\\|"))) (subs t 0 (dec (count t))) t)]
    (mapv #(str/replace (strip %) "\\|" "|") (str/split t #"(?<!\\)\|" -1))))

(defn table-html [ls]
  (let [[head sep & rows] ls
        aligns (mapv #(let [c (strip %)]
                        (cond (and (str/starts-with? c ":") (str/ends-with? c ":")) "center"
                              (str/ends-with? c ":") "right"
                              :else nil))
                     (cells sep))
        row (fn [tag l]
              (str "<tr>" (apply str (map-indexed (fn [k c]
                                                     (str "<" tag (when-let [a (get aligns k)] (str " style=\"text-align:" a "\"")) ">"
                                                          (inline c) "</" tag ">"))
                                                   (cells l)))
                   "</tr>"))]
    (str "<table><thead>" (row "th" head) "</thead><tbody>" (apply str (map #(row "td" %) rows)) "</tbody></table>")))

(def ^:private bullet-re #"^(?:[-*] |\d+\. )")

(defn fence-open
  "The language of a code fence opening line, or nil."
  [l]
  (some-> (re-find #"^(?:`{3,}|~{3,})(\w*)\s*$" l) second))

(defn fence-close?
  "A line that closes a code fence of backticks or tildes."
  [l]
  (boolean (re-find #"^(?:`{3,}|~{3,})\s*$" l)))

(defn split-chunk
  "A chunk's bodies, each [kind body]: :fm (front matter lines between two ---
  rules, only at the chunk's head), :heading, :list (lines), :code (lang and
  lines), :table, and :p (a paragraph)."
  [content]
  (let [content (vec content)
        n (count content)
        rule? #(= "---" (rstrip (content %)))
        stop? (fn [j] (let [l (content j)]
                        (or (str/starts-with? l "#") (fence-open l)
                            (re-find bullet-re l) (table-at? content j))))]
    (loop [i 0 bodies []]
      (if (>= i n)
        bodies
        (let [l (content i)]
          (cond
            (and (zero? i) (rule? i)
                 (some #(rule? %) (range 1 n)))
            (let [j (first (filter #(rule? %) (range 1 n)))]
              (recur (inc j) (conj bodies [:fm (subvec content (inc i) j)])))

            (re-find #"^#{1,6} " l)
            (recur (inc i) (conj bodies [:heading l]))

            (fence-open l)
            (let [j (loop [j (inc i)] (if (and (< j n) (not (fence-close? (rstrip (content j))))) (recur (inc j)) j))]
              (recur (inc j) (conj bodies [:code [(fence-open l) (subvec content (inc i) (min n j))]])))

            (re-find bullet-re l)
            (let [j (loop [j (inc i)]
                      (if (and (< j n) (not (str/starts-with? (content j) "#"))
                               (not (fence-open (content j))))
                        (recur (inc j)) j))
                  items (reduce (fn [items x]
                                  (if (re-find bullet-re x)
                                    (conj items [(second (re-find #"^([-*]|\d+\.) " x))
                                                 (str/replace x bullet-re "")])
                                    (update-in items [(dec (count items)) 1] str " " (strip x))))
                                [] (subvec content i j))]
              (recur j (conj bodies [:list items])))

            (table-at? content i)
            (let [j (table-end content i)]
              (recur j (conj bodies [:table (subvec content i j)])))

            :else
            (let [j (loop [j (inc i)]
                      (if (and (< j n) (not (stop? j))) (recur (inc j)) j))]
              (recur j (conj bodies [:p (join-lines (subvec content i j))])))))))))

(defn tokens
  "Words (letters, digits, _ and -), single punctuation marks, whitespace
  runs, and `code` spans; joined back with no separator they give the text."
  [s]
  (vec (re-seq #"`[^`]*`|[\p{L}\p{N}_-]+|\s+|[^\s]" s)))

(defn word-diff
  "Longest-common-subsequence diff of two token vectors, as a vector of
  [:same|:del|:add token]; on a tie the removal comes first."
  [a b]
  (let [a (vec a) b (vec b)
        n (count a) m (count b)
        tbl (reduce
             (fn [t i]
               (reduce (fn [t j]
                         (assoc t [i j]
                                (if (= (a i) (b j))
                                  (inc (t [(inc i) (inc j)]))
                                  (max (t [(inc i) j]) (t [i (inc j)])))))
                       t (range (dec m) -1 -1)))
             (into {} (concat (for [j (range (inc m))] [[n j] 0])
                              (for [i (range (inc n))] [[i m] 0])))
             (range (dec n) -1 -1))]
    (loop [i 0 j 0 out []]
      (cond
        (and (< i n) (< j m) (= (a i) (b j))) (recur (inc i) (inc j) (conj out [:same (a i)]))
        (and (< i n) (or (= j m) (>= (tbl [(inc i) j]) (tbl [i (inc j)]))))
        (recur (inc i) j (conj out [:del (a i)]))
        (< j m) (recur i (inc j) (conj out [:add (b j)]))
        :else out))))

(defn- chunks
  "A word-diff as chunks: [:same tokens] or [:edit removed added]."
  [diff]
  (->> diff
       (partition-by #(if (= :same (first %)) :same :edit))
       (mapv (fn [grp]
               (if (= :same (ffirst grp))
                 [:same (mapv second grp)]
                 [:edit (mapv second (filter #(= :del (first %)) grp))
                  (mapv second (filter #(= :add (first %)) grp))])))))

(defn- joiner?
  "An unchanged run worth folding into its neighbours: only whitespace, or
  one punctuation mark."
  [toks]
  (let [t (str/join toks)]
    (boolean (or (re-matches #"\s+" t)
                 (and (= 1 (count toks)) (re-matches #"[^\p{L}\p{N}\s]" t))))))

(defn- fold-chunks
  "Fold a joiner lying between two edits into both sides, until none is left."
  [cs]
  (loop [cs cs out []]
    (cond
      (empty? cs) out
      (and (seq out) (>= (count cs) 2)
           (= :edit (first (peek out)))
           (= :same (first (first cs))) (joiner? (second (first cs)))
           (= :edit (first (second cs))))
      (let [[_ d1 a1] (peek out) [_ s] (first cs) [_ d2 a2] (second cs)]
        (recur (drop 2 cs)
               (conj (pop out) [:edit (-> d1 (into s) (into d2)) (-> a1 (into s) (into a2))])))
      :else (recur (rest cs) (conj out (first cs))))))

(defn marked
  "One text: unchanged tokens plain, removed in <del>, added in <ins>; spacing
  is kept as written, and a lone space or mark between two edits joins them."
  [old new fmt]
  (->> (word-diff (tokens old) (tokens new))
       chunks
       fold-chunks
       (map (fn [[kind x y]]
              (if (= kind :same)
                (fmt (str/join x))
                (str (when (seq x) (str "<del>" (fmt (str/join x)) "</del>"))
                     (when (seq y) (str "<ins>" (fmt (str/join y)) "</ins>"))))))
       str/join))

(defn- fm-card
  "Front matter lines as a small labelled card: no YAML syntax, one term per key."
  [lines]
  (let [value (fn [v]
                (let [v (strip v)]
                  (cond (= "[]" v) "none"
                        (and (str/starts-with? v "[") (str/ends-with? v "]"))
                        (str/join ", " (map strip (str/split (subs v 1 (dec (count v))) #",")))
                        :else v)))
        rows (for [l lines :when (not (blank? l))]
               (if-let [m (re-find #"^([A-Za-z][\w-]*):\s*(.*)$" l)]
                 (str "<dt>" (esc (str/capitalize (str/replace (m 1) "-" " "))) "</dt><dd>" (inline (value (m 2))) "</dd>")
                 (str "<dd>" (inline l) "</dd>")))]
    (str "<div class=\"fm\"><div class=\"fm-label\">Front matter</div><dl>" (apply str rows) "</dl></div>")))

(defn- heading-html [l fmt]
  (let [[_ hashes t] (re-find #"^(#{1,6}) (.*)$" l)
        lvl (min 6 (+ 2 (count hashes)))]
    (str "<h" lvl ">" (fmt t) "</h" lvl ">")))

;; ------------------------------------------------------------- highlighting
;;
;; A code fence is highlighted at build time by tree-sitter: the CLI's HTML
;; output, one row per line, its capture classes prefixed ts-. Which grammar
;; serves which fence language is book-grammars.json: a grammar's source is a
;; directory or a flake reference realised once. A language with no grammar,
;; or a grammar that fails, is shown plain. Colour in a book is the sign of a
;; change and lives in the margin; the code's own text keeps its language's
;; colours, each side of a change highlighted whole as valid code.

(def book-grammars
  (cheshire.core/parse-string (slurp (str (fs/path script-dir "book-grammars.json")))))

(defn- run [& args]
  (let [p (.start (ProcessBuilder. ^java.util.List (vec args)))
        _ (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p) :encoding "UTF-8")]
    {:exit (.waitFor p) :out out}))

(defn- realise-grammar
  "A grammar source as a directory: itself, or the store path its flake reference builds."
  [source]
  (if (fs/directory? source)
    (str source)
    (let [{:keys [exit out]} (try (run "nix" "build" "--no-link" "--print-out-paths" source)
                                  (catch Exception _ {:exit 1}))
          store (some->> out str/split-lines (remove blank?) last strip)]
      (when (and (zero? exit) store (fs/directory? store)) store))))

(def ^:private ts-env
  "The tree-sitter config for this build: one parser directory holding a link
  per grammar that could be had; nil when tree-sitter is absent."
  (delay
    (when (fs/which "tree-sitter")
      (let [dir (fs/create-temp-dir {:prefix "book-grammars"})
            parsers (fs/path dir "parsers")
            ok (into {}
                     (for [[name {:strs [source scope]}] (book-grammars "grammars")
                           :let [d (realise-grammar source)]
                           :when d]
                       (do (fs/create-dirs parsers)
                           (fs/create-sym-link (fs/path parsers (str "tree-sitter-" name)) d)
                           [name scope])))
            cfg (str (fs/path dir "config.json"))]
        (spit cfg (cheshire.core/generate-string {"parser-directories" [(str parsers)]}))
        (.deleteOnExit (fs/file dir))
        {:config cfg :dir (str dir) :scopes ok}))))

(def ^:private warned (atom #{}))

(defn- ts-classes [html]
  (str/replace html #"<span class='([^']*)'>"
               (fn [[_ cs]] (str "<span class=\"" (str/join " " (map #(str "ts-" %) (str/split cs #"\s+"))) "\">"))))

(def ^:dynamic *highlight*
  "Whether code is highlighted; false shows every fence plain."
  true)

(def ^:private highlight-lines*
  (memoize
   (fn [lang lines]
     (let [grammar (get (book-grammars "languages") lang)
           {:keys [config dir scopes]} @ts-env
           scope (get scopes grammar)]
       (if-not scope
         (do (when (and grammar (not (@warned lang)))
               (swap! warned conj lang)
               (binding [*out* *err*] (println "book-build: no tree-sitter grammar for" lang "; shown plain")))
             nil)
         (let [f (str (fs/path dir (str "block-" (hash [lang lines]))))]
           (spit f (str (str/join "\n" lines) "\n") :encoding "UTF-8")
           (let [{:keys [exit out]} (run "tree-sitter" "highlight" "--config-path" config
                                         "--html" "--css-classes" "--scope" scope f)
                 rows (mapv second (re-seq #"(?s)<td class=line>(.*?)\n?</td></tr>" out))]
             (fs/delete-if-exists f)
             (when (and (zero? exit) (= (count rows) (count lines)))
               (mapv ts-classes rows)))))))))

(defn highlight-lines
  "The lines of a code text highlighted as HTML, one string per line; nil when
  highlighting is off, the language has no grammar here, or tree-sitter fails."
  [lang lines]
  (when *highlight* (highlight-lines* lang (vec lines))))

(defn- code-class [lang] (get langs lang (if (= "" lang) "text" lang)))

(defn code-html
  "A code fence: its lines highlighted in its language, plain when no grammar
  serves it. With a sign, the whole fence is added (+) or removed (-), and the
  sign is its margin alone."
  ([lang lines] (code-html lang lines nil))
  ([lang lines sign]
   (let [lines (vec lines)]
     (str "<pre" (case sign "+" " class=\"code-add\"" "-" " class=\"code-del\"" "")
          "><code class=\"language-" (code-class lang) "\">"
          (str/join "\n" (or (highlight-lines lang lines) (map esc lines)))
          "</code></pre>"))))

(defn- code-sign
  "A code change line as [kind content]: + added, - removed, two spaces kept."
  [l]
  (let [[k c] (cond (or (= l "+") (str/starts-with? l "+ ")) [:add (subs l (min 2 (count l)))]
                    (or (= l "-") (str/starts-with? l "- ")) [:del (subs l (min 2 (count l)))]
                    (str/starts-with? l "  ") [:ctx (subs l 2)]
                    :else [:ctx l])]
    [k (if (blank? c) "" c)]))

(defn signed-code-html
  "A code fence written as a change; nil when no line is signed. Wholly added
  or wholly removed, it is one fence with a green or red margin. Otherwise the
  module as it stands and as proposed are each highlighted whole, then the
  lines interleave in the order written, each carrying its sign in the gutter."
  [lang lines]
  (let [items (mapv code-sign lines)
        kinds (set (map first (remove #(and (= :ctx (first %)) (blank? (second %))) items)))]
    (cond
      (not (or (kinds :add) (kinds :del))) nil
      (= kinds #{:add}) (code-html lang (map second items) "+")
      (= kinds #{:del}) (code-html lang (map second items) "-")
      :else
      (let [side (fn [k] (mapv second (filter #(#{k :ctx} (first %)) items)))
            old (side :del) new (side :add)
            old-h (or (highlight-lines lang old) (mapv esc old))
            new-h (or (highlight-lines lang new) (mapv esc new))
            rows (loop [[[k] & more] items o 0 n 0 out []]
                   (case k
                     nil out
                     :del (recur more (inc o) n (conj out (str "<span class=\"ln ln-del\">" (old-h o) "\n</span>")))
                     :add (recur more o (inc n) (conj out (str "<span class=\"ln ln-add\">" (new-h n) "\n</span>")))
                     (recur more (inc o) (inc n) (conj out (str "<span class=\"ln\">" (new-h n) "\n</span>")))))]
        (str "<pre class=\"code-diff\"><code class=\"language-" (code-class lang) "\">"
             (apply str rows) "</code></pre>")))))

;; Markdown is never shown in a code block: a fence labelled markdown or md, and
;; the markdown fence a module view of a .md module is written in, render as text.
(def fence-languages
  (cheshire.core/parse-string (slurp (str (fs/path script-dir "book-fence-languages.json")))))
(def markdown-languages (set (fence-languages "markdown")))
(def text-languages (set (fence-languages "text")))

(declare body-html)

(defn markdown-html
  "Markdown lines as rendered text: headings (one level below the sheet's),
  paragraphs, lists and tables; a code fence inside it stays code, except a
  markdown one, which is text again."
  [lines]
  (let [lines (vec lines)
        chunks (loop [i 0 cur [] out [] fence false]
                 (if (>= i (count lines))
                   (cond-> out (seq cur) (conj cur))
                   (let [l (lines i)
                         fence' (if fence (not (fence-close? (rstrip l))) (boolean (fence-open l)))]
                     (if (and (not fence) (not fence') (blank? l))
                       (recur (inc i) [] (cond-> out (seq cur) (conj cur)) false)
                       (recur (inc i) (conj cur l) out fence')))))]
    (apply str
           (for [chunk chunks
                 [kind body :as b] (split-chunk chunk)]
             (case kind
               :heading (let [[_ hashes t] (re-find #"^(#{1,6}) (.*)$" body)
                              lvl (min 6 (inc (count hashes)))]
                          (str "<h" lvl ">" (inline t) "</h" lvl ">"))
               :code (if (markdown-languages (first body))
                       (markdown-html (second body))
                       (body-html b))
               (body-html b))))))

(defn body-html
  "One body of a chunk as formatted HTML."
  [[kind body]]
  (case kind
    :fm (fm-card body)
    :heading (heading-html body inline)
    :list (let [tag (if (re-find #"^\d" (ffirst body)) "ol" "ul")]
            (str "<" tag ">" (apply str (map (fn [[_ t]] (str "<li>" (inline t) "</li>")) body)) "</" tag ">"))
    :code (if (and *module-fig* (drawing? (second body)))
            (*module-fig* (second body))
            (code-html (first body) (second body)))
    :table (table-html body)
    :fig body
    (str "<p>" (inline body) "</p>")))

(defn paired-body
  "A removed and an added body, one text; nil unless both are paragraphs or headings."
  [[kind-a a] [kind-b b]]
  (when (= kind-a kind-b)
    (case kind-a
      :p (str "<p>" (marked a b inline) "</p>")
      :heading (let [[_ ha ta] (re-find #"^(#{1,6}) (.*)$" a) [_ hb tb] (re-find #"^(#{1,6}) (.*)$" b)]
                 (when (= ha hb) (heading-html (str ha " " (marked ta tb inline)) identity)))
      nil)))

(defn panel [sign body]
  (let [body (if (and *module-fig* (not= "-" sign) (= :code (first body)) (= "mermaid" (first (second body))))
               [:fig (*module-fig* (second (second body)))]
               body)
        inner (body-html body)]
    (case (if (= :fig (first body)) (str "fig" sign) sign)
      ;; a figure keeps its own colours: only a surround marks it as added or removed
      "fig+" (str "<div class=\"figmark fig-add\">" inner "</div>")
      "fig-" (str "<div class=\"figmark fig-del\">" inner "</div>")
      ;; the sign is the margin; the text keeps the page's colour
      "+" (str "<div class=\"add\">" inner "</div>")
      "-" (str "<div class=\"del\">" inner "</div>")
      ;; a moved passage, shown once at its destination in its own colour
      "~" (str "<div class=\"moved\">" inner "</div>")
      (str "<div class=\"ctx\">" inner "</div>"))))

(defn change-panels
  "A removed run and the added run after it: bodies paired in order, each pair
  one marked text; the rest wholly removed or wholly added."
  [removed added]
  (let [ra (vec (split-chunk removed)) aa (vec (split-chunk added))]
    (apply str
           (for [i (range (max (count ra) (count aa)))
                 :let [r (get ra i) a (get aa i)]]
             (cond
               (and r a) (or (paired-body r a) (str (panel "-" r) (panel "+" a)))
               r (panel "-" r)
               :else (panel "+" a))))))

(defn- in-fence?
  "The chunk has an open code fence: a fence line without its closing one."
  [[_ content]]
  (odd? (count (filter #(re-find #"^```" %) content))))

(defn- prose-line?
  "A line of a paragraph's running text: not a heading, list item, table row,
  fence, rule or indented code; a module's hard wrap splits one paragraph over
  several such lines."
  [l]
  (boolean (and (not (blank? l)) (not (re-find #"^ {4}" l))
                (not (re-find #"^(?:#|[-*] |\d+\. |\||`{3}|~{3}|---\s*$)" (strip-left l))))))

(defn- continues?
  "In a change written line by line, the line goes on the chunk before it:
  that chunk holds an open code fence or front matter, both are list items or
  table rows, the line is the indented continuation of a list item, or both
  are running text of one paragraph."
  [[_ content :as lst] line]
  (let [l (peek content)
        bullet? #(boolean (re-find bullet-re %))
        row? #(str/starts-with? (strip-left %) "|")]
    (or (in-fence? lst)
        (and (= "---" (rstrip (first content)))
             (= 1 (count (filter #(= "---" (rstrip %)) content))))
        (and (bullet? l) (bullet? line))
        (and (bullet? (first content)) (re-find #"^ {2}\S" line))
        (and (row? l) (row? line))
        (and (prose-line? l) (prose-line? line) (not (bullet? (first content)))))))

(defn- sign-content
  "A change line's sign (\"+\", \"-\" or \" \" for context) and its content."
  [raw]
  (let [signed (and (#{\+ \- \~} (first raw))
                    (or (= 1 (count raw)) (= \space (nth raw 1))))]
    [(if signed (subs raw 0 1) " ")
     (cond signed (if (> (count raw) 2) (subs raw 2) "")
           (str/starts-with? raw "  ") (subs raw 2)
           :else raw)]))

(defn- code-fence-items
  "Change lines with each inner code fence whose lines carry more than one sign
  taken out as [:code lang lines]: its lines stay code, never markdown, each
  line keeping its sign for the line tint. A fence of one sign stays lines."
  [lines]
  (let [lines (vec lines) n (count lines)
        content #(second (sign-content (lines %)))]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [lang (fence-open (content i))
              j (when lang (first (filter #(= "```" (rstrip (content %))) (range (inc i) n))))]
          (if (and j (< 1 (count (set (map #(first (sign-content (lines %))) (range i (inc j)))))))
            (recur (inc j) (conj out [:code lang (subvec lines (inc i) j)]))
            (recur (inc i) (conj out (lines i)))))))))

(defn- code-fence-html
  "An inner code fence of mixed signs: highlighted per side, signs in the gutter."
  [lang lines]
  (or (signed-code-html lang lines) (code-html lang lines)))

(defn- table-diff-html
  "A table written as a change: its rows are [sign content], the kept header and
  separator first, then kept, removed and added rows. One table; a removed or
  added row carries its sign as a red or green margin."
  [rows]
  (let [[[_ head] [_ sep] & body] rows
        aligns (mapv #(let [c (strip %)]
                        (cond (and (str/starts-with? c ":") (str/ends-with? c ":")) "center"
                              (str/ends-with? c ":") "right"
                              :else nil))
                     (cells sep))
        row (fn [sign tag l]
              (str "<tr" (case sign "-" " class=\"row-del\"" "+" " class=\"row-add\"" "") ">"
                   (apply str (map-indexed
                               (fn [k c]
                                 (str "<" tag (when-let [a (get aligns k)] (str " style=\"text-align:" a "\"")) ">"
                                      (inline c) "</" tag ">"))
                               (cells l)))
                   "</tr>"))]
    (str "<table><thead>" (row " " "th" head) "</thead><tbody>"
         (apply str (map (fn [[sign l]] (row sign "td" l)) body)) "</tbody></table>")))

(defn- table-diff-items
  "Change lines with each table whose rows carry more than one sign, header and
  separator among them, taken out as one [:tablediff rows]; a table of one sign
  stays lines."
  [items]
  (let [items (vec items) n (count items)
        row (fn [x] (when (string? x)
                      (let [[sg c] (sign-content x)]
                        (when (str/starts-with? (strip-left c) "|") [sg c]))))]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (if-let [r0 (row (items i))]
          (let [j (loop [j i] (if (and (< j n) (row (items j))) (recur (inc j)) j))
                rows (mapv #(row (items %)) (range i j))]
            (if (and (>= (count rows) 3) (table-sep? (second (second rows)))
                     (< 1 (count (set (map first rows)))))
              (recur j (conj out [:tablediff rows]))
              (recur j (into out (subvec items i j)))))
          (recur (inc i) (conj out (items i))))))))

(defn text-panels
  "A fenced text block: sign-prefixed lines into stacked panels, and its
  nothing drawn. With per-line, as a change written outside a fence,
  consecutive lines of one sign are one paragraph only where they are its
  running text, and each removed paragraph pairs with the added paragraph
  after it, the whole removed text marked against the whole added text.
  A code fence inside whose lines carry different signs stays code."
  ([lines] (text-panels lines false))
  ([lines per-line]
  (let [chunks (reduce
                (fn [chunks raw]
                  (if (vector? raw)
                    (conj chunks raw)
                  (let [[sign content] (sign-content raw)
                        signed (not= " " sign)
                        sketch (if signed content raw)
                        lst (peek chunks)
                        joins? (and lst (= (first lst) sign)
                                    (or (not per-line) (continues? lst content)))]
                    (cond
                      (and lst (= (first lst) sign) (in-fence? lst)) (conj (pop chunks) (-> lst (update 1 conj content) (update 2 conj sketch)))
                      (blank? content) (conj chunks nil)
                      joins? (conj (pop chunks) (-> lst (update 1 conj content) (update 2 conj sketch)))
                      :else (conj chunks [sign [content] [sketch]])))))
                [] (table-diff-items (code-fence-items lines)))
        chunks (vec (remove nil? chunks))
        panels (StringBuilder.)
        run (fn [i sign] (count (take-while #(= sign (first %)) (subvec chunks i))))]
    (loop [i 0]
      (when (< i (count chunks))
        (let [[sign content] (chunks i)
              [nsign ncontent] (get chunks (inc i))]
          (cond
            (= :tablediff sign)
            (do (.append panels (str "<div class=\"ctx\">" (table-diff-html content) "</div>")) (recur (inc i)))

            (= :code sign)
            (do (.append panels (apply code-fence-html (rest (chunks i)))) (recur (inc i)))

            (and per-line (= sign "-"))
            (let [nr (run i "-") na (run (+ i nr) "+")]
              (doseq [k (range (max nr na))]
                (.append panels (change-panels (if (< k nr) (second (chunks (+ i k))) [])
                                               (if (< k na) (second (chunks (+ i nr k))) []))))
              (recur (+ i nr na)))
            (and (= sign "-") (= nsign "+"))
            (do (.append panels (change-panels content ncontent)) (recur (+ i 2)))
            :else (do (doseq [body (split-chunk content)]
                        (.append panels (panel sign body)))
                      (recur (inc i)))))))
    (if (pos? (.length panels)) (str "<div class=\"diff\">" panels "</div>") ""))))

;; ------------------------------------------------------------------- blocks

(def list-item-re #"(?U)^\d+\. ")

(defn split-para
  "A numbered list may open mid-paragraph, after its group heading."
  [para]
  (let [para (vec para)
        k (first (keep-indexed (fn [k l] (when (and (pos? k) (re-find list-item-re l)
                                                    (not (re-find list-item-re (para 0))))
                                           k))
                               para))]
    (if k
      [[:para (subvec para 0 k)] [:para (subvec para k)]]
      [[:para para]])))

(defn diff-para?
  "A paragraph written as a change: every line opens with + or - and a space (or
  is that sign alone) or is an unchanged line opening with two spaces, and it
  adds a line, removes a front-matter rule, or opens on an unchanged line and
  removes one. A list item's indented continuation is not a change."
  [para]
  (let [signed (fn [l] (or (#{"+" "-" "~"} l) (re-find #"^[-+~] " l)))
        kept (fn [l] (str/starts-with? l "  "))]
    (boolean (and (seq para) (every? #(or (signed %) (kept %)) para)
                  (some signed para)
                  (or (some #(or (str/starts-with? % "+") (= "- ---" (rstrip %))) para)
                      (kept (first para)))))))

(declare flush-para signed-line?)

(defn flush-para
  "Outside a proposal, a paragraph written as a change is a text fence read line by line."
  [para]
  (let [para (vec para)]
    (cond
      (diff-para? para) [[:fence "text" para :per-line]]
      :else (split-para para))))

(defn- change-line?
  "A line of a change: signed, or unchanged and two spaces in."
  [l]
  (or (signed-line? l) (str/starts-with? l "  ")))

(defn proposal-para
  "Inside a proposal, a paragraph holding a signed line is a change, read line
  by line, every removed line red and every added line green; a head mark may
  open it. A change that holds any other line is refused, naming that line."
  [para proposal]
  (let [para (vec para)]
    (cond
      ;; unchanged lines alone are the module as it stands around the change
      (every? #(str/starts-with? % "  ") para) [[:fence "text" para :per-line]]
      (not-any? signed-line? para)
      (flush-para para)
      :else
      (let [k (count (take-while (complement change-line?) para))
            head (subvec para 0 k)
            change (subvec para k)]
        (cond-> [] (seq head) (conj [:para head]) true (conj [:fence "text" change :per-line]))))))

(def ^:private proposal-heading #"(?U)^#{2,3} (Proposal \d+): ")

(defn blocks
  "Split the source into blocks: [:fence lang lines] (a change written outside
  a fence also carries :per-line), [:para lines], or [:refused reason] for a
  change inside a proposal that cannot be shown as one. A proposal runs from
  its heading to the next level-two heading."
  [src]
  (let [lines (vec (str/split src #"\n" -1))
        n (count lines)
        skip #"(?U)^(<!--.*-->|Presentation\.\{.*\})\s*$"
        fence-open-re #"(?U)^(`{3,}|~{3,})(\w*)\s*$"
        move-open-re #"^::: move(?:\s+.*)?$"
        comment-open-re #"^::: comment(?:\s+.*)?$"
        shown-open-re #"^::: shown(?:\s+.*)?$"
        shown-location-re #"^([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\s,›·]+)$"
        container-open-re #"^::: (?:comment|move|shown)(?:\s+.*)?$"
        flush (fn [para proposal] (if proposal (proposal-para para proposal) (flush-para para)))]
    (loop [i 0 out [] para [] proposal nil]
      (if (>= i n)
        (cond-> out (seq para) (into (flush para proposal)))
        (let [l (lines i)
              before proposal
              proposal (cond (re-find proposal-heading l) (second (re-find proposal-heading l))
                             (and (empty? para) (str/starts-with? l "## ")) nil
                             :else proposal)]
          (if (re-find shown-open-re l)
            (let [line-no (inc i)
                  _ (when-not (= l "::: shown")
                      (throw (ex-info (str "malformed shown container at source line " line-no) {})))
                  _ (when-not proposal
                      (throw (ex-info (str "shown container outside proposal at source line " line-no) {})))
                  out (cond-> out (seq para) (into (flush para proposal)))
                  start (inc i)
                  [end body] (loop [j start]
                               (if (>= j n)
                                 (throw (ex-info (str "unclosed shown container at source line " line-no) {}))
                                 (let [x (lines j)]
                                   (cond
                                     (= x ":::") [j (subvec lines start j)]
                                     (re-find container-open-re x)
                                     (throw (ex-info (str "nested container at source line " (inc j)) {}))
                                     :else (recur (inc j))))))
                  location (when-let [location-line (first body)]
                             (re-matches shown-location-re location-line))
                  _ (when-not (and location (some (complement blank?) (rest body)))
                      (throw (ex-info (str "shown container needs a module path and excerpt at source line " line-no) {})))]
              (recur (inc end) (conj out [:shown (second location) (nth location 2) (subvec body 1)]) [] proposal))
            (if (re-find comment-open-re l)
              (let [line-no (inc i)
                    _ (when-not (= l "::: comment")
                        (throw (ex-info (str "malformed comment container at source line " line-no) {})))
                    _ (when-not proposal
                        (throw (ex-info (str "comment container outside proposal at source line " line-no) {})))
                    out (cond-> out (seq para) (into (flush para proposal)))
                    start (inc i)
                    [end body] (loop [j start]
                                 (if (>= j n)
                                   (throw (ex-info (str "unclosed comment container at source line " line-no) {}))
                                   (let [x (lines j)]
                                     (cond
                                       (= x ":::") [j (subvec lines start j)]
                                       (re-find container-open-re x)
                                       (throw (ex-info (str "nested container at source line " (inc j)) {}))
                                       :else (recur (inc j))))))]
                (recur (inc end) (conj out [:comment body]) [] proposal))
            (if (re-find move-open-re l)
            (let [state (second (re-find #"^::: move(?:\s+(.*))?$" l))
                  line-no (inc i)
                  out (cond-> out (seq para) (into (flush para proposal)))
                  from-line (when (< (inc i) n) (lines (inc i)))
                  to-line (when (< (+ i 2) n) (lines (+ i 2)))
                  fm (and from-line (re-matches #"from: (.*)" from-line))
                  tm (and to-line (re-matches #"to: (.*)" to-line))
                  _ (when-not (and (#{"proposed" "landed"} state)
                                   fm tm (not (blank? (second fm)))
                                   (not (blank? (second tm)))
                                   (< (+ i 3) n) (= "" (lines (+ i 3))))
                      (throw (ex-info (str "malformed move container at source line " line-no) {})))
                  start (+ i 4)
                  [end body] (loop [j start fence nil]
                               (if (>= j n)
                                 (throw (ex-info (str "unclosed move container at source line " line-no) {}))
                                 (let [x (lines j)
                                       fo (second (re-find fence-open-re x))
                                       fence' (cond
                                                (and fence (re-matches (re-pattern (str "^" (if (= \` (first fence)) "`" "~") "{" (count fence) ",}\\s*$")) x)) nil
                                                fence fence
                                                fo fo
                                                :else nil)]
                                   (cond
                                     (and (nil? fence) (= x ":::")) [j (subvec lines start j)]
                                     (and (nil? fence) (re-find container-open-re x))
                                     (throw (ex-info (str "nested move container at source line " (inc j)) {}))
                                     :else (recur (inc j) fence')))))]
              (recur (inc end) (conj out [:move state (second fm) (second tm) body]) [] proposal))
            (if-let [m (re-find fence-open-re l)]
            (let [out (cond-> out (seq para) (into (flush para proposal)))
                  lang (if (or (= "" (m 2)) (text-languages (m 2))) "text" (m 2))
                  close-re (re-pattern (str "(?U)^" (if (= \` (first (m 1))) "`" "~") "{" (count (m 1)) ",}\\s*$"))
                  j (loop [j (inc i)] (if (and (< j n) (not (re-find close-re (lines j)))) (recur (inc j)) j))]
              (recur (inc j) (conj out (if (= "diff" (m 2))
                                         [:fence "diff" (subvec lines (inc i) (min n j)) :per-line]
                                         (with-meta [:fence lang (subvec lines (inc i) (min n j))]
                                           {:bare (= "" (m 2))}))) [] proposal))
            (cond
              ;; Exactly two spaces mark an unchanged line in a diff paragraph;
              ;; don't mistake that context marker for a paragraph separator.
              (and (blank? l) (not= "  " l))
              (recur (inc i) (cond-> out (seq para) (into (flush para proposal))) [] proposal)
              ;; a proposal heading always opens a new proposal, blank line before it or not;
              ;; the paragraph before it belongs to what came before
              (re-find proposal-heading l)
              (recur (inc i) (-> out (cond-> (seq para) (into (flush para before))) (conj [:para [l]])) [] proposal)
              (re-find skip l) (recur (inc i) out para proposal)
              :else (recur (inc i) out (conj para l) proposal)))))))))))

(defn parse-list [lines]
  (reduce (fn [items l]
            (if-let [m (re-find #"(?U)^(\d+)\. (.*)$" l)]
              (conj items [(Long/parseLong (m 1)) (m 2)])
              (if (seq items)
                (update-in items [(dec (count items)) 1] str " " (strip l))
                (reduced nil))))
          [] lines))

(defn file-line [lines]
  (let [text (join-lines lines)
        m (re-find #"^File: `([^`]+)`(.*)$" text)
        path (if m (m 1) text)
        rest* (strip (if m (m 2) ""))
        rest* (if (str/starts-with? rest* ", ") (subs rest* 2) rest*)
        rest* (if (and (str/starts-with? rest* "(builds on") (str/ends-with? rest* ")"))
                (subs rest* 1 (dec (count rest*))) rest*)]
    (cond-> (str "<p class=\"target\"><code>" (esc path) "</code></p>")
      (not= "" rest*) (str "<p>" (inline rest*) "</p>"))))

(defn signed-line?
  "A fence line written as a change: opens with + or - and a space, or is that sign alone."
  [l]
  (boolean (and (#{\+ \- \~} (first l)) (or (= 1 (count l)) (= \space (nth l 1))))))

(defn signed-figure
  "[sign stripped-lines] when every line of a fence carries one same sign
  (a proposed figure: all + added, all - removed) and, signs stripped, it is a figure
  (an EDN or ASCII graph, an ASCII drawing, or a Mermaid flowchart); else nil."
  [lang lines]
  (let [sign (some-> (first lines) first)]
    (when (and (#{"" "text" "figure" "mermaid"} lang) (seq lines)
               (every? signed-line? lines) (every? #(= sign (first %)) lines))
      (let [stripped (mapv #(if (< (count %) 2) "" (subs % 2)) lines)]
        (when (or (= lang "mermaid")
                  (and (= lang "figure") (try (read-figure stripped) (catch Exception _ nil)))
                  (and (not (#{"figure" "mermaid"} lang)) (drawing? stripped)
                       ;; a drawing, not prose that holds one
                       (every? #(or (blank? %) (<= (count (strip %)) 30) (re-find #"[|+\u2500-\u257f]" %)) stripped)))
          [(str sign) stripped])))))

(defn removed-mermaid
  "The unsigned source lines when every line of a fence is a removed line and the fence is
  Mermaid: labelled mermaid, or text whose source opens as a flowchart; else nil."
  [lang lines]
  (when (and (#{"mermaid" "text"} lang) (seq lines)
             (every? #(and (signed-line? %) (= \- (first %))) lines))
    (let [stripped (mapv #(if (< (count %) 2) "" (subs % 2)) lines)]
      (when (or (= lang "mermaid")
                (re-find #"^(flowchart|graph)\b" (strip (first (remove blank? stripped)))))
        stripped))))

(def ^:private target-langs
  {"ethos" "ethos" "datom" "datom" "rs" "rust" "clj" "clojure" "cljc" "clojure"
   "edn" "clojure" "sh" "bash" "bash" "bash"})

(defn target-lang
  "The code language a File line's path names by extension; \"text\" otherwise."
  [path]
  (get target-langs (some-> path (str/split #"\.") rest last) "text"))

(defn- module-path-text
  "A module heading's text with a trailing change-kind suffix (\" — …\" or \" - …\") stripped."
  [heading]
  (str/trim (str/replace (strip heading) #"\s+[—-]\s+.*$" "")))

(def ^:private file-langs
  (merge target-langs {"js" "javascript" "mjs" "javascript" "py" "python" "nix" "nix" "toml" "toml"
                       "json" "json" "yaml" "yaml" "yml" "yaml" "html" "html" "ts" "typescript"
                       "md" "markdown" "markdown" "markdown"}))

(defn file-language
  "The code language a file's path names by extension; \"text\" otherwise."
  [path]
  (get file-langs (some-> path (str/split #"\.") rest last) "text"))

(defn presentation-title
  "The title of the Presentation datom on the source's first line that is not
  a comment, or nil."
  [src]
  (let [l (first (remove #(or (blank? %) (re-find #"(?U)^<!--.*-->\s*$" %)) (str/split-lines src)))]
    (some->> l (re-find #"(?U)^Presentation\.\{\s*«([^»]*)».*\}\s*$") second strip not-empty)))

(def presentation-kinds #{"Proposal" "Review" "Account"})

(defn presentation-kind
  "The kind of the Presentation datom: Proposal, Review or Account; Proposal when
  the datom names none or no datom is there."
  [src]
  (let [l (first (remove #(or (blank? %) (re-find #"(?U)^<!--.*-->\s*$" %)) (str/split-lines src)))
        k (some->> l (re-find #"(?U)^Presentation\.\{\s*«[^»]*»\s*(\w+)\s*\}\s*$") second)]
    (if (presentation-kinds k) k "Proposal")))

(defn proposal-header
  "The opening of a proposal's sheet. With no File line under it, its title
  says where it lands, and is shown as the target chip in the header."
  [n title where?]
  (str "<article class=\"proposal\"><header><span class=\"pnum\">" n "</span>"
       (if where?
         (str "<h2 class=\"target\"><code style=\"font-size:.95rem\">" (esc (str/replace title "`" "")) "</code></h2>")
         (str "<h2>" (inline title) "</h2>"))
       "</header>"))

(defn module-header
  "Open a proposal authored as the whole module, with its path and no badge or
  state label. `heading` is the text of a \"## repo › path\" heading."
  [heading]
  (str "<article class=\"module diff\"><header>"
       "<h2 class=\"target\"><code style=\"font-size:.95rem\">" (esc (str/replace heading "`" "")) "</code></h2>"
       "</header>"))

;; ------------------------------------------------------------- ethos check
;;
;; Every ```ethos fence, and every fence in a proposal whose language is
;; ethos (its File line or its heading names an .ethos file), is read by
;; ethos-zero's Check; a block it refuses fails the build. A fence written as
;; a change is checked as it reads after the change. A fence in a module view
;; (the module as it stands) is not: book-check authenticates the view as the
;; module's source text, and its fences may use one another's types. ethos-zero is never
;; compiled here: the flake reference is realised with no local build job, so
;; it is substituted or built on the remote builder.

(def pinned-ethos-zero
  "The ethos-zero the books are checked against: tag v20.0.0 of LiGoldragon/ethos-zero."
  "github:LiGoldragon/ethos-zero/04d93e4b705a1b2abbb806fa385a04ce74b13cdd")

(def ^:dynamic *ethos-zero*
  "The ethos-zero that checks proposed code (a fence inside a proposal or
  written as a change): a flake reference, a store path, or an ethos-zero
  binary."
  pinned-ethos-zero)

(def ^:dynamic *ethos-zero-real*
  "The ethos-zero that checks real code: a fence quoting source outside any
  proposal."
  pinned-ethos-zero)

(def ^:dynamic *ethos-check*
  "A function of an ethos text giving nil when ethos-zero accepts it, else
  its refusal; nil means the default: *ethos-zero*'s Check for proposed code,
  *ethos-zero-real*'s for real code."
  nil)

(def ^:dynamic *enforce-ethos*
  "When true, Ethos Check is a publication gate."
  false)

(defn- sh [& args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (vec args))
                    (.redirectErrorStream true)))
        out (slurp (.getInputStream p))]
    {:exit (.waitFor p) :out out}))

(def ethos-zero-binary
  "The ethos-zero binary a reference names, realised once per reference."
  (memoize
   (fn [ref]
     (let [f (fs/file ref)]
       (cond
         (and (fs/regular-file? f) (fs/executable? f)) (str f)
         (fs/executable? (fs/path ref "bin" "ethos-zero")) (str (fs/path ref "bin" "ethos-zero"))
         :else
         (let [{:keys [exit out]} (sh "nix" "build" "--no-link" "--print-out-paths" "--max-jobs" "0" ref)
               store (last (remove blank? (str/split-lines out)))]
           (when-not (and (zero? exit) store (str/starts-with? store "/nix/store/"))
             (throw (ex-info (str "ethos-zero " ref " could not be realised: "
                                  (strip (last (remove blank? (str/split-lines out)))))
                             {})))
           (str store "/bin/ethos-zero")))))))

(def ^:private ethos-roots #{"Library" "Signal" "Operation" "Memory" "Emission"})

(defn- strip-ethos-comments
  "The text with `;` comments (to end of line, outside guillemets) dropped."
  [text]
  (loop [cs (seq text) out (StringBuilder.) quoted false]
    (if-let [c (first cs)]
      (cond
        quoted (do (.append out c)
                   (cond (= c \\) (do (when-let [n (second cs)] (.append out n))
                                       (recur (drop 2 cs) out true))
                         (= c \u00bb) (recur (rest cs) out false)
                         :else (recur (rest cs) out true)))
        (= c \u00ab) (do (.append out c) (recur (rest cs) out true))
        (= c \;) (recur (drop-while #(not= % \newline) cs) out false)
        :else (do (.append out c) (recur (rest cs) out false)))
      (str out))))

(defn- split-ethos-declarations
  "The top-level forms of a comment-free text: runs of non-blank characters,
  broken only at whitespace outside { } [ ] and guillemets."
  [text]
  (loop [cs (seq text) depth 0 quoted false cur (StringBuilder.) forms []]
    (let [flush (fn [] (if (pos? (.length cur)) (conj forms (str cur)) forms))]
      (if-let [c (first cs)]
        (cond
          quoted (do (.append cur c)
                     (cond (= c \\) (do (when-let [n (second cs)] (.append cur n))
                                         (recur (drop 2 cs) depth true cur forms))
                           (= c \u00bb) (recur (rest cs) depth false cur forms)
                           :else (recur (rest cs) depth true cur forms)))
          (= c \u00ab) (do (.append cur c) (recur (rest cs) depth true cur forms))
          (and (zero? depth) (Character/isWhitespace ^char c))
          (let [forms (flush)] (recur (rest cs) depth false (StringBuilder.) forms))
          :else (do (.append cur c)
                    (recur (rest cs)
                           (cond (#{\{ \[} c) (inc depth) (#{\} \]} c) (max 0 (dec depth)) :else depth)
                           false cur forms)))
        (flush)))))

(defn- trait-declaration?
  "A trait declaration is a head whose body is a bracket of capabilities,
  each a lowercase head: `Name.[ capability.... ]`."
  [form]
  (boolean (re-find #"^[A-Z]\w*\.\[\s*[a-z]" form)))

(defn ethos-gate-text
  "The text Check reads for a fence. A fence of bare declarations, with no
  root head, is a fragment of an implied Library: each declaration goes into
  the types or the traits section by what it is, and comments are dropped.
  A fence that opens with a root head (or holds nothing) is checked whole."
  [text]
  (let [bare (strip-ethos-comments text)
        forms (split-ethos-declarations bare)
        head (some-> (first forms) (str/split #"[^A-Za-z0-9_]" 2) first)]
    (if (or (empty? forms) (ethos-roots head))
      text
      (let [{traits true types false} (group-by trait-declaration? forms)]
        (let [section #(if (seq %) (str "[ " (str/join " " %) " ]") "[]")]
          (str "Library [] " (section types) " " (section traits) " []"))))))

(defn ethos-zero-check
  "ethos-zero's Check of an ethos text: nil when accepted, else its refusal
  without the temporary file's path, or why ethos-zero could not be had.
  It only tags how a page renders; it never stops a build."
  [ref text]
  (let [exe (try (ethos-zero-binary ref) (catch clojure.lang.ExceptionInfo e e))]
    (if (instance? clojure.lang.ExceptionInfo exe)
      (ex-message exe)
      (let [dir (fs/create-temp-dir {:prefix "book-ethos"})
            f (str (fs/path dir "block.ethos"))]
        (try
          (spit f (str text "\n") :encoding "UTF-8")
          (let [{:keys [exit out]} (sh exe (str "Check." f))]
            (when-not (zero? exit)
              (let [out (strip out)
                    out (str/replace out (str f " ") "")]
                (if (str/starts-with? out "Rejected.") out (str "ethos-zero failed: " out)))))
          (finally (fs/delete-tree dir)))))))

(defn datom-text?
  "The lines read as a datom: after `;` comments (outside guillemets) are
  dropped, delimiters balance and nest ({ } [ ] < >, guillemets with \\\u00bb
  escaped, parentheses by balance), at least one enclosure is present, and no
  diagram edge is."
  [lines]
  (let [text (str/join "\n" lines)]
    (boolean
     (and (not (diagram? lines))
          (loop [cs (seq text) stack [] opened false]
            (if-let [c (first cs)]
              (cond
                (= c \u00ab) (let [rest* (loop [r (rest cs)]
                                           (cond (empty? r) nil
                                                 (= (first r) \\) (recur (drop 2 r))
                                                 (= (first r) \u00bb) (rest r)
                                                 :else (recur (rest r))))]
                                (when rest* (recur rest* stack opened)))
                (= c \;) (recur (drop-while #(not= % \newline) cs) stack opened)
                (#{\{ \[ \< \(} c) (recur (rest cs) (conj stack c) true)
                (#{\} \] \> \)} c) (when (= (peek stack) ({\} \{ \] \[ \> \< \) \(} c))
                                        (recur (rest cs) (pop stack) opened))
                :else (recur (rest cs) stack opened))
              (and (empty? stack) opened)))))))

(defn unsign-lines
  "The lines of a fence whose every non-blank line opens with + or - and a space
  (or is that sign alone), signs stripped; nil otherwise."
  [lines]
  (let [ls (remove blank? lines)]
    (when (and (seq ls) (every? signed-line? ls))
      (mapv #(if (signed-line? %) (if (< (count %) 2) "" (subs % 2)) %) lines))))

(defn ethos-fence-kind
  "How an Ethos fence is authored: a coherent marked fragment, a context-only
  change, malformed marked text, or a whole Ethos document. Blank lines carry
  no mark; two spaces carry unchanged diff context."
  [lines]
  (let [nonblank (remove blank? lines)
        signed? #(boolean (or (= % "+") (= % "-")
                              (str/starts-with? % "+ ")
                              (str/starts-with? % "- ")))
        context? #(str/starts-with? % "  ")
        has-change? (some signed? nonblank)]
    (cond
      (and has-change? (every? #(or (signed? %) (context? %)) nonblank)) :fragment
      has-change? :malformed-fragment
      (and (seq nonblank) (every? #(str/starts-with? % " ") nonblank)) :context-only
      :else :whole)))

(defn guess-code-language
  "An unlabelled fence's language read from its text: \"ethos\" when it opens
  with a root word or declares Name.{ } / Name.[ ] with Vector<>, \"datom\" when
  it reads as a datom, else nil."
  [lines]
  (let [text (str/join "\n" lines)
        code (str/join "\n" (remove #(str/starts-with? (strip %) ";") lines))]
    (cond
      (re-find #"(?m)^\s*(Library|Signal|Operation|Memory)\b" code) "ethos"
      (and (re-find #"\b[A-Z]\w*\.[\{\[]" code) (re-find #"Vector<" code)) "ethos"
      (and (re-find #"\b[A-Z]\w*\.[\{\[]" code) (re-find #"(?m)^\s*\[\s*$|\]\s*;" text) (not (re-find #"«" code))) "ethos"
      (datom-text? lines) "datom")))

(defn bare-code-language
  "What an unlabelled fence in a module view parses as: \"ethos\" when real ethos-zero (main)
  or the proposals build accepts it, \"datom\" when it reads as a datom, else
  nil: a diagram."
  [lines]
  (cond
    (diagram? lines) nil
    (let [text (str/join "\n" lines)]
      (if *ethos-check*
        (nil? (*ethos-check* text))
        (or (nil? (ethos-zero-check *ethos-zero-real* text))
            (nil? (ethos-zero-check *ethos-zero* text))))) "ethos"
    (datom-text? lines) "datom"))

(defn ethos-readable?
  "Whether some current ethos-zero accepts the text."
  [text]
  (if *ethos-check*
    (nil? (*ethos-check* text))
    (or (nil? (ethos-zero-check *ethos-zero-real* text))
        (nil? (ethos-zero-check *ethos-zero* text)))))

(defn- check-ethos!
  "Run Check and distinguish rejection from an unavailable validator."
  [ref text]
  (let [result (try
                 (if *ethos-check*
                   (*ethos-check* (ethos-gate-text text))
                   (ethos-zero-check ref (ethos-gate-text text)))
                 (catch Throwable e
                   (throw (ex-info (str "ethos validator unavailable: " (ex-message e))
                                   {:kind :validator-unavailable} e))))]
    (when result
      (if (str/starts-with? result "ethos-zero ")
        (throw (ex-info (str "ethos validator unavailable: " result)
                        {:kind :validator-unavailable}))
        (throw (ex-info (str "ethos rejected: " result) {:kind :ethos-rejected}))))
    true))

(defn comment-html
  "The proposal author's reason, apart from the module text it explains."
  [lines]
  (str "<aside class=\"comment\" aria-label=\"Why\">"
       "<div class=\"comment-label\">Why</div>"
       "<div class=\"comment-body\">" (markdown-html lines) "</div></aside>"))

(defn shown-html
  "A checked excerpt of the named source module as it stands."
  [repository path lines]
  (str "<section class=\"shown\"><header><span class=\"mlabel\">as it stands</span>"
       "<code>" (esc (str repository " › " path)) "</code></header>"
       (code-html (file-language path) lines) "</section>"))

(defn build [src code]
  (let [nfig (or *shared-figure-counter* (volatile! 0))
        heading-where (volatile! nil)
        datom-title (presentation-title src)
        kind (presentation-kind src)
        bs (vec (blocks src))
        file-next? (fn [i] (let [[kind ls] (get bs (inc i))]
                             (and (= :para kind) (str/starts-with? (first ls) "File: "))))
        render-fig (fn [lines required?]
                     (let [n (vswap! nfig inc)
                           name (str "fig-" n ".svg")
                           f (when *figures-dir* (fs/path *figures-dir* name))]
                       (if (and f (fs/exists? f))
                         (let [svg (slurp (str f) :encoding "UTF-8")]
                           (try (illustration-el svg)
                                (catch clojure.lang.ExceptionInfo e
                                  (if required?
                                    (throw (ex-info (str "figure " name " unrendered: " (ex-message e))
                                                    {:figure name} e))
                                    (do (binding [*out* *err*]
                                          (println (str "book-build: figure " name " left out: " (ex-message e))))
                                        "")))))
                         (if required?
                           (throw (ex-info (str "figure " name " not found; Mermaid fence " n " unrendered")
                                           {:figure name}))
                           (do (binding [*out* *err*]
                                 (println (str "book-build: figure " name " not found; diagram " n " left out")))
                               "")))))
        show-fig (fn [lines] (render-fig lines false))
        show-mermaid (fn [lines] (render-fig lines true))
        out (atom [])
        target (volatile! nil)
        in-article (volatile! false)
        in-module (volatile! false)
        module-md (volatile! false)
        module-file (volatile! nil)
        module-first (volatile! false)
        after-title (volatile! false)
        rulings (volatile! false)
        pending-close (volatile! false)
        record-open (volatile! false)
        glue (volatile! false)
        append! (fn [s] (swap! out conj s))
        extend-last! (fn [s] (swap! out #(conj (pop %) (str (peek %) s))))
        close-rulings (fn [early]
                        (when @rulings
                          (extend-last! "</li></ol></section>")
                          (vreset! rulings false)
                          (vreset! pending-close false)
                          (vreset! glue early)))
        close-record (fn []
                       (when @record-open
                         (extend-last! "</div>")
                         (vreset! record-open false)))
        emit (fn [s] (if (or @rulings @glue) (extend-last! s) (append! s)))
        emit-panels (fn [diff]
                      (if @record-open
                        (let [diff (str/replace-first diff "<div class=\"diff\">" "")
                              diff (if (str/ends-with? diff "</div>") (subs diff 0 (- (count diff) 6)) diff)]
                          (emit diff)
                          (close-record))
                        (when (not= "" diff) (emit diff))))]
    (doseq [[i b] (map-indexed vector bs)]
      (cond
        (= :comment (first b))
        (emit (comment-html (second b)))

        (= :shown (first b))
        (let [[_ repository path lines] b]
          (emit (shown-html repository path lines)))

        (= :move (first b))
        (let [[_ state from to body] b
              inner (binding [*shared-figure-counter* nfig *move-context* true]
                      (build (str/join "\n" body) ""))]
          (emit (str "<section class=\"move move-" state "\"><header><h2>From "
                     (esc from) " → To " (esc to) "</h2><span class=\"move-state\">"
                     (str/capitalize state) "</span></header>" inner "</section>")))

        (= :fence (first b))
        (let [[_ raw-lang lines per-line] b
              md? (boolean (markdown-languages raw-lang))
              module-sheet? (and @module-md @module-first (not (:bare (meta b)))
                                 (or md? (= raw-lang "text")))
              _ (vreset! module-first false)
              ;; A module view of a code file holds the file as it stands, in its language.
              code-file? (and @in-module (not @module-md) (not= raw-lang "figure"))
              lang (cond md? "text"
                         (and code-file? (= raw-lang "text")) (file-language @module-file)
                         :else raw-lang)
              ethos-kind (when (and (= lang "ethos") @in-article (not code-file?))
                           (ethos-fence-kind lines))]
          ;; A module view is the module as it stands, authenticated as source by the
          ;; checker; its fences are not whole Ethos documents (one may use a type another declares).
          (when (and *enforce-ethos* (= lang "ethos") (not= ethos-kind :fragment)
                     (not (and @in-module (not @in-article))))
            (check-ethos! (if code-file? *ethos-zero-real* *ethos-zero*)
                          (str/join "\n" lines)))
          (when (and @rulings @pending-close) (close-rulings true))
          (cond
            ;; a removed Mermaid fence is its source, as any removed code block: nothing is drawn
            (and @in-article (removed-mermaid lang lines))
            (emit (code-html "mermaid" (removed-mermaid lang lines) "-"))

            ;; a figure proposed for the module it will live in: all + is added, all - removed
            (and @in-article (signed-figure lang lines))
            (let [[sign stripped] (signed-figure lang lines)]
              (emit (str "<div class=\"diff\">"
                         (panel sign [:fig ((if (= lang "mermaid") show-mermaid show-fig) stripped)])
                         "</div>")))

            (and (= lang "figure") (try (read-figure lines) (catch Exception _ nil)))
            (emit (show-fig lines))

            ;; a Mermaid flowchart is a diagram: shown as its fig-N.svg
            (and (= raw-lang "mermaid") (some signed-line? lines))
            (throw (ex-info "mixed Mermaid change: every line must carry the same + or - marker"
                            {:kind :mixed-mermaid-change}))

            (= raw-lang "mermaid")
            (emit (show-mermaid lines))

            ;; A diff fence is prose: its lines are text under their margins, unwrapped.
            ;; Only a fenced block inside it is code.
            (and (= raw-lang "diff") (not-any? #(re-find #"^(?:[+-] |  )?\s*```mermaid" %) lines))
            (emit-panels (binding [*module-fig* show-mermaid] (text-panels lines true)))

            ;; a proposal's unchanged lines, written outside a fence, are panels of the change
            (and per-line (not-any? signed-line? lines))
            (emit-panels (binding [*module-fig* show-mermaid] (text-panels lines true)))

            ;; Whatever its label or place, a fence that is an ASCII drawing is drawn: it needs
            ;; its fig-N.svg, else the build fails. A built page never holds an undrawn drawing.
            (and (not md?) (not-any? signed-line? lines) (drawing? lines))
            (emit (show-fig lines))

            ;; A text fence in a moved block is the moved text word for word: its + and - lines
            ;; are its own words, not a change.
            (and *move-context* (= lang "text") (not md?) (not (diagram? lines)))
            (emit (code-html (target-lang @target) lines))

            ;; Checked only for being the file word for word; never parsed.
            ;; An Ethos file no current ethos-zero reads carries a tag.
            code-file?
            (emit (str (when (and (= lang "ethos") (not (ethos-readable? (str/join "\n" lines))))
                         "<p class=\"mlabel\">not read by current Ethos</p>")
                       (code-html lang lines)))

            (or (and module-sheet? (or md? (not (diagram? lines))))
                (and md? (or (not @in-article) (not-any? signed-line? lines))))
            (emit (str "<div class=\"markdown\">"
                       (binding [*module-fig* show-fig] (markdown-html lines)) "</div>"))

            (and (:bare (meta b)) @in-module (not-any? signed-line? lines)
                 (bare-code-language lines))
            (emit (code-html (bare-code-language lines) lines))

            (and (= lang "text") (not-any? signed-line? lines) (diagram? lines))
            (emit (show-fig lines))

            ;; An unlabelled fence outside a proposal is a diagram, boxed or not.
            (and (:bare (meta b)) (not @in-article) (not-any? signed-line? lines))
            (emit (show-fig lines))

            (and (= lang "text") (not-any? signed-line? lines))
            ;; An unsigned fence that is no diagram is a block of code or file text: kept as written.
            (emit (code-html (target-lang @target) lines))

            ;; An unlabelled fence of signed lines that reads as Ethos or datom is code.
            (and (:bare (meta b)) (unsign-lines lines) (guess-code-language (unsign-lines lines)))
            (emit (signed-code-html (guess-code-language (unsign-lines lines)) lines))

            (= lang "text")
            (emit-panels (binding [*module-fig* show-mermaid]
                           (text-panels lines (= :per-line per-line))))

            ;; Code written as a change: wholly added or removed takes one margin;
            ;; mixed, each side is highlighted whole and the lines interleave.
            :else
            (let [shown (if (= "" lang)
                          (or (some-> (unsign-lines lines) guess-code-language) "text")
                          lang)]
              (emit (or (signed-code-html shown lines) (code-html shown lines))))))
        :else
        (let [lines (second b)
              _ (vreset! module-first false)
              first-line (first lines)
              subtitle? (and @after-title (str/starts-with? (join-lines lines) "Created by"))
              _ (vreset! after-title false)
              text (join-lines lines)
              _ (when-let [m (re-find #"^File: `?([^`\s,]+)" text)] (vreset! target (m 1)))]
          (cond
            (str/starts-with? first-line "# ")
            (let [title (strip (subs first-line 2))]
              (when-not *move-context*
                (append! "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                (append! (str "<title>" (esc (or datom-title title)) "</title>"))
                (when (not= "Proposal" kind)
                  (append! (str "<meta name=\"book-kind\" content=\"" (str/lower-case kind) "\">")))
                (vreset! after-title true))
              (append! (str "<h1 class=\"book-title\">" (esc title) "</h1>")))

            (and @in-module (str/starts-with? first-line "### ") (not (str/starts-with? first-line "### Proposal ")))
            (append! (str "<h4>" (inline (strip (subs first-line 4))) "</h4>"))

            (str/starts-with? first-line "### ")
            (do (close-rulings false)
                (when @in-module (append! "</article>") (vreset! in-module false))
                (close-record)
                (vreset! glue false)
                (vreset! target nil)
                (vreset! heading-where nil)
                (when @in-article (append! "</article>"))
                (if-let [m (re-find #"(?U)^### Proposal (\d+): (.*)$" text)]
                  (append! (proposal-header (m 1) (m 2) false))
                  (append! (str "<h3>" (inline (strip (subs first-line 4))) "</h3>")))
                (vreset! in-article (boolean (re-find #"(?U)^### Proposal " text))))

            (re-find #"(?U)^## Proposal (\d+): (.*)$" text)
            (let [[_ n where] (re-find #"(?U)^## Proposal (\d+): (.*)$" text)]
              (close-rulings false)
              (close-record)
              (vreset! glue false)
              (vreset! target nil)
              (vreset! heading-where (when-not (file-next? i) (str/replace (strip where) "`" "")))
              (when @in-module (append! "</article>") (vreset! in-module false))
              (when @in-article (append! "</article>"))
              (append! (proposal-header n (strip where) (not (file-next? i))))
              (vreset! in-article true))

            (re-find #"(?U)^## \S+ › .+$" first-line)
            (do (close-rulings false)
                (close-record)
                (vreset! glue false)
                (when @in-article (append! "</article>") (vreset! in-article false))
                (when @in-module (append! "</article>"))
                (append! (module-header (strip (subs first-line 3))))
                (vreset! module-md (str/ends-with? (module-path-text (subs first-line 3)) ".md"))
                (vreset! module-file (last (str/split (module-path-text (subs first-line 3)) #" › " 2)))
                (vreset! module-first true)
                (vreset! in-module true))

            (and @in-module (str/starts-with? first-line "## "))
            (do (close-rulings false)
                (close-record)
                (vreset! glue false)
                (append! (str "<h3>" (inline (strip (subs first-line 3))) "</h3>")))

            (str/starts-with? first-line "## ")
            (do (close-rulings false)
                (close-record)
                (vreset! glue false)
                (when @in-article (append! "</article>") (vreset! in-article false))
                (append! (str "<h2>" (inline (strip (subs first-line 3))) "</h2>")))

            ;; a part heading inside a proposal (a moved line's two modules) is a heading
            (and @in-article (re-find #"^#{4,6} " first-line))
            (let [[_ hs t] (re-find #"^(#{4,6}) (.*)$" first-line)
                  t (strip (str/replace t "`" ""))
                  h (str "h" (count hs))]
              (close-record)
              (vreset! heading-where t)
              (emit (str "<" h " class=\"part\"><code>" (esc t) "</code></" h ">")))

            ;; A module-change page carries its short reason as ordinary prose.
            ;; `Why:` is source notation for that reason, not a label in this
            ;; page form, so neither the label nor a comment node is emitted.
            (and @in-module (str/starts-with? text "Why:"))
            (emit (str "<p>" (inline (strip (subs text 4))) "</p>"))

            (str/starts-with? first-line "File: `")
            (do (when (and @rulings @pending-close) (close-rulings true))
                (emit (file-line lines)))

            ;; a File line naming its target in words
            (and @in-article (str/starts-with? first-line "File: "))
            (do (when (and @rulings @pending-close) (close-rulings true))
                (emit (str "<p class=\"target\"><code>" (esc (strip (subs text 6))) "</code></p>")))

            ;; inside a proposal a signed line is shown only as a change
            (some #(table-at? (vec lines) %) (range (count lines)))
            (emit (apply str (map body-html (split-chunk lines))))

            ;; a bullet list outside a proposal; inside one, a leading - is a removal line
            (and (not @in-article) (re-find #"^[-*] " first-line))
            (emit (apply str (map body-html (split-chunk lines))))

            :else
            (if-let [m (do (when (str/starts-with? text "Ruling:") (close-record))
                           (re-find #"^\*\*(Destroy|Archive)\*\*(?U)\s*(.*)$" text))]
              (do (close-record)
                  (emit (cond-> (str "<div class=\"record " (str/lower-case (m 1)) "\">")
                          (not= "" (m 2)) (str "<p>" (inline (m 2)) "</p>")))
                  (vreset! record-open true))
              (if-let [items (seq (parse-list lines))]
                (let [start (ffirst items)
                      attr (cond-> (str " start=\"" start "\"")
                             (> start 1) (str " style=\"counter-reset:choice " (dec start) "\""))]
                  (emit (str "<ol class=\"choices\"" attr ">"
                             (apply str (map (fn [[_ t]] (str "<li>" (inline t) "</li>")) items))
                             "</ol>"))
                  (vreset! pending-close (not (str/ends-with? (rstrip (second (last items))) ":"))))
                (if (str/starts-with? text "Ruling, one number")
                  (do (close-rulings false)
                      (let [n (some #(second (re-find #"<span class=\"pnum\">(\d+)</span>" %)) (rseq @out))]
                        (append! (str "<section class=\"rulings\"><ol><li" (when n (str " value=\"" n "\"")) "><p>" (inline text) "</p>")))
                      (vreset! rulings true)
                      (vreset! pending-close false))
                  (emit (str (if subtitle? "<p class=\"book-subtitle\">" "<p>") (inline text) "</p>")))))))))
    (close-rulings false)
    (close-record)
    (when @in-module (append! "</article>"))
    (when @in-article (append! "</article>"))
    (str (str/join "\n" @out) "\n" code)))

(defn read-text
  "Read a UTF-8 file with newlines normalised to \\n."
  [path]
  (str/replace (slurp (str path) :encoding "UTF-8") #"\r\n?" "\n"))

(defn- check-source! [src]
  (load-file (str (fs/path script-dir "book-check.clj")))
  (let [errors ((ns-resolve 'book-check 'check-book) src)]
    (when (seq errors)
      (throw (ex-info (str "source check failed: " (pr-str errors))
                      {:kind :source-invalid :errors errors})))
    true))

(defn -main [& args]
  (let [args (vec args)]
    (when (< (count args) 2)
      (binding [*out* *err*]
        (println "usage: bb book-build.clj SOURCE.md OUT.html [--code book-code.html] [--figures DIR] [--ethos-zero REF] [--ethos-zero-real REF]"))
      (System/exit 1))
    (let [[src-path out-path] args
          ci (.indexOf args "--code")
          code-path (if (>= ci 0)
                      (get args (inc ci))
                      (str (fs/path script-dir "book-code.html")))
          fi (.indexOf args "--figures")
          ei (.indexOf args "--ethos-zero")
          ri (.indexOf args "--ethos-zero-real")]
      (binding [*figures-dir* (when (>= fi 0) (get args (inc fi)))
                *ethos-zero* (if (>= ei 0) (get args (inc ei)) pinned-ethos-zero)
                *ethos-zero-real* (if (>= ri 0) (get args (inc ri)) pinned-ethos-zero)]
        (try (let [src (read-text src-path)]
               (check-source! src)
               (binding [*enforce-ethos* true]
                 (spit out-path (build src (read-text code-path)) :encoding "UTF-8")))
             (catch clojure.lang.ExceptionInfo e
               (binding [*out* *err*] (println (str "book-build: " (ex-message e))))
               (System/exit 1))
             (catch java.io.IOException e
               (binding [*out* *err*] (println (str "book-build: " (ex-message e))))
               (System/exit 1)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
