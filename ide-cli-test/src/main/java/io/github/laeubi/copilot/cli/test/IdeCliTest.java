/*******************************************************************************
 * Copyright (c) 2025 Christoph Läubrich and others.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package io.github.laeubi.copilot.cli.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Interactive CLI tool for testing the MCP IDE protocol server. Discovers lock
 * files, connects to the server, and allows sending tool calls interactively.
 */
public class IdeCliTest {

	private static final Path LOCK_DIR = Path.of(System.getProperty("user.home"), ".copilot", "ide");
	private static final AtomicInteger requestId = new AtomicInteger(0);

	private static String socketPath;
	private static String scheme;
	private static String nonce;
	private static String sessionId;

	// Persistent connection for POST requests
	private static SocketChannel postChannel;
	private static BufferedReader postReader;
	private static OutputStream postOs;

	public static void main(String[] args) {
		System.out.println("╔══════════════════════════════════════════╗");
		System.out.println("║       IDE CLI Test Tool v1.0.0           ║");
		System.out.println("║  MCP Protocol Tester for Eclipse IDE     ║");
		System.out.println("╚══════════════════════════════════════════╝");
		System.out.println();

		try (Scanner scanner = new Scanner(System.in)) {
			// Step 1: Discover and select IDE
			if (!discoverAndSelectIde(scanner)) {
				return;
			}

			// Step 2: Initialize MCP session
			if (!initializeSession()) {
				return;
			}

			// Step 3: Start SSE listener
			startSseListener();

			// Step 4: Interactive command loop
			commandLoop(scanner);
		} catch (Exception e) {
			System.err.println("Fatal error: " + e.getMessage());
			e.printStackTrace();
		} finally {
			closePostConnection();
		}
	}

	// --- IDE Discovery ---

	private static boolean discoverAndSelectIde(Scanner scanner) {
		System.out.println("🔍 Searching for IDE instances in " + LOCK_DIR + " ...");

		if (!Files.isDirectory(LOCK_DIR)) {
			System.err.println("❌ Lock directory does not exist: " + LOCK_DIR);
			return false;
		}

		List<LockFile> lockFiles = new ArrayList<>();
		try (Stream<Path> stream = Files.list(LOCK_DIR)) {
			stream.filter(p -> p.toString().endsWith(".lock")).forEach(p -> {
				try {
					String content = Files.readString(p, StandardCharsets.UTF_8);
					Map<String, Object> lock = parseJson(content);
					if (lock != null) {
						lockFiles.add(new LockFile(p, lock));
					}
				} catch (Exception e) {
					System.err.println("  ⚠ Could not read: " + p.getFileName() + " (" + e.getMessage() + ")");
				}
			});
		} catch (IOException e) {
			System.err.println("❌ Error listing lock files: " + e.getMessage());
			return false;
		}

		if (lockFiles.isEmpty()) {
			System.err.println("❌ No IDE lock files found. Make sure an IDE with MCP support is running.");
			return false;
		}

		// Filter out stale PIDs
		lockFiles.removeIf(lf -> {
			long pid = ((Number) lf.data.get("pid")).longValue();
			if (ProcessHandle.of(pid).isEmpty()) {
				System.out.println("  ⚠ Skipping stale lock file (PID " + pid + " not running): " + lf.path.getFileName());
				return true;
			}
			return false;
		});

		if (lockFiles.isEmpty()) {
			System.err.println("❌ All lock files are stale. No running IDE found.");
			return false;
		}

		System.out.println();
		System.out.println("Found " + lockFiles.size() + " IDE instance(s):");
		System.out.println();

		for (int i = 0; i < lockFiles.size(); i++) {
			LockFile lf = lockFiles.get(i);
			String ideName = (String) lf.data.getOrDefault("ideName", "Unknown IDE");
			long pid = ((Number) lf.data.get("pid")).longValue();
			@SuppressWarnings("unchecked")
			List<String> folders = (List<String>) lf.data.getOrDefault("workspaceFolders", List.of());
			String firstFolder = folders.isEmpty() ? "(no workspace)" : folders.getFirst();
			if (firstFolder.length() > 60) {
				firstFolder = "..." + firstFolder.substring(firstFolder.length() - 57);
			}
			System.out.printf("  [%d] %s (PID %d) — %s%n", i + 1, ideName, pid, firstFolder);
			if (folders.size() > 1) {
				System.out.printf("      + %d more workspace folder(s)%n", folders.size() - 1);
			}
		}

		System.out.println();
		int choice;
		if (lockFiles.size() == 1) {
			choice = 0;
			System.out.println("Auto-selecting the only available IDE.");
		} else {
			System.out.print("Select IDE [1-" + lockFiles.size() + "]: ");
			try {
				choice = Integer.parseInt(scanner.nextLine().trim()) - 1;
			} catch (NumberFormatException e) {
				System.err.println("❌ Invalid selection.");
				return false;
			}
			if (choice < 0 || choice >= lockFiles.size()) {
				System.err.println("❌ Selection out of range.");
				return false;
			}
		}

		LockFile selected = lockFiles.get(choice);
		socketPath = (String) selected.data.get("socketPath");
		scheme = (String) selected.data.getOrDefault("scheme", "pipe");
		@SuppressWarnings("unchecked")
		Map<String, Object> headers = (Map<String, Object>) selected.data.get("headers");
		String authHeader = (String) headers.get("Authorization");
		nonce = authHeader; // full "Nonce xxx" string

		System.out.println();
		System.out.println("✅ Selected: " + selected.data.getOrDefault("ideName", "IDE") + " (PID "
				+ selected.data.get("pid") + ")");
		System.out.println("   Socket: " + socketPath);
		System.out.println();

		return true;
	}

