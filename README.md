# makeshift-tools

Small Babashka (`bb`, 1.13) tools. Run from the repository root.

## Tools

**book-build.clj** builds a book's HTML page from its Markdown source.
It reads `book-code.html` (page code), `book-grammars.json` and
`book-fence-languages.json` from this directory.

    bb book-build.clj SOURCE.md OUT.html [--code book-code.html] [--figures DIR] [--ethos-zero REF] [--ethos-zero-real REF]

A signed Ethos fence can be an excerpt of a larger declaration: both sides
are highlighted as written. A signed fence beginning with an Ethos root
(`Library`, `Signal`, `Operation`, `Memory`, `Emission`, `Types`, `Traits` or
`SelfContainedLibrary`) is still checked as that document; a rootless excerpt
makes no whole-file validity claim.
Unsigned Ethos fences retain their validation.

**book-check.clj** checks the structure of a book source (`/abs/SOURCE.md`),
printing the errors as JSON on stderr and exiting 1 when there are any
(2 on a bad invocation). As a library, `book-check/check-book` takes the
source text and returns a vector of `{:line n :reason "..."}`.

    bb book-check.clj /abs/SOURCE.md

A Proposal, Review or Account book may close with a section headed
`## Questions`, after the last proposal or module view, in the in-place form or
the heading form: plain prose, typically a numbered list, to the end of the
source. A Proposal or Review asks its questions after at least one proposal
(`questions-without-proposal`); an Account may close its own prose with them. A heading, a proposal, a comment, shown or move container, a code fence
or a change-marked line after that heading is refused (`heading-after-questions`,
`proposal-after-questions`, `comment-in-questions`, `shown-in-questions`,
`move-in-questions`, `code-fence-in-questions`, `change-in-questions`).
book-build renders it as a heading and prose outside every sheet.

A Review shows its added and removed lines under a double margin, the mark of
landed changes: the line's colour, the same as when proposed, beside a rail of
the page's text colour, each as wide as a whole single margin. A Proposal shows
the single margin, the mark of proposed changes. A Review that holds both declares each proposed part with the line
`::: proposed`, the first nonblank line under that proposal's heading (before
its `::: comment`). It is one line with no closing `:::`, in the words of
`::: move proposed`, and takes the same single margin. A proposal without it stays
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
(`proposal in-place proposed` in place), which sets the single margin for its
margins; the marker draws nothing itself.

In an in-place Review module view, landed changes are authenticated against
the module's added side, including its changed description. A part marked
`::: proposed` is authenticated against its removed side, as a Proposal is.

An in-place replacement preserves the unsigned blank lines after its source
passage exactly. Spacing around the proposal heading and its comment remains
presentation structure; an addition absent from the authenticated side adds
no spacing to that side.

A Markdown module's description, the `description` of its front matter, is
written in a `::: description` container at the head of its module view or
proposal; front matter is never written in a book (`description-as-front-matter`,
`front-matter-in-module-view`). In a module view it is the first nonblank line,
its text as it stands, authenticated against the module's front matter
(`module-description-differs`). A changed description is its removed lines then
its added lines, first among its proposal's changes; in place, that proposal is
the first thing in its module view and its removed text is authenticated. A new
module's description is its added lines alone:

    ## psyche-belief › vision/book/0.md

    ::: description
    How a presentation already written is published.
    :::

    ### Proposal 1: Persona · psyche-belief › vision/persona/0.md

    ::: description
    - Persona, the root of the meta harness, is designed or judged.
    + Persona, the meta harness, is designed or judged.
    :::

book-build shows it as a labelled block, `<section class="description">`: as
it stands, its text; changed, its removed text under the red margin and its
added text under the green, each marking only its own changed words.

A module view is headed `## repo › path`. The heading is defined once, in
book-check (`module-view-pattern`), and the checker and the builder both match
it through that definition. A module's file name may be a number, a span or
`all` (`vision/flow-nexus/0-1.md`).

A move is read where it lands. In the view of the module it enters, an
in-place proposal holds the move container at the place the passage lands. Its
heading names that module, or both modules. The builder heads the move with the
module it leaves alone, since the reader is already in the module it enters:

    ## psyche-belief › intent/energy/0.md

    #### Proposal 2: What attention costs · psyche-belief › intent/energy/0.md

    ::: move proposed
    from: psyche-belief › vision/model-roles/0.md
    to: psyche-belief › intent/energy/0.md

    The system is understood energetically.
    :::

In the view of the module it leaves, a placeholder stands where the passage
was. It holds the destination and no text, since the passage is written once,
in the move:

    ## psyche-belief › vision/model-roles/0.md

    #### Proposal 2: What attention costs · psyche-belief › vision/model-roles/0.md

    ::: moved proposed
    to: psyche-belief › intent/energy/0.md
    :::

