# GitHub Copilot CLI Eclipse Plugin

[![Build](https://github.com/laeubi/copilot-cli-eclipse-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/laeubi/copilot-cli-eclipse-plugin/actions/workflows/build.yml)

An Eclipse plugin that integrates [GitHub Copilot CLI](https://github.com/github/copilot-cli) into the Eclipse IDE, providing AI-powered command-line assistance directly within your development environment.

## Features

### Terminal Integration
- **Context-aware Copilot Terminal**: Open a Copilot CLI terminal for the current file's Git repository
- **"Ask Copilot" context menu**: Right-click in Project Explorer or Navigator to ask Copilot about any resource
- **Key binding (Ctrl+Alt+C)**: Quickly open Copilot terminal with keyboard shortcut
- Automatic Git repository detection for context-aware terminal sessions
- Terminal reuse for the same repository to avoid clutter

### IDE Integration via `/ide` Protocol
- **MCP Server**: Embedded [Model Context Protocol](https://modelcontextprotocol.io/) server that enables Copilot CLI's `/ide` mode to communicate with Eclipse
- **Editor context**: Copilot CLI can read the current text selection, cursor position, and active file
- **Diagnostics**: Copilot CLI can access workspace problems (errors, warnings) from the Problems view
- **Diff view**: Copilot CLI can propose file changes that appear as a side-by-side diff in Eclipse's compare editor, with **Accept/Reject buttons** in an integrated action bar
- **Live notifications**: Selection and diagnostics changes are pushed to the CLI in real time
- **Auto-discovery**: The MCP server writes a lock file to `~/.copilot/ide/` so Copilot CLI automatically discovers Eclipse

## Prerequisites

- Eclipse IDE (2025-03 or later recommended)
- Java 21 or higher
- [GitHub Copilot CLI](https://github.com/github/copilot-cli) installed and configured
- Active GitHub Copilot subscription

## Installation

### From Update Site

1. In Eclipse, go to **Help** → **Install New Software...**
2. Click **Add...** to add a new repository
3. Enter the update site URL `https://laeubi.github.io/copilot-cli-eclipse-plugin` - **Important** The URL can *not* be browsed with a regular browser! It still works, just copy and paste into the dialog!
4. Select "GitHub Copilot CLI Feature"
5. Click **Next** and follow the installation wizard
6. Restart Eclipse when prompted

## Usage

The plugin provides multiple ways to open a Copilot CLI terminal:

- Press **Ctrl+Alt+C** (or **Cmd+Alt+C** on macOS) to directly open a copilot-cli terminal view
- You can also right-click on any item in the **Project Explorer**, **Package Explorer**, or **Navigator** and select **"Ask Copilot"** from the context menu:
- You can also manually launch a Copilot terminal from Eclipse's Terminal view using the standard terminal launcher menu.
- You can connect to the IDE project using `/ide` command in any copilot-cli session!

## Configuration

The plugin works out of the box if you have the GitHub Copilot CLI installed and available in your system PATH. To verify your installation:

```bash
copilot --version
```

If the command is not found, please install the GitHub Copilot CLI following the [official installation guide](https://github.com/github/copilot-cli).


## Building from Source

### Prerequisites for Building

- Maven 3.9.x or higher
- JDK 21 or higher

### Build Instructions

1. Clone the repository:
   ```bash
   git clone https://github.com/laeubi/copilot-cli-eclipse-plugin.git
   cd copilot-cli-eclipse-plugin
   ```

2. Build with Maven:
   ```bash
   mvn clean verify
   ```

3. The update site will be generated at:
   ```
   releng/io.github.laeubi.copilot.cli.repository/target/repository/
   ```

4. The repository ZIP will be available at:
   ```
   releng/io.github.laeubi.copilot.cli.repository/target/io.github.laeubi.copilot.cli.repository-*.zip
   ```

## Development Setup

### Importing into Eclipse

1. Ensure you have **Eclipse IDE for RCP/Plugin Developers** installed
2. Import the project:
   - **File** → **Import** → **Maven** → **Existing Maven Projects**
   - Select the cloned repository root directory
   - Import all projects

### Project Structure

The project follows a typical Eclipse plugin structure using Tycho Maven build:

```
copilot-cli-eclipse-plugin/
├── bundles/
│   └── io.github.laeubi.copilot.cli/          # Main plugin bundle
├── features/
│   └── io.github.laeubi.copilot.cli.feature/  # Feature definition
├── releng/
│   └── io.github.laeubi.copilot.cli.repository/ # Update site/P2 repository
└── pom.xml                                     # Parent POM
```

### Running in Eclipse

1. Right-click on the plugin project
2. Select **Run As** → **Eclipse Application**
3. A new Eclipse instance will launch with the plugin installed

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the Eclipse Public License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [GitHub Copilot CLI](https://github.com/github/copilot-cli) - The command-line tool this plugin integrates
- [CopilotCliIde](https://github.com/sailro/CopilotCliIde) - Visual Studio Integration and inital reverse engeneering of the [protocol](https://github.com/sailro/CopilotCliIde/blob/main/doc/protocol.md)
- Eclipse Foundation - For the Eclipse platform and Tycho build system

## Support

For issues, questions, or contributions, please use the [GitHub Issues](https://github.com/laeubi/copilot-cli-eclipse-plugin/issues) page.

