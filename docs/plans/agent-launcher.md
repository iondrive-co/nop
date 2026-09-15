# An agent launcher in nop

nop gains a native launcher for vendor coding agents. You pick an account, the real Claude Code or
Codex TUI opens in a nop tab with its own rendering and keybindings untouched, and when it exits —
or hits a quota wall — nop offers to reopen it, or to hand the work over to a different provider
and carry on there.

It is written entirely in Kotlin, inside this repository. `~/chad` is archived; what follows
reimplements the part of it that earns its keep and drops the rest.

Nothing below is implemented. Facts marked **verified** were checked on this machine on
2026-09-15 against the installed CLIs (claude 2.1.270, codex 0.153.4) and against nop's own source.
Facts marked *assumed* must be checked in the phase that touches them.

---

## 1. The shape of it

A new **Agent** tool tab sits beside Terminal, Commit, Search, Usages, Stash, Preview and Run. With
no session open it shows a picker: your accounts with their current usage, and this project's
earlier sessions. You choose one, and the tab becomes a terminal running the vendor CLI at the
project root — nothing else in it, from first byte to last.

While it runs, nop does four things the CLI does not do for itself:

1. **Keeps each account's credentials isolated**, so three Claude accounts don't collide.
2. **Watches quota** — both by scanning the terminal output for the vendor's own limit message and
   by polling each account's usage.
3. **Reads the CLI's own transcript** off disk into a provider-neutral event log.
4. **Hands over.** That log is rendered into a summary shaped for the incoming provider, written to
   a file, and the new provider's TUI opens pointed at it.

The fourth is the point. Everything else exists to make it possible.

### Two independent channels

```
   nop tab  ◄──── screen bytes ────  vendor TUI  (in nop's own pty4j PTY)
   (JediTerm)                             │
        │  PtyTtyConnector.read taps      │ writes its own transcript
        │  a rolling tail for the         ▼
        │  quota regex — nothing else     $CLAUDE_CONFIG_DIR/projects/<slug>/<id>.jsonl
        │                                 $HOME/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl
        │                                          │
        │                          tailer thread: follow file → AgentEvent
        │                                          ▼
        └──────── nop's event log:  ~/.local/share/nop/agent/sessions/<id>.jsonl
```

**Display** is what nop's terminal already does. One PTY, exactly like a shell tab — `SIGWINCH`
arrives through `PtyTtyConnector.resize` → `PtyProcess.setWinSize`, which already works for vim and
htop. No emulation, no parsing, no relay.

**State** comes from the CLI's own transcript on disk, which for Claude carries `thinking`,
`tool_use`, `tool_result`, full `usage` and `stop_reason`.

The channels never depend on each other. A provider whose transcript format turns out to be
unusable still runs, still gets quota detection from the terminal tail, and still hands off — from a
poorer summary built out of screen output. That is the promised degraded mode, not a failure.

### Why Kotlin, and not a Python library nop shells out to

The obvious alternative is to keep chad as Python — vendored or installed — and have nop run it. It
was rejected because of what nop needs in-process:

- The settings dialog and the usage display are native Compose. They need account listing, usage
  reading, login and the model catalog **as function calls**. Bridging those to Python means
  building an interface over roughly the same surface as the port itself — so you pay for the bridge
  *and* keep the Python.
- A Python launcher can't use nop's PTY, so it needs its own: raw-mode tty, a `select` relay,
  `SIGWINCH` chaining, `TIOCSCTTY`, and a second implementation of all of it for Windows. Nested
  PTYs under a Compose `SwingPanel` then need benchmarking before anyone can call it done.

In Kotlin none of that exists. nop spawns `claude` in pty4j and JediTerm draws it.

The cost is real and worth stating: ~5,000 lines of reimplementation of code that was verified
against the real CLIs, and no standalone command you could run over SSH. Both were accepted.

---

## 2. What exists today

### 2.1 nop — the pieces this builds on

- `terminal/TerminalSession.kt:28` — the PTY session. `forLauncher` 206 and `shell` 226 are the
  factories; `startProcess` 139 builds the pty4j process; `dispose` 130 kills the tree.
  **`startProcess` takes no extra environment** — it does `HashMap(System.getenv())` and sets only
  `TERM`. Per-account isolation needs that changed (§4.5).