`::: moved proposed` and `::: moved landed` take the shade of a move. A
placeholder is tied to its move by the proposal number the two in-place
headings share and by the pair of modules. A proposal may hold several moves;
its placeholders pair with them by content: the checker puts back, at each
placeholder, the move whose passage reconstructs the module there, so the order
the moves are written in the entering view is free. Moves whose passages are
identical pair in the order written. A count that differs leaves a placeholder
or a move unpaired, and it is refused (`moved-without-move`,
`move-without-placeholder`); a placeholder no move fits leaves the view differing from the
module (`context-module-differs`). book-build draws the
placeholder as one small block in the move colour, reading "Moved to repo ›
path", with the path linking to that module's view (`<section class="move
move-proposed move-placeholder">`).

The two views are checked together. In a Proposal, the placeholder stands for
the passage, as the module it leaves holds it: the edit made on its way is
undone and its added lines are left out. In the view of the module it enters,
the move stands for nothing. In a landed Review it is the other way round: the
placeholder stands for nothing, and the move stands for its passage as the
module it enters holds it, with its edit made and its added lines in. A move or
placeholder that ends a current line (the line before it is a nonblank line of
the side being read, with no blank line between) joins that line.

When both modules are on the page, a move written in the view of the module it
enters with no placeholder is refused (`move-without-placeholder`), and so is a
placeholder with no move (`moved-without-move`). When the module it leaves is
not on the page, a proposed passage must be an excerpt of that module's file as
it stands; its first line may be the end of a current line
(`move-source-text-absent`, `move-source-unavailable`). A malformed placeholder
is refused (`malformed-moved-placeholder`), and so is one outside a module view
(`moved-outside-module-view`). A move written in the view of the module it
leaves, the earlier form, keeps working and needs no placeholder.

A sentence that leaves a line is marked inside the line instead: the line keeps
its other words and holds `[[moved to: repo › path]]` where the sentence left,
in a signed or unchanged line of an in-place proposal (a code span or code fence
holds no mark):

    - First. [[moved to: psyche-belief › intent/energy/0.md]] Last.
    + First. Last.

The mark pairs with its move by module pair alone, the module its view leaves
and the module the mark names, in the order written, whatever proposal block its
line stands in (a placeholder pairs by proposal number and module pair), and is
refused as a placeholder is (`moved-without-move`,
`malformed-moved-placeholder`, `move-without-placeholder`). Read on the side
that holds the passage (a Proposal's removed and unchanged lines), the passage
is put back at the mark, joined to the text around it by the spaces around the
mark; read on the other side (a landed Review's added and unchanged lines), the
mark is taken out and one of its two spaces is kept. A passage of several lines
joins the text before the mark with its first line and the text after with its
last. book-build draws the mark as the same "Moved to" block, small and inline
in the line (`<span class="move move-proposed move-placeholder move-inline">`),
in the shade of its proposal: landed in a Review unless the proposal declares
itself `::: proposed`, else proposed. In the word marks of a changed line it is
one token.

A new module is one view, `## repo › path, a new file`. Every line in it is
added, its description among them, and a Markdown module's description is
required (`new-module-without-description`). An unsigned line, or a fence
holding anything but added lines, is refused (`new-module-line-not-added`). The
view counts as a proposal, and a move may land in it under an in-place heading
that names the module without the suffix. In a Proposal no file is read. In a
Review the view's added side is checked against the landed file. book-build
marks the view's sheet New:

    ## psyche-belief › intent/sonnet/0.md, a new file

    ::: description
    + A Sonnet flow takes a piece of work.
    :::

    + Sonnet does large plain work and designs nothing.

**book-label.clj** gives a source its page label, a Pascal-case name that says
the page's subject, a dot and the revision counting from 1, first in the title:
`Panel.1 · the title`. It is run by the publisher at publication, before the
source is built or checked, and prints the label.

    bb book-label.clj /abs/SOURCE.md [--name Name] [--revise] [--labels LABELS.datom]

A source with no label of this form (an old-form `V7 · ` counts as none) needs
`--name`, refused unless Pascal case; it takes one more than the highest
revision recorded for the name, or 1, written into the head title and the H1. A
source that carries `Name.N` keeps it on every rebuild; `--revise`, given when a
redone page is republished, raises it to `Name.(N+1)`. The configuration is one
datom, read from `--labels`, else `$BOOK_LABELS`, else
`/home/li/primary/flows/book/labels.datom`; it keeps the highest revision per
name and is changed under a file lock:

    { Revisions [ { Panel 2 } { Purification 3 } ] }

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

**codex-context-watch.clj** is a stopgap watcher, started by hand for one Codex
flow. It follows the flow's rollout file (`tail -n +1 -F`) and sends the flow two
orders to write its handover: MiddleAbrupt at the warning size, HardAbrupt a
margin later, each once. The start command, as a transient user unit, is at the
head of the file.

    bb codex-context-watch.clj ADDRESS FLOW_ID SIZE [ROLLOUT] [--margin N] [--idle-seconds N] [--home DIR]... [--dry]

## Tests

    bb test

runs every `test/*-test.clj`. Fixtures are in `test/fixtures`. The phone
rendering test (`test/book-phone-code-test.clj`) and the theme test
(`test/book-theme-render-test.clj`, a built book read in the dark and light
settings) drive headless chromium through `test/cdp.clj`: they use
`$CHROMIUM` or `chromium` on PATH, for example

    CHROMIUM=$(nix build nixpkgs#chromium --no-link --print-out-paths)/bin/chromium bb test
