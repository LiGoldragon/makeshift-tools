<!-- to-the-living:start -->
Presentation.{ «Flow: context modules and the prompt» }

# Flow: context modules and the prompt

## Crystallization

### Proposal 1: the system prompt is modules; a part of it is the main flow's alone

A module: one context file composed into a prompt. A harness: the
program that runs a model with its tools, Claude Code or Codex. A
subagent: a flow the harness itself starts inside a main flow's run.

Sources: `flows/5578cc/vision/flow.md`, 2026-10-03;
`flows/d68c82/vision/context-modules.md`, 2026-10-13.

File: `psyche-context/skills/vision-flow.md`

```text
  The Flow Nexus sets up and starts a model flow: its working
  directory, system prompt, training files and instruction prompt. It
  takes the place of the abandoned training daemon.
+
+ The system prompt is not one thing: it is composed of modules, of several types, and what a harness calls a skill overlaps with them. Its anatomy is drawn up in ethos from a study of system prompts of every kind, each line placed by what it is, a statement of what the flow is, a behavior, a personality, an operational safety, and by the kind of training or guidance it belongs to.
+
+ Where the harness allows it, a part of a main flow's system prompt is the main flow's alone, not passed down to its subagents; the main flow is programmed there and its subagents are not.
```

File: `primary/tools/main-flow-mode/system-prompt.md` (removed whole:
the one base file the launcher prepends to every main flow's system
prompt)

```text
- You are a main flow: one of the seats that extend the living psyche. Your aspect, topic and layer are given in your startup prompt.
- 
- Your work is dialogue, coordination, priorities, authority, evidence review, and synthesis. You do not do bounded work yourself. Every inspection, search, test, edit, build, deploy, and probe is delegated to a subflow with a brief, and you receive its concise evidence. A message whose body the main flow wrote itself, it sends itself, in one command. A subflow is a separate process; what it did, you did.
- 
- A question from the living about the flow's own conduct or reasons is answered from the flow's own context, with no subflow and no message to another flow, and presented to the living. A question about the system is answered in that turn by finding out.
- 
- When the living points out a fault, the reply gives its cause and the line that changes the behavior that produced it; repairing the faulty artifact comes second, and never stands in for the behavior change.
- 
- You run a shell command for two things only: claiming your Flow ID, and the writes you own: your log, your psyche records, and your summary. Leave Primary edits for the sole standing committer; never stage, commit, push or take PrimaryPublish yourself. In other repositories, commit and push your own changes. Everything else that reaches into the harness, the cluster, or the code goes through a subflow.
- 
- A design, report, book, prompt or message carries the thing as it now is. What was once wrong, objected, corrected, or run into is not written there, not as context and not as history; it lives in the flow log alone.
- 
- Protect your context. Never read a file whole to find one thing. Never paste a skill or a transcript into your reply. Ask a subflow to locate, read, and return the relevant part.
- 
- Never block on a subflow. When the living speaks, answer from what is already witnessed; a holding line while a subflow is out is a complete answer.
- 
- A claim is relayed as a claim; a thing is verified only by a witness. Keep observations, hypotheses, and unknowns separate.
```

File: `psyche-context/skills/intent-main-flow.md` (as it stands: an
ordinary intent module, composed into the system prompt by each
metaflow's module list, Mind's; it holds the base file's lines, the
third paragraph in this later wording, which stands)

```text
  ---
  description: "The system context of every main-flow seat: composed into the seat's SYSTEM by the launcher, never loaded as a skill."
  user-only: true
  dependencies: []
  ---
  
  You are a main flow: one of the seats that extend the living psyche. Your aspect, topic and layer are given in your startup prompt.
  
  Your work is dialogue, coordination, priorities, authority, evidence review, and synthesis. You do not do bounded work yourself. Every inspection, search, test, edit, build, deploy, and probe is delegated to a subflow with a brief, and you receive its concise evidence. A message whose body the main flow wrote itself, it sends itself, in one command. A subflow is a separate process; what it did, you did.
  
  A question from the living about the flow's own conduct or reasons is answered in that reply, from the flow's own context, with no subflow and no message to another flow. A question about the system is answered in that turn by finding out.
```

File: `primary/tools/claude-main-flow-launch.mjs`

```js
-     ? composeSpiritSystemPrompt(base, mainSystemModules(mode.workspace, '.claude', mode.seat))
```

```js
-     const systemBase = o.systemPromptFile ?? path.join(o.workspace, 'tools/main-flow-mode/system-prompt.md');
-     composeSpiritSystemPrompt(fs.readFileSync(systemBase, 'utf8'), mainSystemModules(o.workspace, '.claude', systemSeat));
```

File: `primary/tools/main-flow-mode/spirit-system-prompt.mjs`

```js
- export function composeSpiritSystemPrompt(base, modules) {
-   if (!base.trim()) throw new Error('main-flow system prompt missing or empty');
-   if (!modules.length || new Set(modules.map(module => module.name)).size !== modules.length)
-     throw new Error('spirit system modules must be nonempty and unique');
-   return `${base.trimEnd()}\n\n${modules.map(module => `# ${module.name}\n\n${module.body}`).join('\n\n')}\n`;
- }
```

```js
-   return composeSpiritSystemPrompt(messages.instructions_template, mainSystemModules(workspace, '.agents', seat));
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 1: the call site that replaces the two above; the system
prompt is modules alone, with no base file)

```clojure
  (defn system-prompt [config aspect mf]
    (join-parts config
                (map #(module-block config %)
                     (modules aspect mf :system))))
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 5: the flag that carries it)

```clojure
     "--system-prompt-file" :system-prompt
```

Ruling, one number from each group:

The paragraphs and the code:
1. Land.
2. Land with changes the living names.
3. Drop.

Where the part not passed down is stated:
4. In vision-flow, as shown, machine choice: the record is a vision
   record of the Flow topic (`flows/5578cc/vision/flow.md`).

### Proposal 2: a context module, not a skill; the context repositories

A context repository: the git repository holding one aspect's
context modules; psyche-context, mind-context and field-context are
their names today.

Sources: `flows/d68c82/vision/context-modules.md`, 2026-10-13, three
records; `flows/5578cc/vision/flow.md`, 2026-10-03;
`flows/f5a6e9/vision/contextModules.md`, 2026-10-09.

File: `psyche-context/skills/vision-context-modules.md` (builds on
«33752b book 29» Proposal 1)

```text
  What a flow loads is a context module. Psyche holds spirit, intent and vision. Mind holds knowledge, what was witnessed of a system, and inquiry, what is not known and what would settle it. Field holds compensation.
+
+ The vocabulary is context, context modules and context building. A skill is the legacy name: a harness's one name for things that differ, becoming obsolete as harness infrastructure improves; what is still called a skill is read as a context module of the regular kind. The context modules live in the context repositories, one per aspect: psyche-context, mind-context and field-context.
```

File: `psyche-context/skills/vision-psyche.md`

```text
- The skills belong in a repository. This is a target shape, not authorization to
- create or migrate a repository (native desktop record, archive ordinal 1835).
+ The context modules belong in a context repository; Psyche's is psyche-context (native desktop record, archive ordinal 1835; flow d68c82, 2026-10-13).
```

File: `psyche-context/skills/vision-flow.md`

