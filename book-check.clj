;; Structural check of a book source: `bb book-check.clj /abs/SOURCE.md`.
;; Ported from book-check.mjs. check-book: source text -> vector of
;; {:line n :reason "..."} errors, sorted by line. This checks source
;; structure, not the merits of a proposed crystallization.
(ns book-check
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; A proposal names one location, or two locations joined by ", and", in its heading.
;; A new/deleted-file suffix belongs to the final location.
(def located #"^Proposal \d+: (?:.+ · )?[^\s›·]+ › [^\s,]+(?:, and [^\s›·]+ › [^\s,]+)?(?:, a (?:new|deleted) file)?\z")
(def single-proposal-location
  #"^Proposal \d+: (?:.+ · )?([^\s›·]+) › ([^\s,]+)(?:, a (?:new|deleted) file)?\z")
;; A removal is a change: removed lines, or the whole file deleted.
(defn change? [line]
  (boolean (or (re-find #"^[+-] .*\S" line)
               (re-find #"^\*\*(?:Archive|Destroy)\*\*" line))))

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
(def context-module #"^(?:spirit|intent|vision|knowledge|notion|compensation|inquiry|role|raw)/[A-Za-z0-9][A-Za-z0-9_.-]*(?:/[0-9]+)?\.md\z")
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
;; The closing Questions section of a Proposal, Review or Account page opens at
;; this heading and runs, plain prose, to the end of the source.
(def questions-heading #"^## Questions\s*\z")
;; The renderer recognizes module views only as level-two headings, and
;; proposal headings only at levels two and three.
(def next-section #"^ {0,3}(?:##\s+(?:Proposal\b|Questions\s*\z|[A-Za-z0-9][A-Za-z0-9_.-]* › [^\s,›·]+\s*\z)|###\s+Proposal\b)")

(defn read-context-module
  "A file of one of the living's repositories as origin main has it; a context
  repository gives only its authored modules, never a generated skill."
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
    (when (or (not (re-find #"^[a-f0-9]{40}\z" (or revision "")))
              (not (re-find #"^100(?:644|755) blob " (git "ls-tree" revision "--" relative-path))))
      (throw (ex-info "module-source-unavailable" {})))
    (git "show" (str revision ":" relative-path))))

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

(defn- contains-lines?
  "Whether needle is one exact contiguous excerpt of lines."
  [haystack needle]
  (and (seq needle)
       (some #(= needle (subvec haystack % (+ % (count needle))))
             (range (inc (- (count haystack) (count needle)))))))

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

(defn moved-source-line
  "A moved line as its source module holds it: removed words kept, added words
  left out, and the space an added word stood between written once."
  [line]
  (let [m (re-matcher #"\[-(.+?)-\]|\[\+(.+?)\+\]" (code-spans-held line))
        sb (StringBuilder.)]
    (loop [pos 0]
      (if (.find m)
        (do (.append sb (subs line pos (.start m)))
            (.append sb (if (.group m 1) (subs line (.start m 1) (.end m 1)) "\u0000"))
            (recur (.end m)))
        (.append sb (subs line pos))))
    (-> (str sb)
      (str/replace #" \u0000+ " " ")
      (str/replace #"^\u0000+ | \u0000+$" "")
      (str/replace #" \u0000+(?=[^\p{L}\p{N}\s`*\[(])" "")
      (str/replace "\u0000" ""))))

;; ---------------------------------------------------------- in place
;;
;; A proposal shown in place stands inside a module view, at the place its
;; edit lands. Its level-four heading names the view's module; a move names
;; the module it leaves and the module it enters. Signed lines are the edit:
;; removals reconstruct the current module and additions do not. Thus the
;; surrounding unsigned text plus every removed line is the module as it
;; stands, to the character. A move container reconstructs its body only in
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

(defn in-place-proposal
  "The in-place proposal whose heading is line h of the module view of
  [repository path] ending before line e: {:heading h :end k :number :title
  :comment lines :current lines :errors [[index reason]]}, k being the line
  where the module resumes, :proposed the index of its `::: proposed` line or
  nil, and :current the lines this edit contributes to the module as it stands."
  [lines h e [repository path]]
  (let [heading (re-matches in-place-heading (lines h))
        [_ _ number title heading-repository heading-path to-repository to-path] heading
        locations (cond-> [] heading-repository (conj [heading-repository heading-path])
                          to-repository (conj [to-repository to-path]))
        ;; A proposal in place declares itself proposed with `::: proposed`, the
        ;; first nonblank line under its heading: the index of that line, or nil.
        proposed (first (filter #(not (str/blank? (lines %))) (range (inc h) e)))
        proposed (when (and proposed (= "::: proposed" (lines proposed))) proposed)
        start (if proposed (inc proposed) (inc h))
        ;; its description container, read, with the index of its opening line
        description (volatile! nil)
        errors (cond-> []
                 (nil? heading) (conj [h "proposal-heading-without-location"])
                 (and heading (not (some #{[repository path]} locations)))
                 (conj [h "in-place-proposal-elsewhere"]))]
    (loop [j start comment nil changed false current [] move nil errors errors]
      (let [done (fn [k]
                   {:heading h :end k :number number :title title :comment comment :proposed proposed
                    :locations locations :current current :move move :description @description
                    :errors (cond-> errors
                              (not changed) (conj [h "no-change-in-proposal"])
                              (and (= 2 (count locations)) (nil? move))
                              (conj [h "move-container-required"]))})]
        (if (>= j e)
          (done e)
          (let [l (lines j)
                fence (re-find #"^(`{3,}|~{3,})\s*(\S*)\s*\z" l)]
            (cond
              (str/blank? l) (recur (inc j) comment changed current move errors)
              (added-line? l) (recur (inc j) comment true current move errors)
              (removed-line? l) (recur (inc j) comment true (conj current (unsigned-change-line l)) move errors)
              (context-line? l) (recur (inc j) comment changed (conj current (subs l 2)) move errors)
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
                  (recur (inc close) (or comment body) changed current move errors)
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
                  (recur (inc close) comment (or changed (boolean (:signed read))) current move errors)
                  (done e)))

              (= l "::: move proposed")
              (let [close (first (filter #(= ":::" (lines %)) (range (inc j) e)))
                    body (if close (subvec lines (inc j) close) [])
                    from (some->> (first body) (re-matches #"from: ([^,]+ › [^,]+)(?:,.*)?") second)
                    to (some->> (second body) (re-matches #"to: ([^,]+ › [^,]+)(?:,.*)?") second)
                    ;; the passage as its source module holds it, any edit made on its way undone
                    content (if (and (>= (count body) 4) (= "" (nth body 2)))
                              (mapv moved-source-line (move-passage-lines (subvec body 3))) [])
                    from-location (some-> from (str/split #" › " 2) vec)
                    to-location (some-> to (str/split #" › " 2) vec)
                    valid? (and close from to (seq content)
                                (= locations [from-location to-location])
                                (= [repository path] from-location))
                    ;; A move container immediately after a nonblank removal is
                    ;; the remainder of that current source line. A blank line
                    ;; before the container makes it a whole line of its own.
                    joins-previous? (and (> j h)
                                         (removed-line? (lines (dec j)))
                                         (not (str/blank? (unsigned-change-line (lines (dec j))))))
                    errors (cond-> errors
                             (nil? close) (conj [j "unclosed-move-container"])
                             (not valid?) (conj [j "malformed-in-place-move"])
                             move (conj [j "second-move-in-proposal"]))
                    errors (into errors (map (fn [[k reason]] [(+ j 1 k) reason]))
                                 (concat (move-body-word-mark-errors body)
                                         (move-added-line-errors body)))]
                (if close
                  (recur (inc close) comment true
                         (if (and valid? joins-previous? (seq current))
                           (into (conj (pop current)
                                       (str (peek current) " " (first content)))
                                 (rest content))
                           (if valid? (into current content) current))
                         (or move [from to]) errors)
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
                        kept (keep #(when (or (removed-line? %) (context-line? %))
                                      (unsigned-change-line %)) body)
                        current' (if (seq kept)
                                   (into current (concat [(str opener language)] kept [opener]))
                                   current)]
                    (if close (recur (inc close) comment true current' move errors) (done e)))
                  (done j)))

              :else (done j))))))))

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
  [lines start end location]
  (loop [j start fence nil out []]
    (if (>= j end)
      out
      (let [l (lines j)]
        (cond
          fence (recur (inc j) (when-not (re-find (plain-fence-close-re (:char fence) (:size fence)) l) fence) out)
          (re-find in-place-start l) (let [p (in-place-proposal lines j end location)]
                                       (recur (:end p) nil (conj out p)))
          :else (let [open (re-find #"^ {0,3}(`{3,}|~{3,})" l)]
                  (recur (inc j) (when open {:char (subs (second open) 0 1) :size (count (second open))}) out)))))))

(defn source-in-place-proposals
  "Every in-place proposal of a source, keyed by the index of its heading line."
  [source]
  (let [lines (vec (str/split (str/replace source "\r\n" "\n") #"\n" -1))]
    (loop [i 0 fence nil out {}]
      (if (>= i (count lines))
        out
        (let [l (lines i)
              view (when-not fence (re-matches #"## ([^\s›·]+) › ([^\s,›·]+)\s*" l))]
          (cond
            fence (recur (inc i) (when-not (re-find (fence-close-re (:char fence) (:size fence)) l) fence) out)
            (and view (valid-location? (nth view 1) (nth view 2)))
            (let [end (module-view-end lines i)]
              (recur end nil (into out (map (juxt :heading identity)
                                            (in-place-proposals lines (inc i) end [(nth view 1) (nth view 2)])))))
            :else
            (let [open (re-find (re-pattern (str "^" sign-prefix " {0,3}(`{3,}|~{3,})")) l)]
              (recur (inc i) (when open {:char (subs (second open) 0 1) :size (count (second open))}) out))))))))

(defn check-book
  "Source text -> vector of {:line n :reason \"...\"}. Option :read-module is
  (fn [repository path]) -> file text; it throws when the module is unavailable."
  ([source] (check-book source {}))
  ([source {:keys [read-module] :or {read-module read-context-module}}]
   (let [lines (vec (str/split (str/replace source "\r\n" "\n") #"\n" -1))
         st (atom {:errors [] :fence nil :section nil :in-comment false :container nil :plain nil
                   :proposals 0 :title false :presentation false :kind "Proposal" :signature nil :shown #{}})
         module-cache (atom {})
         err! (fn [line reason] (swap! st update :errors conj {:line line :reason reason}))
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
                   (not (contains-lines? source excerpt)) (err! line "shown-module-text-absent"))))))
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
                 (when (and (section)
                            (not (:deleted (section)))
                            (not (str/blank? line))
                            (not (change? line))
                            (not (str/starts-with? line "  "))
                            (not (str/starts-with? line "**Comment**"))
                            (not (re-find #"^(?:File:|Ruling(?::|,)|\d+\. |\*\*(?:Archive|Destroy)\*\*)" line)))
                   (plain! line number))
                 (when (str/blank? line) (flush-plain!)))
         handle-module-view!
         (fn [i number heading-text [_ repo path]]
           (let [end (module-view-end lines i)
                 in-place (in-place-proposals lines (inc i) end [repo path])
                 _ (doseq [p in-place [index reason] (:errors p)] (err! (inc index) reason))
                 _ (doseq [p in-place :when (and (:proposed p) (not= "Review" (:kind @st)))]
                     (err! (inc (:proposed p)) "proposed-outside-review"))
                 _ (swap! st update :proposals + (count in-place))
                 ;; The module as it stands: unsigned view lines, with each edit
                 ;; replaced by the current lines it carries (its removals, or a
                 ;; moved passage at the source).
                 by-heading (into {} (map (juxt :heading identity) in-place))
                 ;; The view's description stands at its head, its first nonblank line:
                 ;; a container as it stands, or one changed by a proposal in place there.
                 head (first (filter #(not (str/blank? (lines %))) (range (inc i) end)))
                 head-open (and head (= "::: description" (lines head)))
                 head-close (when head-open (first (filter #(= ":::" (lines %)) (range (inc head) end))))
                 _ (when (and head-open (nil? head-close)) (err! (inc head) "unclosed-description-container"))
                 head-read (when head-close
                             (let [read (description-sides (subvec lines (inc head) head-close))]
                               (doseq [r (:errors read)] (err! (inc head) r))
                               (when (:signed read) (err! (inc head) "description-change-outside-proposal"))
                               (when (and (empty? (:errors read)) (not (:signed read)))
                                 (assoc read :line head))))
                 head-proposal-read (when-let [d (:description (by-heading head))]
                                      (when (empty? (:errors d)) d))
                 view-description (or head-read head-proposal-read)
                 described (or head-open (boolean (:description (by-heading head))))
                 placed (into (if head-open #{head} #{})
                              (keep #(some-> % :description :line) in-place))
                 markdown (str/ends-with? path ".md")
                 _ (doseq [p in-place :when (and (:description p) (not= head (:heading p)))]
                     (err! (inc (:line (:description p))) "description-not-at-head"))
                 book-text (unfenced-indices lines (inc i) end)
                 _ (doseq [k book-text]
                     (cond
                       (and (= "::: description" (lines k)) (not (placed k)))
                       (err! (inc k) "description-not-at-head")
                       (and markdown (front-matter-line? (lines k)))
                       (err! (inc k) "description-as-front-matter")))
                 front-matter? (some #(and markdown (front-matter-line? (lines %))) book-text)
                 raw-lines (loop [j (inc i) out []]
                             (cond
                               (>= j end) out
                               (and head-close (= j head)) (recur (inc head-close) out)
                               (by-heading j) (recur (:end (by-heading j)) (into out (:current (by-heading j))))
                               :else (recur (inc j) (conj out (lines j)))))
                 raw (str/join "\n" raw-lines)
                 shown (str/trim raw)
                 ;; A markdown module may sit in a markdown or text fence; any other file is the
                 ;; content of its one fence, in its language, kept to the character.
                 literal (if markdown
                           (re-find #"^(`{3,}|~{3,})(?:markdown|md|text)\s*\n((?s:.*))\n\1\z" shown)
                           (re-find #"^(`{3,}|~{3,})[^\n`]*\n((?s:.*))\n\1\z" (str/trim raw)))
                 shown (if literal (nth literal 2) shown)]
             (try
               (let [file (str/replace (read-module repo path) "\r\n" "\n")]
                 ;; a Markdown module that has a description shows it at the head of its view
                 (when (and markdown (not described) (module-description file)
                            (not (re-find #"\A---\n" shown)))
                   (err! number "module-view-without-description"))
                 (when (and view-description
                            (not= (:current view-description) (module-description file)))
                   (err! (inc (:line view-description)) "module-description-differs"))
                 (when (not= shown (if markdown (module-body file) (str/replace file #"\n+\z" "")))
                   (cond
                     ;; front matter is never shown raw: its description is a description container
                     (and markdown (re-find #"\A---\n" shown)) (err! number "front-matter-in-module-view")
                     (or front-matter? (and head-open (nil? head-close))) nil
                     :else (err! number "context-module-differs"))))
               (catch Throwable _ (err! number "context-module-unavailable")))
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
               (re-find #"^::: move(?:\s|$)" line) (err! number "move-in-questions")
               (re-find #"^::: proposed(?:\s|$)" line) (err! number "proposed-in-questions")
               (or (change? line) (added-line? line) (removed-line? line))
               (err! number "change-in-questions")
               (re-find #"^ {0,3}(?:`{3,}|~{3,})" line)
               (let [[_ opener] (re-find #"^ {0,3}(`{3,}|~{3,})" line)]
                 (err! number "code-fence-in-questions")
                 (swap! st assoc-in [:questions :fence]
                        {:char (subs opener 0 1) :size (count opener)}))
               (re-find #"^ {0,3}>" line) (err! number "quote-block-in-book")))

           container
           (cond
             (= line ":::")
             (do (when (and (= :comment (:kind container)) (not (:content container)))
                   (err! (:line container) "empty-comment-container"))
                 (when (= :shown (:kind container)) (check-shown! container))
                 (when (= :description (:kind container)) (check-description! container))
                 (when (= :move (:kind container))
                   (doseq [[k reason] (concat (move-body-word-mark-errors (vec (:lines container)))
                                              (move-added-line-errors (vec (:lines container))))]
                     (err! (+ (:line container) 1 k) reason)))
                 (swap! st assoc :container nil))
             (re-find #"^::: (?:comment|move|shown|description)(?:\s|$)" line)
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
                 opening (re-find (re-pattern (str "^(" sign-prefix ") {0,3}(`{3,}|~{3,})\\s*([^ ]*)")) line)
                 opening (when opening [(nth opening 0) (nth opening 2) (nth opening 3) (nth opening 1)])]
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

               malformed-description?
               (do (flush-plain!) (err! number "malformed-description-container"))

               move-open
               (do (flush-plain!)
                   (when (section) (mark-changed!))
                   (swap! st assoc :container {:kind :move :line number :content false :lines []}))

               malformed-move?
               (do (flush-plain!) (err! number "malformed-move-container"))

               ;; `::: proposed`: the first nonblank line under a proposal heading of a
               ;; Review, one line with no closing; the proposal's marks show in the light shade.
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

               opening
               (let [language (str/lower-case (nth opening 2))]
                 (flush-plain!)
                 ;; Every proposal code fence names its language.  Unlabelled
                 ;; fences remain the authored diagram form outside proposals.
                 (when (and (section) (= language ""))
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
                             (and (zero? (:proposals @st)) (not= "Account" (:kind @st)))
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
                             location (when (= level 2) (re-find module-location text))]
                         (cond
                           (and location (valid-location? (nth location 1) (nth location 2)))
                           (handle-module-view! i number text location)

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
                         (swap! st assoc :signature :open)

                         ;; An account holds prose of its own.
                         (= "Account" (:kind @st)) nil

                         :else (err! number "text-outside-proposal-without-module")))
                     (when (re-find #"^ {0,3}>" line) (err! number "quote-block-in-book"))
                     (when (and (section) (str/starts-with? line "**Comment**"))
                       (err! number "comment-container-required"))
                     (body! line number)))))))))
     (finish!)
     (when-let [c (:container @st)]
       (err! (:line c) (str "unclosed-" (name (:kind c)) "-container")))
     (when (and (zero? (:proposals @st)) (not= "Account" (:kind @st))) (err! 1 "missing-proposal"))
     (when-let [f (:fence @st)] (err! (:line f) "unclosed-code-fence"))
     (vec (sort-by :line (:errors @st))))))

(defn- div-end
  "The end offset of the div starting at start, including nested divs."
  [html start]
  (let [matcher (re-matcher #"(?is)</?div\b[^>]*>" html)]
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

(defn repeated-replacement-errors
  "Adjacent removed/added blocks that repeat most of either passage's words.
  The diagnostic names both passages, not merely the enclosing page."
  [html]
  (let [matcher (re-matcher #"<div class=\"del\">" html)
        words #(frequencies (re-seq #"[\p{L}\p{N}]+" (str/lower-case %)))]
    (loop [out []]
      (if-not (.find matcher)
        out
        (let [start (.start matcher)
              end (div-end html start)
              next-open (when end (re-find #"\A\s*<div class=\"add\">" (subs html end)))
              added-start (when next-open (+ end (str/index-of next-open "<div")))
              added-end (when added-start (div-end html added-start))
              removed (when end (visible-block-text (subs html start end)))
              added (when added-end (visible-block-text (subs html added-start added-end)))
              old (words (or removed "")) new (words (or added ""))
              shared (reduce + 0 (map (fn [[word n]] (min n (get new word 0))) old))
              shorter (min (reduce + 0 (vals old)) (reduce + 0 (vals new)))]
          (recur (cond-> out
                   (and (pos? shorter) (> (* 2 shared) shorter))
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
        (let [errors (check-book source)]
          (if (seq errors)
            (do (binding [*out* *err*] (println (json/generate-string {:errors errors}))) 1)
            (do (println (json/generate-string {:checked true :source (first args)})) 0)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (main *command-line-args*)))
