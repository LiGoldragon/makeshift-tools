Presentation.{ «What a book is» }

# What a book is

## psyche-context › vision/book.md

```markdown
## Books

A book is a proposal into belief modules, and the whole of it is its crystallization: a line removed shows in red, a line added in green, and a figure in it is a figure proposed for a module.

A book carries the proposals; it shows no verbatim quote, and the records it draws from are not shown.

The records a proposal draws from live in an unrendered reference file, one per book, in a references/ directory beside the books; they are archived with the landing when some or all of the proposal is accepted.

The first edition of a book is mostly questions.

The book is the center of development: it is the evolution of an idea, and the list of unknowns lives in it.

A figure shows how a thing moves, one question per figure, readable in seconds. Its grammar: a box is a thing or a collection; an arrow is a one-way relation carrying a verb, so that box, verb, box reads as a sentence; nearness and enclosure are kinship; top to bottom is order; colour carries a role or is absent. A list in boxes, a tally, or a sentence in a rectangle is not a figure. A diagram in a book's source is ASCII in that grammar, laid out vertically and no wider than 52 characters so a phone shows it; a sketch without arrows is rewritten before it is sent. The published book never shows the ASCII, but a figure drawn from it by a Tertiary or Quaternary subflow that is given the grammar with the sketch, keeps every box, label and arrow, and adds nothing the source does not say; a figure that lost an arrow is returned. A sizable book carries several figures.

Markdown is never shown in a code block: a module written in Markdown is shown as rendered text.

## Pictures

An idea reaches the living as a picture first. A figure shows one thing, where the idea has a shape, and its colours and marks mean the same in every book.

A proposal is drawn as well as written: what it adds, removes and keeps, in one frame.

A proposal that touches a code component shows the code it changes or adds.

Every comment of his redoes the book it is on, unless the comment closes the topic; a book his comment affects is redone as well. A comment that opens a topic of its own gets a book of its own.
```

### Proposal 1: A book is views of modules · psyche-context › vision/book.md

- A book is a proposal into belief modules, and the whole of it is its crystallization: a line removed shows in red, a line added in green, and a figure in it is a figure proposed for a module.
- A book carries the proposals; it shows no verbatim quote, and the records it draws from are not shown.
+ A book is nothing but views of belief modules. A view shows one module under its path: a knowledge, compensation or inquiry module whole, as it stands, figures and code included; a psyche module with its change, a line removed in red and a line added in green.
+ Outside a module only two things are written: the heading that names the section's kind and path, and the ruling line. A book has no prose of its own and no verbatim quote; the records it draws from are not shown.
+ The comparison is made by placement: the module as it stands first, and directly under it the psyche proposal that answers it, whose own lines say what the vision is of that state.
+ A fact is landed in a knowledge module before it is shown; a doubt is landed in an inquiry module; a short-term solution is shown as the compensation module it is, never as a change to approve. A flow with something to say that fits no module has not finished its work.

```
+------------------+
| book             |
+------------------+
        | holds
        v
+------------------+
| section          |
+------------------+
    | is a view of        | is a proposal to
    v                     v
+------------------+  +------------------+
| knowledge,       |  | vision, intent,  |
| compensation or  |  | spirit, with its |
| inquiry module   |  | change marked    |
| as it stands     |  +------------------+
+------------------+          | answers
        ^                     |
        +---------------------+
```

Ruling: land, amend, or reject.

### Proposal 2: The inquiry module · psyche-context › vision/belief-modules.md

+ An inquiry module holds what is unclear: one question per heading, the observation that raised it, and the answer it waits for. When the answer comes, the question leaves the inquiry module and the answer lands in the module it belongs to. A book shows an inquiry module as it stands, so the living sees what the machine does not know.

Ruling: land, amend, or reject.

### Proposal 3: The first inquiry module · mind-context › inquiry/books.md, a new file

+ ---
+ description: What is unclear about how a book is made and checked.
+ dependencies: []
+ ---
+ ## Which revision does a quoted module match?
+ Observed: the checker compares a quoted module with its blob on the main branch of origin.
+ Waiting for: the rule for a module landed but not yet pushed.
+ ## Who draws a figure for a book built in a flow?
+ Observed: the builder refuses a diagram with no figure file; the Illustrator is a subagent of the Publisher.
+ Waiting for: the route by which a flow other than the Publisher obtains the Illustrator's drawing.