- `terminal/PtyTtyConnector.kt` — adapts `PtyProcess` to JediTerm by extending
  `ProcessTtyConnector`. Overriding `read` is the hook for the quota tail (§4.9); *assumed* that
  `ProcessTtyConnector.read(char[], int, int)` is the only path JediTerm draws through — confirm in
  phase 6.
- `ui/RunPanel.kt:56` `RunSessions` — the model for a strip of terminal tabs: `open` 66,
  `openShell` 81, `close` 98, `disposeAll` 112, with `TerminalTabPanel` 161 drawing them.
  `AgentSessions` is its sibling.
- `ui/ToolTabs.kt:64` `ToolTab` — the tool-panel enum. `Terminal` is already the special case that
  draws one tab per open session; `Agent` joins it.
- `ui/App.kt:534` creates `RunSessions` per project and opens a shell immediately; 1169 and 1229
  wire `openShell`. `Agent` gets the same treatment minus the eager open.
- `ui/ThemeToggleButton.kt:25`, used at `ui/App.kt:1476` with `Modifier.align(BottomEnd)` — the
  floating light/dark button that vacates the bottom-right corner (§4.4).
- `ui/TabStripBar.kt:486` `WrapToggleButton`, pinned at 189 beside `AddGroupButton`, deliberately
  outside the flowing tabs — where the theme toggle is going.
- `Settings.kt` — flat key=value at `$XDG_CONFIG_HOME/nop/state`. **nop has no settings UI at all
  today**; every value is written from code.
- `build.gradle.kts` — Kotlin 2.2.20, JVM 21, and already carries
  `kotlinx-serialization-json:1.7.3`, `jna:5.17.0`, `pty4j:0.13.12`, `jediterm:3.72`, junit-jupiter.
  **The whole port needs no new dependency**: HTTP is `java.net.http.HttpClient`, JSON is
  kotlinx-serialization, the password hash is `PBKDF2WithHmacSHA256` from `javax.crypto`.

### 2.2 chad — what gets reimplemented, and what does not

~5,900 lines of the 31,150 survive as Kotlin. By porting difficulty:

| | Source | Notes |
|---|---|---|
| **Mechanical** | `handoff.py` (412), `message_converter.py` (321), `model_catalog.py` (391), `prompts.py` (962 → ~300), `utils.py`+`config.py` (156), `cleanup.py` (210) | regex, string assembly and data tables. `build_handoff_summary` → `format_for_provider` → `build_prompt` is the handoff chain and it is pure text |
| **Routine** | the usage readers in `providers.py` (~1,800 of 4,205), `installer.py` (799), `provider_login.py` (457), account config from `config_manager.py` (~600 of 1,431), `event_log.py` (512), `build_agent_command` from `task_executor.py:608-829` | file and HTTP I/O. The argv/env builder must be exact — it is what makes account isolation work |
| **Risky** | Claude's OAuth refresh (`providers.py:777`) | HTTP and JSON, but silently breaks accounts if wrong. Gets its own test against a live account |
| **Not ported** | the FastAPI server, React UI, TS client, the ralph-loop executor and its stream parser, verification, Slack, tunnels, worktrees, the tray, the pyte→HTML emulator, the CLI client, Fernet/bcrypt encryption, the master-password key derivation | |

Nothing reads `~/.chad.conf` or `~/.chad/logs`. See D2 for what happens to the existing logins.

### 2.3 What the CLIs write to disk

| CLI | Where (inside the per-account home) | Format | Status |
|---|---|---|---|
| claude | `$CLAUDE_CONFIG_DIR/projects/<slug>/<sessionId>.jsonl`, slug = cwd with `/` and `.` → `-` (`/home/dev/nop` → `-home-dev-nop`) | JSONL; record `type` ∈ user, assistant, attachment, queue-operation, custom-title, last-prompt; `message.content` blocks ∈ text, thinking, tool_use, tool_result; every assistant record carries `message.usage` and `message.stop_reason`; user records carrying tool results also have `toolUseResult`; `cwd`, `gitBranch`, `sessionId`, `parentUuid` on every record | **verified** on a 517-line real session |
| codex | `$HOME/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl` | JSONL; `session_meta` (id, cwd, cli_version), `turn_context` (model, effort), `response_item` payloads message / function_call / function_call_output / custom_tool_call / reasoning / web_search_call, `event_msg` types user_message, agent_message, agent_reasoning, exec_command_end (command, exit_code, duration), task_started/complete, **token_count with rate_limits**, turn_aborted | **verified** across 2,574 rollouts |

