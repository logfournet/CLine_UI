# CLine UI — JetBrains Plugin

Bring the **Cline** AI coding agent into IntelliJ IDEA, PyCharm, PhpStorm, GoLand
and other JetBrains IDEs. The plugin hosts the **Cline CLI TUI** in a docked side
panel so the code editor stays visible next to the agent — the familiar
**file tree | code | agent** layout.

Modelled on [OpenCode_UI_V2](../OpenCode_UI_V2), adapted to how Cline works: the
CLI has no HTTP service to connect to, so the plugin reads Cline's own session
store instead.

---

## Overview

- **Docked panel, not an editor tab** — the Cline TUI runs in a real terminal
  widget inside the `Cline` tool window (right side by default).
- **Session discovery** — the plugin finds the session Cline writes to
  `~/.cline/data/sessions/<id>/<id>.json`:
  - Windows: `%USERPROFILE%\.cline\data\sessions`
  - Other: `~/.cline/data/sessions`
  - Override the data dir with the `CLINE_DATA_DIR` environment variable.
- **Turn tracking from the transcript** — `<id>.messages.json` tells the plugin
  whether a tool call is in flight (running) or Cline is waiting for input
  (idle), so the status bar spinner and the completion notification are driven by
  what Cline actually did, not by terminal scraping.
- **No VCS required** — a cheap workspace snapshot before/after each turn is used
  for the diff, and Local History labels make every turn revertible.

---

## Features

### Docked Cline panel
- Click the **Cline** icon (or press `Ctrl+Alt+C`, macOS `Cmd+Alt+C`) to open the
  panel on the right side.
- The TUI is launched in the project directory, so each IDE window works on its
  own workspace (per-project sessions and history).

### Send code to the agent
- **`Ctrl+Alt+K`** — send the current file / selection as context:
  `@path` or `@path#Lx-y` (pasted into the TUI input).
- **Right-click → Cline** submenu with one-click code actions:
  - **Explain**, **Fix**, **Refactor**, **Add Tests**, **Optimize**, **Document**
  - **Ask Cline…** — free-form prompt dialog
  - These type the prompt and press Enter automatically, so the request starts
    immediately.
- **Drag & drop** files onto the panel to insert `@path` references.

### Clickable file links
- `@path`, `@"path with spaces"`, `@path#Lx-y` and `path/to/File.java:42` inside
  the terminal are clickable — clicking opens the file and highlights / scrolls
  to the lines.

### Diff review with Accept / Reject
- When a turn ends, the plugin compares the workspace with its turn-start
  snapshot and opens IntelliJ's native multi-file diff viewer.
- Per-file **Accept** (keep the change, `git add` when the project is a git repo)
  and **Reject** (restore the pre-turn content, or delete files Cline created).
- **Review Changes** in the title bar re-opens the last turn's diff; when no turn
  has run yet it shows everything changed since the panel was opened.

### Local History (rollback without VCS)
- Right-click → **Local History** → **Show History** shows two named entries per
  turn: `Cline turn start` and `Cline turn finished`.

### Status bar & notifications
- Status bar widget: `◉ Cline` when a session is known, `○ Cline` before that,
  and an animated spinner while a turn runs; click to open the panel.
- Completion notification with the number of changed files and a **Review
  Changes** action.