Ruling: land, amend, or reject.

### Proposal 4: The book tools as they stand · mind-context › knowledge/book-tools.md, a new file

+ ---
+ description: A book is built from its source or checked for its form.
+ dependencies: []
+ ---
+ ## The checker
+ `bb tools/book-check.clj /abs/SOURCE.md` reads one source and prints its refusals, one reason per line. It refuses a proposal with no added line, a `File:` line, a code line over 52 code points, any text or fence outside a proposal or a quoted module, a proposal heading without repository and path, a quoted module whose body differs from its blob on origin main, a heading outside a proposal other than the title, a quote line, a source with no proposal, and an unclosed fence.
+ ## The builder
+ `bb tools/book-build.clj SOURCE.md OUT.html --figures DIR --ethos-zero REF` renders the page. A heading `### Proposal N: title · repo › path` opens a proposal; `+ ` and `- ` lines render green and red, a run of removed lines word-diffed against the added run after it; a heading `repo › type/subject.md` outside a proposal quotes that module. Every ethos fence is written to a file and checked by `ethos-zero Check` from REF, built once with nix; a refused block stops the build. A fence holding a sketch with an arrow is a diagram, never drawn by the builder: diagram n is replaced by `DIR/fig-n.svg`, cleaned of scripts and external links, and a missing figure stops the build.

```
+--------------------+
| flow               |
+--------------------+
   | lands a fact in
   v
+--------------------+
| knowledge module   |
+--------------------+
   | is viewed by
   v
+--------------------+
| book source        |
+--------------------+
   | is refused or passed by
   v
+--------------------+
| book-check         |
+--------------------+
   | is rendered by
   v
+--------------------+
| book-build         |
| ethos checked,     |
| figures inlined    |
+--------------------+
   | is drawn for by
   v
+--------------------+
| Illustrator        |
+--------------------+
   | is published by
   v
+--------------------+
| Publisher          |
+--------------------+
   | reaches
   v
+--------------------+
| the living         |
+--------------------+
   | comments on; a comment redoes
   v
+--------------------+
| next edition       |
+--------------------+
```

Ruling: land, amend, or reject.

### Proposal 5: The checker refuses prose inside a proposal · primary › tools/book-check.clj

+ Inside a proposal and outside a fence, a line is a change line, a `Standing` line, or the ruling line; anything else is prose and is refused.

```js
+ const standing = /^Standing\.(Ruled|Open)$/;
+ const ruling =
+   /^Ruling: land, amend, or reject\.$/;
+ const proseInProposal = (l) =>
+   l.trim() !== "" &&
+   !change.test(l) &&
+   !standing.test(l.trim()) &&
+   !ruling.test(l.trim());
+ // in checkBook, in a proposal, outside a fence:
+ if (proseInProposal(line))
+   errors.push({ line: n,
+                 reason: "prose-in-proposal" });
```

Ruling: land, amend, or reject.

### Proposal 6: The builder renders a view · primary › tools/book-build.clj

+ A quoted module renders as a view: the path as its heading, the module body whole, figures and code as the module carries them, with no change marks.

```clj
+ (defn view-el [repo path body]
+   [:article.view
+    [:h2.path (str repo " › " path)]
+    (body-html body)])
+ ;; in build, where a module heading is met:
+ (view-el repo path (module-body lines))
```

Ruling: land, amend, or reject.

### Proposal 7: Long term, a book's source is a datom · psyche-context › vision/book.md

+ ## Long term: the source is a datom
+ A book's source is a datom against the book library: a title and its sections, each a view or a proposal with its path and body. The Markdown source is the short-term form.

```ethos
Library.
{
[]                                        ; imports
[ Path.String                             ; types
  Kind.[ View Proposal ]
  Section.{ Kind Path String }            ; the body
  Book.{ String Vector<Section> } ]     ; the title
[ Rendering.[ render.[ String ] ]         ; traits
  Checking.[ check.[ Vector<String> ] ] ]
[ Section.[ Rendering ]               ; associations
  Book.[ Rendering Checking ] ] }
```

Ruling: land, amend, or reject.
