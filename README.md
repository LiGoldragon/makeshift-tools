# makeshift-tools

Small Babashka (`bb`, 1.13) tools. Run from the repository root.

## Tools

**book-tools.clj** makes and lands a proposal. A proposal is a directory: one
manifest and the edited copies of the modules it changes, read by the publisher as one page.
Install the author, builder and checker together into a directory on PATH:

    ./install-book-tools ~/.local/bin
    book-tools copy DIRECTORY vision:belief-modules:0 knowledge:book:0 --title TITLE --creator MODEL [--kind Review]
    book-tools new DIRECTORY REPOSITORY vision:fresh:0
    book-tools remove DIRECTORY vision:old:0
    book-tools creator DIRECTORY MODEL
    book-tools replace COPY OLD NEW
    book-tools land DIRECTORY
    book-tools source DIRECTORY
    book-check /abs/DIRECTORY
    book-build DIRECTORY OUT.html

`copy` takes each module by its name, `<type>:<topic>:<version>`, finds its repository
(`psyche-belief`, `mind-belief` or `field-belief`, refusing a name found in none or in
several; `REPOSITORY › path` names any other file), and copies the module as it stands on
origin main into `DIRECTORY/<repository>/<path>`, recording the revision it came from.
`new` begins a module that does not exist as an empty copy to be written; `remove` names a
module as removed. `--title` and `--creator` (the model that created the proposal, one of `subtitle-models` in
`book-check.clj`) are needed when the directory is new, and `--kind` is Proposal
(the default) or Review; each may be given again to change it. `creator` names the creating model in a directory already made, including one whose manifest
has none, and leaves the copies as they are. `replace` edits a copy
without reading it: OLD and NEW are files, `-` being standard input for one of them, each
without the one newline that ends it; the replacement is refused when OLD is absent or occurs
twice, or when COPY is no copy of the directory. `land` applies each copy to its repository,
into `--root` (the directory holding a checkout of each repository, `/git/github.com/LiGoldragon`
by default), after reading the module as it stands now on origin main: where it has not moved
since the recorded revision that is the copy itself; where it has, the difference between the
copy and the recorded revision is applied to the module as it stands, and a part that does not
apply refuses the whole landing, naming the module, with nothing written. A new module that
already stands, and a removal of a module that has changed, are refused. `land` writes the
files and commits nothing. `source` prints the book source the builder reads from the
directory, where the figures are numbered.

The manifest, `DIRECTORY/manifest.datom`, is one datom: the title, the kind, the creating model, and the entries in
the order he reads the modules. The revision is the commit of the repository's origin main
the copy was taken from; the modules are named by their paths, and a page names them by
`<type>:<topic>:<version>`.

```ethos
Manifest.{ Title Kind Creator Entries }
Title.String
Kind.[ Proposal Review ]
Creator.Model
Model.[ Fable Opus Sonnet Haiku Astra Sol Luna Terra ]
Entries.Vector<Entry>
Entry.[ Changed.{ Repository Path Revision }
        New.{ Repository Path }
        Removed.{ Repository Path Revision } ]
Repository.String
Path.String
Revision.String
```

```datom
Manifest.{ «Panel.1 · The book form» Proposal Opus
[ Changed.{ «psyche-belief» «vision/book/0.md» «0123456789abcdef0123456789abcdef01234567» }
  New.{ «psyche-belief» «vision/book-copy/0.md» } ] }
```

`source` prints `Created by <Model>.` under the title; the checker refuses a manifest without a creator
or with one that is no model.

A refusal tells a flow: when `book-build` or `book-check` refuses, it sends
`Soft.Notice.«<tool> refused <path>: <refusal>»` through `flow` before it exits non-zero. The
address is one datom in `$XDG_CONFIG_HOME/book-tools/refusal-notice.datom` (or the file
`BOOK_NOTICE_CONFIGURATION` names): an address such as `{ Psyche core Opus }`, or `Off`. With no
such file nothing is sent and stderr says so; tests and local work turn it off by that file.

