# makeshift-tools

Small Babashka (`bb`, 1.13) tools. Run from the repository root.

## Tools

**book-build.clj** builds a book's HTML page from its Markdown source.
It reads `book-code.html` (page code), `book-grammars.json` and
`book-fence-languages.json` from this directory.

    bb book-build.clj SOURCE.md OUT.html [--code book-code.html] [--figures DIR] [--ethos-zero REF] [--ethos-zero-real REF]

**book-check.clj** checks the structure of a book source (`/abs/SOURCE.md`),
printing the errors as JSON on stderr and exiting 1 when there are any
(2 on a bad invocation). As a library, `book-check/check-book` takes the
source text and returns a vector of `{:line n :reason "..."}`.

    bb book-check.clj /abs/SOURCE.md

A Proposal book may close with a section headed exactly `## Questions`, after
the last proposal: plain prose, typically a numbered list, to the end of the
source. A heading, a proposal, a comment, shown or move container, a code fence
or a change-marked line after that heading is refused (`heading-after-questions`,
`proposal-after-questions`, `comment-in-questions`, `shown-in-questions`,
`move-in-questions`, `code-fence-in-questions`, `change-in-questions`).
book-build renders it as a heading and prose outside every sheet.

A Review shows its added and removed lines in the dark shade, the shade of
landed changes; a Proposal shows the light shade, the shade of proposed
changes. A Review that holds both declares each proposed part with the line
`::: proposed`, the first nonblank line under that proposal's heading (before
its `::: comment`). It is one line with no closing `:::`, in the words of
`::: move proposed`, and takes the same light shade. A proposal without it stays
dark. It stands under a `### Proposal` heading or an in-place `#### Proposal`
heading:

    ### Proposal 2: Part title · psyche-belief › vision/lojix.md

    ::: proposed

    ::: comment
    Why.
    :::

    - Old.
    + New.

In a Proposal or Account book the marker is refused (`proposed-outside-review`),
as it is outside a proposal (`proposed-outside-proposal`), anywhere but first
under the heading (`proposed-not-first-in-proposal`), with text after it
(`malformed-proposed-marker`), or in `## Questions` (`proposed-in-questions`).
book-build gives the proposal's sheet the class `proposal proposed`
(`proposal in-place proposed` in place), which sets the light shade for its
margins; the marker draws nothing itself.

**codex-usage** prints Codex usage left, its reset time and the available usage
resets, from `codex app-server` with the `~/.codex` login. Read-only.
`--json` prints the raw reply. `codex-app-server.clj` is the namespace it shares
with `codex-redeem-reset`.

    codex-usage [--json] [--codex PATH]

**codex-redeem-reset** redeems one usage reset, naming it by id, and shows the
state before and after. It refuses (exit 2) unless the reset is available and
the soonest to expire, and Codex is out or nearly out (a window at least
`--min-used-percent` used) or the reset expires within
`--expiry-window-minutes` and the week is at least 1% used.

    codex-redeem-reset [--codex PATH] [--min-used-percent N] [--expiry-window-minutes N] RESET_ID

Defaults: `--min-used-percent 95`, `--expiry-window-minutes 90`. Without
`--codex` the binary is `codex` on PATH, then the newest
`/nix/store/*-codex-0.1*/bin/codex`.

## Tests

    bb test

runs every `test/*-test.clj`. Fixtures are in `test/fixtures`. The phone
rendering test (`test/book-phone-code-test.clj`) and the theme test
(`test/book-theme-render-test.clj`, a built book read in the dark and light
settings) drive headless chromium through `test/cdp.clj`: they use
`$CHROMIUM` or `chromium` on PATH, for example

    CHROMIUM=$(nix build nixpkgs#chromium --no-link --print-out-paths)/bin/chromium bb test
