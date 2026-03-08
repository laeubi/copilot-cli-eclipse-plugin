/*******************************************************************************
 * Copyright (c) 2026 Christoph Läubrich and others.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package io.github.laeubi.copilot.cli.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.ILog;

/**
 * MCP (Model Context Protocol) server that listens on a Unix domain socket and
 * speaks Streamable HTTP with persistent connections. Handles JSON-RPC 2.0
 * messages for tool calls, initialization, and SSE push notifications.
 */
public class McpServer {

	private static final String MCP_PROTOCOL_VERSION = "2025-11-25";
	private static final String SERVER_NAME = "vscode-copilot-cli";
	private static final String SERVER_VERSION = "1.0.0";

	private final String nonce;
	private final Path socketPath;
	private final McpToolHandler toolHandler;
	private final ExecutorService executor;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<>();
	private final AtomicLong connectionCounter = new AtomicLong(0);

	private ServerSocketChannel serverChannel;

	public McpServer(Path socketPath, String nonce, McpToolHandler toolHandler) {
		this.socketPath = socketPath;
		this.nonce = nonce;
		this.toolHandler = toolHandler;
		this.executor = Executors.newVirtualThreadPerTaskExecutor();
	}

	public void start() throws IOException {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		Files.deleteIfExists(socketPath);
		serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
		ILog.get().info("[MCP] Server listening on " + socketPath);

		executor.submit(() -> {
			while (running.get()) {
				try {
					SocketChannel client = serverChannel.accept();
					long connId = connectionCounter.incrementAndGet();
					ILog.get().info("[MCP] Connection #" + connId + " accepted");
					executor.submit(() -> handleConnection(client, connId));
				} catch (IOException e) {
					if (running.get()) {
						ILog.get().error("[MCP] Error accepting connection: " + e.getMessage(), e);
					}
				}
			}
		});
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		ILog.get().info("[MCP] Server stopping...");
		for (SseClient client : sseClients) {
			client.close();
		}
		sseClients.clear();
		try {
			if (serverChannel != null) {
				serverChannel.close();
			}
		} catch (IOException e) {
			// ignore on shutdown
		}
		try {
			Files.deleteIfExists(socketPath);
		} catch (IOException e) {
			// ignore
		}
		executor.shutdownNow();
		ILog.get().info("[MCP] Server stopped");
	}

	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Push a notification to all connected SSE clients.
	 */
	public void pushNotification(String method, Map<String, Object> params) {
		Map<String, Object> notification = new LinkedHashMap<>();
		notification.put("jsonrpc", "2.0");
		notification.put("method", method);
		notification.put("params", params);
		String json = Json.serialize(notification);

		ILog.get().info("[MCP] Pushing notification '" + method + "' to " + sseClients.size() + " SSE client(s)");

		List<SseClient> dead = new ArrayList<>();
		for (SseClient client : sseClients) {
			if (!client.send(json)) {
				dead.add(client);
			}
		}
		if (!dead.isEmpty()) {
			ILog.get().info("[MCP] Removed " + dead.size() + " dead SSE client(s)");
			sseClients.removeAll(dead);
		}
	}

	// --- Connection handling ---