### 2.4 Where the credentials live — and why only two providers ship first

**verified** by inspection of the existing account homes:

| CLI | Credential | Isolated by |
|---|---|---|
| claude | `$CLAUDE_CONFIG_DIR/.credentials.json` | `CLAUDE_CONFIG_DIR` — a file, so pointing the env var at a per-account directory is complete isolation |
| codex | `$HOME/.codex/auth.json` | `HOME` — likewise |
| agy | **the OS keyring**, one fixed `gemini`/`antigravity` slot shared by every account | nothing. `HOME` has no effect on it |

`agy` is why antigravity is deferred (D3). Isolating it means owning that keyring slot: keeping each
account's credential in a file and writing the right one in immediately before spawn. Worse, the
slot is not reachable through the obvious API — `providers.py:1337-1344` documents that the CLI
writes to the *login* collection while `python-keyring` only searches the one the Secret Service
calls *default*, so chad had to walk every collection over DBus. A JVM implementation has to
replicate that, and both collections on this machine are locked at present, so agy is not currently
working either.

### 2.5 What the CLIs accept

| CLI | Start with a prompt | Resume natively | YOLO | Isolation |
|---|---|---|---|---|
| claude 2.1.270 | positional `claude "<prompt>"` starts the REPL and submits it | `--resume <id>`, `--continue`, `--session-id <uuid>` picks the id up front | `--permission-mode bypassPermissions` | `CLAUDE_CONFIG_DIR` |
| codex 0.153.4 | `codex [OPTIONS] [PROMPT]` | `codex resume <id>` | `--dangerously-bypass-approvals-and-sandbox` | `HOME` |

All **verified** from `--help`. Both YOLO flags stay: this is a YOLO launcher by design.

Linux caps a single argv element at 128 KiB and a full-history handoff can exceed it — which is why
the seed prompt in §4.11 points at a file instead of carrying the text.

---

## 3. Scope

**v1 covers claude and codex.** That is four of the five configured accounts, both with verified
transcript formats, both isolated by a file, neither touching the keyring — and it is the pair that
makes provider switching real.

Antigravity is deferred (D3). Qwen and local are not in scope: there is no account for either, so
nothing about them could be tested.

---

## 4. Target design

### 4.1 Package layout

```
src/main/kotlin/iondrive/nop/
├── agent/
│   ├── Accounts.kt          # account model, JSON config, load/save
│   ├── Passphrase.kt        # PBKDF2 hash + verify; gates the settings dialog
│   ├── Provider.kt          # the enum and its per-provider knowledge
│   ├── Spawn.kt             # argv + env per provider (from build_agent_command)
│   ├── CliTools.kt          # locate / install the vendor binary
│   ├── Login.kt             # run the vendor's own login in a PTY
│   ├── Usage.kt             # per-provider usage readers + the poller
│   ├── Quota.kt             # rolling tail regex over terminal output
│   ├── EventLog.kt          # the session log (§4.8)
│   ├── Handoff.kt           # summary → provider-shaped text → seed prompt
│   ├── AgentSession.kt      # one session: runs, tailer, log, state machine
│   ├── AgentSessions.kt     # the per-project collection (sibling of RunSessions)
│   └── transcript/
│       ├── Tailer.kt        # the interface + the shared follow loop
│       ├── Normalize.kt     # the one tool vocabulary
│       ├── ClaudeTailer.kt
│       └── CodexTailer.kt
└── ui/
    ├── AgentPanel.kt        # the ToolTab.Agent panel: picker or terminal
    ├── AgentPicker.kt       # accounts + this project's sessions
    ├── AgentExitPanel.kt    # the inline post-exit choices
    ├── AccountsDialog.kt    # the global settings dialog
    └── UsageIndicator.kt    # bottom-right, always visible
```

### 4.2 Accounts and config

A new file, `$XDG_CONFIG_HOME/nop/agent.json`, separate from nop's `state` because it is structured
and nop's store is flat key=value.