```text
- ## Repository and skills
+ ## Repository and context modules
  
  The flow repository holds the machinery of the Flow Nexus and is a
- runtime repository. Every skill lives outside it, the basic skills
- included, so that a change to a skill causes no Nix rebuild. The
- basic skills give our own take on how an agent behaves in a harness,
+ runtime repository. Every context module lives outside it, the basic modules
+ included, so that a change to a module causes no Nix rebuild. The
+ basic modules give our own take on how an agent behaves in a harness,
  replacing the prompt the harnesses build in.
```

File: `/git/github.com/LiGoldragon/psyche-skills` (the link, removed
whole; the three links are the old names, flow d68c82, 2026-10-13:
«should also have been renamed from skills by now»)

```text
- psyche-skills -> /git/github.com/LiGoldragon/psyche-context
```

File: `/git/github.com/LiGoldragon/mind-skills` (the link, removed
whole)

```text
- mind-skills -> /git/github.com/LiGoldragon/mind-context
```

File: `/git/github.com/LiGoldragon/field-skills` (the link, removed
whole)

```text
- field-skills -> /git/github.com/LiGoldragon/field-context
```

Ruling, one number from each group:

The paragraphs and the lines:
1. Land.
2. Land with changes the living names.
3. Drop.

The layout inside a context repository:
4. One directory per subaspect, the file named for the topic:
   `vision/ethos.md` as it already stands, `skills/vision-flow.md`
   becoming `vision/flow.md` (flow f5a6e9, 2026-10-09: «should be
   psyche-skills/vision/ethos.md»):

File: `psyche-context/skills/` (the same in mind-context and
field-context; the kind is the first word of the name, the topic the
rest)

```bash
+ for f in skills/*-*.md; do
+   n=${f#skills/}
+   git mv "$f" "${n%%-*}/${n#*-}"
+ done
```

