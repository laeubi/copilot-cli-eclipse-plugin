# Copilot Instructions

## Build

```bash
mvn clean verify
```

Requires JDK 21+ and Maven 3.9+. The build uses **Tycho 5.0.2** to compile Eclipse plugins against p2 repositories (Eclipse 2025-12 release). There are no tests or linters configured. The build output is a p2 update site at `releng/io.github.laeubi.copilot.cli.repository/target/repository/`.

## Architecture

This is an Eclipse IDE plugin that integrates GitHub Copilot CLI into the Eclipse Terminal view and provides an embedded MCP server for the Copilot CLI `/ide` protocol. It follows the standard **Tycho multi-module** layout:

- `bundles/io.github.laeubi.copilot.cli` — the single OSGi plugin bundle (packaging: `eclipse-plugin`)
- `features/io.github.laeubi.copilot.cli.feature` — feature for p2 installation (packaging: `eclipse-feature`)
- `releng/io.github.laeubi.copilot.cli.repository` — p2 update site (packaging: `eclipse-repository`)

The plugin has Java classes organized into four packages under `io.github.laeubi.copilot.cli`:

| Package | Classes | Role |
|---------|---------|------|
| `handler` | `OpenPromptHandler`, `AskCopilotHandler` | Eclipse command handlers (extend `AbstractHandler`) |
| `connector` | `CopilotCliConnector`, `CopilotCliSettingsPage` | Terminal connector (extends `ProcessConnector`) |
| `launcher` | `CopilotCliLauncherDelegate`, `CopilotCliConfigurationPanel` | Terminal launcher delegate (extends `AbstractLauncherDelegate`) |
| `mcp` | `McpServer`, `McpToolHandler`, `EclipseToolHandler`, `McpLifecycleManager`, `LockFileManager`, `NotificationPusher`, `Json` | MCP server for Copilot CLI `/ide` integration |
| _(root)_ | `Activator` | Bundle lifecycle — starts/stops MCP server |

### Terminal integration

Extension points in `plugin.xml`:
- `org.eclipse.terminal.control.connectors` — registers the Copilot CLI terminal connector
- `org.eclipse.terminal.view.ui.launcherDelegates` — registers the terminal launcher
- `org.eclipse.ui.commands` / `handlers` / `menus` / `bindings` — command definitions, keybindings (Ctrl+Alt+C), context menus

### MCP server (`mcp` package)

The embedded MCP server implements the [Copilot CLI `/ide` protocol](https://github.com/AbandonedScope/CopilotCliIde/blob/main/doc/protocol.md):

1. **`McpServer`** — Unix domain socket listener, HTTP/1.1 parser, JSON-RPC 2.0 dispatch, SSE push
2. **`EclipseToolHandler`** — implements 6 MCP tools using Eclipse APIs:
   - `get_vscode_info` — IDE metadata (must use this exact name for CLI compatibility)
   - `get_selection` — current editor selection via `ITextEditor`/`ITextSelection`
   - `get_diagnostics` — workspace markers via `IMarker` API
   - `open_diff` — opens `CompareEditorInput`, blocks until user acts
   - `close_diff` — closes compare editor by tab name
   - `update_session_name` — fire-and-forget
3. **`NotificationPusher`** — pushes `selection_changed` and `diagnostics_changed` SSE events with 200ms debounce
4. **`LockFileManager`** — writes/deletes `~/.copilot/ide/{uuid}.lock` for CLI discovery
5. **`McpLifecycleManager`** — orchestrates startup/shutdown from `Activator`
6. **`Json`** — minimal JSON parser/serializer (no external dependencies)

### Key flow

1. Plugin activates → `McpLifecycleManager.start()` creates Unix domain socket + lock file
2. Copilot CLI scans `~/.copilot/ide/*.lock`, connects to socket, sends `initialize`
3. CLI invokes tools via `POST /mcp` with JSON-RPC `tools/call`
4. Server pushes `selection_changed`/`diagnostics_changed` via SSE on `GET /mcp`

## Conventions

- **Java 21** language level. The bundle requires `JavaSE-21` execution environment.
- **No external dependencies** — the plugin depends only on Eclipse platform bundles (`org.eclipse.ui`, `org.eclipse.core.*`, `org.eclipse.terminal.*`, `org.eclipse.jface.text`, `org.eclipse.compare`, `org.eclipse.cdt.utils.pty`). JSON handling uses a built-in minimal parser.
- OSGi metadata lives in `META-INF/MANIFEST.MF` (not generated from pom.xml). The `plugin.xml` declares all extension points. Both files must be kept in sync with Java code changes.
- The `copilot` command name is hardcoded in `CopilotCliConnector`. The plugin assumes the CLI is on the system PATH.
- The MCP server name **must** be `"vscode-copilot-cli"` and all 6 tool names must match exactly — the CLI matches on these strings.
- Cross-platform handling: PTY support is detected at runtime, line separators and local echo are configured per-platform using `Platform.getOS()`.
- The bundle uses **lazy activation** (`Bundle-ActivationPolicy: lazy`).