```kotlin
@Serializable data class Account(
    val name: String,            // "claude-main"
    val provider: Provider,      // Anthropic | OpenAI
    val home: String,            // the per-account credential directory
    val model: String? = null,
    val reasoning: String? = null,
)
@Serializable data class AgentConfig(
    val accounts: List<Account> = emptyList(),
    val passphrase: String? = null,   // PBKDF2 hash, or null for no gate
)
```

`home` is explicit rather than derived. New accounts get
`~/.local/share/nop/agent/homes/<name>`; the five existing ones are declared pointing at their
current `~/.chad/claude-configs/<name>` and `~/.chad/codex-homes/<name>` paths. That is what makes
"start fresh" cost no re-logins (D2) — and it keeps nop from having an opinion about where someone
else's credentials live.

There is nothing to encrypt. Every provider in scope keeps an OAuth token in a file inside its own
home, which nop points at but never reads. So `passphrase` is a PBKDF2 hash and nothing more — it
gates the settings dialog (D4), and `javax.crypto` provides it with no dependency.

### 4.3 The settings dialog — `AccountsDialog.kt`

Accounts only. Reached from the usage indicator and from the ▶ launcher menu. If `passphrase` is
set, it asks once per nop run before showing anything.

Per account: name, provider, model, reasoning level, logged-in state, current usage, and Log in /
Log out. Plus Add account, which creates the home directory and runs the vendor's own login.

Claude's model list comes from the Anthropic Models API using the account's token
(`discover_claude_models`), with free text as the fallback; Codex's is a static list. Both fall back
to "default", which omits the flag entirely.

Deliberately not built: theme, wrap, fonts. They stay where they are (D5).

### 4.4 The usage display — `UsageIndicator.kt`

Bottom-right corner of the window, always visible, independent of which tool tab is showing —
because it is global state and the reason to look at it is to decide what to do next.

One compact row per configured account: name, a bar, the percentage, and the reset ETA
(`claude-main ███░░ 54% · 3h 12m`). Readings land on a background coroutine and the row shows `…`
until the first one arrives. Clicking opens the settings dialog.

**The theme toggle moves out of that corner.** `ThemeToggleButton` (`ui/ThemeToggleButton.kt:25`)
leaves its `Modifier.align(BottomEnd)` overlay at `ui/App.kt:1476` and joins `WrapToggleButton` in
the pinned trailing group of `ui/TabStripBar.kt:189`, which needs an `onToggleTheme` threaded
through it.

One thing to settle when implementing: `TabStripBar` is per-viewer-panel and `App.kt` passes
`onToggleWrap` at both 1146 and 1213, so in a split view the theme toggle would render twice. Either
it goes in the primary strip only, or it moves to `ProjectBar` instead. Decide with it on screen.

### 4.5 Spawning — `Spawn.kt` and the `TerminalSession` change

```kotlin
fun command(account: Account, projectDir: File, seed: String?, resumeId: String?):
    Pair<List<String>, Map<String, String>>
```

| provider | new session | resume | seed prompt |
|---|---|---|---|
| Anthropic | `claude --permission-mode bypassPermissions [--model M] --session-id <uuid nop mints>` | `claude --permission-mode bypassPermissions --resume <id>` | positional arg |
| OpenAI | `codex --dangerously-bypass-approvals-and-sandbox -C <dir> [-m M] [-c model_reasoning_effort="E"]` | `codex --dangerously-bypass-approvals-and-sandbox -C <dir> resume <id>` | positional arg |

Env per provider, ported verbatim from `task_executor.py:673-745`: `CLAUDE_CONFIG_DIR` and
`MAX_THINKING_TOKENS` (the low/medium/high → think/megathink/ultrathink budget map) for Anthropic;
`HOME` for OpenAI. A test asserts the Kotlin env map equals a frozen JSON capture of what the Python
builder produced, so isolation cannot regress silently.

`TerminalSession` gains an `env: Map<String, String>` constructor parameter, merged into
`startProcess` (`terminal/TerminalSession.kt:139`) after `System.getenv()` and `TERM`, and a third
factory beside `forLauncher` and `shell`:

```kotlin
fun agent(command: List<String>, env: Map<String, String>, dir: File, title: String)
```

`isLauncher = false` — no Stop/Re-run header; the TUI owns the interaction.

### 4.6 The Agent tool tab