`book-build DIRECTORY` and `book-check /abs/DIRECTORY` take the directory in place of a
source. The builder compares each copy with the module at its recorded revision and shows the
whole module under its name, every change marked in place: a replaced line read once with its
changed words marked, added and removed lines under their margins, and a passage that leaves one
copy and stands unchanged in another (a paragraph of at least `move-minimum-characters`, in
`book-proposal.clj`) once, as moved, in the module it enters with a placeholder in the module it
leaves. A new module shows whole as added and a removed one whole as removed. Unchanged lines stand
as they are; figures and fences are drawn as in any page. A Review shows the same changes as
landed. `book-label` labels a directory in the title of its manifest. A presentation that
changes no module, an account among them, keeps its plain source, and a page already
published from a source of `+` and `-` lines is built as before; `book-tools start` and `edit`
write that older form. The installer takes `--repository CHECKOUT` and `--flake REFERENCE`: each installed command runs the flake's `book-tools` package, which carries babashka, tree-sitter, a compiler, git, ethos-zero and the grammars, so the tools run the same on every host (chromium and inotify-tools are for the tests, in `nix develop`).
Without installation, run `bb book-tools.clj` with the same arguments.

The older form, a source of `+` and `-` lines:

    book-tools start SOURCE.md TITLE 'REPOSITORY › MODULE'...
    printf '%s\n' 'Added paragraph.' | book-tools edit SOURCE.md 'REPOSITORY › MODULE' ANCHOR --after
    book-tools edit SOURCE.md 'REPOSITORY › MODULE' ANCHOR --replace-old OLD --replace-new NEW

Start preserves an existing source; its draft deliberately has no changes yet,
so the publication checker reports `missing-proposal` until an edit exists.
After inserts stdin text after the anchor's containing line; replacement
replaces OLD exactly once within the anchor's containing line, preserving surrounding text.
Edits become numbered in-place proposals with ordinary change marks.
A missing or repeated anchor, an anchor already inside a proposal, invalid
arguments, an empty change or a checker-invalid result preserves the source.
Markdown module views are editable; other views can be included by start.
Other module views stay byte-identical. Validation precedes atomic replacement.

**book-build.clj** builds a book's HTML page from its Markdown source, or from a proposal directory.
It reads `book-code.html` (page code), `book-grammars.json` and
`book-fence-languages.json` from this directory.

    bb book-build.clj SOURCE.md OUT.html [--code book-code.html] [--figures DIR] [--ethos-zero REF] [--ethos-zero-real REF]

Figures are numbered in source order, one counter for the whole page: the
n-th figure is `fig-n.svg` in the `--figures` directory. Every Mermaid fence
takes the next number, wherever it sits: a proposal, a move, or
a module view's unchanged text. Added, removed and unchanged fences are drawn under their own margins. A Mermaid fence without its
file, and a `fig-n.svg` numbered past the page's last figure, stop the build
naming the file.

A standalone Markdown image line, such as `![](heat-map.svg)`, embeds an SVG
data figure from beside the source or from inside a proposal directory. Prefix
the line with `+ ` or `- ` to show the figure under the addition or removal
margin, with the same proposed and landed styles as text. The built page
contains the original colours and does not need the separate SVG to display.
Data figures do not use the Mermaid figure counter. The checker requires a
local filename and a readable SVG document. When calling `book-check/check-book` directly, pass
`:source-path` in its options so it can find the SVG.

A proposal directory can show standalone `![alt text](drawing.png)` lines in
the body of a Markdown module. Put each PNG at the proposal root beside
`manifest.datom`. An added line uses the copy and appears under the green
addition margin. In a Changed module, a removed line comes from the Git
revision in the manifest and appears under the red removal margin. For an
image replacement with different bytes, name separate root files for the old
and new sides. Each panel embeds the exact validated bytes, in the order of
the signed lines. Neither image uses a numbered SVG or a separate image request.

Both commands check the PNG format, filename and placement. Supported
placements are signed body lines in a Proposal: additions to New and Changed
modules, and removals from Changed modules. Refusals go to the configured
coordinator.
The packaged commands include `pngcheck`; direct Babashka calls require
`pngcheck` on PATH when a proposal names a PNG.

To check a rendered artifact against its proposal, run
`book-check /abs/PROPOSAL_DIRECTORY /abs/BUILT.html`. The checker compares the
embedded PNGs in document order against the authenticated module copies and
the original assets. It maps the signed source lines to position slots in
the module's addition and removal panels without rendering any expected PNG markup. The one-path
`book-check /abs/PROPOSAL_DIRECTORY` checks the source alone.

