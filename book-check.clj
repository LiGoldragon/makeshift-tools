;; Structural check of a book source: `bb book-check.clj /abs/SOURCE.md`.
;; Ported from book-check.mjs. check-book: source text -> vector of
;; {:line n :reason "..."} errors, sorted by line. This checks source
;; structure, not the merits of a proposed crystallization.
(ns book-check
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; The longest subtitle, in words: the signature line under the title is one sentence of
;; at most this many.
(def subtitle-word-limit 12)

;; A proposal names one location, or two locations joined by ", and", in its heading.
;; A new/deleted-file suffix belongs to the final location.
(def located #"^Proposal \d+: (?:.+ · )?[^\s›·]+ › [^\s,]+(?:, and [^\s›·]+ › [^\s,]+)?(?:, a (?:new|deleted) file)?\z")
(def single-proposal-location
  #"^Proposal \d+: (?:.+ · )?([^\s›·]+) › ([^\s,]+)(?:, a (?:new|deleted) file)?\z")
;; A removal is a change: removed lines, or the whole file deleted.
(defn change? [line]
  (boolean (or (re-find #"^[+-] .*\S" line)
               (re-find #"^\*\*(?:Archive|Destroy)\*\*" line))))

(defn subtitle-fits?
  "Whether a subtitle is one sentence of at most `subtitle-word-limit` words."
  [text]
  (and (<= (count (str/split (str/trim text) #"\s+")) subtitle-word-limit)
       (= 1 (count (remove str/blank? (str/split text #"[.!?]+(?:\s+|$)"))))))

(defn- signed-fence-line? [line]
  (boolean (and (#{\+ \-} (first line))
                (or (= 1 (count line)) (= \space (nth line 1))))))

(defn- coherent-signed-mermaid? [lines]
  (and (seq lines) (every? signed-fence-line? lines)
       (apply = (map first lines))))

(defn- ethos-fence-kind [lines]
  (let [nonblank (remove str/blank? lines)
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
;; The three context repositories hold only their authored modules; any other
;; repository of the living holds any file, named by a relative path.
(def context-repository #"^(?:psyche|mind|field)-belief\z")
;; A module's file name is its version: a number (0.md), a span (0-1.md) or the word all (all.md).
(def context-module #"^(?:spirit|intent|vision|knowledge|notion|compensation|inquiry|role|raw)/[A-Za-z0-9][A-Za-z0-9_.-]*(?:/(?:[0-9]+(?:-[0-9]+)?|all))?\.md\z")
(def repository-name #"^[A-Za-z0-9][A-Za-z0-9_.-]*\z")
(def file-path #"^(?!/)(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\z|^(?!/)(?:[A-Za-z0-9_.-]+/)*\.[A-Za-z0-9_-][A-Za-z0-9_.-]*\z")

(defn valid-location? [repository relative-path]
  (boolean
   (and (re-find repository-name repository)
        (if (re-find context-repository repository)
          (re-find context-module relative-path)
          (and (re-find file-path relative-path)
               (not-any? #(or (= % ".") (= % "..")) (str/split relative-path #"/" -1)))))))

(def module-location #"^([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\s,›·]+)\z")
;; A module view is headed `## repo › path`, or `## repo › path, a new file` for a
;; module the view creates. This is the one definition of that heading: the
;; checker and the builder both match it through these.
(def ^:private module-view-pattern "([A-Za-z0-9][A-Za-z0-9_.-]*) › ([^\\s,›·]+)(, a new file)?")
;; A module view heading's text, after `## `: groups repository, path, new-file suffix.
(def module-view-text (re-pattern (str "^" module-view-pattern "\\z")))
;; A module view heading line, as the renderer reads it.
(def module-view-line (re-pattern (str "^## " module-view-pattern "\\s*\\z")))

(defn module-view-heading
  "The module view a line heads: {:repository :path :new? :location}, or nil when
  the line heads none or names an invalid location."
  [line]
  (when-let [[_ repository path new-file] (re-matches module-view-line line)]
    (when (valid-location? repository path)
      {:repository repository :path path :new? (boolean new-file) :location [repository path]})))

;; The closing Questions section of a Proposal, Review or Account page opens at
;; this heading and runs, plain prose, to the end of the source.
(def questions-heading #"^## Questions\s*\z")
;; The renderer recognizes module views only as level-two headings, and
;; proposal headings only at levels two and three.
(def next-section (re-pattern (str "^ {0,3}(?:##\\s+(?:Proposal\\b|Questions\\s*\\z|" module-view-pattern "\\s*\\z)|###\\s+Proposal\\b)")))

(defn module-text-at
  "A module as the revision has it; a path the revision lacks is read from the
  parent of the last commit that deleted it, so a deleted module still shows as it
  stood. A path that never existed throws. `git` runs git in the repository."
  [git revision relative-path]
  (let [file? (fn [commit] (re-find #"^100(?:644|755) blob " (git "ls-tree" commit "--" relative-path)))
        deletion (str/trim (git "log" "-1" "--diff-filter=D" "--format=%H" revision "--" relative-path))
        commit (cond (file? revision) revision
                     (re-find #"^[a-f0-9]{40}\z" deletion) (str deletion "^"))]
    (when-not (and commit (file? commit))
      (throw (ex-info "module-source-unavailable" {})))
    (git "show" (str commit ":" relative-path))))

(defn read-context-module
  "A file of one of the living's repositories as origin main has it, or as it stood
  before origin main deleted it; a context repository gives only its authored
  modules, never a generated skill."
  [repository relative-path]
  (when-not (valid-location? repository relative-path)
    (throw (ex-info "module-location-invalid" {})))
  (let [root (str "/git/github.com/LiGoldragon/" repository)
        git (fn [& args]
              (let [r (deref (process/process (into ["git" "-C" root] args)
                                              {:out :string :err :string :in ""})
                             15000 ::timeout)]
                (when (or (= r ::timeout) (not (zero? (:exit r))))
                  (throw (ex-info "git failed" {:args args})))
                (:out r)))
        ;; Qualify main now; an unavailable revision refuses instead of using a stale copy.
        revision (first (str/split (str/trim (git "ls-remote" "origin" "refs/heads/main")) #"\s+"))]
    (when-not (re-find #"^[a-f0-9]{40}\z" (or revision ""))
      (throw (ex-info "module-source-unavailable" {})))
    (module-text-at git revision relative-path)))

(def ^:private sign-prefix "(?:[+-] |  )?")

(defn- fence-close-re [ch size]
  (re-pattern (str "^" sign-prefix " {0,3}" (java.util.regex.Pattern/quote ch) "{" size ",}\\s*\\z")))

(defn- diagram-fence?
  "An unlabelled fence whose body draws boxes or arrows; prose is not a diagram."
  [lines open [_ fence-str]]
  (let [close (fence-close-re (subs fence-str 0 1) (count fence-str))
        body (take-while #(not (re-find close %)) (drop (inc open) lines))]
    (boolean (re-find #"(?m)[─-╿]|\+-{2,}\+|^\s*[|v^]\s*$|-->|<--" (str/join "\n" body)))))

(defn- module-body [text]
  (-> text (str/replace "\r\n" "\n") (str/replace-first #"\A---\n(?s:.*?)\n---\n" "") str/trim))

(defn- normalized-paragraph [lines]
  (-> (str/join "\n" lines) str/trim (str/replace #"(?U)\s+" " ")))

(defn- module-paragraphs [text]
  (->> (str/split (module-body text) #"\n\s*\n")
       (map #(normalized-paragraph [%]))
       (remove str/blank?)
       set))

;; ---------------------------------------------------------- word marks
;;
;; A moved passage edited on its way is written once, inside its move
;; container, its changed words marked in place: [-removed words-] and
;; [+added words+]. A mark opens and closes on one line, holds a word, and
;; holds no other mark; a code span stands in a mark as one word. Lines in a
;; code fence carry no marks.

(def ^:private word-mark-token #"\[-|\[\+|-\]|\+\]")

(defn- code-spans-held
  "The line with each code span written over as one opaque word of its length."
  [line]
  (str/replace line #"`[^`]+`" #(apply str (repeat (count %) "x"))))

(defn word-mark-errors
  "The faults in one line's word marks, as reasons; empty when every mark is sound."
  [line]
  (let [held (code-spans-held line)
        m (re-matcher word-mark-token held)]
    (loop [open nil errors []]
      (if (.find m)
        (let [token (.group m)]
          (cond
            (#{"[-" "[+"} token)
            (let [mark {:closer (str (subs token 1) "]") :from (.end m)}]
              (cond
                (nil? open) (recur mark errors)
                ;; the open mark closes after this one: this one is inside it
                (str/index-of held (:closer open) (.end m)) (recur open (conj errors "nested-word-mark"))
                :else (recur mark (conj errors "unclosed-word-mark"))))
            (and open (= token (:closer open)))
            (recur nil (cond-> errors
                         (str/blank? (subs held (:from open) (.start m)))
                         (conj "empty-word-mark")))
            :else (recur open errors)))
        (cond-> errors open (conj "unclosed-word-mark"))))))

(defn move-body-word-mark-errors
  "[[index reason]] for the word marks of a move body, its fenced lines left aside."
  [body]
  (loop [k 0 fence nil out []]
    (if (>= k (count body))
      out
      (let [line (body k)
            opener (second (re-find #"^ {0,3}(`{3,}|~{3,})" line))]
        (cond
          fence (recur (inc k) (when-not (re-find (re-pattern (str "^ {0,3}" (java.util.regex.Pattern/quote (subs fence 0 1))
                                                                    "{" (count fence) ",}\\s*\\z")) line)
                                 fence) out)
          opener (recur (inc k) opener out)
          :else (recur (inc k) nil (into out (map (fn [r] [k r]) (distinct (word-mark-errors line))))))))))

(defn- moved-line-side
  "A moved line with the words of one side kept, group 1 (removed) or group 2
  (added), the other side's words left out, and the space a left-out word stood
  between written once."
  [line kept]
  (let [m (re-matcher #"\[-(.+?)-\]|\[\+(.+?)\+\]" (code-spans-held line))
        sb (StringBuilder.)]
    (loop [pos 0]
      (if (.find m)
        (do (.append sb (subs line pos (.start m)))
            (.append sb (if (.group m kept) (subs line (.start m kept) (.end m kept)) "\u0000"))
            (recur (.end m)))
        (.append sb (subs line pos))))
    (-> (str sb)
      (str/replace #" \u0000+ " " ")
      (str/replace #"^\u0000+ | \u0000+$" "")
      (str/replace #" \u0000+(?=[^\p{L}\p{N}\s`*\[(])" "")
      (str/replace "\u0000" ""))))

(defn moved-source-line
  "A moved line as its source module holds it: removed words kept, added words
  left out, and the space an added word stood between written once."
  [line]
  (moved-line-side line 1))

(defn moved-landed-line
  "A moved line as the module it enters holds it: added words kept, removed
  words left out, and the space a removed word stood between written once."
  [line]
  (moved-line-side line 2))

;; ---------------------------------------------------------- inline moved mark
;;
;; In the view of the module a sentence leaves, the line keeps its other words
;; and holds, where the sentence left, `[[moved to: repo › path]]`. Read on the
;; side that holds the passage, the mark is the passage, joined by the spaces
;; around the mark; read on the side that does not, the mark is nothing.

(def ^:private inline-moved-mark #"\[\[moved to: ([^\[\]]+)\]\]")
(def ^:private inline-moved-opener "[[moved to:")

(defn inline-moved-marks
  "The marks in a line outside its code spans, in order: [{:start :end :to}], :to
  the text after `moved to: `. :malformed is the number of openers no mark closes."
  [line]
  (let [held (code-spans-held line)
        m (re-matcher inline-moved-mark held)
        marks (loop [out []]
                (if (.find m)
                  (recur (conj out {:start (.start m) :end (.end m) :to (.group m 1)}))
                  out))
        openers (count (re-seq (re-pattern (java.util.regex.Pattern/quote inline-moved-opener)) held))]
    {:marks marks :malformed (- openers (count marks))}))

(defn expand-inline-marks
  "A line with each of its marks replaced, in order, by the matching passage of
  passages (a vector of lines), or by nothing where that passage is nil. A
  passage's first line joins the text before the mark and its last line the text
  after it. Where a mark is taken out, one space of the two around it is kept.
  The result is a vector of lines."
  [line marks passages]
  (let [n (count marks)
        segment (fn [i] (subs line
                              (if (zero? i) 0 (:end (nth marks (dec i))))
                              (if (= i n) (count line) (:start (nth marks i)))))]
    (loop [i 0 passages passages acc [(segment 0)] taken-out false]
      (if (= i n)
        (if (and taken-out (str/blank? (segment n)))
          (conj (pop acc) (str/trimr (peek acc)))
          acc)
        (let [passage (first passages) after (segment (inc i))]
          (if (seq passage)
            (recur (inc i) (rest passages)
                   (let [joined (-> (pop acc) (conj (str (peek acc) (first passage))) (into (rest passage)))]
                     (conj (pop joined) (str (peek joined) after)))
                   false)
            (let [before (peek acc)
                  after (cond
                          (and (str/ends-with? before " ") (str/starts-with? after " ")) (subs after 1)
                          (str/blank? before) (str/triml after)
                          :else after)]
              (recur (inc i) (rest passages) (conj (pop acc) (str before after)) true))))))))

;; ---------------------------------------------------------- in place
;;
;; A proposal shown in place stands inside a module view, at the place its
;; edit lands. Its level-four heading names the view's module; a move names
;; the module it leaves and the module it enters. Signed lines are the edit:
;; proposed changes contribute removed lines; landed Review changes contribute
;; added lines. The surrounding unsigned text and that side reconstruct the
;; module as it stands, to the character. A move container reconstructs its body only in
;; the module named by from and is rendered once.

(def in-place-heading
  #"^#### (Proposal (\d+): (?:(.+) · )?([^\s›·]+) › ([^\s,›·]+)(?:, and ([^\s›·]+) › ([^\s,›·]+))?)\s*\z")
(def ^:private in-place-start #"^#### Proposal\b")

(defn- added-line? [line]
  (boolean (or (= line "+") (str/starts-with? line "+ "))))

(defn- removed-line? [line]
  (boolean (or (= line "-") (str/starts-with? line "- "))))

(defn- context-line? [line]
  (str/starts-with? line "  "))

(defn- unsigned-change-line [line]
  (if (= 1 (count line)) "" (subs line 2)))

;; A line added to a moved passage, a heading among them, is written inside
;; its move container as an added line, "+ text" (a bare "+" is a blank line of
;; the addition). It is not in the source module, so an in-place move leaves it
;; out of its comparison. A "+ " line inside a code fence of the passage is code.

(defn move-added-indices
  "The indices of a move body's added lines: unfenced lines of the form `+` or `+ text`."
  [body]
  (loop [k 0 fence nil out #{}]
    (if (>= k (count body))
      out
      (let [line (body k)
            opener (second (re-find #"^ {0,3}(`{3,}|~{3,})" line))]
        (cond
          fence (recur (inc k)
                       (when-not (re-find (re-pattern (str "^ {0,3}" (java.util.regex.Pattern/quote (subs fence 0 1))
                                                           "{" (count fence) ",}\\s*\\z")) line)
                         fence)
                       out)
          opener (recur (inc k) opener out)
          (or (= line "+") (str/starts-with? line "+ ")) (recur (inc k) nil (conj out k))
          :else (recur (inc k) nil out))))))

(defn move-added-line-errors
  "[[index reason]] for each run of added lines of a move body that says nothing."
  [body]
  (let [added (move-added-indices body)]
    (loop [k 0 out []]
      (cond
        (>= k (count body)) out
        (added k)
        (let [end (first (remove added (iterate inc k)))
              run (subvec body k end)]
          (recur end (cond-> out
                       (every? #(str/blank? (subs % (min 2 (count %)))) run)
                       (conj [k "empty-added-move-line"]))))
        :else (recur (inc k) out)))))

(defn move-passage-lines
  "A move body without its added lines: the passage as its source module holds it.
  The blank lines an addition stood between are written once."
  [body]
  (let [added (move-added-indices body)]
    (loop [k 0 out [] gap false]
      (cond
        (>= k (count body))
        (if (and gap (seq out) (str/blank? (peek out))) (pop out) out)
        (added k) (recur (inc k) out true)
        (and gap (str/blank? (body k)) (or (empty? out) (str/blank? (peek out)))) (recur (inc k) out gap)
        :else (recur (inc k) (conj out (body k)) (and gap (str/blank? (body k))))))))

(declare plain-fence-close-re)

(defn- outside-fences
  "lines with f applied to each line outside a code fence; a fence's own lines
  and its body stay as written, since a line in a code fence carries no marks."
  [f lines]
  (loop [k 0 fence nil out []]
    (if (>= k (count lines))
      out
      (let [line (lines k)
            opener (second (re-find #"^ {0,3}(`{3,}|~{3,})" line))]
        (cond
          fence (recur (inc k) (when-not (re-find (plain-fence-close-re (subs fence 0 1) (count fence)) line) fence)
                       (conj out line))
          opener (recur (inc k) opener (conj out line))
          :else (recur (inc k) nil (conj out (f line))))))))

(defn move-source-lines
  "A move body read as the module it leaves holds the passage: its added lines
  left out and its word marks undone."
  [body]
  (outside-fences moved-source-line (move-passage-lines body)))

(defn move-landed-lines
  "A move body read as the module it enters holds the passage: its added lines
  in place, their sign taken off, and its word marks applied."
  [body]
  (let [added (move-added-indices body)]
    (outside-fences moved-landed-line
                    (vec (map-indexed (fn [k l] (if (added k) (unsigned-change-line l) l)) body)))))

;; ---------------------------------------------------------- description
;;
;; A Markdown module's description, the `description` of its front matter, is
;; shown in a `::: description` container at the head of its module view or
;; proposal, never as front matter: its lines unsigned as it stands, or its
;; removed lines then its added lines when it changes, each side one text. A
;; description as it stands is authenticated against the module's front matter.

(defn- description-text [lines]
  (let [t (normalized-paragraph lines)] (when-not (str/blank? t) t)))

(defn description-sides
  "A description container's body read: {:current text :added text :signed bool
  :errors [reasons]}. Unsigned lines are the description as it stands (:current);
  signed lines are its removed lines (:current) then its added lines (:added)."
  [body]
  (let [nonblank (vec (remove str/blank? body))
        signs (mapv #(cond (removed-line? %) :removed (added-line? %) :added
                           (context-line? %) :context :else :plain)
                    nonblank)
        side (fn [k] (description-text (remove nil? (map (fn [l s] (when (= s k) (unsigned-change-line l))) nonblank signs))))
        ordered? (= signs (vec (concat (filter #{:removed} signs) (filter #{:added} signs))))]
    (cond
      (empty? nonblank) {:errors ["empty-description-container"]}
      (every? #{:plain} signs) {:current (description-text nonblank) :signed false :errors []}
      (and ordered? (or (side :removed) (side :added)))
      (let [current (side :removed) added (side :added)]
        {:current current :added added :signed true
         :errors (cond-> [] (and current (= current added)) (conj "description-change-changes-nothing"))})
      :else {:errors ["malformed-description"]})))

(defn module-description
  "The description in a module's front matter, as one text; nil when it has none."
  [text]
  (when-let [[_ front-matter] (re-find #"\A---\n((?s:.*?))\n---(?:\n|\z)" (str/replace text "\r\n" "\n"))]
    (when-let [[_ value] (re-find #"(?m)^description:[ \t]*(.*)$" front-matter)]
      (let [value (str/trim value)]
        (description-text
         [(cond (re-matches #"\".*\"" value) (str/replace (subs value 1 (dec (count value))) #"\\(.)" "$1")
                (re-matches #"'.*'" value) (str/replace (subs value 1 (dec (count value))) "''" "'")
                :else value)])))))

(defn front-matter-line?
  "A book line that is a description written as front matter: a `description:` key, signed or not."
  [line]
  (boolean (re-find #"^(?:[+-] |  )?description:(?:\s|\z)" line)))

(defn- plain-fence-close-re [ch size]
  (re-pattern (str "^ {0,3}" (java.util.regex.Pattern/quote ch) "{" size ",}\\s*\\z")))

(defn- fence-close-index
  "The index of the line closing the fence opened at j, before end; nil when none does."
  [lines j end ch size]
  (let [close (plain-fence-close-re ch size)]
    (first (filter #(re-find close (lines %)) (range (inc j) end)))))

(defn- location-text [[repository path]] (str repository " › " path))

(defn- location-of [text]
  (some-> text (str/split #" › " 2) vec))

(defn- added-fence-language-errors
  "Signed fences in a proposal retain removed openers and label added ones."
  [lines start end]
  (loop [i start errors []]
    (if (>= i end)
      errors
      (if-let [[_ sign opener language]
               (re-matches #"^([+-] |  )? {0,3}(`{3,}|~{3,})\s*(\S*(?: sketch)?)\s*\z" (lines i))]
        (let [close (first (filter #(re-find (fence-close-re (subs opener 0 1) (count opener)) (lines %))
                                   (range (inc i) end)))]
          (recur (if close (inc close) end)
                 (cond-> errors
                   (and (= sign "+ ") (= language ""))
                   (conj [i "fence-language-required"]))))
        (recur (inc i) errors)))))

(defn in-place-proposal
  "The in-place proposal whose heading is line h of the module view of
  [repository path] ending before line e: {:heading h :end k :number :title
  :comment lines :current lines :moves :placeholders :errors [[index reason]]},
  k being the line where the module resumes, :proposed the index of its
  `::: proposed` line or nil, and :current the lines this edit contributes to
  the module as it stands. source-sign selects removed (proposed) or added
  (landed) lines; an explicitly proposed part of a Review always contributes its
  removed lines.

  A move stands in the view of the module it leaves or of the module it enters.
  Read on the removed side, its passage is the module it leaves as it stands;
  read on the added side, the module it enters. A placeholder, `::: moved
  proposed|landed` holding one `to: repo › path` line, stands where a passage
  written in the destination's view left: on the removed side it stands for
  that passage, which `resolve-moved` gives as (fn [number from to]) -> the
  move's record, or nil when no move is left to pair with it. Without
  resolve-moved a placeholder stands for nothing and pairs with nothing."
  ([lines h e location] (in-place-proposal lines h e location :removed))
  ([lines h e location source-sign] (in-place-proposal lines h e location source-sign nil))
  ([lines h e [repository path] source-sign resolve-moved]
  (let [view [repository path]
        heading (re-matches in-place-heading (lines h))
        [_ _ number title heading-repository heading-path to-repository to-path] heading
        locations (cond-> [] heading-repository (conj [heading-repository heading-path])
                          to-repository (conj [to-repository to-path]))
        ;; A proposal in place declares itself proposed with `::: proposed`, the
        ;; first nonblank line under its heading: the index of that line, or nil.
        proposed (first (filter #(not (str/blank? (lines %))) (range (inc h) e)))
        proposed (when (and proposed (= "::: proposed" (lines proposed))) proposed)
        start (if proposed (inc proposed) (inc h))
        source-sign (if proposed :removed source-sign)
        source-line? (case source-sign :removed removed-line? :added added-line?)
        ;; A passage contributed after a nonblank line of the side read is the
        ;; remainder of that current line; a blank line before it makes it a whole line.
        joins? (fn [j] (and (> j h)
                            (source-line? (lines (dec j)))
                            (not (str/blank? (unsigned-change-line (lines (dec j)))))))
        contribute (fn [current j passage]
                     (cond
                       (empty? passage) current
                       (and (joins? j) (seq current))
                       (into (conj (pop current) (str (peek current) " " (first passage))) (rest passage))
                       :else (into current passage)))
        ;; An inline moved mark in a line of the proposal: each pairs with its move as a
        ;; placeholder does. Returns {:lines the line as the module holds it on the
        ;; side read, nil when the line is of the other side :errors :placeholders}.
        read-marks (fn [j text read?]
                     (let [{:keys [marks malformed]} (inline-moved-marks text)
                           reads (map (fn [{:keys [to]}]
                                        (let [to-location (location-of to)
                                              valid? (and to-location
                                                          (valid-location? (first to-location) (second to-location))
                                                          (not= view to-location)
                                                          (or (= locations [view to-location]) (= locations [view])))
                                              paired (when (and valid? resolve-moved) (resolve-moved nil view to-location))]
                                          {:to-location to-location :valid? valid? :paired paired}))
                                      marks)]
                       {:lines (when read?
                                 (expand-inline-marks text marks
                                                      (mapv #(when (= source-sign :removed) (:source-lines (:paired %))) reads)))
                        :errors (-> []
                                    (into (repeat malformed [j "malformed-moved-placeholder"]))
                                    (into (mapcat (fn [{:keys [valid? paired]}]
                                                    (cond (not valid?) [[j "malformed-moved-placeholder"]]
                                                          (and resolve-moved (nil? paired)) [[j "moved-without-move"]]))
                                                  reads)))
                        :placeholders (vec (for [{:keys [to-location valid?]} reads :when valid?]
                                             {:line j :inline true :to to-location}))}))
        ;; its description container, read, with the index of its opening line
        description (volatile! nil)
        errors (cond-> []
                 (nil? heading) (conj [h "proposal-heading-without-location"])
                 (and heading (not (some #{view} locations)))
                 (conj [h "in-place-proposal-elsewhere"]))]
    (loop [j start comment nil changed false current [] move nil moves [] placeholders [] errors errors]
      (let [done (fn [k]
                   {:heading h :end k :number number :title title :comment comment :proposed proposed
                    :locations locations :current current :move move :moves moves
                    :placeholders placeholders :description @description :sign source-sign
                    :errors (cond-> (into errors (added-fence-language-errors lines start k))
                              (not changed) (conj [h "no-change-in-proposal"])
                              (and (= 2 (count locations)) (nil? move) (empty? placeholders))
                              (conj [h "move-container-required"]))})]
        (if (>= j e)
          (done e)
          (let [l (lines j)
                fence (re-find #"^(`{3,}|~{3,})\s*(\S*(?: sketch)?)\s*\z" l)
                move-open (re-matches #"::: move (proposed|landed)" l)
                moved-open (re-matches #"::: moved (proposed|landed)" l)]
            (cond
              (str/blank? l) (recur (inc j) comment changed
                                    (cond-> current (seq current) (conj l)) move moves placeholders errors)
              (or (added-line? l) (removed-line? l) (context-line? l))
              (let [signed? (not (context-line? l))
                    text (if signed? (unsigned-change-line l) (subs l 2))
                    read? (or (not signed?) (source-line? l))
                    marked (read-marks j text read?)
                    marks? (boolean (or (seq (:placeholders marked)) (seq (:errors marked))))]
                (recur (inc j) comment (or signed? marks? changed)
                       (into current (or (:lines marked) (when read? [text])))
                       move moves (into placeholders (:placeholders marked)) (into errors (:errors marked))))
              (re-find in-place-start l) (done j)

              (= l "::: comment")
              (let [close (first (filter #(= ":::" (lines %)) (range (inc j) e)))
                    body (if close (subvec lines (inc j) close) [])
                    errors (cond-> errors
                             (nil? close) (conj [j "unclosed-comment-container"])
                             (and close (every? str/blank? body)) (conj [j "empty-comment-container"])
                             comment (conj [j "second-comment-in-proposal"])
                             changed (conj [j "comment-after-change"]))]
                (if close
                  (recur (inc close) (or comment body) changed current move moves placeholders errors)
                  (done e)))

              ;; A description changed in place: its removed lines then its added lines,
              ;; first among the proposal's changes. Its module's description is
              ;; authenticated by the module view, at whose head this proposal stands.
              (= l "::: description")
              (let [close (first (filter #(= ":::" (lines %)) (range (inc j) e)))
                    read (description-sides (if close (subvec lines (inc j) close) []))
                    errors (cond-> (into errors (map (fn [r] [j r])) (if close (:errors read) []))
                             (nil? close) (conj [j "unclosed-description-container"])
                             @description (conj [j "second-description-in-proposal"])
                             changed (conj [j "description-not-at-head"])
                             (and close (empty? (:errors read)) (not (:signed read)))
                             (conj [j "in-place-description-unchanged"]))]
                (when (and close (nil? @description)) (vreset! description (assoc read :line j)))
                (if close
                  (recur (inc close) comment (or changed (boolean (:signed read))) current move moves placeholders errors)
                  (done e)))

              ;; A move, in the view of the module it leaves or of the module it enters.
              move-open
              (let [close (first (filter #(= ":::" (lines %)) (range (inc j) e)))
                    body (if close (subvec lines (inc j) close) [])
                    from (some->> (first body) (re-matches #"from: ([^,]+ › [^,]+)(?:,.*)?") second)
                    to (some->> (second body) (re-matches #"to: ([^,]+ › [^,]+)(?:,.*)?") second)
                    passage (if (and (>= (count body) 4) (= "" (nth body 2))) (subvec body 3) [])
                    ;; the passage as the module it leaves holds it, any edit made on its way undone
                    source-lines (move-source-lines passage)
                    ;; and as the module it enters holds it, the edit made
                    landed-lines (move-landed-lines passage)
                    from-location (location-of from)
                    to-location (location-of to)
                    side (cond (= view from-location) :from (= view to-location) :to)
                    valid? (and close from to (seq source-lines) side
                                (not= from-location to-location)
                                (or (= locations [from-location to-location]) (= locations [view])))
                    errors (cond-> errors
                             (nil? close) (conj [j "unclosed-move-container"])
                             (not valid?) (conj [j "malformed-in-place-move"]))
                    errors (into errors (map (fn [[k reason]] [(+ j 1 k) reason]))
                                 (concat (move-body-word-mark-errors body)
                                         (move-added-line-errors body)))
                    record {:line j :number number :state (second move-open) :from from-location :to to-location
                            :side side :sign source-sign :source-lines source-lines :landed-lines landed-lines}]
                (if close
                  (recur (inc close) comment true
                         (cond
                           (not valid?) current
                           ;; the module it leaves holds the passage as it stands, before the move
                           (and (= side :from) (= source-sign :removed)) (contribute current j source-lines)
                           ;; the module it enters holds it once the move has landed
                           (and (= side :to) (= source-sign :added)) (contribute current j landed-lines)
                           :else current)
                         (or move [from to])
                         (cond-> moves valid? (conj record))
                         placeholders errors)
                  (done e)))

              ;; A placeholder, where a passage written in the destination's view left.
              moved-open
              (let [close (first (filter #(= ":::" (lines %)) (range (inc j) e)))
                    body (if close (vec (remove str/blank? (subvec lines (inc j) close))) [])
                    to (when (= 1 (count body)) (second (re-matches #"to: ([^,]+ › [^,]+)" (first body))))
                    to-location (location-of to)
                    valid? (and close to-location
                                (valid-location? (first to-location) (second to-location))
                                (not= view to-location)
                                (or (= locations [view to-location]) (= locations [view])))
                    paired (when (and valid? resolve-moved) (resolve-moved number view to-location))
                    errors (cond-> errors
                             (nil? close) (conj [j "unclosed-moved-container"])
                             (and close (not valid?)) (conj [j "malformed-moved-placeholder"])
                             (and valid? resolve-moved (nil? paired)) (conj [j "moved-without-move"]))]
                (if close
                  (recur (inc close) comment true
                         (if (and paired (= source-sign :removed))
                           (contribute current j (:source-lines paired))
                           current)
                         move moves
                         (cond-> placeholders valid? (conj {:line j :state (second moved-open) :to to-location}))
                         errors)
                  (done e)))

              fence
              (let [[_ opener language] fence
                    close (fence-close-index lines j e (subs opener 0 1) (count opener))
                    body (subvec lines (inc j) (or close e))
                    nonblank (remove str/blank? body)
                    change-lines? (and (seq nonblank)
                                       (every? #(or (added-line? %) (removed-line? %) (context-line? %)) nonblank)
                                       (some #(or (added-line? %) (removed-line? %)) nonblank))]
                (if change-lines?
                  (let [errors (cond-> errors
                                 (nil? close) (conj [j "unclosed-code-fence"])
                                 (= "" language) (conj [j "fence-language-required"])
                                 (and (= "mermaid" (str/lower-case language))
                                      (not (coherent-signed-mermaid? body)))
                                 (conj [j "mixed-mermaid-change"]))
                        kept (keep #(when (or (source-line? %) (context-line? %))
                                      (unsigned-change-line %)) body)
                        current' (if (seq kept)
                                   (into current (concat [(str opener language)] kept [opener]))
                                   current)]
                    (if close (recur (inc close) comment true current' move moves placeholders errors) (done e)))
                  (done j)))

              :else (done j)))))))))

(defn module-view-end
  "The line after the module view whose heading is line i: the next proposal or
  module heading, the to-the-living end mark, or the end of the source."
  [lines i]
  (loop [end (inc i) literal nil]
    (if (>= end (count lines))
      end
      (let [text (lines end)]
        (if literal
          (recur (inc end)
                 (if (re-find (fence-close-re (:char literal) (:size literal)) text) nil literal))
          (if (or (re-find next-section text) (re-find #"^<!-- to-the-living:end -->\z" text))
            end
            (let [open (re-find #"^ {0,3}(`{3,}|~{3,})" text)]
              (recur (inc end)
                     (when open {:char (subs (second open) 0 1) :size (count (second open))})))))))))

(defn unfenced-indices
  "The indices in [start, end) of the lines that are book text: outside every
  code fence (a fence's opening line among them) and every comment or shown
  container's body."
  [lines start end]
  (loop [j start fence nil container false out []]
    (if (>= j end)
      out
      (let [l (lines j)]
        (cond
          fence (recur (inc j) (when-not (re-find (fence-close-re (:char fence) (:size fence)) l) fence) container out)
          container (recur (inc j) nil (not= ":::" l) out)
          (#{"::: comment" "::: shown"} l) (recur (inc j) nil true out)
          :else (let [open (re-find (re-pattern (str "^" sign-prefix " {0,3}(`{3,}|~{3,})")) l)]
                  (recur (inc j) (when open {:char (subs (second open) 0 1) :size (count (second open))})
                         false (conj out j))))))))

(defn in-place-proposals
  "The in-place proposals of the module view of location, whose lines are
  [start, end); a heading inside the module's own fences is the module's."
  ([lines start end location] (in-place-proposals lines start end location :removed))
  ([lines start end location source-sign] (in-place-proposals lines start end location source-sign nil))
  ([lines start end location source-sign resolve-moved]
  (loop [j start fence nil out []]
    (if (>= j end)
      out
      (let [l (lines j)]
        (cond
          fence (recur (inc j) (when-not (re-find (plain-fence-close-re (:char fence) (:size fence)) l) fence) out)
          (re-find in-place-start l) (let [p (in-place-proposal lines j end location source-sign resolve-moved)]
                                       (recur (:end p) nil (conj out p)))
          :else (let [open (re-find #"^ {0,3}(`{3,}|~{3,})" l)]
                  (recur (inc j) (when open {:char (subs (second open) 0 1) :size (count (second open))}) out))))))))

(defn source-lines [source]
  (vec (str/split (str/replace source "\r\n" "\n") #"\n" -1)))

(defn source-kind
  "The kind its Presentation line gives a source, before any heading: Proposal,
  Review or Account; Proposal when it names none."
  [lines]
  (let [kind (some (fn [l]
                     (cond (re-find #"^ {0,3}#" l) ::none
                           (re-find #"^Presentation\.\{ «[^»]*»(?: \w+)? \}\z" l)
                           (or (second (re-find #"»(?: (\w+))? \}\z" l)) "Proposal")))
                   lines)]
    (if (string? kind) kind "Proposal")))

(defn- kind-sign [kind] (if (= "Review" kind) :added :removed))

(defn module-views
  "Every module view of a source's lines, in order: {:heading i :end k :location
  [repository path] :new? bool :proposals [in-place proposals]}, each proposal
  read with the side the kind gives."
  ([lines] (module-views lines (source-kind lines)))
  ([lines kind]
   (do
     (loop [i 0 fence nil out []]
       (if (>= i (count lines))
         out
         (let [l (lines i)
               view (when-not fence (module-view-heading l))]
           (cond
             fence (recur (inc i) (when-not (re-find (fence-close-re (:char fence) (:size fence)) l) fence) out)
             view
             (let [end (module-view-end lines i)]
               (recur end nil (conj out (assoc view :heading i :end end
                                               :proposals (in-place-proposals lines (inc i) end (:location view)
                                                                              (kind-sign kind))))))
             :else
             (let [open (re-find (re-pattern (str "^" sign-prefix " {0,3}(`{3,}|~{3,})")) l)]
               (recur (inc i) (when open {:char (subs (second open) 0 1) :size (count (second open))}) out)))))))))

(defn source-in-place-proposals
  "Every in-place proposal of a source, keyed by the index of its heading line."
  [source]
  (into {} (for [view (module-views (source-lines source)) p (:proposals view)] [(:heading p) p])))

(defn- permutations
  "Every order of the items of xs, the order given first."
  [xs]
  (let [v (vec xs)]
    (if (<= (count v) 1)
      [v]
      (for [k (range (count v))
            tail (permutations (concat (subvec v 0 k) (subvec v (inc k))))]
        (vec (cons (v k) tail))))))

(defn- distinct-by
  "xs without the items whose key f repeats an earlier item's."
  [f xs]
  (let [seen (volatile! #{})]
    (filter (fn [x] (let [k (f x)] (when-not (@seen k) (vswap! seen conj k) true))) xs)))

(defn- passage-in-module?
  "Whether passage is one contiguous excerpt of a module's lines, its first line
  allowed to be the remainder of a current line."
  [module passage]
  (let [n (count passage)]
    (and (pos? n)
         (some (fn [k]
                 (and (let [line (module k)]
                        (or (= line (first passage)) (str/ends-with? line (str " " (first passage)))))
                      (= (rest passage) (subvec module (inc k) (+ k n)))))
               (range (inc (- (count module) n)))))))

(def dependency-id #"^(spirit|intent|vision|knowledge|inquiry|compensation|role|raw):([a-z0-9-]+):((?:0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*))?)\z")

(defn dependencies-read
  "The added inline list of explicit registry IDs in a new module's container."
  [lines]
  (let [body (vec (remove str/blank? lines))
        value (when (= 1 (count body)) (second (re-matches #"\+ \[(.*)\]" (first body))))
        ids (when value (vec (remove str/blank? (str/split value #"[,\s]+"))))]
    (cond
      (nil? value) {:errors ["malformed-dependencies-list"]}
      (not-every? #(re-matches dependency-id %) ids) {:errors ["invalid-dependency-id"]}
      (not= (count ids) (count (distinct ids))) {:errors ["duplicate-dependency"]}
      :else {:ids ids :errors []})))

(defn registry-names
  "Names in one modules.datom registry; no module bodies are read."
  [text]
  (let [entry (str "\\{\\s*\\{\\s*(Spirit|Intent|Vision|Knowledge|Inquiry|Compensation|Role|Raw)"
                   "\\s+([a-z0-9-]+)\\s+(?:Single\\.(0|[1-9][0-9]*)|Span\\.\\{\\s*(0|[1-9][0-9]*)"
                   "\\s+(0|[1-9][0-9]*)\\s*\\})\\s*\\}\\s+(?:«[^»]*»|[^\\s{}\\[\\]]+)\\s*\\}")]
    (when-not (re-matches (re-pattern (str "\\s*\\[\\s*(?:" entry "\\s*)*\\]\\s*")) text)
      (throw (ex-info "dependency-registry-unavailable" {})))
    (set (for [[_ kind topic single first-version last-version] (re-seq (re-pattern entry) text)]
           (str (str/lower-case kind) ":" topic ":" (or single (str first-version "-" last-version)))))))

(defn registry-dependency-names
  "Read the three authored registries at their current origin/main revisions."
  []
  (reduce
   into #{}
   (for [repository ["psyche-belief" "mind-belief" "field-belief"]]
     (let [root (str "/git/github.com/LiGoldragon/" repository)
           git (fn [& arguments]
                 (let [result @(process/process (into ["git" "-C" root] arguments)
                                                {:in "" :out :string :err :string})]
                   (when-not (zero? (:exit result))
                     (throw (ex-info "dependency-registry-unavailable" {})))
                   (:out result)))
           revision (first (str/split (str/trim (git "ls-remote" "origin" "refs/heads/main")) #"\s+"))]
       (when-not (re-matches #"[a-f0-9]{40}" (or revision ""))
         (throw (ex-info "dependency-registry-unavailable" {})))
       (registry-names (git "show" (str revision ":modules.datom")))))))

(defn data-figure-line [line]
  (when-let [[_ alt-text filename] (re-matches #"!\[([^\]]*)\]\((.*)\)\s*" line)]
    {:alt-text alt-text :filename filename}))

(defn- svg-document? [bytes]
  (try
    (with-open [input (java.io.ByteArrayInputStream. bytes)]
      (let [events (xml/event-seq input {:support-dtd false :supporting-external-entities false
                                         :include-node? #{:element} :location-info false})
            tag (:tag (first events))]
        (when (and (= "svg" (xml/qname-local tag))
                   (= "http://www.w3.org/2000/svg" (xml/qname-uri tag)))
          (dorun events)
          true)))
    (catch Exception _ false)))

(defn read-data-figure
  "Read a sibling SVG as drawn, returning its validated bytes."
  [source-path {:keys [filename]}]
  (let [refuse (fn [reason]
                 (throw (ex-info (str reason ": " filename) {:reason reason :filename filename})))
        _ (when-not (re-matches #"(?i)[^/\\:\s\p{Cntrl}()]+\.svg" filename)
            (refuse "data-figure-sibling-svg-required"))
        _ (when-not source-path (refuse "data-figure-source-path-required"))
        directory (fs/parent (fs/absolutize source-path))
        file (fs/path directory filename)
        bytes (try
                (when (and (fs/regular-file? file)
                           (= (fs/real-path directory) (fs/parent (fs/real-path file))))
                  (fs/read-all-bytes file))
                (catch java.io.IOException _ nil))]
    (when-not bytes (refuse "data-figure-file-unavailable"))
    (when-not (svg-document? bytes) (refuse "data-figure-invalid-svg"))
    bytes))

(defn check-book
  "Source text -> vector of {:line n :reason \"...\"}. Option :read-module is
  (fn [repository path]) -> file text; it throws when the module is unavailable.
  Option :dependency-names is a zero-argument reader of the registry ID set.
  Option :source-path locates SVG data figures beside the source."
  ([source] (check-book source {}))
  ([source {:keys [read-module dependency-names source-path]
            :or {read-module read-context-module dependency-names registry-dependency-names}}]
   (let [lines (vec (str/split (str/replace source "\r\n" "\n") #"\n" -1))
         st (atom {:errors [] :fence nil :section nil :in-comment false :container nil :plain nil
                   :proposals 0 :title false :presentation false :kind "Proposal" :signature nil :shown #{}})
         module-cache (atom {})
         dependency-registry (delay (dependency-names))
         err! (fn [line reason] (swap! st update :errors conj {:line line :reason reason}))
         check-data-figure! (fn [number figure]
                              (try (read-data-figure source-path figure)
                                   (catch clojure.lang.ExceptionInfo e
                                     (err! number (:reason (ex-data e))))))
         ;; Every module view, read before any is checked: a move written in the
         ;; view of the module it enters pairs with the placeholder in the view of
         ;; the module it leaves, by the proposal number the two share and the pair
         ;; of modules, several of them in the order written.
         views (module-views lines (source-kind lines))
         module-review? (and (= "Review" (source-kind lines)) (seq views))
         viewed (set (map :location views))
         entering-moves (vec (for [v views p (:proposals v) m (:moves p) :when (= :to (:side m))] m))
         unpaired (atom (reduce (fn [acc m] (update acc [(:number m) (:from m) (:to m)] (fnil conj []) m))
                                {} entering-moves))
         ;; A placeholder pairs by its proposal number and the pair of modules; an inline
         ;; mark, whose number is nil, by the pair of modules alone, the first move
         ;; written of the pair. Among the moves of one pair, the module view of the
         ;; module they leave chooses the order whose passages put the module back;
         ;; moves of identical passages, and a view no order fits, keep the order written.
         pairing (fn [unpaired]
                   (fn [number from to]
                     (let [k (if number
                               [number from to]
                               (->> @unpaired
                                    (filter (fn [[[_ f t] ms]] (and (= f from) (= t to) (seq ms))))
                                    (sort-by (fn [[_ ms]] (:line (first ms))))
                                    ffirst))]
                       (when-let [m (and k (first (get @unpaired k)))]
                         (swap! unpaired update k (comp vec rest))
                         m))))
         resolve-moved (pairing unpaired)
         check-moves!
         (fn []
           ;; With both modules on the page, a move has its placeholder.
           (doseq [m (sort-by :line (apply concat (vals @unpaired))) :when (viewed (:from m))]
             (err! (inc (:line m)) "move-without-placeholder"))
           ;; With the module it leaves not on the page, a proposed passage is that
           ;; module's text as it stands.
           (doseq [m entering-moves :when (and (not (viewed (:from m))) (= :removed (:sign m)))]
             (let [[repository path] (:from m)
                   module (try (vec (str/split (module-body (read-module repository path)) #"\n" -1))
                               (catch Throwable _ ::unavailable))]
               (cond
                 (= ::unavailable module) (err! (inc (:line m)) "move-source-unavailable")
                 (not (passage-in-module? module (vec (drop-while str/blank? (:source-lines m)))))
                 (err! (inc (:line m)) "move-source-text-absent")))))
         section #(:section @st)
         set-section! (fn [f] (swap! st update :section f))
         mark-changed! (fn [] (set-section! #(assoc % :changed true)))
         flush-plain!
         (fn []
           (when-let [{:keys [line lines repository path]} (:plain @st)]
             (let [paragraph (normalized-paragraph lines)]
               (when-not (str/blank? paragraph)
                 (if-not (and repository path)
                   (err! line "proposal-module-text-absent")
                   (let [key [repository path]
                         paragraphs (if (contains? @module-cache key)
                                      (@module-cache key)
                                      (let [v (try (module-paragraphs (read-module repository path))
                                                   (catch Throwable _ ::unavailable))]
                                        (swap! module-cache assoc key v)
                                        v))]
                     (if (= ::unavailable paragraphs)
                       (err! line "context-module-unavailable")
                       (when-not (contains? paragraphs paragraph)
                         (err! line "proposal-module-text-absent")))))))
             (swap! st assoc :plain nil)))
         plain! (fn [line number]
                  (when (and (section) (not (str/blank? line))) (set-section! #(assoc % :body true)))
                  (if (str/blank? line)
                    (flush-plain!)
                    (swap! st update :plain
                           #(if % (update % :lines conj line)
                                {:line number :lines [line]
                                 :repository (:repository (section)) :path (:path (section))}))))
         check-shown!
         (fn [{:keys [line lines]}]
           (let [location (when-let [location-line (first lines)]
                            (re-matches module-location location-line))
                 excerpt (vec (rest lines))]
             (cond
               (nil? location) (err! line "shown-location-invalid")
               (not (some (complement str/blank?) excerpt)) (err! line "shown-excerpt-empty")
               :else
               (let [repository (second location) path (nth location 2)
                     source (try (vec (str/split (module-body (read-module repository path)) #"\n" -1))
                                 (catch Throwable _ ::unavailable))]
                 (cond
                   (= ::unavailable source) (err! line "shown-module-unavailable")
                   (not (passage-in-module? source excerpt)) (err! line "shown-module-text-absent"))))))
         check-description!
         (fn [{:keys [line lines]}]
           (let [read (description-sides (vec lines))
                 {:keys [repository path] :as s} (section)]
             (doseq [r (:errors read)] (err! line r))
             (when s (set-section! #(assoc % :described true)))
             (when (and s (empty? (:errors read)))
               (cond
                 (:signed read) (mark-changed!)
                 ;; as it stands, it is the description of the proposal's one module
                 (not (and repository path)) (err! line "description-without-module")
                 :else
                 (let [file (try (read-module repository path) (catch Throwable _ ::unavailable))]
                   (cond
                     (= ::unavailable file) (err! line "context-module-unavailable")
                     (not= (:current read) (module-description file)) (err! line "module-description-differs")))))))
         check-dependencies!
         (fn [{:keys [line lines]}]
           (let [{:keys [ids errors]} (dependencies-read lines)]
             (doseq [reason errors] (err! line reason))
             (when (seq ids)
               (try
                 (let [names @dependency-registry]
                   (doseq [id ids :when (not (contains? names id))]
                     (err! line (str "unknown-dependency:" id))))
                 (catch Throwable _ (err! line "dependency-registry-unavailable"))))))
         finish! (fn []
                   (flush-plain!)
                   (let [s (section)]
                     (when (and s (not (:changed s)) (not (:deleted s)))
                       (err! (:line s) "no-change-in-proposal"))
                     ;; a new Markdown module shows its description
                     (when (and s (:new-markdown s) (not (:described s)))
                       (err! (:line s) "new-module-without-description"))
                     (swap! st assoc :section nil)))
         body! (fn [line number]
                 ;; The location lives in the heading; a File: line in the body is the retired form.
                 (when (re-find #"^File:" line) (err! number "file-line-in-body"))
                 ;; a description is a description container, never a front matter line
                 (when (and (section) (front-matter-line? line)
                            (str/ends-with? (or (:path (section)) ".md") ".md"))
                   (err! number "description-as-front-matter"))
                 (when (and (section) (change? line))
                   (flush-plain!)
                   (mark-changed!))
                 ;; A bare "+" is a blank line inside an added passage: it ends a
                 ;; paragraph and is neither module text nor a change of its own.
                 (when (and (section) (= line "+")) (flush-plain!))
                 (when (and (section)
                            (not (:deleted (section)))
                            (not (str/blank? line))
                            (not= line "+")
                            (not (change? line))
                            (not (str/starts-with? line "  "))
                            (not (str/starts-with? line "**Comment**"))
                            (not (re-find #"^(?:File:|Ruling(?::|,)|\d+\. |\*\*(?:Archive|Destroy)\*\*)" line)))
                   (plain! line number))
                 (when (str/blank? line) (flush-plain!)))
         handle-module-view!
         (fn [i number {:keys [repository path new?]}]
           (let [repo repository
                 end (module-view-end lines i)
                 review? (= "Review" (:kind @st))
                 ;; The view's description stands at its head, its first nonblank line.
                 head (first (filter #(not (str/blank? (lines %))) (range (inc i) end)))
                 head-open (and head (= "::: description" (lines head)))
                 head-close (when head-open (first (filter #(= ":::" (lines %)) (range (inc head) end))))
                 dependency-head (when head-close
                                   (first (filter #(not (str/blank? (lines %))) (range (inc head-close) end))))
                 dependency-open (and dependency-head (= "::: dependencies" (lines dependency-head)))
                 dependency-close (when dependency-open
                                    (first (filter #(= ":::" (lines %)) (range (inc dependency-head) end))))
                 _ (when dependency-open
                     (when-not new? (err! (inc dependency-head) "dependencies-outside-new-module"))
                     (if dependency-close
                       (check-dependencies! {:line (inc dependency-head)
                                             :lines (subvec lines (inc dependency-head) dependency-close)})
                       (err! (inc dependency-head) "unclosed-dependencies-container")))
                 markdown (str/ends-with? path ".md")
                 ;; The module as the view shows it: unsigned view lines, with each
                 ;; edit replaced by the current lines it carries. A markdown module
                 ;; may sit in a markdown or text fence; any other file is the content
                 ;; of its one fence, in its language, kept to the character.
                 shown-of (fn [in-place]
                            (let [by-heading (into {} (map (juxt :heading identity) in-place))
                                  raw-lines (loop [j (inc i) out []]
                                              (cond
                                                (>= j end) out
                                                (and head-close (= j head)) (recur (inc head-close) out)
                                                (and dependency-close (= j dependency-head)) (recur (inc dependency-close) out)
                                                (by-heading j) (recur (:end (by-heading j)) (into out (:current (by-heading j))))
                                                (and new? (added-line? (lines j))) (recur (inc j) (conj out (unsigned-change-line (lines j))))
                                                :else (recur (inc j) (conj out (lines j)))))
                                  raw (str/join "\n" raw-lines)
                                  shown (str/trim raw)
                                  literal (if markdown
                                            (re-find #"^(`{3,}|~{3,})(?:markdown|md|text)\s*\n((?s:.*))\n\1\z" shown)
                                            (re-find #"^(`{3,}|~{3,})[^\n`]*\n((?s:.*))\n\1\z" (str/trim raw)))]
                              (if literal (nth literal 2) shown)))
                 ;; Moves of one pair that leave this module, their passages differing:
                 ;; the placeholders here take the order whose passages put the module
                 ;; back; the order written is tried first and kept where none fits.
                 expected (try (let [file (str/replace (read-module repo path) "\r\n" "\n")]
                                 (if markdown (module-body file) (str/replace file #"\n+\z" "")))
                               (catch Throwable _ nil))
                 ambiguous (when expected
                             (for [[[_ from _ :as k] ms] @unpaired
                                   :when (and (= from [repo path])
                                              (< 1 (count (distinct (map :source-lines ms))) )
                                              (<= (count ms) 6))]
                               [k ms]))
                 _ (when (seq ambiguous)
                     (let [orders (fn orders [[[k ms] & more]]
                                    (if k
                                      (for [perm (distinct-by #(mapv :source-lines %) (permutations ms))
                                            rest-orders (orders more)]
                                        (assoc rest-orders k perm))
                                      [{}]))
                           fits (first (filter (fn [order]
                                                 (let [trial (atom (merge @unpaired order))]
                                                   (= expected
                                                      (shown-of (in-place-proposals lines (inc i) end [repo path]
                                                                                    (if review? :added :removed)
                                                                                    (pairing trial))))))
                                               (orders ambiguous)))]
                       (when fits (swap! unpaired merge fits))))
                 in-place (in-place-proposals lines (inc i) end [repo path]
                                              (if review? :added :removed) resolve-moved)
                 _ (doseq [p in-place [index reason] (:errors p)] (err! (inc index) reason))
                 _ (doseq [p in-place :when (and (:proposed p) (not review?))]
                     (err! (inc (:proposed p)) "proposed-outside-review"))
                 ;; a new module's view is itself a proposal: the module, every line added
                 _ (swap! st update :proposals + (count in-place) (if new? 1 0))
                 ;; The module as it stands: unsigned view lines, with each edit
                 ;; replaced by the current lines it carries (its removals, or a
                 ;; moved passage at the source).
                 by-heading (into {} (map (juxt :heading identity) in-place))
                 _ (when (and head-open (nil? head-close)) (err! (inc head) "unclosed-description-container"))
                 head-read (when head-close
                             (let [read (description-sides (subvec lines (inc head) head-close))]
                               (doseq [r (:errors read)] (err! (inc head) r))
                               (if new?
                                 ;; a new module's description is its added lines alone
                                 (do (when (and (empty? (:errors read)) (or (not (:signed read)) (:current read)))
                                       (err! (inc head) "new-module-line-not-added"))
                                     (when (and (empty? (:errors read)) (:signed read) (nil? (:current read)))
                                       (assoc read :line head)))
                                 (do (when (:signed read) (err! (inc head) "description-change-outside-proposal"))
                                     (when (and (empty? (:errors read)) (not (:signed read)))
                                       (assoc read :line head))))))
                 head-proposal-read (when-let [d (:description (by-heading head))]
                                      (when (empty? (:errors d)) d))
                 view-description (or head-read head-proposal-read)
                 described (or head-open (boolean (:description (by-heading head))))
                 placed (into (if head-open #{head} #{})
                              (keep #(some-> % :description :line) in-place))
                 _ (doseq [p in-place :when (and (:description p) (not= head (:heading p)))]
                     (err! (inc (:line (:description p))) "description-not-at-head"))
                 book-text (unfenced-indices lines (inc i) end)
                 _ (doseq [k book-text
                           :let [figure (when markdown (data-figure-line (lines k)))]]
                     (cond
                       figure
                       (check-data-figure! (inc k) figure)
                       (and (= "::: description" (lines k)) (not (placed k)))
                       (err! (inc k) "description-not-at-head")
                       (and (re-find #"^::: dependencies(?:\s|$)" (lines k))
                            (not= k dependency-head))
                       (err! (inc k) "dependencies-not-at-head")
                       (and markdown (front-matter-line? (lines k)))
                       (err! (inc k) "description-as-front-matter")))
                 front-matter? (some #(and markdown (front-matter-line? (lines %))) book-text)
                 ;; A new module's view, outside its proposals in place, holds added
                 ;; lines and fences of added lines alone.
                 _ (when new?
                     (loop [j (inc i)]
                       (when (< j end)
                         (let [l (lines j)
                               opener (re-find #"^ {0,3}(`{3,}|~{3,})" l)]
                           (cond
                             (and head-close (= j head)) (recur (inc head-close))
                             (and dependency-close (= j dependency-head)) (recur (inc dependency-close))
                             (by-heading j) (recur (:end (by-heading j)))
                             (or (str/blank? l) (added-line? l)) (recur (inc j))
                             opener
                             (let [fence (second opener)
                                   close (fence-close-index lines j end (subs fence 0 1) (count fence))
                                   body (remove str/blank? (subvec lines (inc j) (or close end)))]
                               (when-not (and close (seq body) (every? added-line? body))
                                 (err! (inc j) "new-module-line-not-added"))
                               (recur (if close (inc close) end)))
                             :else (do (err! (inc j) "new-module-line-not-added") (recur (inc j))))))))
                 shown (shown-of in-place)]
             (cond
               ;; A new module proposed does not stand yet: its view adds every line,
               ;; and nothing in it is the module as it stands.
               (and new? (not review?))
               (do (when (and markdown (not described))
                     (err! number "new-module-without-description"))
                   (doseq [p in-place :when (some (complement str/blank?) (:current p))]
                     (err! (inc (:heading p)) "new-module-line-not-added")))

               :else
               (try
                 (let [file (str/replace (read-module repo path) "\r\n" "\n")]
                   ;; a Markdown module that has a description shows it at the head of its view
                   (when (and markdown (not described) (or new? (module-description file))
                              (not (re-find #"\A---\n" shown)))
                     (err! number (if new? "new-module-without-description" "module-view-without-description")))
                   (when (and view-description
                              (not= (if (and review?
                                             (:signed view-description)
                                             (not (:proposed (by-heading head))))
                                      (:added view-description)
                                      (:current view-description))
                                    (module-description file)))
                     (err! (inc (:line view-description)) "module-description-differs"))
                   (when (not= shown (if markdown (module-body file) (str/replace file #"\n+\z" "")))
                     (cond
                       ;; front matter is never shown raw: its description is a description container
                       (and markdown (re-find #"\A---\n" shown)) (err! number "front-matter-in-module-view")
                       (or front-matter? (and head-open (nil? head-close))) nil
                       :else (err! number "context-module-differs"))))
                 (catch Throwable _ (err! number "context-module-unavailable"))))
             (swap! st update :shown into (range (inc i) end))))]
     (doseq [i (range (count lines))]
       (let [line (lines i)
             number (inc i)
             {:keys [fence in-comment container shown]} @st]
         (cond
           (contains? shown i) nil

           ;; The Questions section is plain prose to the end of the source.
           (:questions @st)
           (let [heading (re-find #"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*\z" line)
                 fence (:fence (:questions @st))]
             (cond
               ;; a refused fence is refused once, at its opener
               fence (when (re-find (fence-close-re (:char fence) (:size fence)) line)
                       (swap! st assoc-in [:questions :fence] nil))
               (and heading (re-find #"(?i)^Proposal\b" (second heading)))
               (err! number "proposal-after-questions")
               heading (err! number "heading-after-questions")
               (re-find #"^::: comment(?:\s|$)" line) (err! number "comment-in-questions")
               (re-find #"^::: shown(?:\s|$)" line) (err! number "shown-in-questions")
               (re-find #"^::: description(?:\s|$)" line) (err! number "description-in-questions")
               (re-find #"^::: dependencies(?:\s|$)" line) (err! number "dependencies-in-questions")
               (re-find #"^::: moved?(?:\s|$)" line) (err! number "move-in-questions")
               (re-find #"^::: proposed(?:\s|$)" line) (err! number "proposed-in-questions")
               (or (change? line) (added-line? line) (removed-line? line))
               (err! number "change-in-questions")
               (re-find #"^ {0,3}(?:`{3,}|~{3,})" line)
               (let [[_ opener] (re-find #"^ {0,3}(`{3,}|~{3,})" line)]
                 (err! number "code-fence-in-questions")
                 (swap! st assoc-in [:questions :fence]
                        {:char (subs opener 0 1) :size (count opener)}))
               (data-figure-line line) (err! number "data-figure-in-questions")
               (re-find #"^ {0,3}>" line) (err! number "quote-block-in-book")))

           container
           (cond
             (= line ":::")
             (do (when (and (= :comment (:kind container)) (not (:content container)))
                   (err! (:line container) "empty-comment-container"))
                 (when (= :shown (:kind container)) (check-shown! container))
                 (when (= :description (:kind container)) (check-description! container))
                 (when (= :dependencies (:kind container)) (check-dependencies! container))
                 (when (= :move (:kind container))
                   (doseq [[k reason] (concat (move-body-word-mark-errors (vec (:lines container)))
                                              (move-added-line-errors (vec (:lines container))))]
                     (err! (+ (:line container) 1 k) reason)))
                 (swap! st assoc :container nil))
             (re-find #"^::: (?:comment|move|moved|shown|description|dependencies)(?:\s|$)" line)
             (err! number "nested-container")
             :else
             (swap! st (fn [s] (-> s
                                    (update-in [:container :lines] (fnil conj []) line)
                                    (cond-> (not (str/blank? line))
                                      (assoc-in [:container :content] true))))))

           fence
           (cond
             (re-find (fence-close-re (:char fence) (:size fence)) line)
             ;; Markdown is never shown in a code block: an unsigned plain-text fence
             ;; is built as <pre>, so Markdown headings in it would show as code.
             (do (when (and (:plain fence)
                            (not-any? change? (:lines fence))
                            (not-any? #(re-find #"^- \S" %) (:lines fence))
                            (some #(re-find #"^#{1,6} \S" %) (:lines fence)))
                   (err! (:line fence) "markdown-in-code-block"))
                 (when (and (= "mermaid" (:language fence))
                            (some signed-fence-line? (:lines fence))
                            (not (coherent-signed-mermaid? (:lines fence))))
                   (err! (:line fence) "mixed-mermaid-change"))
                 (when (and (section) (= "ethos" (:language fence)))
                   (case (ethos-fence-kind (:lines fence))
                     :context-only (err! (:line fence) "ethos-change-changes-nothing")
                     :malformed-fragment (err! (:line fence) "malformed-ethos-change-fragment")
                     nil))
                 (swap! st assoc :fence nil))

             (:code fence)
             (do (swap! st update-in [:fence :lines] conj line)
                 (when (and (section) (re-find #"^[+-] .*\S" line)) (mark-changed!)))

             :else
             (do (swap! st update-in [:fence :lines] conj line)
                 (when (re-find #"^File:" line) (err! number "file-line-in-body"))
                 (when (and (section) (change? line)) (mark-changed!))
                 (when (:diff fence)
                   ;; A nested fence is code rather than module prose.
                   (cond
                     (:inner fence)
                     (if (re-find (re-pattern (str "^" sign-prefix " {0,3}`{3,}\\s*$")) line)
                       (swap! st assoc-in [:fence :inner] false))
                     (re-find (re-pattern (str "^" sign-prefix " {0,3}`{3,}\\s*[^ `]*\\s*$")) line)
                     (swap! st assoc-in [:fence :inner] true)
                     :else
                     (body! line number)))))

           in-comment
           (when (str/includes? line "-->") (swap! st assoc :in-comment false))

           (str/starts-with? (str/triml line) "<!--")
           (swap! st assoc :in-comment (not (str/includes? line "-->")))

           :else
           (let [comment-open? (= line "::: comment")
                 malformed-comment? (re-find #"^::: comment(?:\s|$)" line)
                 shown-open? (= line "::: shown")
                 malformed-shown? (re-find #"^::: shown(?:\s|$)" line)
                 description-open? (= line "::: description")
                 malformed-description? (re-find #"^::: description(?:\s|$)" line)
                 move-open (re-matches #"::: move (proposed|landed)" line)
                 malformed-move? (re-find #"^::: move(?:\s|$)" line)
                 proposed-open? (= line "::: proposed")
                 malformed-proposed? (re-find #"^::: proposed(?:\s|$)" line)
                 opening (re-find (re-pattern (str "^(" sign-prefix ") {0,3}(`{3,}|~{3,})\\s*([^ ]*(?: sketch)?)")) line)
                 opening (when opening [(nth opening 0) (nth opening 2) (nth opening 3) (nth opening 1)])
                 data-figure (data-figure-line line)]
             (cond
               comment-open?
               (do (flush-plain!)
                   (if-not (section)
                     (err! number "comment-outside-proposal")
                     (let [s (section)]
                       (cond (:comment s) (err! number "second-comment-in-proposal")
                             (:changed s) (err! number "comment-after-change"))
                       (set-section! #(assoc % :comment true))))
                   (swap! st assoc :container {:kind :comment :line number :content false :lines []}))

               malformed-comment?
               (do (flush-plain!) (err! number "malformed-comment-container"))

               shown-open?
               (do (flush-plain!)
                   (when-not (section) (err! number "shown-outside-proposal"))
                   (swap! st assoc :container {:kind :shown :line number :content false :lines []}))

               malformed-shown?
               (do (flush-plain!) (err! number "malformed-shown-container"))

               ;; `::: description`: the module's description, at the head of its proposal,
               ;; before any change or module text, once.
               description-open?
               (do (flush-plain!)
                   (if-not (section)
                     (err! number "description-outside-proposal")
                     (let [s (section)]
                       (cond (:description s) (err! number "second-description-in-proposal")
                             (or (:changed s) (:body s)) (err! number "description-not-at-head"))
                       (set-section! #(assoc % :description true))))
                   (swap! st assoc :container {:kind :description :line number :content false :lines []}))

               (= line "::: dependencies")
               (do (flush-plain!)
                   (let [s (section)]
                     (cond
                       (not (:new-markdown s)) (err! number "dependencies-outside-new-module")
                       (:dependencies s) (err! number "second-dependencies-in-proposal")
                       (or (not (:description s)) (:body s)) (err! number "dependencies-not-at-head"))
                     (when s (set-section! #(assoc % :dependencies true))))
                   (swap! st assoc :container {:kind :dependencies :line number :lines []}))

               (re-find #"^::: dependencies(?:\s|$)" line)
               (do (flush-plain!) (err! number "malformed-dependencies-container"))

               malformed-description?
               (do (flush-plain!) (err! number "malformed-description-container"))

               move-open
               (do (flush-plain!)
                   (when (section) (mark-changed!))
                   (swap! st assoc :container {:kind :move :line number :content false :lines []}))

               malformed-move?
               (do (flush-plain!) (err! number "malformed-move-container"))

               ;; A placeholder stands only where a passage left a module view.
               (re-find #"^::: moved(?:\s|$)" line)
               (do (flush-plain!)
                   (err! number "moved-outside-module-view")
                   (swap! st assoc :container {:kind :moved :line number :content false :lines []}))

               ;; `::: proposed`: the first nonblank line under a proposal heading of a
               ;; Review, one line with no closing; the proposal's marks show the single margin.
               proposed-open?
               (do (flush-plain!)
                   (cond (not (section)) (err! number "proposed-outside-proposal")
                         (not= "Review" (:kind @st)) (err! number "proposed-outside-review")
                         (not (every? str/blank? (subvec lines (:line (section)) i)))
                         (err! number "proposed-not-first-in-proposal")))

               malformed-proposed?
               (do (flush-plain!) (err! number "malformed-proposed-marker"))

               (= line ":::")
               (do (flush-plain!) (err! number "unexpected-container-close"))

               data-figure
               (do (flush-plain!)
                   (when (section) (set-section! #(assoc % :body true)))
                   (check-data-figure! number data-figure))

               opening
               (let [language (str/lower-case (nth opening 2))]
                 (flush-plain!)
                 ;; Removed and context fences keep the module’s authored opener.
                 ;; New proposal fences name their language.
                 (when (and (section) (= language "")
                            (not (#{"- " "  "} (nth opening 3))))
                   (err! number "fence-language-required"))
                 ;; Outside a section a fence is allowed as a diagram (an unlabelled
                 ;; sketch or a labelled mermaid figure, a context figure), and in an
                 ;; Account as labelled code, as inside a proposal.
                 (when (and (not (section))
                            (not (and (= "Account" (:kind @st)) (not= language "")))
                            (not (or (= language "mermaid")
                                     (and (= language "") (diagram-fence? lines i opening)))))
                   (err! number "text-outside-proposal-without-module"))
                 (when (section) (set-section! #(assoc % :body true)))
                 ;; A signed opener is a change line, as the fence it opens is read.
                 (when (and (section) (re-find #"^[+-] " (nth opening 3))) (mark-changed!))
                 (swap! st assoc :fence
                        {:char (subs (second opening) 0 1) :size (count (second opening)) :line number
                         :language language
                         ;; A diff fence is prose; only its fenced blocks are code.
                         :diff (= language "diff")
                         :code (not (#{"text" "prose" "markdown" "md" "diff"} language))
                         :plain (boolean (#{"" "text" "txt" "prose"} language))
                         :lines []}))
               :else
               ;; Inside a proposal, a line prefixed "  ", "+ " or "- " is module content, never book structure.
               (let [heading (when-not (and (section) (re-find #"^(?:  |\+ |- )" line))
                               (re-find #"^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*\z" line))]
                 (if heading
                   (let [level (count (nth heading 1)) text (nth heading 2)]
                     (when (and (section) (<= level (:level (section)))) (finish!))
                     (cond
                       (and (= level 4) (re-find #"^Proposal\b" text))
                       (do (finish!) (err! number "in-place-proposal-outside-module-view"))

                       ;; The closing Questions section of every kind of page: after the
                       ;; last proposal or module view, to the end. An Account may close
                       ;; its own prose with it, proposals or none.
                       (re-find questions-heading line)
                       (do (finish!)
                           (cond
                             (:questions @st) (err! number "heading-after-questions")
                             (and (zero? (:proposals @st)) (not= "Account" (:kind @st)) (not module-review?))
                             (err! number "questions-without-proposal"))
                           (swap! st update :questions #(or % {:line number})))

                       (re-find #"(?i)^Proposal\b" text)
                       (do (finish!)
                           (if (re-find located text)
                             (let [location (re-matches single-proposal-location text)]
                               (swap! st #(-> % (update :proposals inc)
                                              (assoc :section {:line number :level level :changed false
                                                               :deleted (str/ends-with? text ", a deleted file")
                                                               ;; the new file is the heading's last location
                                                               :new-markdown (and (str/ends-with? text ", a new file")
                                                                                  (boolean (re-find #"\.md, a new file\z" text)))
                                                               :repository (nth location 1 nil)
                                                               :path (nth location 2 nil)
                                                               :comment false}))))
                             (err! number "proposal-heading-without-location")))

                       (not (section))
                       (let [account? (= "Account" (:kind @st))
                             view (when (= level 2) (module-view-heading (str "## " text)))]
                         (cond
                           view
                           (handle-module-view! i number view)

                           (and (= level 1) (not (re-find module-location text))
                                (not (:title @st)) (zero? (:proposals @st)) (empty? (:shown @st)))
                           (swap! st assoc :title true)

                           ;; An account holds headings of its own.
                           account? nil

                           :else (err! number "heading-outside-proposal-without-module")))))
                   (do
                     (when (and (not (section)) (str/blank? line) (= :open (:signature @st)))
                       (swap! st assoc :signature :done))
                     (when (and (not (section)) (not (str/blank? line)))
                       (cond
                         (and (not (:presentation @st)) (not (:title @st)) (zero? (:proposals @st))
                              (empty? (:shown @st)) (re-find #"^Presentation\.\{ «[^»]*»(?: \w+)? \}\z" line))
                         (let [kind (second (re-find #"»(?: (\w+))? \}\z" line))]
                           (swap! st assoc :presentation true :kind (or kind "Proposal"))
                           (when-not (#{"Proposal" "Review" "Account"} (or kind "Proposal"))
                             (err! number "presentation-kind-unknown")))

                         ;; The signature line: the paragraph under the title naming the creating
                         ;; model and each helper's help.
                         (or (= :open (:signature @st))
                             (and (:title @st) (nil? (:signature @st)) (zero? (:proposals @st))
                                  (empty? (:shown @st)) (str/starts-with? line "Created by")))
                         (swap! st #(-> % (assoc :signature :open)
                                        (update :subtitle (fn [subtitle] {:line (or (:line subtitle) number)
                                                                          :text (str/join " " (remove nil? [(:text subtitle) line]))}))))

                         ;; An account holds prose of its own.
                         (= "Account" (:kind @st)) nil

                         :else (err! number "text-outside-proposal-without-module")))
                     (when (re-find #"^ {0,3}>" line) (err! number "quote-block-in-book"))
                     (when (and (section) (str/starts-with? line "**Comment**"))
                       (err! number "comment-container-required"))
                     (body! line number)))))))))
     (finish!)
     (check-moves!)
     (when-let [{:keys [line text]} (:subtitle @st)]
       (when-not (subtitle-fits? text) (err! line "subtitle-too-long")))
     (when-let [c (:container @st)]
       (err! (:line c) (str "unclosed-" (name (:kind c)) "-container")))
     (when (and (zero? (:proposals @st)) (not= "Account" (:kind @st)) (not module-review?)) (err! 1 "missing-proposal"))
     (when-let [f (:fence @st)] (err! (:line f) "unclosed-code-fence"))
     (vec (sort-by :line (:errors @st))))))

(defn- change-block-end
  "The end offset of a change div, code line or table row, including nested spans."
  [html start]
  (let [tag (second (re-find #"^<(div|span|tr)\b" (subs html start)))
        matcher (re-matcher (re-pattern (str "(?is)</?" tag "\\b[^>]*>")) html)]
    (.region matcher start (count html))
    (loop [depth 0]
      (when (.find matcher)
        (let [depth (+ depth (if (str/starts-with? (str/lower-case (.group matcher)) "</") -1 1))]
          (if (zero? depth) (.end matcher) (recur depth)))))))

(defn- visible-block-text [html]
  (-> html (str/replace #"<[^>]*>" " ")
      (str/replace "&amp;" "&") (str/replace "&lt;" "<") (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'")
      (str/replace #"\s+" " ") str/trim))

(defn- change-run
  "Consecutive change blocks of one sign, and the offset immediately after them."
  [html start head]
  (loop [offset start texts []]
    (let [space (re-find #"^\s*" (subs html offset))
          next (+ offset (count space))]
      (if (str/starts-with? (subs html next) head)
        (if-let [end (change-block-end html next)]
          (recur end (conj texts (visible-block-text (subs html next end))))
          {:end offset :text (str/join " " texts)})
        {:end offset :text (str/join " " texts)}))))

(defn repeated-replacement-errors
  "Adjacent removed/added runs that repeat most of both passages' words: the
  shared words exceed half of the longer run's. A short removed sentence absorbed
  into a much longer added passage is not a repeat. Covers prose blocks, code
  lines and table rows; names both passages."
  [html]
  (let [matcher (re-matcher #"<(?:div class=\"del\"|span class=\"ln ln-del\"|tr class=\"row-del\")>" html)
        words #(frequencies (re-seq #"[\p{L}\p{N}]+" (str/lower-case %)))]
    (loop [offset 0 out []]
      (if-not (.find matcher offset)
        out
        (let [start (.start matcher)
              removed-head (.group matcher)
              added-head (str/replace removed-head "del\"" "add\"")
              removed-run (change-run html start removed-head)
              added-run (change-run html (:end removed-run) added-head)
              removed (:text removed-run) added (:text added-run)
              old (words removed) new (words added)
              shared (reduce + 0 (map (fn [[word n]] (min n (get new word 0))) old))
              longer (max (reduce + 0 (vals old)) (reduce + 0 (vals new)))]
          (recur (max (inc start) (:end added-run))
                 (cond-> out
                   (and (pos? longer) (> (* 2 shared) longer))
                   (conj {:line (inc (count (re-seq #"\n" (subs html 0 start))))
                          :reason "replaced-line-printed-twice"
                          :removed removed :added added}))))))))

(defn check-built-page
  "Built HTML -> typed structural errors. Document chrome belongs to the page,
  never to an authenticated module article. A module's own level-one heading is
  rendered as module structure rather than as another page head."
  [html]
  (loop [lines (map-indexed vector (str/split-lines html))
         in-module false
         errors []]
    (if-let [[index line] (first lines)]
      (let [opened (str/includes? line "<article class=\"module diff\">")
            here (or in-module opened)
            bad (when here
                  (cond
                    (str/includes? line "<meta ") "meta"
                    (str/includes? line "<title>") "title"
                    (str/includes? line "<h1 class=\"book-title\">") "h1.book-title"))
            closed (and here (str/includes? line "</article>"))]
        (recur (next lines) (and here (not closed))
               (cond-> errors bad (conj {:line (inc index)
                                         :reason "book-heading-inside-module-view"
                                         :element bad}))))
      (vec (sort-by :line (into errors (repeated-replacement-errors html)))))))

(defn main [args]
  (cond
    (or (not= 1 (count args)) (not (fs/absolute? (first args))))
    (do (binding [*out* *err*]
          (println (json/generate-string {:errors [{:reason "one-absolute-source-path-required"}]})))
        2)
    :else
    (let [source (try (slurp (first args)) (catch Exception _ nil))]
      (if (nil? source)
        (do (binding [*out* *err*]
              (println (json/generate-string {:errors [{:reason "source-unreadable"}]})))
            2)
        (let [errors (check-book source {:source-path (first args)})]
          (if (seq errors)
            (do (binding [*out* *err*] (println (json/generate-string {:errors errors}))) 1)
            (do (println (json/generate-string {:checked true :source (first args)})) 0)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (main *command-line-args*)))