`ToolTab.Agent("Agent")` joins the enum at `ui/ToolTabs.kt:64`, as the second entry that draws one
tab per open session rather than a single fixed tab. `AgentSessions` mirrors `RunSessions`
(`ui/RunPanel.kt:56`): created per project in `App.kt`, `disposeAll` on project-tab switch — which
for an agent session also stops its tailer thread and closes its log.

Unlike `RunSessions`, nothing opens eagerly. With no session, `AgentPanel` shows the picker.

Agent tabs are not persisted across restarts, in keeping with `TabsPersistence` skipping terminals
today. The picker's session list is the visible home they come back to.

### 4.7 Transcript tailers — `transcript/`

```kotlin
interface Tailer {
    fun locate(run: ProviderRun): Path?      // null until the CLI creates it
    fun parse(line: String): List<AgentEvent>
}
```

One daemon thread per run polls every 250 ms: size grew → read the new complete lines → parse →
append to the log. inotify is Linux-only and 250 ms is invisible next to a model's turn. On exit it
drains once more and stops. The path and byte offset go into the run's `RunStarted` event, so
nothing is ever re-read.

**Claude** — `locate` is exact: the file is `<configDir>/projects/<slug(cwd)>/<id>.jsonl` with the
id nop minted via `--session-id`, so the path is known before the file exists. Mapping:

| record | event |
|---|---|
| `type=user`, string content | `UserMessage` |
| `type=user`, `tool_result` blocks | one `ToolFinished` per block — `callId` from `tool_use_id`, summary from the first 500 chars, `isError` from the block, `exitCode` from `toolUseResult` when the tool was Bash, `durationMs` against the matching `tool_use` record's timestamp |
| `type=assistant` | `AssistantMessage(blocks, usage, stopReason, model)`, plus one `ToolStarted` per `tool_use` block |
| `custom-title` | the session's title in the picker |
| `attachment`, `queue-operation`, `last-prompt` | ignored |

Because the user can `/clear` or `/resume` *inside* the TUI and land in a different id, `follow`
also watches the slug directory and switches to a newer file when one appears after spawn, logging a
fresh `RunStarted` for the new id. *Assumed*: `--resume` appends to the same file — check in
phase 4; if it forks, the directory watch already covers it.

**Codex** — `session_meta.payload.id` → native id; `response_item.message` → user/assistant;
`function_call`/`custom_tool_call` → `ToolStarted` through `Normalize` (Codex names `shell`,
`apply_patch`, … need a mapping table and a test); `function_call_output` + `exec_command_end` →
`ToolFinished` with a real exit code and duration; `reasoning.summary` → thinking blocks;
`token_count` → usage on the following assistant message, **and straight into the usage indicator**
(§4.10). `locate`: the newest `rollout-*.jsonl` whose mtime is after spawn and whose
`session_meta.cwd` is the project.

Both go behind `Tailer` so the sqlite risk (§7) lands in one file.

### 4.8 The event log — `EventLog.kt`

A fresh format, designed around what the tailers emit rather than inherited. One JSONL file per
session under `~/.local/share/nop/agent/sessions/<id>.jsonl`, a kotlinx-serialization sealed
hierarchy:

```
SessionStarted(projectPath, startedAt)
RunStarted(provider, account, model, reasoning, nativeSessionId,
           transcriptPath, transcriptOffset, argv, seededFromHandoff)
UserMessage(text)
AssistantMessage(blocks, usage, stopReason, model)
ToolStarted(callId, tool, args)
ToolFinished(callId, summary, isError, exitCode, durationMs)
ScreenTail(text)                              // only before the first tailer event
RunEnded(exitCode, reason: Exited | Quota | Switched | Killed)
ProviderSwitched(from, to, reason, handoffPath)
```

`RunStarted`/`RunEnded` are the session's manifest: "reopen natively" reads the last run's
`nativeSessionId` and account straight off them.

`ScreenTail` is the degraded path. It is written only while a run has produced no transcript event
yet — ANSI-stripped deltas, deduplicated — so a provider without a working tailer still has
something to build a handoff from, and one with a tailer never bloats the log with screen redraws.
The switch happens once, on the tailer's first event.

`sessions()` reads only the head of each file to list a project's history.

### 4.9 Quota — `Quota.kt`

Two ways:

