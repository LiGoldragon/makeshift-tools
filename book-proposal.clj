;; A proposal is a directory: a manifest and the edited copies of the modules it changes.
;; The writer copies each module as it stands on origin main of its repository, edits the
;; copies, and gives the publisher the directory. This file holds the manifest, the copy,
;; replace and land commands the writer and the lander run, and the reading of a directory
;; into the book source the builder shows: each module whole under its name, every change
;; marked in place, a passage that leaves one copy and stands in another shown once as moved.
(ns book-proposal
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-directory (str (fs/parent (fs/absolutize *file*))))
(when-not (find-ns 'book-check) (load-file (str (fs/path script-directory "book-check.clj"))))

;; The repositories whose authored modules are named by `<type>:<topic>:<version>`.
(def context-repositories ["psyche-belief" "mind-belief" "field-belief"])
;; The directory holding one checkout of each repository, and the remote branch read.
(def repository-root "/git/github.com/LiGoldragon")
(def remote-name "origin")
(def branch-name "main")
;; The manifest's file name in a proposal directory.
(def manifest-file "manifest.datom")
;; The fewest characters a paragraph holds to be seen as a passage that moved.
(def move-minimum-characters 24)
;; The title every change of a module shows under in the source the builder reads.
(def change-title "Change")

(defn refuse [message] (throw (ex-info message {})))

;; ------------------------------------------------------------------ git

(defn- git
  "Run git in a directory; the output, or nil when git refuses."
  [directory & arguments]
  (let [result (deref (process/process (into ["git" "-C" (str directory)] arguments)
                                       {:out :string :err :string :in ""})
                      60000 ::timeout)]
    (when (and (not= result ::timeout) (zero? (:exit result)))
      (:out result))))

(defn- git! [directory & arguments]
  (or (apply git directory arguments)
      (refuse (str "git " (str/join " " arguments) " failed in " directory))))

(defn repository-directory [root repository] (str (fs/path root repository)))

(defn remote-revision
  "The commit the remote branch of the repository stands at now."
  [root repository]
  (let [directory (repository-directory root repository)]
    (git! directory "fetch" "--quiet" remote-name branch-name)
    (str/trim (git! directory "rev-parse" "FETCH_HEAD"))))

(defn text-at
  "The text of a file at a revision of the repository, or nil when it stands nowhere there."
  [root repository revision path]
  (git (repository-directory root repository) "show" (str revision ":" path)))

;; ------------------------------------------------------------- manifest
;;
;; One datom in the proposal directory:
;;   Manifest.{ «Title» Proposal Creator
;;   [ Changed.{ «repository» «path» «revision» }
;;     New.{ «repository» «path» }
;;     Removed.{ «repository» «path» «revision» } ] }

(def entry-states #{:changed :new :removed})
(def state-names {:changed "Changed" :new "New" :removed "Removed"})
(def kinds ["Proposal" "Review"])

(defn manifest-text [{:keys [title kind creator entries]}]
  (str "Manifest.{ «" title "» " kind " " creator "\n"
       "[ " (str/join "\n  " (for [{:keys [state repository path revision]} entries]
                               (str (state-names state) ".{ «" repository "» «" path "»"
                                    (when revision (str " «" revision "»")) " }")))
       " ] }\n"))

(defn- tokens [text]
  (re-seq #"«[^»]*»|[A-Za-z][A-Za-z0-9]*\.(?=[{\[])|[\[\]{}]|[^\s\[\]{}«»]+" text))

(defn- read-forms
  "The forms of a token list up to the closer that ends it: a string, a bare word,
  [:variant name forms] or [:group forms]."
  [token-list]
  (loop [remaining token-list forms []]
    (if-let [token (first remaining)]
      (cond
        (#{"]" "}"} token) [forms (rest remaining)]
        (#{"[" "{"} token) (let [[inside after] (read-forms (rest remaining))]
                             (recur after (conj forms [:group inside])))
        (re-matches #"[A-Za-z][A-Za-z0-9]*\." token)
        (let [opener (second remaining)
              [inside after] (read-forms (drop 2 remaining))]
          (when-not (#{"[" "{"} opener) (refuse (str "Manifest: " token " is followed by neither { nor [.")))
          (recur after (conj forms [:variant (subs token 0 (dec (count token))) inside])))
        (str/starts-with? token "«") (recur (rest remaining) (conj forms (subs token 1 (dec (count token)))))
        :else (recur (rest remaining) (conj forms token)))
      [forms nil])))

(defn read-manifest
  "The manifest a text holds: {:title :kind :creator :entries [{:state :repository :path :revision}]}."
  [text]
  (let [[forms rest-tokens] (read-forms (tokens text))
        [tag name inside] (first forms)]
    (when-not (and (= 1 (count forms)) (nil? rest-tokens) (= [:variant "Manifest"] [tag name]))
      (refuse "Manifest: one Manifest.{ ... } datom is expected."))
    (let [[title kind creator entries] inside
          entries (when (= :group (first entries)) (second entries))]
      (when-not (and (string? title) (some #{kind} kinds) entries)
        (refuse "Manifest: Manifest.{ «Title» Proposal|Review Creator [ entries ] } is expected."))
      (when-not (some #{creator} book-check/subtitle-models)
        (refuse (str "Manifest: the creator is one of " (str/join ", " book-check/subtitle-models) ".")))
      {:title title :kind kind :creator creator
       :entries (vec (for [[_ state-name fields] entries
                           :let [state (some (fn [[state name]] (when (= name state-name) state)) state-names)
                                 [repository path revision] fields]]
                       (do (when-not (and state (string? repository) (string? path)
                                          (= (if (= state :new) 2 3) (count fields)))
                             (refuse (str "Manifest: an entry is Changed.{ «repository» «path» «revision» }, "
                                          "New.{ «repository» «path» } or Removed.{ «repository» «path» «revision» }.")))
                           (cond-> {:state state :repository repository :path path}
                             revision (assoc :revision revision)))))})))

(defn manifest-path [directory] (str (fs/path directory manifest-file)))

(defn read-directory-manifest [directory]
  (let [path (manifest-path directory)]
    (when-not (fs/exists? path) (refuse (str "No manifest at " path)))
    (read-manifest (slurp path :encoding "UTF-8"))))

(defn- write-directory-manifest! [directory manifest]
  (spit (manifest-path directory) (manifest-text manifest) :encoding "UTF-8"))

(defn set-creator!
  "Name the creating model in an existing proposal directory, whose manifest may be of the
  older form with no creator. The copies are not touched."
  [directory creator]
  (when-not (some #{creator} book-check/subtitle-models)
    (refuse (str "Creator is one of " (str/join ", " book-check/subtitle-models) ", not " creator)))
  (let [text (slurp (manifest-path directory) :encoding "UTF-8")
        headed (str/replace-first text #"^(Manifest\.\{ «[^»]*» (?:Proposal|Review))(?: [A-Za-z]+)?(?=\n)"
                                  (str "$1 " creator))]
    (write-directory-manifest! directory (read-manifest headed))
    creator))

(defn copy-path
  "Where the copy of an entry's module lies in the proposal directory."
  [directory {:keys [repository path]}] (str (fs/path directory repository path)))

(defn- clean-title [title]
  (when (or (str/blank? title) (re-find #"[\r\n«»]" title))
    (refuse "Title must be one nonempty line without guillemets."))
  title)

;; ------------------------------------------------------ copy, new, remove

(defn find-module
  "The repository, path, revision and text of the module a name gives, which stands in
  exactly one context repository on its remote branch; or of `repository › path`."
  [root specification revisions]
  (let [revision-of (fn [repository]
                      (or (get @revisions repository)
                          (let [revision (remote-revision root repository)]
                            (swap! revisions assoc repository revision)
                            revision)))
        [_ explicit-repository explicit-path] (re-matches book-check/module-location specification)
        path (or explicit-path (book-check/module-path specification))]
    (when-not path (refuse (str "Not a module name, like vision:belief-modules:0, nor repository › path: " specification)))
    (let [candidates (if explicit-repository [explicit-repository] context-repositories)
          _ (when (and explicit-repository (not (book-check/valid-location? explicit-repository path)))
              (refuse (str "Invalid module location: " specification)))
          found (vec (for [repository candidates
                           :let [revision (revision-of repository)
                                 text (text-at root repository revision path)]
                           :when text]
                       {:repository repository :path path :revision revision :text text}))]
      (case (count found)
        0 (refuse (str "The module " specification " stands in none of " (str/join ", " candidates) " on " branch-name "."))
        1 (first found)
        (refuse (str "The module " specification " stands in several repositories: "
                     (str/join ", " (map :repository found))))))))

(defn- start-manifest
  "The manifest of a proposal directory, made when the directory has none."
  [directory {:keys [title kind creator]}]
  (if (fs/exists? (manifest-path directory))
    (read-directory-manifest directory)
    (do (when-not title (refuse "A new proposal directory needs --title."))
        (when-not creator (refuse "A new proposal directory needs --creator."))
        (fs/create-dirs directory)
        {:title (clean-title title) :kind (or kind "Proposal") :creator creator :entries []})))

(defn- with-options [manifest {:keys [title kind creator]}]
  (cond-> manifest
    title (assoc :title (clean-title title))
    creator (assoc :creator (if (some #{creator} book-check/subtitle-models)
                              creator
                              (refuse (str "Creator is one of " (str/join ", " book-check/subtitle-models) ", not " creator))))
    kind (assoc :kind (if (some #{kind} kinds) kind (refuse (str "Kind is Proposal or Review, not " kind))))))

(defn- same-module? [a b] (and (= (:repository a) (:repository b)) (= (:path a) (:path b))))

(defn- added-entry [manifest entry]
  (when (some #(same-module? % entry) (:entries manifest))
    (refuse (str "The proposal already holds " (:repository entry) " › " (:path entry) ".")))
  (update manifest :entries conj entry))

(defn copy!
  "Copy each module as it stands on the remote branch of its repository into the
  directory, recording the revision it came from. A name is vision:belief-modules:0."
  [directory specifications options root]
  (when (empty? specifications) (refuse "Copy needs at least one module name."))
  (let [revisions (atom {})
        manifest (with-options (start-manifest directory options) options)
        found (mapv #(find-module root % revisions) specifications)
        manifest (reduce (fn [manifest {:keys [repository path revision]}]
                           (added-entry manifest {:state :changed :repository repository :path path :revision revision}))
                         manifest found)]
    (doseq [{:keys [repository path text]} found]
      (let [target (copy-path directory {:repository repository :path path})]
        (fs/create-dirs (fs/parent target))
        (spit target text :encoding "UTF-8")))
    (write-directory-manifest! directory manifest)
    (mapv #(copy-path directory %) found)))

(defn new!
  "Begin a module that does not exist: an empty copy in the directory, to be written."
  [directory repository module-name options root]
  (let [path (or (book-check/module-path module-name) module-name)
        manifest (with-options (start-manifest directory options) options)]
    (when-not (book-check/valid-location? repository path)
      (refuse (str "Invalid module location: " repository " › " path)))
    (when (text-at root repository (remote-revision root repository) path)
      (refuse (str repository " › " path " already stands on " branch-name "; copy it instead.")))
    (let [manifest (added-entry manifest {:state :new :repository repository :path path})
          target (copy-path directory {:repository repository :path path})]
      (fs/create-dirs (fs/parent target))
      (spit target "" :encoding "UTF-8")
      (write-directory-manifest! directory manifest)
      target)))

(defn remove!
  "Name a module as removed by the proposal, at the revision it stands at now."
  [directory specification options root]
  (let [manifest (with-options (start-manifest directory options) options)
        {:keys [repository path revision]} (find-module root specification (atom {}))]
    (write-directory-manifest! directory
                               (added-entry manifest {:state :removed :repository repository :path path :revision revision}))
    (str repository " › " path)))

;; --------------------------------------------------------------- replace

(defn- occurrences [text old]
  (loop [from 0 found []]
    (let [at (str/index-of text old from)]
      (if at (recur (+ at (count old)) (conj found at)) found))))

(defn- manifest-above
  "The directory above a path that holds a manifest, with the path relative to it."
  [path]
  (loop [directory (fs/parent (fs/absolutize path))]
    (cond
      (nil? directory) nil
      (fs/exists? (manifest-path directory)) (str directory)
      :else (recur (fs/parent directory)))))

(defn replace!
  "Replace the one occurrence of old text in a copy by new text. Refused when the old text
  is absent or occurs twice, or when the file is no copy of a proposal directory."
  [path old new]
  (when (empty? old) (refuse "The old text is empty."))
  (let [directory (or (manifest-above path) (refuse (str path " is not in a proposal directory.")))
        manifest (read-directory-manifest directory)
        absolute (str (fs/absolutize path))]
    (when-not (some #(and (not= :removed (:state %)) (= absolute (str (fs/absolutize (copy-path directory %)))))
                    (:entries manifest))
      (refuse (str path " is not a copy the manifest holds.")))
    (let [text (slurp absolute :encoding "UTF-8")
          found (occurrences text old)]
      (case (count found)
        0 (refuse (str "The old text is absent from " path "."))
        1 (do (spit absolute (str (subs text 0 (first found)) new (subs text (+ (first found) (count old)))) :encoding "UTF-8")
              absolute)
        (refuse (str "The old text occurs " (count found) " times in " path "."))))))

;; ------------------------------------------------------------------ land

(defn- temporary-files [contents f]
  (let [directory (fs/create-temp-dir {:prefix "book-proposal-"})]
    (try
      (let [paths (vec (map-indexed (fn [i text]
                                      (let [path (str (fs/path directory (str "file-" i)))]
                                        (spit path text :encoding "UTF-8") path))
                                    contents))]
        (f paths))
      (finally (fs/delete-tree directory)))))

(defn merge-texts
  "The module as it stands, with the difference between the base and the copy applied:
  {:text merged} or {:conflicts n} when part of the difference does not apply."
  [current base copy]
  (temporary-files [current base copy]
                   (fn [[current-path base-path copy-path]]
                     (let [result (deref (process/process ["git" "merge-file" "-p" current-path base-path copy-path]
                                                          {:out :string :err :string :in ""})
                                         60000 ::timeout)]
                       (cond
                         (= result ::timeout) (refuse "git merge-file did not finish.")
                         (neg? (:exit result)) (refuse (str "git merge-file failed: " (:err result)))
                         (pos? (:exit result)) {:conflicts (:exit result)}
                         :else {:text (:out result)})))))

(defn landing
  "What landing a proposal directory does: [{:entry :action :text}] with :action
  :write or :delete or :keep, or a refusal naming every module that does not apply."
  [directory root]
  (let [{:keys [entries]} (read-directory-manifest directory)
        revisions (atom {})
        current-revision (fn [repository]
                           (or (get @revisions repository)
                               (let [revision (remote-revision root repository)]
                                 (swap! revisions assoc repository revision)
                                 revision)))
        results (for [{:keys [state repository path revision] :as entry} entries
                      :let [where (str repository " › " path)
                            current (text-at root repository (current-revision repository) path)
                            base (when revision (text-at root repository revision path))
                            copy (when (not= state :removed) (slurp (copy-path directory entry) :encoding "UTF-8"))]]
                  (case state
                    :new (if current
                           {:entry entry :refusal (str where " already stands on " branch-name ".")}
                           {:entry entry :action :write :text copy})
                    :removed (cond (nil? base) {:entry entry :refusal (str where " stood at no module at its recorded revision.")}
                                   (nil? current) {:entry entry :action :keep}
                                   (= current base) {:entry entry :action :delete}
                                   :else {:entry entry :refusal (str where " changed since it was copied, so its removal does not apply.")})
                    :changed (cond (nil? base) {:entry entry :refusal (str where " stood at no module at its recorded revision.")}
                                   (nil? current) {:entry entry :refusal (str where " no longer stands on " branch-name ".")}
                                   (= copy base) {:entry entry :action :keep}
                                   (= current base) {:entry entry :action :write :text copy}
                                   (= current copy) {:entry entry :action :keep}
                                   :else (let [{:keys [text conflicts]} (merge-texts current base copy)]
                                           (if text
                                             {:entry entry :action :write :text text}
                                             {:entry entry :refusal (str where " moved since it was copied, and " conflicts
                                                                         " part" (when (not= 1 conflicts) "s")
                                                                         " of the difference does not apply to it.")})))))
        results (vec results)]
    (when-let [refusals (seq (keep :refusal results))]
      (refuse (str/join "\n" refusals)))
    results))

(defn land!
  "Apply the difference of every copy to the module as it stands, into the working tree of
  each repository under root; nothing is written when any part does not apply."
  [directory root]
  (let [results (landing directory root)]
    (vec (for [{:keys [entry action text]} results
               :let [{:keys [repository path]} entry
                     target (str (fs/path root repository path))
                     where (str repository " › " path)]]
           (case action
             :write (do (fs/create-dirs (fs/parent target))
                        (spit target text :encoding "UTF-8")
                        (str "written " where))
             :delete (do (fs/delete-if-exists target) (str "removed " where))
             :keep (str "unchanged " where))))))

;; ------------------------------------------------- a directory as a book
;;
;; Each module of the manifest is compared with the module at its recorded revision.
;; Lines are compared as lines; an unchanged run is the module's own text, and every
;; change stands in its place as an in-place proposal of the module view the builder reads.

(defn- text-lines
  "The lines of a text, without the one newline that ends it."
  [text]
  (let [text (str/replace text "\r\n" "\n")
        text (if (str/ends-with? text "\n") (subs text 0 (dec (count text))) text)]
    (if (= "" text) [] (vec (str/split text #"\n" -1)))))

(defn- trimmed-lines
  "The lines without the blank ones that begin and end them."
  [lines]
  (->> lines (drop-while str/blank?) reverse (drop-while str/blank?) reverse vec))

(defn- front-matter-and-body [text]
  (let [text (str/replace text "\r\n" "\n")]
    (if-let [[whole front] (re-find #"\A---\n((?s:.*?))\n---(?:\n|\z)" text)]
      {:front (text-lines front) :body (trimmed-lines (text-lines (subs text (count whole))))}
      {:front [] :body (trimmed-lines (text-lines text))})))

(defn- front-key? [key line] (str/starts-with? line (str key ":")))

(defn- diff-ops
  "The lines of two line vectors as ops {:sign :keep|:del|:add :text :b base-index :c copy-index}."
  [base copy]
  (if (= base copy)
    (vec (map-indexed (fn [k line] {:sign :keep :text line :b k :c k}) base))
    (temporary-files [(str (str/join "\n" base) (when (seq base) "\n"))
                      (str (str/join "\n" copy) (when (seq copy) "\n"))]
                     (fn [[base-path copy-path]]
                       (let [output (:out (deref (process/process ["git" "diff" "--no-index" "--no-color" "--patience" "-U0"
                                                                   "--no-ext-diff" base-path copy-path]
                                                                  {:out :string :err :string :in ""})
                                                 60000 ::timeout))
                             hunks (for [[_ a b c d] (re-seq #"(?m)^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@" output)]
                                     [(parse-long a) (if b (parse-long b) 1) (parse-long c) (if d (parse-long d) 1)])]
                         (loop [hunks hunks i 0 ops []]
                           (if-let [[a b c d] (first hunks)]
                             (let [base-start (if (zero? b) a (dec a))
                                   copy-start (if (zero? d) c (dec c))
                                   offset (- copy-start base-start)]
                               (recur (rest hunks) (+ base-start b)
                                      (-> ops
                                          (into (for [k (range i base-start)] {:sign :keep :text (base k) :b k :c (+ k offset)}))
                                          (into (for [k (range base-start (+ base-start b))] {:sign :del :text (base k) :b k}))
                                          (into (for [k (range copy-start (+ copy-start d))] {:sign :add :text (copy k) :c k})))))
                             (into ops (for [k (range i (count base))]
                                         {:sign :keep :text (base k) :b k :c (+ k (- (count copy) (count base)))})))))))))

(defn- fence-open [line] (second (re-find #"^ {0,3}(`{3,}|~{3,})" line)))
(defn- fence-closes? [open line]
  (boolean (re-find (re-pattern (str "^ {0,3}" (java.util.regex.Pattern/quote (subs open 0 1)) "{" (count open) ",}\\s*\\z")) line)))

(defn- fence-ids
  "For each line, the index of the line opening the fence it lies in (its own opening line
  and its closing line among them), or nil outside fences."
  [lines]
  (loop [k 0 open nil start nil out []]
    (if (>= k (count lines))
      out
      (let [line (lines k)]
        (cond
          open (recur (inc k) (when-not (fence-closes? open line) open) (when-not (fence-closes? open line) start) (conj out start))
          (fence-open line) (recur (inc k) (fence-open line) k (conj out k))
          :else (recur (inc k) nil nil (conj out nil)))))))

(defn- op-fences [ops base-fences copy-fences]
  (mapv (fn [{:keys [b c]}]
          (cond-> #{}
            (and b (base-fences b)) (conj [:b (base-fences b)])
            (and c (copy-fences c)) (conj [:c (copy-fences c)])))
        ops))

(defn- change-regions
  "The [start end] position intervals of the ops that change, each widened to hold whole
  every fence it touches, and merged where they meet."
  [ops fences]
  (let [positions-of (reduce (fn [m [p keys]] (reduce #(update %1 %2 (fnil conj []) p) m keys))
                             {} (map-indexed vector fences))
        span (fn [key] [(apply min (positions-of key)) (apply max (positions-of key))])
        merge-intervals (fn [intervals]
                          (reduce (fn [merged [s e]]
                                    (if-let [[ls le] (peek merged)]
                                      (if (<= s (inc le)) (conj (pop merged) [ls (max le e)]) (conj merged [s e]))
                                      (conj merged [s e])))
                                  [] (sort intervals)))
        widen (fn [[s e]]
                (reduce (fn [[s e] p]
                          (reduce (fn [[s e] key] (let [[fs fe] (span key)] [(min s fs) (max e fe)])) [s e] (fences p)))
                        [s e] (range s (inc e))))]
    (loop [intervals (merge-intervals (for [[p op] (map-indexed vector ops) :when (not= :keep (:sign op))] [p p]))]
      (let [widened (merge-intervals (map widen intervals))]
        (if (= widened intervals) intervals (recur widened))))))

(defn- signed [sign line] (if (str/blank? line) sign (str sign " " line)))

(defn- region-render
  "The lines of a run of ops as an in-place proposal's body."
  [ops]
  (loop [ops ops base-open nil copy-open nil out []]
    (if-let [{:keys [sign text]} (first ops)]
      (let [opener (fence-open text)
            in-base (and (#{:keep :del} sign) base-open)
            in-copy (and (#{:keep :add} sign) copy-open)
            inside (or in-base in-copy)
            closing (and inside (fence-closes? inside text))
            opening (and (not inside) opener)
            bare (or closing opening)
            next-base (cond (not (#{:keep :del} sign)) base-open
                            closing nil
                            opening opener
                            :else base-open)
            next-copy (cond (not (#{:keep :add} sign)) copy-open
                            closing nil
                            opening opener
                            :else copy-open)]
        (recur (rest ops) next-base next-copy
               (conj out (cond
                           bare text
                           (= sign :keep) (if (str/blank? text) "" (str "  " text))
                           (= sign :del) (signed "-" text)
                           :else (signed "+" text)))))
      out)))

(defn- sign-like? [line]
  (or (= line "-") (= line "+") (str/starts-with? line "- ") (str/starts-with? line "+ ") (str/starts-with? line "  ")))

;; --- the passages that moved between modules

(defn- paragraphs
  "The paragraphs of the changed ops of one sign outside every fence:
  [{:positions [p..] :text}], a paragraph being consecutive nonblank lines."
  [ops fences sign]
  (loop [positions (range (count ops)) current [] out []]
    (let [flush (fn [out] (if (seq current)
                            (conj out {:positions current :text (str/join "\n" (map #(:text (ops %)) current))})
                            out))]
      (if-let [p (first positions)]
        (let [op (ops p)]
          (if (and (= sign (:sign op)) (not (str/blank? (:text op))) (empty? (fences p)))
            (recur (rest positions) (conj current p) out)
            (recur (rest positions) [] (flush out))))
        (flush out)))))

(defn- only-blanks-between? [ops sign from to]
  (every? #(and (= sign (:sign (ops %))) (str/blank? (:text (ops %)))) (range (inc from) to)))

(defn- find-moves
  "Passages that leave one module and stand in another: [{:from :to :from-positions :to-positions
  :lines}], each a run of whole paragraphs, in the order the modules are read. `modules` is
  [{:ops :fences}]."
  [modules]
  (let [removed (vec (for [[m {:keys [ops fences]}] (map-indexed vector modules)
                           paragraph (paragraphs ops fences :del)
                           :when (>= (count (:text paragraph)) move-minimum-characters)]
                      (assoc paragraph :module m)))
        added (vec (for [[m {:keys [ops fences]}] (map-indexed vector modules)
                         paragraph (paragraphs ops fences :add)]
                     (assoc paragraph :module m)))
        pairs (loop [removed removed used #{} out []]
                (if-let [paragraph (first removed)]
                  (if-let [target (first (filter #(and (not (used (:positions %))) (not= (:module %) (:module paragraph))
                                                       (= (:text %) (:text paragraph)))
                                                 added))]
                    (recur (rest removed) (conj used (:positions target)) (conj out [paragraph target]))
                    (recur (rest removed) used out))
                  out))
        adjacent? (fn [ops sign a b] (and (= (:module a) (:module b))
                                          (only-blanks-between? ops sign (peek (:positions a)) (first (:positions b)))))]
    (reduce (fn [moves [from to]]
              (let [last-move (peek moves)
                    ops-of (fn [m] (:ops (modules m)))]
                (if (and last-move
                         (= (:module from) (:from last-move)) (= (:module to) (:to last-move))
                         (adjacent? (ops-of (:from last-move)) :del {:module (:from last-move) :positions [(peek (:from-positions last-move))]} from)
                         (adjacent? (ops-of (:to last-move)) :add {:module (:to last-move) :positions [(peek (:to-positions last-move))]} to))
                  (conj (pop moves) (-> last-move
                                        (update :from-positions into (concat (range (inc (peek (:from-positions last-move))) (first (:positions from)))
                                                                             (:positions from)))
                                        (update :to-positions into (concat (range (inc (peek (:to-positions last-move))) (first (:positions to)))
                                                                           (:positions to)))))
                  (conj moves {:from (:module from) :to (:module to)
                               :from-positions (vec (:positions from)) :to-positions (vec (:positions to))}))))
            [] pairs)))

;; --- one module's view

(defn- location-text [{:keys [repository path]}] (str repository " › " path))

(defn- proposal-head [number entry]
  (str "#### Proposal " number ": " change-title " · " (location-text entry)))

(defn- container [name lines] (concat [(str "::: " name)] lines [":::"]))

(defn- with-blank-before [out]
  (if (or (empty? out) (str/blank? (peek out))) out (conj out "")))

(defn- description-of [text]
  (when text (book-check/module-description text)))

(defn- front-without-description [front]
  (vec (remove #(front-key? "description" %) front)))

(defn- prepare-module
  "A module of the manifest read for comparison: the entry, its lines before and after,
  and the ops between them."
  [{:keys [state] :as entry} base-text copy-text]
  (let [old (when base-text (front-matter-and-body base-text))
        new (when copy-text (front-matter-and-body copy-text))
        base (or (:body old) [])
        copy (or (:body new) [])
        ops (diff-ops base copy)
        base-fences (fence-ids base)
        copy-fences (fence-ids copy)]
    {:entry entry
     :new? (= state :new)
     :ops ops
     :fences (op-fences ops base-fences copy-fences)
     :old-description (description-of base-text)
     :new-description (description-of copy-text)
     :old-front (front-without-description (or (:front old) []))
     :new-front (front-without-description (or (:front new) []))}))

(defn- numbered-moves
  "The moves between the prepared modules, each with its number and its place in both."
  [modules next-number!]
  (let [moves (find-moves modules)]
    (vec (for [{:keys [from to from-positions to-positions]} moves]
           {:id (next-number!) :from from :to to :from-positions from-positions :to-positions to-positions
            :lines (mapv #(:text (get-in modules [to :ops %])) to-positions)}))))

(defn- move-index
  "For each module, the move each of its op positions belongs to: {position {:move :side}}."
  [modules moves]
  (reduce (fn [index {:keys [from to from-positions to-positions] :as move}]
            (-> index
                (update from into (for [p from-positions] [p {:move move :side :from}]))
                (update to into (for [p to-positions] [p {:move move :side :to}]))))
          (vec (repeat (count modules) {}))
          moves))

(defn- plain-lines
  "Unchanged lines as the module has them, except that right after a proposal the
  lines that read as signs stand as context, so the proposal does not take them as changes."
  [texts tail?]
  (reduce (fn [[out tail?] text]
            (if (and tail? (or (str/blank? text) (sign-like? text)))
              [(conj out (if (str/blank? text) "" (str "  " text))) true]
              [(conj out text) false]))
          [[] tail?] texts))

(defn- module-view
  "The lines of one module's view: its heading, its description, and its body whole with
  every change standing in its place."
  [modules index m state next-number!]
  (let [{:keys [entry ops fences new? old-description new-description old-front new-front]} (modules m)
        move-at (index m)
        heading (str "## " (location-text entry) (when new? ", a new file"))
        description
        (cond
          new? (when new-description (container "description" (map #(signed "+" %) (str/split-lines new-description))))
          (= old-description new-description)
          (when new-description (container "description" (str/split-lines new-description)))
          :else (concat [(proposal-head (next-number!) entry) ""]
                        (container "description"
                                   (concat (map #(signed "-" %) (some-> old-description str/split-lines))
                                           (map #(signed "+" %) (some-> new-description str/split-lines))))))
        dependencies (when new?
                       (some-> (first (filter #(front-key? "dependencies" %) new-front))
                               (str/replace-first #"^dependencies:\s*" "")
                               (as-> value (container "dependencies" [(str "+ " value)]))))
        front (when (and (not new?) (not= old-front new-front))
                (concat [(proposal-head (next-number!) entry) "" "```yaml"]
                        (region-render (diff-ops old-front new-front))
                        ["```"]))
        regions (if new?
                  (if (seq ops) [[0 (dec (count ops))]] [])
                  (change-regions ops fences))
        head (-> [heading ""]
                 (into (when description (concat description [""])))
                 (into (when dependencies (concat dependencies [""])))
                 (into (when front (concat front [""]))))
        tail-after-head? (boolean (or (and (not new?) (not= old-description new-description)) front))
        emit-segment (fn [out segment]
                       (if (some #(not (str/blank? (:text %))) segment)
                         (let [lines (region-render segment)]
                           (if new?
                             (into out lines)
                             (-> (with-blank-before out)
                                 (conj (proposal-head (next-number!) entry))
                                 (conj "")
                                 (into lines))))
                         out))
        emit-move (fn [out {:keys [move side]}]
                    (let [{:keys [id from to lines]} move
                          state-word state]
                      (-> (with-blank-before out)
                          (conj (str "#### Proposal " id ": " change-title " · " (location-text entry)))
                          (conj "")
                          (into (if (= side :to)
                                  (container (str "move " state-word)
                                             (concat [(str "from: " (location-text (get-in modules [from :entry])))
                                                      (str "to: " (location-text (get-in modules [to :entry]))) ""]
                                                     lines))
                                  (container (str "moved " state-word)
                                             [(str "to: " (location-text (get-in modules [to :entry])))]))))))
        render-region (fn [out [s e]]
                        (loop [p s segment [] out out done #{}]
                          (if (> p e)
                            (emit-segment out segment)
                            (if-let [{:keys [move] :as mark} (move-at p)]
                              (let [out (emit-segment out segment)]
                                (if (done (:id move))
                                  (recur (inc p) [] out done)
                                  (recur (inc p) [] (emit-move out mark) (conj done (:id move)))))
                              (recur (inc p) (conj segment (ops p)) out done)))))]
    (loop [regions regions p 0 out head tail? tail-after-head?]
      (if-let [[s e] (first regions)]
        (let [[plain tail?] (plain-lines (map :text (subvec ops p s)) tail?)
              out (-> out (into plain) (render-region [s e]) with-blank-before)]
          (recur (rest regions) (inc e) out true))
        (let [[plain _] (plain-lines (map :text (subvec ops p)) tail?)]
          (into out plain))))))

(defn directory-source
  "The book source a proposal directory shows: its title and kind, then each module of the
  manifest, in its order, as a whole module view."
  [directory root]
  (let [{:keys [title kind creator entries]} (read-directory-manifest directory)
        state (if (= kind "Review") "landed" "proposed")
        counter (atom 0)
        next-number! #(swap! counter inc)
        modules (mapv (fn [{:keys [state repository path revision] :as entry}]
                        (prepare-module entry
                                        (when revision
                                          (or (text-at root repository revision path)
                                              (refuse (str repository " › " path " stands nowhere at revision " revision "."))))
                                        (when-not (= state :removed)
                                          (slurp (copy-path directory entry) :encoding "UTF-8"))))
                      entries)
        moves (numbered-moves modules next-number!)
        index (move-index modules moves)]
    (str "Presentation.{ «" title "» " kind " }\n\n# " title "\n\nCreated by " creator ".\n\n"
         (str/join "\n" (mapcat #(concat (module-view modules index % state next-number!) [""])
                                (range (count modules)))))))

(defn check-directory
  "The faults of a proposal directory, as [{:reason ...}], empty when the builder can read it."
  [directory root]
  (try
    (let [{:keys [entries]} (read-directory-manifest directory)
          faults (concat
                  (when (empty? entries) [{:reason "manifest-without-modules"}])
                  (for [[a b] (map vector entries (rest entries)) :when (same-module? a b)]
                    {:reason "module-repeated" :module (location-text a)})
                  (for [{:keys [state repository path revision] :as entry} entries
                        fault (concat
                               (when-not (book-check/valid-location? repository path) [{:reason "module-location-invalid"}])
                               (when (and (not= state :removed) (not (fs/exists? (copy-path directory entry))))
                                 [{:reason "copy-missing"}])
                               (when (and (= state :new) (fs/exists? (copy-path directory entry))
                                          (str/blank? (slurp (copy-path directory entry))))
                                 [{:reason "new-module-empty"}])
                               (when (and revision (not (re-matches #"[a-f0-9]{40}" revision)))
                                 [{:reason "revision-invalid"}]))]
                    (assoc fault :module (location-text entry))))]
      (vec faults))
    (catch clojure.lang.ExceptionInfo e [{:reason "manifest-invalid" :detail (ex-message e)}])))
