;; Phone rendering of book-code.html in headless chromium, at four phone widths.
;; Run: bb test/book-phone-code-test.clj [BOOK-CODE.html]
;; Chromium is the CHROMIUM environment variable if set, else `chromium` on PATH.
;; Without one:  CHROMIUM=$(nix build nixpkgs#chromium --no-link --print-out-paths)/bin/chromium bb test/book-phone-code-test.clj
;; The browser is driven over the Chrome DevTools Protocol (test/cdp.clj).
(ns book-phone-code-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def template (slurp (or (first *command-line-args*) (str (fs/path root "book-code.html")))))

(def value "{ Psyche flow Primary } startInputVital")
(def code (str "Library\n    Source.{ Repository Hash Path }\n" (apply str (repeat 52 "x")) "\n"))
(def overflow-code (apply str (repeat 12 "UnbrokenCodeToken")))
(def literal "## Spacing\n    unchanged spaces\n")
(defn escape [text] (-> text (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn fixture-html []
  (str "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<!-- A viewer that fixes the page height at the screen's (as quirks mode does) leaves the grid short of room: scroll containers may shrink to nothing. -->
<style>html,body{height:100%}</style></head><body>
<section class=\"proposal\"><header><span class=\"pnum\">1</span><h2>Flow title</h2></header>
<section class=\"rulings\"><ol><li value=\"3\"><p>the title of a thread.</p><ol class=\"choices\" type=\"a\"><li>the address written short, then the flow's three words: <code id=\"choice-value\">" (escape value) "</code></li><li>other</li></ol></li></ol></section></section>
<pre id=\"full-code\"><code class=\"language-ethos\">" (escape code) "</code></pre>
<!-- A deliberate overflow stress case for the page's sideways code scrolling. -->
<pre id=\"overflow-code\"><code class=\"language-ethos\">" (escape overflow-code) "</code></pre>
<div class=\"figure\" id=\"fig\"><svg viewBox=\"0 0 520 321\" width=\"520\" height=\"321\" style=\"display:block\"><rect width=\"520\" height=\"321\"/></svg></div>
<table id=\"tbl\"><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody><tr><td>" (apply str (repeat 40 "wide")) "</td><td>one</td></tr>" (apply str (repeat 30 "<tr><td>two</td><td>three</td></tr>")) "</tbody></table>
<h3 id=\"outside-heading\">Outside heading</h3><div class=\"textblock\">" (escape literal) "</div>
" template "</body></html>"))

(def measure-js "document.fonts.ready.then(() => {
        const value = document.querySelector('#choice-value');
        const text = value.firstChild;
        const tokens = [...text.textContent.matchAll(/[A-Za-z][A-Za-z0-9]*/g)].map(match => {
          const range = document.createRange(); range.setStart(text, match.index); range.setEnd(text, match.index + match[0].length);
          return { token: match[0], lines: new Set([...range.getClientRects()].filter(r => r.width > 0).map(r => Math.round(r.top))).size };
        });
        const pre = document.querySelector('#full-code'); const overflow = document.querySelector('#overflow-code'); const body = getComputedStyle(document.body);
        const fig = document.querySelector('#fig');
        const tb = document.querySelector('#tbl'); const tr = [...tb.querySelectorAll('tr')].reduce((h, r) => h + r.getBoundingClientRect().height, 0);
        return { tblBottom: tb.getBoundingClientRect().bottom, tblNextTop: tb.nextElementSibling.getBoundingClientRect().top, figBottom: fig.getBoundingClientRect().bottom, figNextTop: fig.nextElementSibling.getBoundingClientRect().top, tblBox: tb.getBoundingClientRect().height, tblRows: tr, tblScrollsX: tb.scrollWidth > tb.clientWidth, tblScrollsY: tb.scrollHeight > tb.clientHeight, figBox: fig.getBoundingClientRect().height, figSvg: fig.querySelector('svg').getBoundingClientRect().height, figScrollsY: fig.scrollHeight > fig.clientHeight,
          width: document.documentElement.clientWidth, pageWidth: document.documentElement.scrollWidth,
          choiceDisplay: getComputedStyle(value).display, value: value.textContent, tokens,
          blockWidth: pre.getBoundingClientRect().width,
          availableWidth: document.body.clientWidth - parseFloat(body.paddingLeft) - parseFloat(body.paddingRight),
          scrolls: overflow.scrollWidth > overflow.clientWidth, overflowCode: overflow.querySelector('code').textContent, overflowWidth: overflow.getBoundingClientRect().width, code: pre.querySelector('code').textContent,
          codeWhitespace: getComputedStyle(pre.querySelector('code')).whiteSpace,
          literal: document.querySelector('.textblock').textContent,
          literalHeadings: document.querySelector('.textblock').querySelectorAll('h1,h2,h3,h4,h5,h6').length,
          outsideHeadingDisplay: getComputedStyle(document.querySelector('#outside-heading')).display };
      })")

;; ---- DevTools protocol over a WebSocket: test/cdp.clj ----

(load-file (str (fs/path root "test" "cdp.clj")))
(def connect cdp/connect)
(def chromium-binary cdp/chromium-binary)
(def launch cdp/launch)
(def endpoint cdp/endpoint)

(defn run-measurements
  "Returns the vector of measurement maps, one per phone width."
  [binary]
  (let [directory (fs/create-temp-dir {:prefix "book-phone-"})
        profile (fs/create-dirs (fs/path directory "profile"))
        config (fs/create-dirs (fs/path directory "config"))
        cache (fs/create-dirs (fs/path directory "cache"))
        fixture (fs/path directory "fixture.html")
        _ (spit (str fixture) (fixture-html))
        browser (launch profile config cache directory binary)
        connection (volatile! nil)]
    (try
      (let [conn (connect (endpoint profile browser))
            _ (vreset! connection conn)
            request (:request conn)
            {target "targetId"} (request "Target.createTarget" {"url" "about:blank"} nil)
            {session "sessionId"} (request "Target.attachToTarget" {"targetId" target "flatten" true} nil)]
        (request "Page.enable" {} session)
        (vec (for [width [320 375 390 768]]
               (do (request "Emulation.setDeviceMetricsOverride"
                            {"width" width "height" 900 "deviceScaleFactor" 1 "mobile" true} session)
                   (let [loaded (promise)]
                     (swap! (:events conn) assoc (str session ":Page.loadEventFired") loaded)
                     (request "Page.navigate" {"url" (str "file://" fixture)} session)
                     (deref loaded 30000 nil))
                   (let [r (request "Runtime.evaluate" {"awaitPromise" true "returnByValue" true
                                                        "expression" measure-js} session)]
                     (when (get r "exceptionDetails")
                       (throw (ex-info (str "page script failed: " (json/generate-string (get r "exceptionDetails"))) {})))
                     (assoc (get-in r ["result" "value"]) "viewport" width))))))
      (finally
        (some-> @connection :socket (.abort))
        (.destroyForcibly ^Process (:proc browser))
        (fs/delete-tree directory)))))

(deftest phone-rendering
  (let [binary (chromium-binary)]
    (is binary "no chromium: set CHROMIUM or put chromium on PATH")
    (when binary
      (let [work (future (run-measurements binary))
            measurements (deref work 90000 ::timeout)]
        (when (= measurements ::timeout) (future-cancel work))
        (is (not= ::timeout measurements) "Phone renderer check exceeded 90 seconds")
        (when (vector? measurements)
          (doseq [m measurements
                  :let [width (get m "viewport")
                        w #(str % " at " width)]]
            (is (== width (get m "width")) (w "actual phone viewport"))
            (is (<= (get m "pageWidth") width) (w "page does not overflow horizontally"))
            (is (= value (get m "value")) (w "choice bytes preserved"))
            (is (= "inline" (get m "choiceDisplay")) (w "choice code stays in normal text flow"))
            (is (every? #(== 1 (get % "lines")) (get m "tokens")) (w "no code token breaks across lines"))
            (is (< (Math/abs (- (get m "blockWidth") (get m "availableWidth"))) 1) (w "block spans full page content width"))
            (is (< (Math/abs (- (get m "overflowWidth") (get m "availableWidth"))) 1) (w "overflow block spans full page content width"))
            (is (get m "scrolls") (w "wide code scrolls inside block"))
            (is (= overflow-code (get m "overflowCode")) (w "overflow token bytes preserved"))
            (is (= code (get m "code")) (w "code whitespace and bytes preserved"))
            (is (= "pre" (get m "codeWhitespace")))
            (is (= literal (get m "literal")) (w "literal fence bytes preserved"))
            (is (zero? (get m "literalHeadings")))
            (is (== 321 (get m "figSvg")) (w "figure drawn at its height"))
            (is (== 321 (get m "figBox")) (w "figure box shows the whole figure in height"))
            (is (and (pos? (get m "tblRows")) (>= (get m "tblBox") (get m "tblRows"))) (w "table box shows every row in height"))
            (is (<= (get m "tblBottom") (+ (get m "tblNextTop") 0.5)) (w "table ends above the next element"))
            (is (<= (get m "figBottom") (+ (get m "figNextTop") 0.5)) (w "figure ends above the next element"))
            (is (or (> width 400) (get m "tblScrollsX")) (w "a table wider than the screen scrolls sideways"))
            (is (not (get m "tblScrollsY")) (w "table never scrolls vertically"))
            (is (not (get m "figScrollsY")) (w "figure never scrolls vertically"))
            (is (= "block" (get m "outsideHeadingDisplay")))))))))

(let [{:keys [fail error]} (run-tests 'book-phone-code-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