	// --- MCP Session ---

	private static boolean initializeSession() {
		System.out.println("🔌 Connecting to MCP server...");

		try {
			openPostConnection();

			// Send initialize
			Map<String, Object> initParams = new LinkedHashMap<>();
			initParams.put("protocolVersion", "2025-11-25");
			initParams.put("capabilities", Map.of());
			initParams.put("clientInfo", Map.of("name", "ide-cli-test", "version", "1.0.0"));

			String response = sendRequest("initialize", initParams);
			if (response == null) {
				System.err.println("❌ No response to initialize request.");
				return false;
			}

			System.out.println("✅ Initialize response:");
			System.out.println("   " + response);
			System.out.println();

			// Send notifications/initialized
			sendNotification("notifications/initialized", Map.of());
			System.out.println("✅ Sent notifications/initialized");
			System.out.println();

			return true;
		} catch (Exception e) {
			System.err.println("❌ Failed to initialize: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	// --- SSE Listener ---

	private static void startSseListener() {
		Thread.ofVirtual().name("sse-listener").start(() -> {
			try {
				SocketChannel sseChannel = openChannel();

				OutputStream sseOs = Channels.newOutputStream(sseChannel);
				InputStream sseIs = Channels.newInputStream(sseChannel);

				// Send GET /mcp
				StringBuilder req = new StringBuilder();
				req.append("GET /mcp HTTP/1.1\r\n");
				req.append("Authorization: ").append(nonce).append("\r\n");
				req.append("Accept: text/event-stream\r\n");
				if (sessionId != null) {
					req.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
				}
				req.append("Host: localhost\r\n");
				req.append("Connection: keep-alive\r\n");
				req.append("\r\n");

				sseOs.write(req.toString().getBytes(StandardCharsets.UTF_8));
				sseOs.flush();

				BufferedReader sseReader = new BufferedReader(new InputStreamReader(sseIs, StandardCharsets.UTF_8));

				// Read HTTP response headers
				String line;
				while ((line = sseReader.readLine()) != null && !line.isEmpty()) {
					// skip response headers
				}

				System.out.println("📡 SSE listener connected, waiting for events...");
				System.out.println();

				// Read SSE events (chunked encoding)
				while ((line = sseReader.readLine()) != null) {
					if (line.startsWith("event:")) {
						String eventType = line.substring(6).trim();
						String dataLine = sseReader.readLine();
						if (dataLine != null && dataLine.startsWith("data:")) {
							String data = dataLine.substring(5).trim();
							System.out.println();
							System.out.println("📨 SSE Event [" + eventType + "]:");
							printPrettyJson(data, "   ");
							System.out.println();
							System.out.print("Command> ");
							System.out.flush();
						}
					}
					// Skip chunk size lines and empty lines
				}
			} catch (Exception e) {
				System.err.println("📡 SSE listener error: " + e.getMessage());
			}
		});
	}

	// --- Command Loop ---

	private static void commandLoop(Scanner scanner) {
		String[] commands = { "get_vscode_info", "get_selection", "get_diagnostics", "open_diff", "close_diff",
				"update_session_name", "refresh_path", "build", "list_consoles", "get_console_text", "list_launches",
				"stop_launch", "list_launch_configs", "launch", "tools/list", "ping", "raw", "exit" };

		while (true) {
			System.out.println("┌─────────────────────────────────────────┐");
			System.out.println("│  Available Commands:                    │");
			System.out.println("│                                         │");
			for (int i = 0; i < commands.length; i++) {
				System.out.printf("│  [%2d] %-33s │%n", i + 1, commands[i]);
			}
			System.out.println("└─────────────────────────────────────────┘");
			System.out.println();
			System.out.print("Command> ");
			String input = scanner.nextLine().trim();

			if (input.isEmpty()) {
				continue;
			}

			int cmdIdx;
			try {
				cmdIdx = Integer.parseInt(input) - 1;
			} catch (NumberFormatException e) {
				// Try matching by name
				cmdIdx = -1;
				for (int i = 0; i < commands.length; i++) {
					if (commands[i].equalsIgnoreCase(input)) {
						cmdIdx = i;
						break;
					}
				}
				if (cmdIdx < 0) {
					System.err.println("❌ Unknown command: " + input);
					continue;
				}
			}

			if (cmdIdx < 0 || cmdIdx >= commands.length) {
				System.err.println("❌ Invalid command number.");
				continue;
			}

			String cmd = commands[cmdIdx];

			try {
				switch (cmd) {
				case "exit" -> {
					System.out.println("👋 Goodbye!");
					return;
				}
				case "get_vscode_info" -> callTool(cmd, Map.of());
				case "get_selection" -> callTool(cmd, Map.of());
				case "get_diagnostics" -> {
					System.out.print("Filter URI (empty for all): ");
					String uri = scanner.nextLine().trim();
					Map<String, Object> args = new LinkedHashMap<>();
					if (!uri.isEmpty()) {
						args.put("uri", uri);
					}
					callTool(cmd, args);
				}
				case "open_diff" -> {
					System.out.print("Original file path: ");
					String origPath = scanner.nextLine().trim();
					if (origPath.isEmpty()) {
						System.err.println("❌ Path is required.");
						continue;
					}
					System.out.print("Tab name: ");
					String tabName = scanner.nextLine().trim();
					if (tabName.isEmpty()) {
						tabName = "Test Diff";
					}
					System.out.println("Enter new file contents (end with a line containing only '---END---'):");
					StringBuilder contents = new StringBuilder();
					String contentLine;
					while (!(contentLine = scanner.nextLine()).equals("---END---")) {
						if (contents.length() > 0) {
							contents.append('\n');
						}
						contents.append(contentLine);
					}
					Map<String, Object> diffArgs = new LinkedHashMap<>();
					diffArgs.put("original_file_path", origPath);
					diffArgs.put("new_file_contents", contents.toString());
					diffArgs.put("tab_name", tabName);
					callTool(cmd, diffArgs);
				}
				case "close_diff" -> {
					System.out.print("Tab name to close: ");
					String tn = scanner.nextLine().trim();
					if (tn.isEmpty()) {
						System.err.println("❌ Tab name is required.");
						continue;
					}
					callTool(cmd, Map.of("tab_name", tn));
				}
				case "update_session_name" -> {
					System.out.print("Session name: ");
					String name = scanner.nextLine().trim();
					if (name.isEmpty()) {
						System.err.println("❌ Name is required.");
						continue;
					}
					callTool(cmd, Map.of("name", name));
				}
				case "refresh_path" -> {
					System.out.print("Path to refresh: ");
					String rpath = scanner.nextLine().trim();
					if (rpath.isEmpty()) {
						System.err.println("❌ Path is required.");
						continue;
					}
					callTool(cmd, Map.of("path", rpath));
				}
				case "build" -> {
					System.out.print("Path (file or folder in project): ");
					String bpath = scanner.nextLine().trim();
					if (bpath.isEmpty()) {
						System.err.println("❌ Path is required.");
						continue;
					}
					callTool(cmd, Map.of("path", bpath));
				}
				case "list_consoles" -> callTool(cmd, Map.of());
				case "get_console_text" -> {
					System.out.print("Console name: ");
					String cname = scanner.nextLine().trim();
					if (cname.isEmpty()) {
						System.err.println("❌ Name is required.");
						continue;
					}
					callTool(cmd, Map.of("name", cname));
				}
				case "list_launches" -> callTool(cmd, Map.of());
				case "stop_launch" -> {
					System.out.print("Launch index or name: ");
					String launchId = scanner.nextLine().trim();
					if (launchId.isEmpty()) {
						System.err.println("❌ Index or name is required.");
						continue;
					}
					callTool(cmd, Map.of("id", launchId));
				}
				case "list_launch_configs" -> callTool(cmd, Map.of());
				case "launch" -> {
					System.out.print("Launch config name: ");
					String lcName = scanner.nextLine().trim();
					if (lcName.isEmpty()) {
						System.err.println("❌ Name is required.");
						continue;
					}
					System.out.print("Mode (run/debug/profile, default=run): ");
					String lcMode = scanner.nextLine().trim();
					Map<String, Object> lcArgs = new LinkedHashMap<>();
					lcArgs.put("name", lcName);
					if (!lcMode.isEmpty()) {
						lcArgs.put("mode", lcMode);
					}
					callTool(cmd, lcArgs);
				}
				case "tools/list" -> {
					String resp = sendRequest("tools/list", Map.of());
					System.out.println();
					System.out.println("📋 tools/list response:");
					printPrettyJson(resp, "   ");
					System.out.println();
				}
				case "ping" -> {
					String resp = sendRequest("ping", Map.of());
					System.out.println();
					System.out.println("🏓 ping response: " + resp);
					System.out.println();
				}
				case "raw" -> {
					System.out.print("Method: ");
					String method = scanner.nextLine().trim();
					System.out.println("Params JSON (single line): ");
					String paramsJson = scanner.nextLine().trim();
					if (paramsJson.isEmpty()) {
						paramsJson = "{}";
					}
					Map<String, Object> params = parseJson(paramsJson);
					if (params == null) {
						System.err.println("❌ Invalid JSON.");
						continue;
					}
					String resp = sendRequest(method, params);
					System.out.println();
					System.out.println("📥 Response:");
					printPrettyJson(resp, "   ");
					System.out.println();
				}
				}
			} catch (Exception e) {
				System.err.println("❌ Error: " + e.getMessage());
				e.printStackTrace();
			}
		}
	}

	private static void callTool(String toolName, Map<String, Object> arguments) throws Exception {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("name", toolName);
		params.put("arguments", arguments);

		System.out.println();
		System.out.println("🔧 Calling tool: " + toolName);
		if (!arguments.isEmpty()) {
			System.out.println("   Arguments: " + serializeJson(arguments));
		}

		long start = System.currentTimeMillis();
		String response = sendRequest("tools/call", params);
		long elapsed = System.currentTimeMillis() - start;

		System.out.println();
		System.out.println("📥 Response (" + elapsed + "ms):");
		printPrettyJson(response, "   ");
		System.out.println();
	}

	// --- HTTP/MCP Transport ---

	private static void openPostConnection() throws IOException {
		postChannel = openChannel();
		postOs = Channels.newOutputStream(postChannel);
		postReader = new BufferedReader(
				new InputStreamReader(Channels.newInputStream(postChannel), StandardCharsets.UTF_8));
	}

	/**
	 * Opens a {@link SocketChannel} to the server.  Uses TCP when the lock file
	 * scheme is {@code "http"}, otherwise connects via Unix domain socket.
	 */
	private static SocketChannel openChannel() throws IOException {
		if ("http".equals(scheme)) {
			URI uri = URI.create(socketPath);
			SocketChannel ch = SocketChannel.open();
			ch.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
			return ch;
		}
		SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
		ch.connect(UnixDomainSocketAddress.of(socketPath));
		return ch;
	}

	private static void closePostConnection() {
		try {
			if (postChannel != null && postChannel.isOpen()) {
				postChannel.close();
			}
		} catch (IOException e) {
			// ignore
		}
	}

	private static void ensurePostConnection() throws IOException {
		if (postChannel == null || !postChannel.isOpen()) {
			openPostConnection();
		}
	}

	private static String sendRequest(String method, Map<String, Object> params) throws Exception {
		ensurePostConnection();

		int id = requestId.incrementAndGet();
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("method", method);
		request.put("params", params);
		request.put("jsonrpc", "2.0");
		request.put("id", id);

		return sendPostAndReadResponse(serializeJson(request));
	}

	private static void sendNotification(String method, Map<String, Object> params) throws Exception {
		ensurePostConnection();

		Map<String, Object> notification = new LinkedHashMap<>();
		notification.put("method", method);
		notification.put("params", params);
		notification.put("jsonrpc", "2.0");

		sendPostAndReadResponse(serializeJson(notification));
	}

	private static String sendPostAndReadResponse(String body) throws IOException {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

		StringBuilder req = new StringBuilder();
		req.append("POST /mcp HTTP/1.1\r\n");
		req.append("Authorization: ").append(nonce).append("\r\n");
		req.append("Content-Type: application/json\r\n");
		req.append("Accept: application/json, text/event-stream\r\n");
		if (sessionId != null) {
			req.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
		}
		req.append("Host: localhost\r\n");
		req.append("Connection: keep-alive\r\n");
		req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
		req.append("\r\n");

		postOs.write(req.toString().getBytes(StandardCharsets.UTF_8));
		postOs.write(bodyBytes);
		postOs.flush();

		// Read HTTP response
		String statusLine = postReader.readLine();
		if (statusLine == null) {
			throw new IOException("Connection closed by server");
		}

		// Parse headers
		Map<String, String> headers = new LinkedHashMap<>();
		String line;
		while ((line = postReader.readLine()) != null && !line.isEmpty()) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
			}
		}

		// Capture session ID
		String newSessionId = headers.get("mcp-session-id");
		if (newSessionId != null) {
			sessionId = newSessionId;
		}

		// Read body
		String responseBody;
		String te = headers.get("transfer-encoding");
		if (te != null && te.contains("chunked")) {
			responseBody = readChunkedBody(postReader);
		} else {
			int contentLength = 0;
			String cl = headers.get("content-length");
			if (cl != null) {
				contentLength = Integer.parseInt(cl.trim());
			}
			if (contentLength > 0) {
				char[] buf = new char[contentLength];
				int read = 0;
				while (read < contentLength) {
					int r = postReader.read(buf, read, contentLength - read);
					if (r < 0)
						break;
					read += r;
				}
				responseBody = new String(buf, 0, read);
			} else {
				responseBody = "";
			}
		}

		// Parse SSE if content type is text/event-stream
		String contentType = headers.getOrDefault("content-type", "");
		if (contentType.contains("text/event-stream")) {
			// Extract JSON from SSE data lines
			StringBuilder jsonData = new StringBuilder();
			for (String sseLine : responseBody.split("\n")) {
				if (sseLine.startsWith("data:")) {
					jsonData.append(sseLine.substring(5).trim());
				}
			}
			return jsonData.toString();
		}

		return responseBody;
	}

	private static String readChunkedBody(BufferedReader reader) throws IOException {
		StringBuilder result = new StringBuilder();
		while (true) {
			String sizeLine = reader.readLine();
			if (sizeLine == null)
				break;
			int semi = sizeLine.indexOf(';');
			if (semi >= 0)
				sizeLine = sizeLine.substring(0, semi);
			sizeLine = sizeLine.trim();
			int chunkSize;
			try {
				chunkSize = Integer.parseInt(sizeLine, 16);
			} catch (NumberFormatException e) {
				break;
			}
			if (chunkSize == 0) {
				reader.readLine();
				break;
			}
			char[] buf = new char[chunkSize];
			int read = 0;
			while (read < chunkSize) {
				int r = reader.read(buf, read, chunkSize - read);
				if (r < 0)
					break;
				read += r;
			}
			result.append(buf, 0, read);
			reader.readLine();
		}
		return result.toString();
	}

	// --- Minimal JSON Parser/Serializer ---

	@SuppressWarnings("unchecked")
	static Map<String, Object> parseJson(String json) {
		if (json == null || json.isBlank())
			return null;
		json = json.trim();
		if (!json.startsWith("{"))
			return null;
		try {
			Object[] result = parseValue(json, 0);
			return (Map<String, Object>) result[0];
		} catch (Exception e) {
			return null;
		}
	}

	private static Object[] parseValue(String json, int pos) {
		pos = skipWs(json, pos);
		char c = json.charAt(pos);
		return switch (c) {
		case '{' -> parseObject(json, pos);
		case '[' -> parseArray(json, pos);
		case '"' -> parseString(json, pos);
		case 't', 'f' -> parseBool(json, pos);
		case 'n' -> parseNull(json, pos);
		default -> parseNumber(json, pos);
		};
	}

	private static Object[] parseObject(String json, int pos) {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++; // skip {
		pos = skipWs(json, pos);
		if (json.charAt(pos) == '}')
			return new Object[] { map, pos + 1 };
		while (true) {
			pos = skipWs(json, pos);
			Object[] key = parseString(json, pos);
			pos = skipWs(json, (int) key[1]);
			pos++; // skip :
			Object[] val = parseValue(json, pos);
			map.put((String) key[0], val[0]);
			pos = skipWs(json, (int) val[1]);
			if (json.charAt(pos) == '}')
				return new Object[] { map, pos + 1 };
			pos++; // skip ,
		}
	}

	private static Object[] parseArray(String json, int pos) {
		List<Object> list = new ArrayList<>();
		pos++; // skip [
		pos = skipWs(json, pos);
		if (json.charAt(pos) == ']')
			return new Object[] { list, pos + 1 };
		while (true) {
			Object[] val = parseValue(json, pos);
			list.add(val[0]);
			pos = skipWs(json, (int) val[1]);
			if (json.charAt(pos) == ']')
				return new Object[] { list, pos + 1 };
			pos++; // skip ,
		}
	}

	private static Object[] parseString(String json, int pos) {
		pos++; // skip opening "
		StringBuilder sb = new StringBuilder();
		while (pos < json.length()) {
			char c = json.charAt(pos);
			if (c == '"')
				return new Object[] { sb.toString(), pos + 1 };
			if (c == '\\') {
				pos++;
				c = json.charAt(pos);
				switch (c) {
				case '"', '\\', '/' -> sb.append(c);
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'u' -> {
					sb.append((char) Integer.parseInt(json.substring(pos + 1, pos + 5), 16));
					pos += 4;
				}
				}
			} else {
				sb.append(c);
			}
			pos++;
		}
		return new Object[] { sb.toString(), pos };
	}

	private static Object[] parseNumber(String json, int pos) {
		int start = pos;
		boolean isFloat = false;
		if (json.charAt(pos) == '-')
			pos++;
		while (pos < json.length()) {
			char c = json.charAt(pos);
			if (c == '.' || c == 'e' || c == 'E')
				isFloat = true;
			if (!Character.isDigit(c) && c != '.' && c != 'e' && c != 'E' && c != '+' && c != '-')
				break;
			pos++;
		}
		String num = json.substring(start, pos);
		if (isFloat)
			return new Object[] { Double.parseDouble(num), pos };
		long l = Long.parseLong(num);
		if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
			return new Object[] { (int) l, pos };
		return new Object[] { l, pos };
	}

	private static Object[] parseBool(String json, int pos) {
		if (json.startsWith("true", pos))
			return new Object[] { true, pos + 4 };
		return new Object[] { false, pos + 5 };
	}

	private static Object[] parseNull(String json, int pos) {
		return new Object[] { null, pos + 4 };
	}

	private static int skipWs(String json, int pos) {
		while (pos < json.length() && Character.isWhitespace(json.charAt(pos)))
			pos++;
		return pos;
	}

	@SuppressWarnings("unchecked")
	static String serializeJson(Object value) {
		if (value == null)
			return "null";
		if (value instanceof String s)
			return "\"" + escapeJson(s) + "\"";
		if (value instanceof Number n)
			return n.toString();
		if (value instanceof Boolean b)
			return b.toString();
		if (value instanceof Map<?, ?> map) {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first)
					sb.append(",");
				first = false;
				sb.append("\"").append(escapeJson(e.getKey().toString())).append("\":");
				sb.append(serializeJson(e.getValue()));
			}
			sb.append("}");
			return sb.toString();
		}
		if (value instanceof List<?> list) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (Object item : list) {
				if (!first)
					sb.append(",");
				first = false;
				sb.append(serializeJson(item));
			}
			sb.append("]");
			return sb.toString();
		}
		return "\"" + escapeJson(value.toString()) + "\"";
	}

	private static String escapeJson(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			switch (c) {
			case '"' -> sb.append("\\\"");
			case '\\' -> sb.append("\\\\");
			case '\n' -> sb.append("\\n");
			case '\r' -> sb.append("\\r");
			case '\t' -> sb.append("\\t");
			default -> {
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
			}
		}
		return sb.toString();
	}

	// --- Pretty Print ---

	private static void printPrettyJson(String json, String indent) {
		if (json == null || json.isBlank()) {
			System.out.println(indent + "(empty)");
			return;
		}
		try {
			Object parsed = parseValue(json.trim(), 0)[0];
			printValue(parsed, indent, 0);
		} catch (Exception e) {
			// Fall back to raw output
			System.out.println(indent + json);
		}
	}

	@SuppressWarnings("unchecked")
	private static void printValue(Object value, String baseIndent, int depth) {
		String indent = baseIndent + "  ".repeat(depth);
		String childIndent = baseIndent + "  ".repeat(depth + 1);

		if (value instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				System.out.print("{}");
				return;
			}
			System.out.println("{");
			int i = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				System.out.print(childIndent + "\"" + entry.getKey() + "\": ");
				printValue(entry.getValue(), baseIndent, depth + 1);
				if (++i < map.size())
					System.out.println(",");
				else
					System.out.println();
			}
			System.out.print(indent + "}");
		} else if (value instanceof List<?> list) {
			if (list.isEmpty()) {
				System.out.print("[]");
				return;
			}
			System.out.println("[");
			for (int i = 0; i < list.size(); i++) {
				System.out.print(childIndent);
				printValue(list.get(i), baseIndent, depth + 1);
				if (i < list.size() - 1)
					System.out.println(",");
				else
					System.out.println();
			}
			System.out.print(indent + "]");
		} else if (value instanceof String s) {
			if (s.length() > 120) {
				System.out.print("\"" + escapeJson(s.substring(0, 117)) + "...\"");
			} else {
				System.out.print("\"" + escapeJson(s) + "\"");
			}
		} else if (value == null) {
			System.out.print("null");
		} else {
			System.out.print(value);
		}
	}

	// --- Helper types ---

	record LockFile(Path path, Map<String, Object> data) {
	}
}
