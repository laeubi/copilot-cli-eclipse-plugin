# Copilot Instructions

## Build

```bash
mvn clean verify
```

Requires JDK 17+ and Maven 3.9+. The build uses **Tycho 4.0.11** to compile Eclipse plugins against p2 repositories (Eclipse 2024-12 release). There are no tests or linters configured. The build output is a p2 update site at `releng/io.github.laeubi.copilot.cli.repository/target/repository/`.

## Architecture

This is an Eclipse IDE plugin that integrates GitHub Copilot CLI into the Eclipse Terminal view. It follows the standard **Tycho multi-module** layout:

- `bundles/io.github.laeubi.copilot.cli` — the single OSGi plugin bundle (packaging: `eclipse-plugin`)
- `features/io.github.laeubi.copilot.cli.feature` — feature for p2 installation (packaging: `eclipse-feature`)
- `releng/io.github.laeubi.copilot.cli.repository` — p2 update site (packaging: `eclipse-repository`)

The plugin has 7 Java classes organized into three packages under `io.github.laeubi.copilot.cli`:

| Package | Classes | Role |
|---------|---------|------|
| `handler` | `OpenPromptHandler`, `AskCopilotHandler` | Eclipse command handlers (extend `AbstractHandler`) |
| `connector` | `CopilotCliConnector`, `CopilotCliSettingsPage` | Terminal connector (extends `ProcessConnector`) |
| `launcher` | `CopilotCliLauncherDelegate`, `CopilotCliConfigurationPanel` | Terminal launcher delegate (extends `AbstractLauncherDelegate`) |
| _(root)_ | `Activator` | Bundle lifecycle |

### Key extension points

The plugin extends three Eclipse extension points declared in `plugin.xml`:

- `org.eclipse.terminal.control.connectors` — registers the Copilot CLI terminal connector
- `org.eclipse.terminal.view.ui.launcherDelegates` — registers the terminal launcher
- `org.eclipse.ui.commands` / `org.eclipse.ui.handlers` / `org.eclipse.ui.menus` / `org.eclipse.ui.bindings` — command definitions, handlers, context menus, and keybindings

### Flow

1. User triggers a command (keyboard shortcut or context menu)
2. Handler resolves the working directory by walking up the filesystem for a `.git` directory
3. Handler optionally builds a prompt string from the editor selection (e.g., `See File.java[Line 5-10]`)
4. `CopilotCliLauncherDelegate` creates a `CopilotCliConnector` with platform-aware PTY settings
5. Terminal opens in the Eclipse Terminal view, reusing an existing terminal for the same Git root

## Conventions

- **Java 17** language level. The bundle requires `JavaSE-17` execution environment.
- **No external dependencies** — the plugin depends only on Eclipse platform bundles (`org.eclipse.ui`, `org.eclipse.core.*`, `org.eclipse.terminal.*`, `org.eclipse.jface.text`, `org.eclipse.cdt.utils.pty`).
- OSGi metadata lives in `META-INF/MANIFEST.MF` (not generated from pom.xml). The `plugin.xml` declares all extension points. Both files must be kept in sync with Java code changes.
- The `copilot` command name is hardcoded in `CopilotCliConnector`. The plugin assumes the CLI is on the system PATH.
- Cross-platform handling: PTY support is detected at runtime, line separators and local echo are configured per-platform using `Platform.getOS()`.
- The bundle uses **lazy activation** (`Bundle-ActivationPolicy: lazy`).