	private void handleConnection(SocketChannel channel, long connId) {
		String prefix = "[MCP #" + connId + "]";
		int requestCount = 0;
		try (channel;
				InputStream is = Channels.newInputStream(channel);
				OutputStream os = Channels.newOutputStream(channel)) {
			BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

			// HTTP persistent connection loop: handle multiple requests per connection
			while (running.get() && channel.isOpen()) {
				String requestLine = reader.readLine();
				if (requestLine == null) {
					ILog.get().info(prefix + " Client closed connection (EOF) after " + requestCount + " request(s)");
					break;
				}
				if (requestLine.isBlank()) {
					continue; // skip blank lines between requests
				}

				requestCount++;
				ILog.get().info(prefix + " Request #" + requestCount + ": " + requestLine);

				String[] parts = requestLine.split(" ");
				if (parts.length < 3) {
					ILog.get().warn(prefix + " Malformed request line: " + requestLine);
					break;
				}
				String httpMethod = parts[0];
				String path = parts[1];

				// Read headers
				Map<String, String> headers = new LinkedHashMap<>();
				String line;
				while ((line = reader.readLine()) != null && !line.isEmpty()) {
					int colon = line.indexOf(':');
					if (colon > 0) {
						String key = line.substring(0, colon).trim().toLowerCase();
						String value = line.substring(colon + 1).trim();
						headers.put(key, value);
					}
				}
				ILog.get().info(prefix + " Headers: " + headers);

				// Validate nonce
				String auth = headers.get("authorization");
				if (auth == null || !auth.equals("Nonce " + nonce)) {
					ILog.get().warn(prefix + " Auth failed, expected nonce but got: " + auth);
					sendResponse(os, 401, "Unauthorized", "text/plain", "Unauthorized", prefix);
					break;
				}

				// Only handle /mcp and /
				if (!"/mcp".equals(path) && !"/".equals(path)) {
					ILog.get().warn(prefix + " Unknown path: " + path);
					sendResponse(os, 404, "Not Found", "text/plain", "Not Found", prefix);
					break;
				}

				switch (httpMethod) {
				case "POST" -> handlePost(reader, headers, os, prefix);
				case "GET" -> {
					// GET opens an SSE stream — blocks until closed, then exit loop
					handleGet(headers, os, channel, prefix);
					return;
				}
				case "DELETE" -> {
					handleDelete(headers, os, prefix);
					// DELETE terminates the session — close connection
					return;
				}
				default -> {
					ILog.get().warn(prefix + " Unsupported HTTP method: " + httpMethod);
					sendResponse(os, 405, "Method Not Allowed", "text/plain", "Method Not Allowed", prefix);
					break;
				}
				}

				// Check if client requested connection close
				String conn = headers.get("connection");
				if (conn != null && conn.equalsIgnoreCase("close")) {
					ILog.get().info(prefix + " Client requested Connection: close");
					break;
				}
			}
		} catch (IOException e) {
			if (running.get()) {
				ILog.get().warn(prefix + " Connection error after " + requestCount + " request(s): " + e.getMessage());
			}
		}
		ILog.get().info(prefix + " Connection closed, handled " + requestCount + " request(s)");
	}

	private void handlePost(BufferedReader reader, Map<String, String> headers, OutputStream os, String prefix)
			throws IOException {
		// Read body — supports both Content-Length and chunked transfer encoding
		String jsonBody;
		String te = headers.get("transfer-encoding");
		if (te != null && te.toLowerCase().contains("chunked")) {
			ILog.get().info(prefix + " Reading chunked body...");
			jsonBody = readChunkedBody(reader);
		} else {
			int contentLength = 0;
			String clHeader = headers.get("content-length");
			if (clHeader != null) {
				contentLength = Integer.parseInt(clHeader.trim());
			}
			ILog.get().info(prefix + " Reading body, Content-Length=" + contentLength);
			char[] body = new char[contentLength];
			int read = 0;
			while (read < contentLength) {
				int r = reader.read(body, read, contentLength - read);
				if (r < 0)
					break;
				read += r;
			}
			jsonBody = new String(body, 0, read);
		}

		ILog.get().info(prefix + " Body (" + jsonBody.length() + " chars): " + truncate(jsonBody, 500));

		Map<String, Object> request = Json.parseObject(jsonBody);
		if (request == null) {
			ILog.get().warn(prefix + " Failed to parse JSON body");
			sendResponse(os, 400, "Bad Request", "text/plain", "Invalid JSON", prefix);
			return;
		}

		String method = (String) request.get("method");
		Object id = request.get("id");

		ILog.get().info(prefix + " JSON-RPC method='" + method + "' id=" + id);

		// Notification (no id) → 202 Accepted
		if (id == null) {
			ILog.get().info(prefix + " Notification '" + method + "' → 202 Accepted");
			sendResponse(os, 202, "Accepted", "text/plain", "", prefix);
			return;
		}

		// Handle JSON-RPC request
		Map<String, Object> response = dispatch(method, request, prefix);

		// Build JSON-RPC response
		Map<String, Object> jsonRpcResponse = new LinkedHashMap<>();
		jsonRpcResponse.put("jsonrpc", "2.0");
		jsonRpcResponse.put("id", id);
		if (response.containsKey("error")) {
			jsonRpcResponse.put("error", response.get("error"));
		} else {
			jsonRpcResponse.put("result", response.get("result"));
		}

		String responseJson = Json.serialize(jsonRpcResponse);

		// Determine session ID
		String sessionId = headers.get("mcp-session-id");
		if (sessionId == null) {
			sessionId = "vs-" + UUID.randomUUID().toString();
		}

		ILog.get().info(prefix + " Response (" + responseJson.length() + " chars): " + truncate(responseJson, 500));

		// Send as SSE event
		String ssePayload = "event: message\ndata: " + responseJson + "\n\n";
		sendSseResponse(os, 200, sessionId, ssePayload, prefix);
	}