File: `primary/SKILL_VARIABLES.md` (the variable names the repository;
the layout inside it is the registry's to know, Proposal 5)

```text
- Psyche context: /git/github.com/LiGoldragon/psyche-context/skills
- Mind context: /git/github.com/LiGoldragon/mind-context/skills
- Field context: /git/github.com/LiGoldragon/field-context/skills
+ Psyche context: /git/github.com/LiGoldragon/psyche-context
+ Mind context: /git/github.com/LiGoldragon/mind-context
+ Field context: /git/github.com/LiGoldragon/field-context
```

File: `primary/config/main-flow/registry.edn` (Proposal 5; each
entry's `:path` alike)

```clojure
-    :path "skills/vision-flow.md"}}
+    :path "vision/flow.md"}}
```

```clojure
-    :path "skills/compensation-truth.md"}}]
+    :path "compensation/truth.md"}}]
```

5. The flat `skills/<kind>-<topic>.md` kept for the regular modules,
   machine choice: Curriculum generates the harness trees from that
   directory today.

### Proposal 3: the metaflow module, one section per layer; the subagent section

A section: a directory of a context repository. A subagent
definition: the file a harness reads to run a named subagent, the
agent file of «book 08» Proposal 1. Front matter: the fenced key
lines at the head of a file, read by the harness, not by the model.

Sources: `flows/d68c82/vision/context-modules.md`, 2026-10-13, two
records; `flows/5578cc/vision/flow.md`, 2026-10-03.

File: `psyche-context/skills/vision-context-modules.md`, after
Proposal 2's paragraph

```text
+ A context module has a type beyond its subaspect. The regular module is one, the one a skill parallels. The metaflow module is another: it belongs to a flow's role, not to a subject, and the flow it is given to does not see it as a module. A context repository holds, beside its regular modules, one section per layer, primary, secondary, tertiary and quaternary, and may hold sections for specialized topics; a module in a layer section is addressed by its repository's aspect, its section's layer and its file's topic, so that psyche-context/primary/core.md is the Psyche core Primary's. What is about a topic rather than a role goes in a regular module, vision-ethos for the ethos topic. Every context repository may also hold a subagent section, whose modules the named subagent definitions are made from.
+
+ +--------------------------------------+
+ | the metaflow address                 |
+ | { Psyche core Primary }              |
+ +--------------------------------------+
+         | names, by its aspect
+         v
+ +--------------------------------------+
+ | psyche-context, the context repo     |
+ +--------------------------------------+
+         | holds, by its layer
+         v
+ +--------------------------------------+
+ | primary/, the layer section          |
+ +--------------------------------------+
+         | holds, by its topic
+         v
+ +--------------------------------------+
+ | core.md, the metaflow module         |
+ +--------------------------------------+
+         | enters, unseen as a module
+         v
+ +--------------------------------------+
+ | the flow's system prompt             |
+ +--------------------------------------+
```

File: `primary/tools/main-seat-roles/psyche-core-tertiary.md`
(removed whole: the one role file the launcher reads today; its
`name`, `model`, `effort` and `tools` are the three files below;
`description` has no file, machine reason: it is the harness agent
file's field, and a main flow has no agent file)

```text
- ---
- name: psyche-core-tertiary
- description: 'Main-seat role instruction module'
- model: 'sonnet'
- effort: medium
- tools: Read, Grep, Glob, Bash, Edit, Write, WebFetch, WebSearch, LSP
- ---
- 
- Psyche Core Tertiary receives the living's notice that he has commented on a book. It does not watch artifacts, arm replies, ask him to click, or run a timer.
- 
- On that notice, read every comment on the named book with ArtifactComments. For each new comment, read the artifact page around its anchor to recover the full anchored passage; the comment listing truncates that passage. Preserve the book, section, complete anchored passage and the living's exact words, with the available author, time and input-mode provenance. Do not infer missing provenance.
- 
- Send each new comment whole as #psyche to the registered Psyche Core Secondary through the supported messenger route. The Secondary routes it. Do not reply to or resolve the comment, publish a book, or decide its ruling.
- 
- Keep a persistent relay ledger in FLOW_DIRECTORY/comment-relay-ledger.jsonl. Identify a comment by its book, thread identity, author, time and exact words. Read the ledger before sending, and record each successful delivery with its receipt. A held or refused send is not recorded as delivered. Retain the context and pending delivery, report its concrete blocker, and never resend a delivered comment. A later notice reads all comments and relays only new ones. Recover this ledger with the same standing role; do not treat a new session as an empty relay history.
```

File: `psyche-context/tertiary/core.md`

```text
+ Psyche Core Tertiary receives the living's notice that he has commented on a book. It does not watch artifacts, arm replies, ask him to click, or run a timer.
+ 
+ On that notice, read every comment on the named book with ArtifactComments. For each new comment, read the artifact page around its anchor to recover the full anchored passage; the comment listing truncates that passage. Preserve the book, section, complete anchored passage and the living's exact words, with the available author, time and input-mode provenance. Do not infer missing provenance.
+ 
+ Send each new comment whole as #psyche to the registered Psyche Core Secondary through the supported messenger route. The Secondary routes it. Do not reply to or resolve the comment, publish a book, or decide its ruling.
+ 
+ Keep a persistent relay ledger in FLOW_DIRECTORY/comment-relay-ledger.jsonl. Identify a comment by its book, thread identity, author, time and exact words. Read the ledger before sending, and record each successful delivery with its receipt. A held or refused send is not recorded as delivered. Retain the context and pending delivery, report its concrete blocker, and never resend a delivered comment. A later notice reads all comments and relays only new ones. Recover this ledger with the same standing role; do not treat a new session as an empty relay history.
```

File: `primary/config/main-flow/metaflow/Psyche-core-Tertiary.edn`
(builds on «book 07» Proposal 10 and «book 08» Proposal 5; a new
shorthand: `name` is the file's address; `tools` is the `:claude` set
of «book 07» Proposal 6, machine choice: Grep, Glob and LSP are not in
that set, and Bash reaches what they reach; `:mode :design` the
machine's, as «book 08» Proposal 5)

```clojure
+ {:aspect :Psyche :topic :core :layer :Tertiary
+  :mode :design
+  :tools :claude
+  :modules {:system [] :user []}}
```

File: `primary/config/main-flow/psyche.edn` (builds on «book 08»
Proposal 2, as it stands there: the layer decides `model` and
`effort`)

```clojure
            {:layer :Tertiary
             :model [:sonnet :medium]
             :window 1000000}
```

File: `primary/subagents/scripter.md` (builds on «book 08»
Proposal 1; under choice 6)

```text
- # Scripter
- 
- A main flow calls you with a brief naming a
- small thing to run. Write the script, run it,
- and return what happened: the command, its
- output and what you conclude, in a few lines.
```

File: `psyche-context/subagent/scripter.md`

```text
+ # Scripter
+ 
+ A main flow calls you with a brief naming a
+ small thing to run. Write the script, run it,
+ and return what happened: the command, its
+ output and what you conclude, in a few lines.
```

File: `primary/subagents/` (under choice 6; the three files Primary
holds, in no context repository, moved whole; Mind's: Curriculum
reads `subagents/<name>.md` into each Claude agent it generates, and
Primary's generated agents are `book` and `book-page`, not
`book-reader`)

```bash
+ git mv subagents/book.md \
+   ../psyche-context/subagent/book.md
+ git mv subagents/book-page.md \
+   ../psyche-context/subagent/book-page.md
+ git mv subagents/book-reader.md \
+   ../psyche-context/subagent/book-reader.md
```

File: `primary/tools/main-flow-mode/spirit-system-prompt.mjs`

```js
-   const role = `${aspect.toLowerCase()}-${topic.toLowerCase()}-${layer.toLowerCase()}`;
-   const roleFile = path.join(workspace, 'tools', 'main-seat-roles', `${role}.md`);
-   if (fs.existsSync(roleFile)) {
-     const body = fs.readFileSync(roleFile, 'utf8').replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '').trim();
-     if (!body) throw new Error(`empty main-seat role: ${role}`);
-     modules.push({name: `main-seat-role-${role}`, body});
-   } else if (aspect === 'Psyche' && topic === 'Core' && layer === 'Tertiary') {
-     throw new Error('generated Psyche Core Tertiary role is missing');
-   }
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 1, as it stands there: what replaces `mainSystemModules`;
`metaflow-block` below adds the role)

```clojure
  (defn modules [aspect mf placement]
    (concat (get-in aspect [:modules placement])
            (get-in mf [:modules placement])))
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 10; inside its map)

```clojure
+  ;; the section is the layer lowercased, the
+  ;; file the topic: the address names the file.
+  :metaflow-module "%s/%s.md"
+  :subagent-module "subagent/%s.md"
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 1 and «book 08» Proposal 1; after `module-body`;
`AspectRepositoryUnknown`, `MetaflowModuleMissing` and
`SubagentModuleMissing` are the three refusals' own names)

```clojure
+ ;; The aspect names its context repository: the
+ ;; repository key is the aspect's name lowercased.
+ (defn aspect-repo [config aspect-config]
+   (let [key (keyword
+              (str/lower-case
+               (name (:aspect aspect-config))))]
+     (or (get-in config [:repositories key])
+         (throw (ex-info "AspectRepositoryUnknown"
+                         {:aspect key})))))
+
+ ;; git's own words read the file at the declared
+ ;; revision, in its revision:path form.
+ (defn repo-file [repo file refusal]
+   (let [out (sh "git" "-C" (:path repo) "show"
+                 (str (:ref repo) ":" file))]
+     (when-not (zero? (:exit out))
+       (throw (ex-info refusal {:file file})))
+     (:out out)))
+
+ (defn metaflow-block [config aspect-config mf]
+   (let [file (format (:metaflow-module config)
+                      (str/lower-case
+                       (name (:layer mf)))
+                      (name (:topic mf)))]
+     (module-body
+      (repo-file (aspect-repo config aspect-config)
+                 file "MetaflowModuleMissing"))))
+
+ (defn subagent-block [config aspect-config sub]
+   (let [file (format (:subagent-module config)
+                      (name (:name sub)))]
+     (module-body
+      (repo-file (aspect-repo config aspect-config)
+                 file "SubagentModuleMissing"))))
+
+ (defn main-system-prompt [config aspect mf]
+   (join-parts config
+               [(metaflow-block config aspect mf)
+                (system-prompt config aspect mf)]))
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 5 and Proposal 10 and «book 08» Proposal 2; in
`start-call`; `:system-prompt` is the field the command template
reads for `--system-prompt-file`)

```clojure
             :effort (effort! mv (:effort asked))
-            :tools (tools config mf)}
+            :tools (tools config mf)
+            :system-prompt
+            (main-system-prompt config
+                                aspect-config mf)}
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
08» Proposal 1; in `agent-file`)

```clojure
           "\n---\n\n"
-          (system-prompt config {} sub))))
+          (join-parts
+           config
+           [(subagent-block config aspect-config
+                            sub)
+            (system-prompt config {} sub)]))))
```

File: `mind-context/skills/operation-skill-designing.md` (a Mind
module, the machine's; its role-skill lines describe what the
metaflow module now is)

```text
- A role skill carries an aspect's identity and names its
- dependencies. Mark role skills user-only.
+ A metaflow module carries a flow's role; it is read from its context repository's layer section and is never loaded by the flow.
```

Ruling, one number from each group:

The paragraph, the figure and the code:
1. Land.
2. Land with changes the living names.
3. Drop.

A metaflow whose layer section has no file for its topic:
4. Refused, `MetaflowModuleMissing`, as shown, machine choice: a
   launch takes what is declared («book 07» Proposal 11).
5. Started without a metaflow block, machine choice: a metaflow
   without a role file is every metaflow today:

```clojure
- (defn metaflow-block [config aspect-config mf]
-   (let [file (format (:metaflow-module config)
-                      (str/lower-case
-                       (name (:layer mf)))
-                      (name (:topic mf)))]
-     (module-body
-      (repo-file (aspect-repo config aspect-config)
-                 file "MetaflowModuleMissing"))))
+ (defn metaflow-block [config aspect-config mf]
+   (let [file (format (:metaflow-module config)
+                      (str/lower-case
+                       (name (:layer mf)))
+                      (name (:topic mf)))
+         repo (aspect-repo config aspect-config)
+         out (sh "git" "-C" (:path repo) "show"
+                 (str (:ref repo) ":" file))]
+     (when (zero? (:exit out))
+       (module-body (:out out)))))
```

```clojure
  (defn main-system-prompt [config aspect mf]
    (join-parts config
-               [(metaflow-block config aspect mf)
-                (system-prompt config aspect mf)]))
+               (remove
+                nil?
+                [(metaflow-block config aspect mf)
+                 (system-prompt config aspect
+                                mf)])))
```

The subagent section a subagent belongs to:
6. The calling aspect's context repository, as shown for
   `scripter`, machine choice: his words give every context
   repository a subagent section and name no subagent's home.
7. field-context for every subagent, machine choice: a subagent
   definition is machine-written and unreviewed (flow d68c82,
   2026-10-13: «Field is 100% agent-reviewed»):

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 10; inside its map)

```clojure
+  :subagent-home :field
```

File: `messenger-clj/src/messenger_clj/launch.clj`, `subagent-block`
above:

```clojure
      (module-body
-      (repo-file (aspect-repo config aspect-config)
+      (repo-file (get-in config
+                         [:repositories
+                          (:subagent-home config)])
                  file "SubagentModuleMissing"))))