**Reactive.** A `PtyTtyConnector` subclass overrides `read`, passes the characters through
untouched, and feeds a rolling 4 KiB ANSI-stripped tail to the provider's quota matcher — the
patterns from `handoff.py:21-52` plus Claude's own "You've hit your limit" line. A hit sets the run's
end reason to `Quota`, kills the TUI and shows the exit panel with the reset ETA. Overload errors
are matched first and ignored: they are transient and the TUI retries itself.

**Proactive.** Each account's usage every 5 minutes on a background coroutine, feeding §4.4. It
never interrupts a running TUI (D6) — it is a number to look at, not a trigger.

### 4.10 Usage readers — `Usage.kt`

**Claude** is an HTTPS GET to `https://api.anthropic.com/api/oauth/usage` with the account's OAuth
bearer token, returning `five_hour` and `seven_day` blocks with `utilization` and `resets_at`. The
token comes from `.credentials.json` in the account's home, refreshed against the OAuth token
endpoint when expired and written back — `providers.py:777-825` is the reference, and it is the one
genuinely risky port.

**Codex** is not an API call. It walks `$HOME/.codex/sessions/**/*.jsonl` newest-first and takes the
most recent populated `rate_limits` block from a `token_count` event. So **Codex usage is only as
fresh as your last Codex session** — the indicator shows the snapshot's age. The upside: it is the
same file the tailer is already reading, so a running session updates usage live.

### 4.11 Switching and handoff — `Handoff.kt`

On "Switch to X", from the exit panel or after a quota kill:

1. Wait for the tailer to drain; log `RunEnded`.
2. `ProviderSwitched(from, to, reason)`.
3. Build the summary: conversation extraction → provider-shaped formatting → resume prompt. The
   task is the session's first `UserMessage` — in a TUI the first thing typed *is* the task.
4. Write it to `~/.local/share/nop/agent/handoffs/<session>/handoff-<n>.md`. Outside the project, so
   nothing lands in the repo and nothing is gitignored.
5. Spawn X with a seed prompt that is one short line: *"Continue the task handed over from
   &lt;provider&gt;. Read &lt;absolute path&gt; in full before doing anything else, then carry on
   from its Remaining Work section."* The file keeps the 128 KiB argv limit irrelevant and leaves
   something to read when a handoff goes wrong.
6. `RunStarted(seededFromHandoff = true)`, new tailer.

"Reopen" is the same minus the summary: native resume argv, no seed.

When the summary was built from `ScreenTail` rather than a transcript, the exit panel says so, so a
poor first turn on the new provider is not a mystery.

### 4.12 The exit panel — `AgentExitPanel.kt`

When a run ends, the terminal stays on screen showing the TUI's last frame and a panel appears over
it: the exit reason, the reset ETA if it was quota, and Reopen &lt;account&gt; / Switch to
&lt;each other account&gt; / New session / Close. The last output is usually what tells you whether
switching is the right call, so it stays visible underneath.

---

## 5. Order of work

Each phase ends with `./gradlew test` green and is a sensible commit boundary. Nothing is committed
without being asked. Tests land with each phase, not in a later pile.

| # | Deliverable | Done when |
|---|---|---|
| 1 | `agent/{Accounts,Provider,Spawn,CliTools}.kt`, the `TerminalSession` env parameter and `agent` factory, `ToolTab.Agent` + `AgentSessions` + a bare `AgentPanel` | real Claude Code runs in a nop tab at the project root with an isolated `CLAUDE_CONFIG_DIR`, reflows on resize, and exits cleanly. `SpawnTest` passes, including env parity against the frozen Python capture |
| 2 | `Passphrase.kt`, `Login.kt`, `AccountsDialog.kt`, the picker's account half | you can add an account, log it in through the vendor's own flow in a PTY, set its model, and launch it — without hand-editing a file |
| 3 | `Usage.kt`, `UsageIndicator.kt`, the theme-toggle move | usage for every account shows bottom-right, refreshes on its own, survives tab switches, and the bottom-right corner is otherwise empty |
| 4 | `EventLog.kt`, `transcript/{Tailer,Normalize,ClaudeTailer}.kt`, the picker's session half | a real Claude session leaves a log with user/assistant/tool-started/tool-finished events carrying usage and stop_reason, and lists as a past session next time. The `/clear`-mid-run directory switch is covered |
| 5 | `transcript/CodexTailer.kt`, Codex argv/env, Codex usage | codex runs, resumes, and tails; `CodexTailerTest` passes against fixture rollouts |
| 6 | `Quota.kt`, `AgentExitPanel.kt`, `Handoff.kt`, native reopen | claude hits a quota wall, the panel offers codex, codex opens having read the handoff file, and reopening claude lands in the real session rather than a summary of it |