A proposal's heading renders as the module's name alone, `<type>:<topic>:<version>`
(`vision/belief-modules/0.md` is `vision:belief-modules:0`), taken from the heading's
`repository › path`; a path that is not a module's is shown as it is. The proposal
number, its title and the repository do not render. Proposals one after another into
the same modules, in the same state, render under one heading in one sheet, their
changes in source order; a proposal in place stands under its module view's own
heading and adds none. The source heading keeps its form for the checker. A
`::: comment` container is optional everywhere and never required by the checker;
when present it renders as a plain note with no label. The subtitle, the line under
the title, is `Created by <Model>.`, the model one of `subtitle-models` at the head
of `book-check.clj`, and the checker refuses anything else on that line
(`subtitle-not-creator`, at the line); the builder renders it as written.

A replaced passage is one text, read once: unchanged words stand once, removed
words are struck through and shaded red, added words are underlined and shaded
green, each in place. The margin of a replaced passage carries both colours, red
beside green. Prose, descriptions, lists, headings, table rows and code lines
use this same rule; code retains its syntax highlighting. A removed and an added
Mermaid fence stay two figures.

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
source. A Proposal asks its questions after at least one proposal; a Review
after a proposal or a module view
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

A Review may also show whole module views with no proposal and nothing marked,
authenticated against each module as it stands and open to comments. An empty
Review still requires a proposal or a module view.

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

A new module may show its dependencies immediately after its description,
as an added inline list of complete registry IDs (an empty list is `+ []`):

    ::: dependencies
    + [vision:aspects:0, intent:design:0]
    :::

Each ID is checked against `modules.datom` at the current `origin/main`
revision of psyche-belief, mind-belief and field-belief; an unknown name or
an unavailable registry refuses the container. Module bodies are never read. IDs carry explicit canonical versions, and duplicates are
refused. The builder shows a labelled Dependencies block at the head of the
module sheet, with the addition margin. Existing books may omit this container.
The checker option `:dependency-names` supplies the membership reader in tests.

A module view is headed `## repo › path`. The heading is defined once, in
book-check (`module-view-pattern`), and the checker and the builder both match
it through that definition. The builder displays the full `repo › path` in
the module sheet’s heading, with the New badge only on a new module. A
module's file name may be a number, a span or `all` (`vision/flow-nexus/0-1.md`).

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

**codex-context-watch.clj** is a stopgap watcher for one Codex permaflow address.
It discovers the current flow through `herdr agent list`, matches the address
and flow id in rollout headers, and watches Codex session directories with
`inotifywait` (inotify-tools). New rollout files move the watcher to the fresh
flow of that address and rearm its two handover orders: MiddleAbrupt at SIZE,
HardAbrupt at SIZE + margin, each at most once per flow during the watcher’s
lifetime. Old rollout appends and reopened recordings preserve that accounting.
There is no idle expiry or timer scan; stop it with SIGTERM.

    bb codex-context-watch.clj ADDRESS SIZE [ROLLOUT] [--margin N] [--home DIR]... [--inotifywait COMMAND] [--dry]

Start one transient user unit per address, named
`codex-context-watch-<Aspect>-<topic>-<Model>`, so every unit name holds the
whole address. The complete start command is in the file
header. SIZE and margin remain arguments (currently 300000 and 50000).
`--home` is repeatable. ROLLOUT selects an initial recording for a `--dry`
replay; dry mode prints readings and orders and sends nothing. Directory
watches are installed before discovery, so appends during startup stay queued.

## Tests

    nix flake check -L

builds the test environment from the locked flake and runs all eleven
`test/*-test.clj` files in the Nix build sandbox. Babashka, Chromium,
inotify-tools, Python, Git, Nix, tree-sitter, its compiler and grammar sources,
the configured Ethos validator, fonts and shell utilities come from the flake;
no test executable needs to be installed on the host. Fixtures are in
`test/fixtures`.

For working-tree tests or an individual test file, use the same inputs:

    nix develop -c bb test
    nix develop -c bb test/book-phone-code-test.clj

The phone rendering and theme tests drive headless Chromium through
`test/cdp.clj`; the watcher integration test uses `inotifywait`. Outside
the flake environment, `bb test` uses the executables on PATH, with
`CHROMIUM` and `INOTIFYWAIT` available as explicit overrides.

`BOOK_GRAMMARS` and `BOOK_ETHOS` select grammar and validator JSON
configuration files; by default the builder reads the adjacent
`book-grammars.json` and `book-ethos.json`. The flake supplies equivalent
configurations pointing at locked store inputs, leaving authored registries
unchanged. The development shell uses a fresh temporary cache so compiled
parsers from another grammar revision cannot affect the run. Its validator input is checked against `book-ethos.json` so a
validator update requires updating the flake input and lock together.