```

File: `field-context/subagent/scripter.md` (in place of
`psyche-context/subagent/scripter.md` above)

```text
+ # Scripter
+ 
+ A main flow calls you with a brief naming a
+ small thing to run. Write the script, run it,
+ and return what happened: the command, its
+ output and what you conclude, in a few lines.
```

### Proposal 4: each module in the system prompt or the first prompt; a hook injects what was queued

A hook: a program the harness runs at an event of the flow's run,
before a prompt enters among them. The first prompt: the first
message the flow receives after its system prompt, the startup
prompt of intent-startup-prompt.

Sources: `flows/e5a0bc/vision/flow.md`, 2026-10-07;
`flows/edf227/vision/contextModules.md`, 2026-10-03;
`flows/bad807/vision/flowContextInjection.md`, 2026-10-04.

File: `psyche-context/skills/vision-flow.md`

```text
- A layer is Primary, Secondary, Tertiary or Quaternary, in every aspect; the layer decides the model, declared once in Flow as configuration and changed only over its meta socket. A launch names the metaflow, the context modules composed into its prompts, and a brief of a small paragraph; the Nexus composes the prompt from its module registry, checking each module's hash against its source.
+ A layer is Primary, Secondary, Tertiary or Quaternary, in every aspect; the layer decides the model, declared once in Flow as configuration and changed only over its meta socket. A launch names the metaflow, the context modules composed into its prompts, each placed in the system prompt or in the first prompt, and a brief of a small paragraph; the Nexus composes the prompt from its module registry, checking each module's hash against its source.
```

File: `psyche-context/skills/vision-flow.md`

```text
  A request reaching a metaflow is one of a closed set of types, each with its waking effect: an order, a question, the living's words, a result, a notice. Message asks Flow to send a request to a metaflow; Flow owns the lock and the delivery, answers sent or queued, and Message subscribes to Flow's events, learning when a request has entered the flow's context. A metaflow idle is woken by a waking request and receives its whole queue, oldest first, in the first prompt; a metaflow under refresh queues what arrives until its successor has started.
+
+ A hook controls what is injected into a flow's prompt and what goes through messaging. What has been queued in preparation for a flow, the vision touching its topic among it, is injected along with the message that wakes it, so that the flow knows it as soon as it wakes, and the flow is not woken each time something touching its topic accumulates.
+
+ +--------------------------------------+
+ | what touches the topic: vision, and  |
+ | the rest that accumulates            |
+ +--------------------------------------+
+         | is queued for
+         v
+ +--------------------------------------+
+ | the idle metaflow                    |
+ +--------------------------------------+
+         | is woken by
+         v
+ +--------------------------------------+
+ | a waking request                     |
+ +--------------------------------------+
+         | passes through
+         v
+ +--------------------------------------+
+ | the hook                             |
+ +--------------------------------------+
+         | injects, with the whole queue, into
+         v
+ +--------------------------------------+
+ | the flow's prompt                    |
+ +--------------------------------------+
```

File: `primary/tools/claude-main-flow-launch.mjs` (a module's place
is the metaflow file's `:system` or `:user` vector, «book 07»
Proposal 1, not its kind)

```js
-   if (skillNames.some(name => name === 'spirit' || name.startsWith('spirit-') || name.startsWith('intent-'))) throw new Error('startup spirit and intent modules belong only in the main system prompt');
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 1; inside `:prompt`; the queue is a part of the first
prompt, before the brief)

```clojure
-  :prompt {:parts [:words :mode :modules :brief]
+  :prompt {:parts [:words :mode :modules
+                   :queue :brief]
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 10; inside its map; a metaflow's queue is a file named from
its address, as its shorthand is)

```clojure
+  :queues {:dir "queue" :file "%s-%s-%s.edn"}
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 7; inside `:hooks`, the `:claude` vector; `inject` is the
command below)

```clojure
-                   {:event "UserPromptSubmit"
-                    :command "remind"
-                    :timeout-s 10}]
+                   {:event "UserPromptSubmit"
+                    :command "remind"
+                    :timeout-s 10}
+                   {:event "UserPromptSubmit"
+                    :command "inject"
+                    :timeout-s 10}]
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 10; after `shorthand`; Mind's: a UserPromptSubmit hook
in Claude and Codex adds the text it already has, and nothing in the
harness selects, drains or acknowledges a queue, so these three do)

```clojure
+ (defn queue-file [config address]
+   (let [{:keys [dir file]} (:queues config)
+         parts (map name ((juxt :aspect :topic
+                                :layer)
+                          address))]
+     (fs/path (:dir config) dir
+              (apply format file parts))))
+
+ (defn queued [config address]
+   (let [file (queue-file config address)]
+     (if (fs/exists? file)
+       (edn/read-string (slurp (str file)))
+       [])))
+
+ ;; What arrives for an idle metaflow is appended,
+ ;; so the queue reads oldest first.
+ (defn queue! [config address text]
+   (let [file (queue-file config address)]
+     (fs/create-dirs (fs/parent file))
+     (spit (str file)
+           (pr-str (conj (queued config address)
+                         text)))))
+
+ ;; The whole queue is taken, and acknowledged by
+ ;; the emptying of its file.
+ (defn drain-queue! [config address]
+   (let [texts (queued config address)]
+     (spit (str (queue-file config address))
+           (pr-str []))
+     texts))
+
+ ;; The hook's stdout is the text the harness adds
+ ;; to the prompt it is submitting.
+ (defn inject [config address]
+   (print (join-parts config
+                      (drain-queue! config
+                                    address))))
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 1; in `prompt-parts`; the queue enters the first prompt
by the same draining)

```clojure
     :modules (join-parts
               config
               (map #(module-block config %)
                    (modules aspect mf :user)))
+    :queue (join-parts config
+                       (drain-queue! config mf))
     :brief (:brief launch)})
```

Ruling, one number from each group:

The sentences, the figure and the code:
1. Land.
2. Land with changes the living names.
3. Drop.

Which flows the hook serves (flow bad807, 2026-10-04: «some of the
flows, or maybe all of the flows»):
4. All, as shown, machine choice: his words name both, and all
   needs no declaration per flow.
5. Some, the machine reading of his first words:

```text
- A hook controls what is injected into a flow's prompt and what goes through messaging.
+ For the flows so declared, a hook controls what is injected into the flow's prompt and what goes through messaging.
```

### Proposal 5: the module registry

A registry: the table Flow keeps of where each context module is. A
hash: a short fingerprint computed from content, the same content
giving the same fingerprint; blake3 is one such function, its
fingerprint 32 bytes. Base64: a way of writing bytes as 44 letters
and digits. Curriculum: the Nexus that generates the harness trees
from the context modules.

Sources: `flows/f5a6e9/vision/contextModules.md`, 2026-10-09;
`flows/e5a0bc/vision/flow.md`, 2026-10-07;
`flows/edf227/vision/contextModules.md`, 2026-10-03.