### TUI actions in the panel title bar
- **New Task** — `/clear` in the TUI plus a fresh diff baseline.
- **Stop** — Ctrl+C in the TUI (Cline's abort binding).
- **Toggle Plan / Act** — Tab in the TUI.
- **Review Changes** / **Copy Output as Markdown**.

---

## Requirements

1. **Cline CLI** installed and authenticated:
   ```powershell
   npm install -g cline
   cline auth
   ```
2. A JetBrains IDE **2024.2+** (build 242+).

The plugin auto-detects `cline` (npm global bin, `%APPDATA%\npm\cline.cmd`,
`/usr/local/bin`, `/opt/homebrew/bin`, `~/.local/bin`) and falls back to whatever
`cline` is on `PATH`. An explicit path can be set in
**Settings → Tools → Cline**.

---

## Settings (Settings → Tools → Cline)

| Setting | Meaning |
| --- | --- |
| Cline CLI | Path to the executable (blank = auto-detect) |
| Start in | Act or Plan mode (`--plan`) |
| Auto-approve tools | CLI default / always on / always off (`--auto-approve true\|false`) |
| Model override | `-m <model>` |
| Provider override | `-P <provider>` |
| Extra arguments | Free-form, appended to the launch command |
| Open the panel when a project opens | Off by default |
| Terminal scrollback | Unlimited by default, else a finite line cap |

Changing a launch setting restarts the TUI so the new flags take effect.

---

## Keyboard shortcuts

| Action | Shortcut |
| --- | --- |
| Open / focus the Cline panel | `Ctrl+Alt+C` (macOS `Cmd+Alt+C`) |
| Send file / selection as context | `Ctrl+Alt+K` |
| Code actions (Explain / Fix / …) | Right-click → **Cline** |
| Abort the running turn | `Stop` action, or `Ctrl+C` in the panel |
| Plan/Act in the panel | `Tab` (Cline's own binding) |

---

## Building from source

### Gradle (IntelliJ Platform Gradle Plugin 2.10.2, JDK 17 target)

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12"
.\gradlew.bat buildPlugin
```

Output: `build\distributions\CLine_UI-1.0.0.zip`.

### Offline script (no Gradle download)

`build.ps1` compiles with the JDK bundled in an installed IDE and packages the
plugin zip:

```powershell
.\build.ps1                  # auto-detect the IDE
.\build.ps1 -IdePath "C:\...\PyCharm"
.\build.ps1 -Install         # also copy into the IDE's plugins directory
```

Output:

- `build\distributions\CLine_UI-1.0.0.zip` — installable plugin
  (`CLine_UI/lib/*.jar`)
- `build\plugin\CLine_UI\lib\CLine_UI-1.0.0.jar`

---

## Project structure

```
src/main/java/ai/cline/ide/jetbrains/
  ClinePlugin.java            plugin constants (ids, notification group, labels)
  service/    ClineService    terminal hosting, launch command, input, session polling, notifications
  session/    ClineSessionStore  ~/.cline/data/sessions discovery + turn probe
              ClineSession       session metadata (status, cwd, model, mode)
              ClineChangeTracker workspace snapshots, Local History labels, Accept/Reject IO
              WorkspaceSnapshot / SnapshotWalker / FileChange
  diff/       ClineDiffViewer (native diff + Accept/Reject), ClineDiffHandler
  ui/         ClineToolWindowFactory, ClineStatusBarWidget(+Factory),
              ClineSettingsConfigurable, ClineStartupActivity
  terminal/   ClineTerminalLinkFilter (clickable @path / path:line)
  util/       ClineReference (@path#Lx-y builder), ClineBinaryLocator
  actions/    QuickLaunch, SendSelection, NewTask, Interrupt, TogglePlanAct,
              ReviewChanges, CopyOutput, ClineAsk, ClineAskWithPrompt, ClineActionsGroup
src/main/resources/META-INF/plugin.xml, icons/cline.svg
```

---

## How turn tracking works

Cline writes two files per session:

- `<id>.json` — `session_id`, `status`, `cwd`, `workspace_root`, `pid`,
  `started_at`, `provider`, `model`, `metadata.mode`. `status` stays `running`
  for the whole TUI process, so it is only used to find the session, not to
  detect a turn.
- `<id>.messages.json` — the Anthropic-style transcript. The plugin reads the last
  message:

| Last message | Meaning |
| --- | --- |
| `assistant` with a `tool_use` block | running (tools in flight) |
| `user` (prompt or `tool_result`) | running (the model still has to answer) |
| `assistant` with only `text` / `thinking` | idle (turn finished) |

A turn is closed when the transcript has been idle for ~2 polls **and** unchanged
for 5 s (so a streaming answer is not cut off). `Stop` force-closes it after 10 s,
and a transcript that never moves is closed after 3 minutes (aborted tool call).

## Known limits

- The diff is computed from workspace snapshots of text files ≤ 512 KiB (build
  output, VCS metadata, `node_modules` and similar directories are skipped, and a
  snapshot is capped at 64 MiB). Files changed outside the project root are not
  part of the review.
- Only the newest interactive session whose `cwd` matches the project root is
  followed; a Cline process started with a custom `--data-dir` is only visible
  when `CLINE_DATA_DIR` is set for the IDE.
- `Accept` stages the file with `git add` only when the project has a `.git`.

---

## Links

- **Cline:** <https://cline.bot>
- **Cline CLI docs:** <https://docs.cline.bot/usage/cli-overview>
- **Cline TUI docs:** <https://docs.cline.bot/usage/tui>
