;; A built book read in headless chromium at phone width, in the reader's dark
;; and light settings: every ink of a figure is the page text colour and every
;; fill the page surface, whatever colours the figure was drawn in; an excerpt of
;; a prose module as it stands is wrapped text; a long code line scrolls inside its
;; own box; the page opens with the key to its colours, light proposed, dark landed.
;; Run: bb test/book-theme-render-test.clj
;; Chromium is the CHROMIUM environment variable if set, else `chromium` on PATH.
(ns book-theme-render-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(load-file (str (fs/path root "test" "cdp.clj")))
(load-file (str (fs/path root "book-build.clj")))

(def figures (str (fs/path root "test" "fixtures" "figures")))

(def long-code
  "Send.{ { Mind flowNexus Opus } Soft.Question.«Why did 0.54.0 refresh no flow, and which flow holds the answer?» }")

(def source
  (str "Presentation.{ «Colours» Proposal }\n\n# Colours\n\nCreated by a test.\n\n"
       "## psyche-belief › vision/remembering.md\n\nA module as it stands.\n\n"
       "```mermaid\nflowchart TB\n    a[\"living content\"] -- \"passes to\" --> b[\"fresh flow\"]\n```\n\n"
       "### Proposal 1: Remembering · psyche-belief › vision/remembering.md\n\n"
       "::: shown\npsyche-belief › vision/remembering.md\n"
       "All flows are one subjectivity; this is the reason behind\n"
       "remembering. Told \"you did\" or \"you said\" of a thing it did not\n"
       "itself do or say, a flow remembers it at a depth fit to the question,\n"
       "reaching the transcript directly.\n:::\n\n"
       "+ Remembering reaches every permaflow.\n\n"
       "```datom\n+ " long-code "\n```\n\n"
       "```mermaid\n+ flowchart TB\n+     f[\"a flow\"] -- \"remembers\" --> t[\"a transcript\"]\n```\n"))

(defn page []
  (let [dir (fs/create-temp-dir {:prefix "book-theme-figs-"})]
    (try
      (fs/copy (fs/path figures "white-boxes.svg") (fs/path dir "fig-1.svg"))
      (fs/copy (fs/path figures "black-current-colour.svg") (fs/path dir "fig-2.svg"))
      (binding [book-build/*figures-dir* (str dir) book-build/*highlight* false]
        (book-build/build source (book-build/read-text (fs/path root "book-code.html"))))
      (finally (fs/delete-tree dir)))))

(def measure-js "document.fonts.ready.then(() => {
  const probe = (prop, value) => { const d = document.createElement('div'); d.style[prop] = value; document.body.appendChild(d);
    const c = getComputedStyle(d)[prop]; d.remove(); return c; };
  const fg = probe('color', 'var(--fg)'), surface = probe('color', 'var(--chip)');
  const paints = [...document.querySelectorAll('.figure svg *')]
    .filter(e => ['text','tspan','line','path','polyline','polygon','rect','circle','ellipse'].includes(e.tagName))
    .map(e => { const s = getComputedStyle(e); return { tag: e.tagName, fill: s.fill, stroke: s.stroke }; });
  const shown = document.querySelector('.shown'); const prose = shown.querySelector('.markdown');
  const pre = document.querySelector('pre:has(>code.language-datom)');
  const key = document.querySelector('.key'); const firstSheet = document.querySelector('.module, .proposal');
  const bg = s => { const e = document.querySelector(s); return e ? getComputedStyle(e).backgroundColor : null; };
  return { fg, surface, paints,
    proseFont: getComputedStyle(prose || shown).fontFamily, proseMono: !!shown.querySelector('pre, code[class*=language-]'),
    shownFits: shown.scrollWidth <= shown.clientWidth, proseLines: prose && Math.round(prose.getBoundingClientRect().height / parseFloat(getComputedStyle(prose).lineHeight)),
    preScrolls: pre.scrollWidth > pre.clientWidth, preRight: pre.getBoundingClientRect().right,
    preText: pre.textContent, preShade: getComputedStyle(pre).backgroundAttachment, width: document.documentElement.clientWidth, pageWidth: document.documentElement.scrollWidth,
    keyBeforeSheets: !!key && !!(key.compareDocumentPosition(firstSheet) & Node.DOCUMENT_POSITION_FOLLOWING),
    keyItems: key ? [...key.querySelectorAll('li')].map(li => li.textContent) : [],
    keyNote: key ? key.querySelector('.key-note').textContent : null,
    swatches: Object.fromEntries(['add','add-landed','del','del-landed','move','move-landed','shown','module'].map(n => [n, bg('.sw-' + n)])) };
})")

(defn lightness
  "CIE L* of a computed rgb() colour."
  [css]
  (book-build/lightness (book-build/colour-rgb css)))

(defn measure [binary]
  (let [dir (fs/create-temp-dir {:prefix "book-theme-page-"})
        file (fs/path dir "page.html")]
    (spit (str file) (page))
    (try
      (cdp/with-page binary
        (fn [request session events]
          (request "Emulation.setDeviceMetricsOverride" {"width" 390 "height" 900 "deviceScaleFactor" 1 "mobile" true} session)
          (vec (for [scheme ["dark" "light"]]
                 (do (request "Emulation.setEmulatedMedia" {"features" [{"name" "prefers-color-scheme" "value" scheme}]} session)
                     (cdp/navigate request session events (str "file://" file))
                     (assoc (cdp/evaluate request session measure-js) "scheme" scheme))))))
      (finally (fs/delete-tree dir)))))

(deftest a-built-page-reads-in-the-dark-and-the-light
  (let [binary (cdp/chromium-binary)]
    (is binary "no chromium: set CHROMIUM or put chromium on PATH")
    (when binary
      (let [work (future (measure binary))
            results (deref work 120000 ::timeout)]
        (when (= results ::timeout) (future-cancel work))
        (is (not= ::timeout results) "the theme check exceeded 120 seconds")
        (when (vector? results)
          (is (= ["dark" "light"] (map #(get % "scheme") results)))
          (is (> (lightness (get (first results) "fg")) 80) "the dark setting gives light text")
          (is (< (lightness (get (second results) "fg")) 20) "the light setting gives dark text")
          (doseq [m results
                  :let [scheme (get m "scheme") fg (get m "fg") surface (get m "surface")
                        w #(str % ", " scheme)
                        paints (get m "paints")]]
            (is (> (Math/abs (- (lightness fg) (lightness surface))) 60) (w "ink and surface stand apart"))
            (is (seq paints) (w "the figures are on the page"))
            (doseq [{tag "tag" fill "fill" stroke "stroke"} paints]
              (is (contains? #{fg surface "none"} fill) (w (str tag " fill " fill " is the page's ink, its surface or none")))
              (is (contains? #{fg "none"} stroke) (w (str tag " stroke " stroke " is the page's ink or none"))))
            (is (every? #(= fg (get % "fill")) (filter #(= "text" (get % "tag")) paints)) (w "every label is in the page text colour"))
            (is (every? #(= fg (get % "stroke")) (filter #(#{"line" "polyline"} (get % "tag")) paints)) (w "every arrow is in the page text colour"))
            (is (some #(= surface (get % "fill")) (filter #(= "rect" (get % "tag")) paints)) (w "a white box takes the page surface"))
            (is (not (get m "proseMono")) (w "the excerpt of a prose module holds no code block"))
            (is (not (re-find #"(?i)mono" (str (get m "proseFont")))) (w "the excerpt is in the prose font"))
            (is (get m "shownFits") (w "the excerpt never scrolls sideways"))
            (is (> (or (get m "proseLines") 0) 3) (w "the excerpt wraps to the screen"))
            (is (get m "preScrolls") (w "a long code line scrolls inside its own box"))
            (is (<= (get m "preRight") (get m "width")) (w "the code box ends inside the screen"))
            (is (str/starts-with? (str (get m "preShade")) "local, local, scroll, scroll") (w "a shade at the box's edge shows hidden code"))
            (is (str/includes? (get m "preText") long-code) (w "the code line is whole"))
            (is (<= (get m "pageWidth") (get m "width")) (w "the page never scrolls sideways"))
            (is (get m "keyBeforeSheets") (w "the key opens the page"))
            (is (= ["added" "removed" "moved" "an excerpt, as it stands" "a module, as it stands"] (get m "keyItems")) (w "the key names every colour"))
            (is (= "Light shade: proposed. Dark shade: landed." (get m "keyNote")))
            (when-let [sw (and (every? some? (vals (get m "swatches"))) (get m "swatches"))]
              (doseq [k ["add" "del" "move"]]
                (is (> (lightness (get sw k)) (lightness (get sw (str k "-landed")))) (w (str k ": the proposed shade is the lighter"))))
              (is (= 8 (count (set (vals sw)))) (w "every swatch is its own colour")))))))))

(let [{:keys [fail error]} (run-tests 'book-theme-render-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