File: `psyche-context/skills/vision-flow.md`, after Proposal 4's
first paragraph

```text
+ The registry is keyed by subaspect and topic, { Subaspect Topic }, and holds for each module its containing source, that source's hash, and the module's path relative to the source; from the Nexus's point of view a module's location is nothing but this entry. The registry's payloads live on the meta signal, through which the registry is modified; Curriculum can get or check the hash as a query comes in, according to its operation, write or read. Every context module is indexed in such a registry, possibly in more than one place.
+
+ +--------------------------------------+
+ | the launch: metaflow, modules, brief |
+ +--------------------------------------+
+         | names each module by
+         v
+ +--------------------------------------+
+ | { Subaspect Topic }, the key         |
+ +--------------------------------------+
+         | finds, in the registry
+         v
+ +--------------------------------------+
+ | the entry: source, hash, path        |
+ +--------------------------------------+
+         | is checked against
+         v
+ +--------------------------------------+
+ | the containing source                |
+ +--------------------------------------+
+         | yields
+         v
+ +--------------------------------------+
+ | the module's text, into its prompt   |
+ +--------------------------------------+
```

File: `psyche-context/skills/vision-nexus.md`

```text
  A Nexus starts with no arguments and there is no bootstrap binary.
  Its executable holds a default configuration as a constant. On start
  it looks for its Sema database at the default location: a database
  that exists holds the configuration; a database created new is
  seeded with the defaults. The meta socket carries a Configure
  interface, and changed values are accepted through it.
+
+ Flow's module registry is configuration of this kind: its entries change only through the meta socket.
```

File: `flow/crates/flow-ethos/ethos/library.ethos` (uncommitted in
the flow tree; Ethos, the Ethos metaflow's. Apart. Trial is removed,
flow d68c82, 2026-10-13; Operation by vision-context-modules)

```ethos
-   Subaspect.[ Vision Intent Knowledge Operation Trial Compensation ]
+   Subaspect.[ Spirit Intent Vision
+               Knowledge Inquiry Compensation ]
```

File: `primary/config/main-flow/registry.edn` (a new file, the
registry's form for the launcher; one entry per module, these two the
machine's examples; the Name is the hyphenated module name, machine
choice: Curriculum keeps hyphenated names, and Topic:Name is not yet
expressible in Flow's library, Mind's; each `:hash` is blake3 over
the source's tree, Proposal 6, as `register!` below writes it)

```clojure
+ [{:key {:subaspect :Vision :topic :vision-flow}
+   :source
+   {:repository :psyche
+    :hash
+    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
+    :path "skills/vision-flow.md"}}
+  {:key {:subaspect :Compensation
+         :topic :compensation-truth}
+   :source
+   {:repository :field
+    :hash
+    "hG4L9ewTx9IeTaasjVlkJk+H8NUal40N/EVfdg23vtk="
+    :path "skills/compensation-truth.md"}}]
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 1; inside each repository, then inside its map: the module's
path and its home are the registry's, no longer the configuration's)

```clojure
-                         :skills "skills/%s.md"}
+                         }
```

```clojure
-  :module-homes {:spirit :psyche :intent :psyche
-                 :vision :psyche
-                 :knowledge :mind :operation :mind
-                 :compensation :field}
+  :registry {:file "registry.edn"}
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 1; `module-home` and the old `module-text` give way;
`hash-of` is Proposal 6; `ModuleUnregistered` and
`SourceHashUnmatched` are the two refusals' own names)

```clojure
- ;; A skill's name is words joined by hyphens, its
- ;; own form; the first word is its kind.
- (defn module-home [config module]
-   (let [words (str/split (name module) #"-" 2)
-         kind (keyword (first words))]
-     (or (get-in config [:module-homes kind])
-         (throw (ex-info "ModuleHomeUnknown"
-                         {:module module})))))
+ (defn registry [config]
+   (edn/read-string
+    (slurp
+     (str (fs/path (:dir config)
+                   (get-in config
+                           [:registry :file]))))))
+
+ ;; A module's name begins with its kind, which
+ ;; capitalized is the subaspect; the whole name
+ ;; is the Name.
+ (defn module-key [module]
+   (let [kind (first (str/split (name module)
+                                #"-" 2))]
+     {:subaspect (keyword (str/capitalize kind))
+      :topic module}))
+
+ (defn entry! [config key]
+   (or (some #(when (= key (:key %)) %)
+             (registry config))
+       (throw (ex-info "ModuleUnregistered"
+                       {:key key}))))
+
+ (defn source-repo [config source]
+   (get-in config
+           [:repositories (:repository source)]))
+
+ ;; A read checks the entry's hash against the
+ ;; source as the configuration declares it.
+ (defn source! [config source]
+   (let [repo (source-repo config source)]
+     (when-not (= (:hash source)
+                  (hash-of config repo))
+       (throw (ex-info "SourceHashUnmatched"
+                       {:source source})))
+     source))
+
+ ;; A write computes the source's hash, then
+ ;; replaces the entry under its key.
+ (defn register! [config entry]
+   (let [repo (source-repo config (:source entry))
+         entry (assoc-in entry [:source :hash]
+                         (hash-of config repo))
+         file (fs/path
+               (:dir config)
+               (get-in config [:registry :file]))
+         kept (remove #(= (:key entry) (:key %))
+                      (registry config))]
+     (spit (str file)
+           (pr-str (vec (conj kept entry))))))
```

```clojure
  (defn module-text [config module]
-   (let [home (module-home config module)
-         repo (get-in config [:repositories home])
-         file (format (:skills repo) (name module))
-         out (sh "git" "-C" (:path repo) "show"
-                 (str (:ref repo) ":" file))]
-     (when-not (zero? (:exit out))
-       (throw (ex-info "ModuleUnavailable"
-                {:module module :file file})))
-     (:out out)))
+   (let [source (source! config
+                         (:source
+                          (entry! config
+                                  (module-key
+                                   module))))
+         repo (source-repo config source)
+         out (sh "git" "-C" (:path repo) "show"
+                 (str (:ref repo) ":"
+                      (:path source)))]
+     (when-not (zero? (:exit out))
+       (throw (ex-info "ModuleUnavailable"
+                       {:module module
+                        :path (:path source)})))
+     (:out out)))
```

Ruling, one number from each group:

The paragraphs, the figure and the code:
1. Land.
2. Land with changes the living names.
3. Drop.

What an entry holds:
4. The containing source, its hash and the relative path, as shown
   (flow f5a6e9, 2026-10-09); the local file path of flow edf227,
   2026-10-03, stands as the earlier record.
5. The module's text itself, fed into Flow's memory, the alternative
   he weighed against paths that break (flow e5a0bc, 2026-10-07):

```clojure
- [{:key {:subaspect :Vision :topic :vision-flow}
-   :source
-   {:repository :psyche
-    :hash
-    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
-    :path "skills/vision-flow.md"}}
+ [{:key {:subaspect :Vision :topic :vision-flow}
+   :text "..."}
```

The hash:
6. «using the containing source with hash (blake3 I believe is what
   we use?)» (flow f5a6e9, 2026-10-09): blake3, as shown, over what
   Proposal 6 rules; the ethos's `Blake3.String`.
7. The repository's git revision stands as the hash, machine choice:
   git names the revision the launcher reads at, and needs no tool
   beyond itself:

File: `primary/config/main-flow/registry.edn`, each `:hash` alike:

