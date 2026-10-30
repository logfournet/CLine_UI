# Changelog

All notable changes to CLine UI are documented here.

## 1.0.0 — 2026-10-30

First public release.

### Added
- Docked **Cline** tool window hosting the Cline CLI TUI in a terminal widget
  (open with the Cline icon or `Ctrl+Alt+C`).
- Session discovery from Cline's session store
  (`~/.cline/data/sessions/<id>/<id>.json`), matched to the project root by `cwd`;
  `CLINE_DATA_DIR` overrides the root.
- Turn tracking from the transcript (`<id>.messages.json`): running / idle based on
  the last message (`assistant` + `tool_use` or `user` = running, `assistant`
  text/thinking = idle), with quiet-period debounce, a 10 s stop grace and a 3 min
  stuck-turn valve.
- Native multi-file diff review with **Accept** (keep, `git add` in git projects)
  and **Reject** (restore pre-turn content / delete created files), computed from
  per-turn workspace snapshots (no VCS required).
- Local History labels `Cline turn start` / `Cline turn finished` for VCS-free
  rollback.
- Status bar widget with spinner and tooltip (session id, mode, model, cwd).
- Completion notification with changed-file counts and a **Review Changes** action.
- Send file / selection as `@path` / `@path#Lx-y` context (`Ctrl+Alt+K`), right-click
  **Cline** code actions (Explain, Fix, Refactor, Add Tests, Optimize, Document,
  Ask Cline…), drag & drop of files.
- Clickable `@path`, `@"path with spaces"`, `@path#Lx-y` and `path:line` links in
  the terminal.
- Tool window actions: New Task (`/clear`), Stop (Ctrl+C), Toggle Plan/Act (Tab),
  Review Changes, Copy Output as Markdown.
- Per-project settings: CLI path, start mode (Act/Plan), auto-approve, model and
  provider overrides, extra CLI arguments, open panel on project open, terminal
  scrollback (unlimited by default).

[1.0.0]: https://github.com/logfournet/CLine_UI/releases/tag/v1.0.0
