# Additional MCP Tools

These tools extend the base Copilot CLI `/ide` protocol with Eclipse-specific capabilities. They are registered alongside the standard 6 tools (`get_vscode_info`, `get_selection`, `get_diagnostics`, `open_diff`, `close_diff`, `update_session_name`).

---

## `refresh_path`

Refresh a file or folder in the Eclipse workspace so the IDE picks up external changes.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "path": { "type": "string", "description": "Absolute file or folder path to refresh" }
  },
  "required": ["path"]
}
```

**Response:**
```json
{ "success": true, "path": "/ProjectName/src/Main.java" }
```

---

## `build`

Trigger an incremental build on the project that contains the given path. The project is refreshed first to ensure external changes are picked up.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "path": { "type": "string", "description": "Absolute path to a file or folder; the containing project will be built" }
  },
  "required": ["path"]
}
```

**Response:**
```json
{ "success": true, "project": "my-project" }
```

---

## `list_consoles`

List all open consoles in the Eclipse Console view.

**Input schema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response:**
```json
[
  { "index": 0, "name": "Maven Console", "type": "org.eclipse.m2e.core.maven.console" },
  { "index": 1, "name": "MyApp [Java Application]", "type": "org.eclipse.debug.ui.ProcessConsoleType" }
]
```

---

## `get_console_text`

Get the text content of a console. Accepts the console name (substring match, case-insensitive) or numeric index from `list_consoles`.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "name": { "type": "string", "description": "Console name (substring match) or numeric index from list_consoles" }
  },
  "required": ["name"]
}
```

**Response:**
```json
{ "name": "MyApp [Java Application]", "text": "Hello World\n", "length": 12 }
```

---

## `list_launches`

List all launches in the IDE with their attached processes.

**Input schema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response:**
```json
[
  {
    "index": 0,
    "name": "MyApp",
    "mode": "run",
    "terminated": false,
    "processes": [
      {
        "label": "/opt/java/jdk-21/bin/java (08.03.2026, 06:50:30) [pid: 24929]",
        "terminated": false
      }
    ]
  }
]
```

Fields:
- `index` — sequential position, can be used with `stop_launch`
- `name` — launch configuration name, can also be used with `stop_launch`
- `mode` — one of `run`, `debug`, `profile`
- `terminated` — `true` if all processes have exited
- `processes` — list of OS processes spawned by this launch (with `label`, `terminated`, and `exitValue` when terminated)

---

## `stop_launch`

Terminate a launch and all its processes, by index or name (substring match).

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "id": { "type": "string", "description": "Launch index or config name (substring match)" }
  },
  "required": ["id"]
}
```

**Response:**
```json
{ "success": true, "name": "MyApp" }
```

---

## `list_launch_configs`

List all saved launch configurations in the workspace.

**Input schema:**
```json
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
```

**Response:**
```json
[
  { "name": "MyApp", "type": "Java Application", "typeId": "org.eclipse.jdt.launching.localJavaApplication" },
  { "name": "AllTests", "type": "JUnit", "typeId": "org.eclipse.jdt.junit.launchconfig" }
]
```

---

## `launch`

Launch a saved launch configuration by name.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "name": { "type": "string", "description": "Name of the launch configuration" },
    "mode": { "type": "string", "description": "Launch mode: run, debug, or profile (default: run)" }
  },
  "required": ["name"]
}
```

**Response:**
```json
{ "success": true, "config": "MyApp", "mode": "run", "launchId": 7654321 }
```

The launch is executed asynchronously via an Eclipse Job. The response is returned once the launch has been initiated.