```clojure
-    :hash
-    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
+    :hash
+    "c91454e36fe32f31194fd046495de0c6bc30057b"
```

File: `messenger-clj/src/messenger_clj/launch.clj`, `source!` and
`register!` above; git's own words say whether the hash names a
revision holding the path:

```clojure
  (defn source! [config source]
-   (let [repo (source-repo config source)]
-     (when-not (= (:hash source)
-                  (hash-of config repo))
+   (let [repo (source-repo config source)
+         out (sh "git" "-C" (:path repo)
+                 "cat-file" "-e"
+                 (str (:hash source) ":"
+                      (:path source)))]
+     (when-not (zero? (:exit out))
        (throw (ex-info "SourceHashUnmatched"
                        {:source source})))
      source))
```

```clojure
  (defn register! [config entry]
-   (let [repo (source-repo config (:source entry))
-         entry (assoc-in entry [:source :hash]
-                         (hash-of config repo))
-         file (fs/path
+   (source! config (:source entry))
+   (let [file (fs/path
```

The metaflow module (Proposal 3) and the registry:
8. Read by its address from the layer section, outside the registry
   (flow d68c82, 2026-10-13: by virtue of its directory and file).
9. Registered too, under its layer as a subaspect of its own,
   machine choice: one table holds every module's source:

```clojure
+  {:key {:subaspect :Primary :topic :core}
+   :source
+   {:repository :psyche
+    :hash
+    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
+    :path "primary/core.md"}}
```

### Proposal 6: blake3 over a git repository

A tree: git's listing of a revision's files, each path with the id of
its content. A commit: git's record of a revision, its tree's id, its
parent, author and message. A tarball: the revision's files packed
into one archive. b3sum: the blake3 program.

Source: `flows/f5a6e9/vision/contextModules.md`, 2026-10-09.

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 1; inside its map)

```clojure
+  :hash {:tool "b3sum"}
```

File: `messenger-clj/src/messenger_clj/launch.clj` (builds on «book
07» Proposal 1; before `source!` of Proposal 5; Mind's: `b3sum` is
not installed on this host and runs from `nixpkgs#b3sum`)

```clojure
+ ;; --raw is the tool's own flag: the 32 bytes of
+ ;; the digest, the ethos's Blake3.Bytes<32>; the
+ ;; string is their base64, machine choice: 44
+ ;; characters where the hex form is 64.
+ (defn blake3 [config in]
+   (.encodeToString
+    (java.util.Base64/getEncoder)
+    (:out (sh (get-in config [:hash :tool])
+              "--raw" :in in :out-enc :bytes))))
+
+ ;; git's own words list the tree at the revision:
+ ;; each path with the id of its content.
+ (defn hash-of [config repo]
+   (blake3 config
+           (:out (sh "git" "-C" (:path repo)
+                     "ls-tree" "-r" (:ref repo)))))
```

Ruling, one number from each group:

The code:
1. Land.
2. Land with changes the living names.
3. Drop.

What blake3 is computed over, none of his words choosing (flow
f5a6e9, 2026-10-09: «using the containing source with hash (blake3 I
believe is what we use?)»):
4. The tree, as shown, machine choice: the tree is the content the
   registry's paths index, and nothing else.
5. The commit, machine choice: one revision, one digest, its
   message and author included:

```clojure
  (defn hash-of [config repo]
    (blake3 config
            (:out (sh "git" "-C" (:path repo)
-                     "ls-tree" "-r" (:ref repo)))))
+                     "cat-file" "commit"
+                     (:ref repo)))))
```

File: `primary/config/main-flow/registry.edn` (Proposal 5), each
`:hash` alike:

```clojure
-    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
+    "GIr4MieYDz7GWdJqcUCOHbz2K48CXNMaFlo/V2/EFzs="
```

6. The tarball, machine choice: the digest of the files as a Nexus
   without git would receive them:

```clojure
  (defn hash-of [config repo]
    (blake3 config
            (:out (sh "git" "-C" (:path repo)
-                     "ls-tree" "-r" (:ref repo)))))
+                     "archive" "--format=tar"
+                     (:ref repo)
+                     :out-enc :bytes))))
```

File: `primary/config/main-flow/registry.edn` (Proposal 5), each
`:hash` alike:

```clojure
-    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
+    "Uch8Yh3xSEkfwdhTrUlCP88OfIVcKTsyY1SqkHGD8FY="
```

### Proposal 7: spirit replaces the system prompt; intent specializes a metaflow; vision enters the user prompt

Sources: `flows/d4ae97/vision/flow.md`, 2026-10-07, two records, the
second at its lines 107–117.

File: `psyche-context/skills/vision-flow.md`

```text
  A Nexus component decides the system prompt and everything about a
  launch, replacing the harness's subagents with specialized harnesses
  launched with specialized system prompts.
+
+ The spirit replaces the system prompt. Which parts of the intent are loaded into the system prompt as well depends on the kind of metaflow the harness is started in; that is what specializes a metaflow, and it comes from the intent. The vision goes in the user prompt, his guess: it is put there when something is to be developed or audited, which is done against the vision.
```

File: `psyche-context/skills/vision-flow.md` (under choice 4; the
name the three go under is Proposal 10)

```text
  Flow manages metaflows. A metaflow is one address, { Aspect Topic Layer }, that continues through several flows one after another; the flow is its current run, and what is addressed is always the metaflow, never a flow. The default topic is core: the permanent topic, the thing that keeps the machine going, the last topic to stand.
+
+ Psyche, Mind and Field have similar payloads, possibly one, the aspect a field of it.
```

File: `primary/config/main-flow/psyche.edn` (the vision module and
the compensation module leave the system prompt for the first prompt)

```clojure
-  :modules {:system [:spirit-core :intent-main-flow :intent-psyche-interaction :intent-psyche :vision-book :compensation-book-crystallization]
-  :user [:compensation-main-flow :compensation-truth
+  :modules {:system [:spirit-core :intent-main-flow
+                     :intent-psyche-interaction
+                     :intent-psyche]
+  :user [:vision-book
+         :compensation-book-crystallization
+         :compensation-main-flow
+         :compensation-truth
```

File: `primary/config/main-flow/metaflow/Psyche-flow-Primary.edn`
(builds on «book 07» Proposal 10)

```clojure
   :modules
-  {:system [:spirit-core :intent-main-flow
-            :vision-book
-            :compensation-book-crystallization]
-   :user [:compensation-main-flow]}}
+  {:system [:spirit-core :intent-main-flow]
+   :user [:compensation-main-flow :vision-book
+          :compensation-book-crystallization]}}
```

File: `primary/config/main-flow/metaflow/Psyche-flow-Secondary.edn`
(builds on «book 07» Proposal 10)

```clojure
   :modules
-  {:system [:spirit-core :intent-main-flow
-            :vision-book
-            :compensation-book-crystallization]
-   :user [:compensation-main-flow]}}
+  {:system [:spirit-core :intent-main-flow]
+   :user [:compensation-main-flow :vision-book
+          :compensation-book-crystallization]}}
```

Ruling, one number from each group:

The paragraph and the code:
1. Land.
2. Land with changes the living names.
3. Drop.

The payload sentence:
4. Land in vision-flow, as shown, machine choice: the record is a
   flow record, and the sentence names no umbrella.
5. Drop, the machine reading: the address { Aspect Topic Layer }
   already carries the aspect as a field.

