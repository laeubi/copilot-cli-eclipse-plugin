# GitHub Copilot CLI Eclipse Plugin

[![Build](https://github.com/laeubi/copilot-cli-eclipse-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/laeubi/copilot-cli-eclipse-plugin/actions/workflows/build.yml)

An Eclipse plugin that integrates [GitHub Copilot CLI](https://github.com/github/copilot-cli) into the Eclipse IDE, providing AI-powered command-line assistance directly within your development environment.

## Features

- Integration of GitHub Copilot CLI capabilities into Eclipse
- Quick access to AI-powered terminal commands
- **Context-aware Copilot Terminal**: Open a Copilot CLI terminal for the current file's Git repository
- **Key binding (Ctrl+Shift+P)**: Quickly open Copilot terminal with a keyboard shortcut
- Seamless workflow within the Eclipse IDE
- Automatic Git repository detection for context-aware terminal sessions

## Prerequisites

- Eclipse IDE (2024-09 or later recommended)
- Java 17 or higher
- [GitHub Copilot CLI](https://github.com/github/copilot-cli) installed and configured
- Active GitHub Copilot subscription

## Installation

### From Update Site

1. In Eclipse, go to **Help** → **Install New Software...**
2. Click **Add...** to add a new repository
3. Enter the update site URL (to be published)
4. Select "GitHub Copilot CLI Feature"
5. Click **Next** and follow the installation wizard
6. Restart Eclipse when prompted

### From Release

1. Download the latest release ZIP from the [Releases](https://github.com/laeubi/copilot-cli-eclipse-plugin/releases) page
2. In Eclipse, go to **Help** → **Install New Software...**
3. Click **Add...** → **Archive...**
4. Select the downloaded ZIP file
5. Follow the installation wizard

## Usage

### Opening a Copilot Terminal

The plugin provides multiple ways to open a Copilot CLI terminal:

#### Using the Keyboard Shortcut

Press **Ctrl+Shift+P** (or **Cmd+Shift+P** on macOS) to open a Copilot terminal for your current context:

- If you have a file open in the editor, the plugin will:
  1. Detect the file's location
  2. Search upward for a Git repository root (a directory containing `.git`)
  3. Open a Copilot terminal with the repository root as the working directory
  
- If no editor is active, it will use the currently selected item in the Project Explorer

- If a terminal already exists for that Git repository, it will reuse the existing terminal instead of creating a new one

#### From the Terminal View

You can also manually launch a Copilot terminal from Eclipse's Terminal view using the standard terminal launcher menu.

## Implementation Status

### Completed Features

- ✅ Terminal connector for GitHub Copilot CLI
- ✅ Terminal launcher delegate
- ✅ Context-aware terminal opening via Ctrl+Shift+P
- ✅ Git repository detection
- ✅ Terminal reuse for the same repository
- ✅ Integration with Eclipse editor and Project Explorer

### Technical Details

The plugin uses the Eclipse Terminal framework to provide:
- Process-based terminal connector running the `copilot` CLI command
- Command handler that extracts context from the active editor or selection
- Automatic detection of Git repository roots by searching for `.git` directories
- Working directory configuration based on detected context

## Configuration

The plugin works out of the box if you have the GitHub Copilot CLI installed and available in your system PATH. To verify your installation:

```bash
copilot --version
```

If the command is not found, please install the GitHub Copilot CLI following the [official installation guide](https://github.com/github/copilot-cli).


## Building from Source

### Prerequisites for Building

- Maven 3.9.x or higher
- JDK 17 or higher

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

1. Ensure you have **Eclipse IDE for Eclipse Committers** or **Eclipse IDE for Java and DSL Developers** installed
2. Install **Maven Integration for Eclipse (m2e)** if not already installed
3. Import the project:
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
- Eclipse Foundation - For the Eclipse platform and Tycho build system

## Support

For issues, questions, or contributions, please use the [GitHub Issues](https://github.com/laeubi/copilot-cli-eclipse-plugin/issues) page.