	private void handleGet(Map<String, String> headers, OutputStream os, SocketChannel channel, String prefix)
			throws IOException {
		String sessionId = headers.get("mcp-session-id");
		if (sessionId == null) {
			sessionId = "vs-" + UUID.randomUUID().toString();
		}

		ILog.get().info(prefix + " Opening SSE stream, sessionId=" + sessionId);

		// Send SSE headers
		String httpHeaders = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/event-stream\r\n"
				+ "Cache-Control: no-cache, no-transform\r\n" + "Connection: keep-alive\r\n" + "Mcp-Session-Id: "
				+ sessionId + "\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n";
		os.write(httpHeaders.getBytes(StandardCharsets.UTF_8));
		os.flush();

		// Register as SSE client
		SseClient client = new SseClient(os, channel, prefix);
		sseClients.add(client);
		ILog.get().info(prefix + " SSE client registered, total=" + sseClients.size());

		// Keep the connection alive until closed
		try {
			while (running.get() && client.isAlive()) {
				Thread.sleep(1000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			sseClients.remove(client);
			ILog.get().info(prefix + " SSE client removed, remaining=" + sseClients.size());
		}
	}

	private void handleDelete(Map<String, String> headers, OutputStream os, String prefix) throws IOException {
		ILog.get().info(prefix + " DELETE → session terminated");
		sendResponse(os, 200, "OK", "text/plain", "", prefix);
	}

	// --- JSON-RPC dispatch ---

	@SuppressWarnings("unchecked")
	private Map<String, Object> dispatch(String method, Map<String, Object> request, String prefix) {
		Map<String, Object> params = (Map<String, Object>) request.get("params");
		if (params == null) {
			params = Map.of();
		}

		ILog.get().info(prefix + " Dispatching method='" + method + "'");
		long start = System.currentTimeMillis();

		Map<String, Object> result = switch (method) {
		case "initialize" -> handleInitialize(params);
		case "tools/list" -> handleToolsList();
		case "tools/call" -> handleToolsCall(params, prefix);
		case "ping" -> Map.of("result", Map.of());
		default -> {
			ILog.get().warn(prefix + " Unknown method: " + method);
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("error", Map.of("code", -32601, "message", "Method not found: " + method));
			yield error;
		}
		};

		long elapsed = System.currentTimeMillis() - start;
		ILog.get().info(prefix + " Method '" + method + "' completed in " + elapsed + "ms");
		return result;
	}

	private Map<String, Object> handleInitialize(Map<String, Object> params) {
		Map<String, Object> serverInfo = new LinkedHashMap<>();
		serverInfo.put("name", SERVER_NAME);
		serverInfo.put("version", SERVER_VERSION);

		Map<String, Object> capabilities = new LinkedHashMap<>();
		capabilities.put("tools", Map.of("listChanged", true));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("protocolVersion", MCP_PROTOCOL_VERSION);
		result.put("capabilities", capabilities);
		result.put("serverInfo", serverInfo);

		return Map.of("result", result);
	}

	private Map<String, Object> handleToolsList() {
		List<Map<String, Object>> tools = new ArrayList<>();

		tools.add(toolDef("get_vscode_info", "Get IDE instance information",
				Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));

		tools.add(toolDef("get_selection", "Get the current text selection or cursor position",
				Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));

		Map<String, Object> diagProps = new LinkedHashMap<>();
		diagProps.put("uri", Map.of("type", "string", "description", "File URI to filter diagnostics"));
		tools.add(toolDef("get_diagnostics", "Get language diagnostics (errors, warnings)",
				Map.of("type", "object", "properties", diagProps)));

		Map<String, Object> diffProps = new LinkedHashMap<>();
		diffProps.put("original_file_path", Map.of("type", "string", "description", "Path to the original file"));
		diffProps.put("new_file_contents", Map.of("type", "string", "description", "The proposed new file content"));
		diffProps.put("tab_name", Map.of("type", "string", "description", "Display name for the diff tab"));
		tools.add(toolDef("open_diff", "Open a diff view with proposed changes",
				Map.of("type", "object", "properties", diffProps,
						"required", List.of("original_file_path", "new_file_contents", "tab_name"))));

		Map<String, Object> closeDiffProps = new LinkedHashMap<>();
		closeDiffProps.put("tab_name", Map.of("type", "string", "description", "Tab name of the diff to close"));
		tools.add(toolDef("close_diff", "Close a diff tab",
				Map.of("type", "object", "properties", closeDiffProps, "required", List.of("tab_name"))));

		Map<String, Object> sessionProps = new LinkedHashMap<>();
		sessionProps.put("name", Map.of("type", "string", "description", "The new session name"));
		tools.add(toolDef("update_session_name", "Set the CLI session display name",
				Map.of("type", "object", "properties", sessionProps, "required", List.of("name"))));

		return Map.of("result", Map.of("tools", tools));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> handleToolsCall(Map<String, Object> params, String prefix) {
		String name = (String) params.get("name");
		Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
		if (arguments == null) {
			arguments = Map.of();
		}

		ILog.get().info(prefix + " Tool call: " + name + " args=" + truncate(Json.serialize(arguments), 200));

		try {
			Object result = toolHandler.callTool(name, arguments);
			String text;
			if (result instanceof Map<?, ?> map) {
				text = Json.serialize(map);
			} else if (result instanceof List<?> list) {
				text = Json.serialize(list);
			} else {
				text = String.valueOf(result);
			}

			ILog.get().info(prefix + " Tool '" + name + "' result (" + text.length() + " chars): " + truncate(text, 300));

			Map<String, Object> content = new LinkedHashMap<>();
			content.put("type", "text");
			content.put("text", text);

			return Map.of("result", Map.of("content", List.of(content), "isError", false));
		} catch (Exception e) {
			ILog.get().error(prefix + " Tool '" + name + "' error: " + e.getMessage(), e);
			Map<String, Object> content = new LinkedHashMap<>();
			content.put("type", "text");
			content.put("text", "Error: " + e.getMessage());

			return Map.of("result", Map.of("content", List.of(content), "isError", true));
		}
	}

	private Map<String, Object> toolDef(String name, String description, Map<String, Object> inputSchema) {
		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("name", name);
		tool.put("description", description);
		tool.put("inputSchema", inputSchema);
		return tool;
	}

	// --- Chunked transfer encoding ---

	/**
	 * Reads an HTTP chunked transfer encoded body per RFC 7230 §4.1.
	 * Each chunk: hex-size CRLF data CRLF, terminated by a 0-size chunk.
	 */
	private String readChunkedBody(BufferedReader reader) throws IOException {
		StringBuilder result = new StringBuilder();
		while (true) {
			String sizeLine = reader.readLine();
			if (sizeLine == null) {
				break;
			}
			// Strip chunk extensions (after semicolon)
			int semi = sizeLine.indexOf(';');
			if (semi >= 0) {
				sizeLine = sizeLine.substring(0, semi);
			}
			sizeLine = sizeLine.trim();
			int chunkSize;
			try {
				chunkSize = Integer.parseInt(sizeLine, 16);
			} catch (NumberFormatException e) {
				break;
			}
			if (chunkSize == 0) {
				// Read trailing CRLF after final chunk
				reader.readLine();
				break;
			}
			char[] chunkData = new char[chunkSize];
			int totalRead = 0;
			while (totalRead < chunkSize) {
				int r = reader.read(chunkData, totalRead, chunkSize - totalRead);
				if (r < 0)
					break;
				totalRead += r;
			}
			result.append(chunkData, 0, totalRead);
			// Read trailing CRLF after chunk data
			reader.readLine();
		}
		return result.toString();
	}

	// --- HTTP helpers ---

	private void sendResponse(OutputStream os, int status, String statusText, String contentType, String body,
			String prefix) throws IOException {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		String response = "HTTP/1.1 " + status + " " + statusText + "\r\n" + "Content-Type: " + contentType + "\r\n"
				+ "Content-Length: " + bodyBytes.length + "\r\n" + "\r\n";
		ILog.get().info(prefix + " → HTTP " + status + " " + statusText + " (" + bodyBytes.length + " bytes)");
		os.write(response.getBytes(StandardCharsets.UTF_8));
		os.write(bodyBytes);
		os.flush();
	}

	private void sendSseResponse(OutputStream os, int status, String sessionId, String ssePayload, String prefix)
			throws IOException {
		byte[] payloadBytes = ssePayload.getBytes(StandardCharsets.UTF_8);
		String headers = "HTTP/1.1 " + status + " OK\r\n" + "Content-Type: text/event-stream\r\n" + "Mcp-Session-Id: "
				+ sessionId + "\r\n" + "Content-Length: " + payloadBytes.length + "\r\n" + "\r\n";
		ILog.get().info(prefix + " → SSE HTTP " + status + " sessionId=" + sessionId + " (" + payloadBytes.length
				+ " bytes)");
		os.write(headers.getBytes(StandardCharsets.UTF_8));
		os.write(payloadBytes);
		os.flush();
	}

	private static String truncate(String s, int maxLen) {
		if (s == null)
			return "null";
		return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(" + s.length() + " total)";
	}

	// --- SSE client wrapper ---

	static class SseClient {
		private final OutputStream os;
		private final SocketChannel channel;
		private final String prefix;
		private volatile boolean alive = true;

		SseClient(OutputStream os, SocketChannel channel, String prefix) {
			this.os = os;
			this.channel = channel;
			this.prefix = prefix;
		}

		boolean send(String json) {
			if (!alive)
				return false;
			try {
				String sseEvent = "event: message\ndata: " + json + "\n\n";
				byte[] data = sseEvent.getBytes(StandardCharsets.UTF_8);
				String chunk = Integer.toHexString(data.length) + "\r\n";
				os.write(chunk.getBytes(StandardCharsets.UTF_8));
				os.write(data);
				os.write("\r\n".getBytes(StandardCharsets.UTF_8));
				os.flush();
				ILog.get().info(prefix + " SSE push sent (" + data.length + " bytes)");
				return true;
			} catch (IOException e) {
				ILog.get().info(prefix + " SSE push failed (client disconnected): " + e.getMessage());
				alive = false;
				return false;
			}
		}

		boolean isAlive() {
			return alive && channel.isOpen();
		}

		void close() {
			alive = false;
			try {
				channel.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}
}