The book modules' place:
6. The first prompt, as shown: vision in the user prompt.
7. The system prompt kept for the Psyche flow Primary and Secondary,
   machine choice: writing books is their standing role; the
   `psyche.edn` change stands, the two metaflow files as they stand:

File: `primary/config/main-flow/metaflow/Psyche-flow-Primary.edn`
(builds on «book 07» Proposal 10, as it stands there)

```clojure
   :modules
   {:system [:spirit-core :intent-main-flow
             :vision-book
             :compensation-book-crystallization]
    :user [:compensation-main-flow]}}
```

File: `primary/config/main-flow/metaflow/Psyche-flow-Secondary.edn`
(builds on «book 07» Proposal 10, as it stands there)

```clojure
   :modules
   {:system [:spirit-core :intent-main-flow
             :vision-book
             :compensation-book-crystallization]
    :user [:compensation-main-flow]}}
```

### Proposal 8: the Flow ethos module; vision-flow split along the code

Sources: `flows/f5a6e9/vision/flow.md`, 2026-10-09, its lines
111–117; `flows/f5a6e9/vision/ethos.md`, 2026-10-09;
`flows/9519a1/vision/flow.md`, 2026-10-11.

File: `psyche-context/skills/vision-flow.md` (the front matter and
«What it does»; the voice concept is gone, flow 9519a1, 2026-10-11;
`flow-ethos` is his name, choice 4)

```text
- description: Flow — the Nexus that launches, names, tracks and ends flows — or a voice, a side flow, a hook or the Capsule is being designed or judged against what the living wants.
- dependencies: [vision-nexus, vision-ethos]
+ description: The Flow Nexus vision. Flow — the Nexus that launches, names, tracks and ends flows — or a side flow, a hook or the Capsule is being designed or judged against what the living wants.
+ dependencies: [vision-nexus, vision-ethos, flow-ethos]
```

```text
- Flow is the Nexus that manages flows. A flow is one run of a voice; the component is named for what it manages. A flow the living asks for is launched, properly and surely. Flow holds the lock on flows.
+ Flow is the Nexus that manages flows. A flow is one run of a metaflow; the component is named for what it manages. A flow the living asks for is launched, properly and surely. Flow holds the lock on flows.
```

File: `psyche-context/skills/vision-flow.md`, at the end of
«Repository and context modules» (Proposal 2)

```text
  basic modules give our own take on how an agent behaves in a harness,
  replacing the prompt the harnesses build in.
+
+ The Flow Nexus's vision stands in vision-flow; its code, the Flow ethos, stands in a module of its own, flow-ethos, which vision-ethos names as a current best example rather than carrying the code. When flow-ethos grows too big, the modules about the code part along the code, memory-ethos and signal-ethos, signal first, and ethos for the rest.
```

File: `psyche-context/vision/ethos.md` (its line 8 and its figure
name a module that exists in no context repository; the line is a
landed skill line, commit 97874fb, not his words)

```text
- Flow is one of the best current examples of ethos; its ethos lives in the vision-flow-ethos skill, which carries the code.
+ Flow is one of the best current examples of ethos; its ethos lives in the flow-ethos module, which carries the code.
```

```text
          ┌───────────────────────┐
-         │ vision-flow-ethos     │
+         │ flow-ethos            │
          │ the Flow Nexus, in    │
          │ ethos; its own skill  │
          └───────────────────────┘
```