Phases 1–2 are usable daily. Phase 6 is the point of the whole thing.

Tests, by phase: `SpawnTest` (argv + env parity), `AccountsTest` (config round-trip, passphrase),
`UsageTest` (both readers against fixture files and a stubbed HTTP handler), `EventLogTest`,
`ClaudeTailerTest` / `CodexTailerTest` (anonymised fixture slices → exact event sequences, offset
resumption, the directory switch), `QuotaTest` (the patterns against real captured output),
`HandoffTest` (log → summary → seed argv), and a UI check under Xvfb with an isolated
`XDG_CONFIG_HOME` and `dbus-run-session`: open the Agent tab, launch a fake vendor CLI, assert the
tab appears and the exit panel offers the other account.

---

## 6. Decisions taken here that you may want to reverse

- **D1 — Kotlin, in nop, with no standalone command.** The launcher is a nop feature. There is no
  `chad` you can run in a plain terminal, over SSH or in tmux. If that turns out to matter, it needs
  a headless entry point doing raw-tty passthrough in Kotlin — most of the complexity this design
  deletes.
- **D2 — fresh config and a fresh log format; the existing logins are kept.** Nothing reads
  `~/.chad.conf` or the 4,709 logs in `~/.chad/logs`, which become dead files to archive or delete.
  But each account's `home` is declared explicitly, so the five existing accounts point at their
  current credential directories and **no account needs logging in again**.
- **D3 — antigravity is deferred.** Supporting it means owning a shared OS-keyring slot through the
  Secret Service over DBus, walking every collection because the obvious API searches the wrong one
  (§2.4). That is the fiddliest code in the project and only one provider needs it. Both keyring
  collections on this machine are currently locked, so agy is not working today either.
- **D4 — the passphrase gates the settings dialog and nothing else.** There is nothing left to
  encrypt: every provider keeps its own OAuth token in its own home. A launch from an
  already-configured account never prompts. It is a PBKDF2 hash, not a key.
- **D5 — the settings dialog is accounts-only.** Theme and wrap keep their toggles. Building nop's
  first general settings window is a bigger piece of work than this feature should drag in, and
  there is no second section asking for one yet.
- **D6 — no threshold-triggered switching.** Interrupting a working TUI because a poller crossed 80%
  is worse than showing the number and letting you decide at the next exit. Reactive quota kills
  stay, because there the session is already over.
- **D7 — qwen and local are out of scope**, having no configured account. `local` in particular
  (qwen against a llama-server) would be the one provider that never hits a quota wall, so it may be
  worth adding once the rest works.

---

## 7. Risks

- **Codex may stop writing rollout JSONL.** 0.153 already ships
  `migrate-rollouts … to paginated thread history` and `thread_history_1.sqlite` exists in these
  homes. The design survives — PTY-only run, handoff from the screen tail — but both the tailer and
  the usage reader read that file, so a sqlite reader must be able to replace `CodexTailer` without
  touching the event mapping.
- **Claude's OAuth refresh** is the single port most likely to break something silently. If it
  writes a malformed `.credentials.json`, it breaks the account for the real CLI too, not just for
  nop. It should read, refresh and write through a temp file and rename, and be tested against a
  live account before phase 3 is called done.
- **The `read` tap on `PtyTtyConnector`** assumes JediTerm draws only through
  `ProcessTtyConnector.read(char[], int, int)`. If it doesn't, quota detection silently never fires
  — so phase 6's test asserts the tail actually saw the bytes, rather than asserting the regex works.
- **Claude's directory watch** is what keeps the tailer honest through a `/clear` or `/resume` typed
  inside the TUI. It needs a test where a second transcript file appears mid-run.
- **Handoff quality without a transcript** is only as good as an ANSI-stripped screen tail. The exit
  panel must say when that is what it used.
- **The theme toggle's new home** may render twice in a split view (§4.4). Cheap to fix, easy to
  miss until someone opens a split.