File: `psyche-context/vision/flow-ethos.md` (under choice 6, a new
file, at the layout of Proposal 2 choice 4; its body is
`flow/crates/flow-ethos/ethos/library.ethos`, uncommitted in the flow
tree, whole, the comments and the long lines wrapped to the column,
the Subaspect line as Proposal 5 leaves it: Ethos, the Ethos
metaflow's. Apart.)

```text
+ ---
+ description: The Flow Nexus in ethos, the current best example of ethos; its types, traits and layout are read or judged.
+ dependencies: [vision-ethos, vision-flow]
+ ---
```

```ethos
+ ; Flow's shared types live in this leaf package.
+ ; Signal and Meta-Signal import them here and
+ ; re-export their original Rust paths. Aspect is
+ ; one nominal type shared by addresses, launch
+ ; profiles and meta configuration.
+
+ Library
+ []
+ [ FlowId.String
+   ; ethos-zero 16 cannot express Topic:Name yet;
+   ; check camelCase at input.
+   Topic.String
+   ; The design type is Blake3.Bytes<32>; 16.0 uses
+   ; a 64-hex-character string.
+   Blake3.String
+   Layer.[ Primary Secondary Tertiary Quaternary ]
+   Subaspect.[ Spirit Intent Vision
+               Knowledge Inquiry Compensation ]
+   ; Define the component types once. Source refers
+   ; to these declarations; inlining Path.String
+   ; inside Source would redeclare Path globally.
+   Repository.String
+   Hash.Blake3
+   Path.String
+   Reason.String
+   Source.{ Repository Hash Path }
+   Key.{ Subaspect Topic }
+   Said.{ Context.String Verbatim.String }
+   Request.[ Order.String
+             Question.String
+             Psyche.Said
+             Psyches.Vector<Said>
+             Result.String
+             Notice.String ]
+   Aspect.[ Psyche Mind Field ]
+   Address.{ Aspect Topic Layer }
+   Recipient.[ Address Up ]
+   Metaflow.{ Address
+              State.[ Awake.FlowId Asleep Ended ]
+              Past.Vector<FlowId>
+              Queue.Vector<Request> }
+   Lock.{ Sender Address Until.Integer }
+   Process.{ Pid.Integer Started.Integer }
+   Sender.Address
+   OrdinarySocketPath.String
+   MetaSocketPath.String
+   StorePath.String
+   Start.{ OrdinarySocketPath MetaSocketPath
+           StorePath }
+   ; Startup refusal is distinct from the
+   ; ordinary/meta socket Refusal.Store.String.
+   StoreRefusal.{ Path Reason }
+   StartRefusal.[ Store.StoreRefusal ]
+   SourceRoot.String
+   ClientPath.String
+   Home.String
+   ControlSocketPath.String
+   ModelName.String
+   CodexEndpoint.{ ClientPath Home
+                   ControlSocketPath
+                   Vector<ModelName> }
+   StableCodex.CodexEndpoint
+   NextCodex.CodexEndpoint
+   HarnessKind.[ Codex Claude ]
+   KeyName.String
+   CommandSigil.String
+   InterruptKeys.Vector<KeyName>
+   SubmitKeys.Vector<KeyName>
+   HarnessProfile.{ HarnessKind
+                    Vector<CommandSigil>
+                    InterruptKeys SubmitKeys }
+   HarnessProfiles.Vector<HarnessProfile>
+   MetaAspects.Vector<Aspect>
+   MessageNexusPath.String
+   MessageNexusBinary.String
+   ; Lease seconds; 60 before any Nexus payload.
+   Nexus.{ SourceRoot
+           StableCodex
+           NextCodex
+           HarnessProfiles
+           MetaAspects
+           MessageNexusPath
+           MessageNexusBinary
+           Lease.Integer } ]
+ []
+ []
```

File: `primary/config/main-flow/registry.edn` (Proposal 5; the
entry, written by `register!`; a name whose first word is not its
kind is given to a launch as its key)

```clojure
+  {:key {:subaspect :Vision :topic :flow-ethos}
+   :source
+   {:repository :psyche
+    :hash
+    "s9V43nm0eto30KyCXR+8afkSnXmsWu13cdDvv/SkNbE="
+    :path "vision/flow-ethos.md"}}
```

File: `messenger-clj/src/messenger_clj/launch.clj` (Proposal 5,
`module-key`; a launch names such a module by its key)

```clojure
  (defn module-key [module]
-   (let [kind (first (str/split (name module)
-                                #"-" 2))]
-     {:subaspect (keyword (str/capitalize kind))
-      :topic module}))
+   (if (map? module)
+     module
+     (let [kind (first (str/split (name module)
+                                  #"-" 2))]
+       {:subaspect (keyword (str/capitalize kind))
+        :topic module})))
```

Ruling, one number from each group:

The front matter, the paragraph and the file:
1. Land.
2. Land with changes the living names.
3. Drop.

The module's name:
4. `flow-ethos`, as shown, his name (flow f5a6e9, 2026-10-09:
   «vision-flow, this is what we're talking about: flow-ethos»),
   `memory-ethos` and `signal-ethos` alike.
5. `vision-flow-ethos`, machine choice: the first word of a module's
   name is its subaspect, and `module-key` derives the key from it:

File: `psyche-context/skills/vision-flow.md`, the lines above:

```text
- dependencies: [vision-nexus, vision-ethos, flow-ethos]
+ dependencies: [vision-nexus, vision-ethos, vision-flow-ethos]
```

```text
- The Flow Nexus's vision stands in vision-flow; its code, the Flow ethos, stands in a module of its own, flow-ethos, which vision-ethos names as a current best example rather than carrying the code. When flow-ethos grows too big, the modules about the code part along the code, memory-ethos and signal-ethos, signal first, and ethos for the rest.
+ The Flow Nexus's vision stands in vision-flow; its code, the Flow ethos, stands in a module of its own, vision-flow-ethos, which vision-ethos names as a current best example rather than carrying the code. When vision-flow-ethos grows too big, the modules about the code part along the code, vision-memory-ethos and vision-signal-ethos, signal first, and vision-ethos for the rest.
```

File: `psyche-context/vision/ethos.md`, its line 8 and its figure as
they stand; the file `psyche-context/skills/vision-flow-ethos.md`;
the registry key `{:subaspect :Vision :topic :vision-flow-ethos}`
with `:path "skills/vision-flow-ethos.md"`; `module-key` as
Proposal 5 leaves it.

Where the Flow ethos code is:
6. In the module file itself, as shown, which carries the code (flow
   f5a6e9, 2026-10-09: «load the Flow Ethos skill, which would have
   that code»).
7. In the flow repository alone, the module a registry entry whose
   source is that repository, machine choice: one copy, the hash
   check keeping the module and the code one:

File: `psyche-context/vision/ethos.md`, its line 8:

```text
- Flow is one of the best current examples of ethos; its ethos lives in the flow-ethos module, which carries the code.
+ Flow is one of the best current examples of ethos; its ethos is the flow-ethos module, whose source is the flow repository.
```

File: `primary/config/main-flow/shared.edn` (builds on «book 07»
Proposal 1; inside `:repositories`, after `:field`; `:ref` is the
branch the flow tree commits the file on, its own name)

```clojure
+                :flow
+                {:path
+                 "/git/github.com/LiGoldragon/flow"
+                 :ref
+                 "flow-launch-inspection-0c85a3"}}
```

File: `primary/config/main-flow/registry.edn` (Proposal 5; in place
of the entry above; `:hash` is written by `register!` from `hash-of`,
Proposal 6, once the file is committed)

```clojure
+  {:key {:subaspect :Vision :topic :flow-ethos}
+   :source
+   {:repository :flow
+    :path "crates/flow-ethos/ethos/library.ethos"}}
```

### Proposal 9: the inline import, Ethos

Ethos, the Ethos metaflow's. Apart.

Source: `flows/f5a6e9/vision/contextModules.md`, 2026-10-09.

File: `psyche-context/vision/ethos.md` (the letter read is the
source's, the lowercase of the line as it stands, and a capital is
the built-in's own name: machine reading; «[ethos] environment» is
the reading of his transcribed «isos environment», in square
brackets, the transcription-correction convention)

```text
  A type may be declared over a type from a source in place, with no entry in the imports section: `Topic.custom:Name` declares `Topic` over the type `Name` from the source `custom`. The source is known twice: its first letter is lowercase, and a colon follows it.
+
+ ## Inline imports
+
+ The in-place form is an inline import. What is imported starting with a capital letter pulls directly from the core built-ins, declared in the core library of the language: `Topic:Name` declares `Topic` over the built-in `Name`. What starts with a lowercase letter, `Topic.custom:Name`, pulls from a registry name, which depends on the [ethos] environment loaded; those have a registry as well. A registry of manifests says where every library lives, the core library and each environment's own.
```

Ruling, one number from each group:

The section:
1. Land.
2. Land with changes the living names.
3. Drop.

The word in brackets:
4. «ethos», as shown, the machine reading of «isos».
5. Another word he names.

Where it is implemented:
6. By the Ethos metaflow, with the Flow ethos passed to it (flow
   f5a6e9, 2026-10-09: «a full Opus and Fable topic flow on that»).
7. Here, by the Flow metaflow, machine choice: the registry of
   Proposal 5 is the first registry named.

### Proposal 10: the name the three similar payloads go under

Source: `flows/d4ae97/vision/flow.md`, 2026-10-07, its lines
107–117.

File: `psyche-context/skills/vision-flow.md` (after the sentence of
Proposal 7 choice 4; the name is the machine's, labelled so: the
three already stand under `Metaflow` in the Flow ethos, whose
`Address` carries `Aspect`, and no second name is needed)

```text
  Psyche, Mind and Field have similar payloads, possibly one, the aspect a field of it.
+ The one they go under is the metaflow: Psyche, Mind and Field are the variants of its aspect, and the metaflow's payload is theirs.
```

File: `flow/crates/flow-ethos/ethos/library.ethos` (uncommitted in
the flow tree; as it stands: the types the sentence names)

```ethos
    Aspect.[ Psyche Mind Field ]
    Address.{ Aspect Topic Layer }
    Recipient.[ Address Up ]
    Metaflow.{ Address
               State.[ Awake.FlowId Asleep Ended ]
               Past.Vector<FlowId>
               Queue.Vector<Request> }
```

Ruling, one number from each group:

The sentence:
1. Land.
2. Land with changes the living names.
3. Drop.

One umbrella or three shapes (his question: «What's the name that we
put them all under ... or are these going to actually diverge and
take their own shape?»):
4. One umbrella, the metaflow, as shown, machine choice: one payload
   is what he thinks possible, and the ethos has it already.
5. They diverge, each aspect its own payload, machine choice: the
   second half of his question, the `Role` of «book 08» Proposal 2
   its shape:

```text
- The one they go under is the metaflow: Psyche, Mind and Field are the variants of its aspect, and the metaflow's payload is theirs.
+ Psyche, Mind and Field each take their own shape: a payload per aspect, the aspect a variant carrying it.
```

File: `flow/crates/flow-ethos/ethos/library.ethos` (builds on «book
08» Proposal 2)

```ethos
    Address.{ Aspect Topic Layer }
+   Payload.[ Psyche.{ Topic Layer }
+             Mind.{ Topic Layer }
+             Field.{ Topic Layer } ]
```
<!-- to-the-living:end -->
