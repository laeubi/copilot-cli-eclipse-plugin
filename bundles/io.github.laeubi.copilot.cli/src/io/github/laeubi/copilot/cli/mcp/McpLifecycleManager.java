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
package io.github.laeubi.copilot.cli.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;

/**
 * Manages the MCP server lifecycle: starts the Unix domain socket server,
 * writes the lock file for CLI discovery, registers notification listeners, and
 * tears everything down on shutdown.
 */
public class McpLifecycleManager {

	private static McpLifecycleManager instance;

	private McpServer server;
	private LockFileManager lockFileManager;
	private NotificationPusher notificationPusher;
	private EclipseToolHandler toolHandler;
	private String uuid;

	private McpLifecycleManager() {
	}

	public static synchronized McpLifecycleManager getInstance() {
		if (instance == null) {
			instance = new McpLifecycleManager();
		}
		return instance;
	}

	/**
	 * Start the MCP server infrastructure. Safe to call multiple times.
	 */
	public synchronized void start() {
		if (server != null && server.isRunning()) {
			ILog.get().info("[MCP Lifecycle] Already running, skipping start");
			return;
		}

		try {
			ILog.get().info("[MCP Lifecycle] Starting MCP server infrastructure...");

			// Clean stale lock files from previous sessions
			LockFileManager.cleanStaleLockFiles();

			uuid = UUID.randomUUID().toString();
			String nonce = UUID.randomUUID().toString();
			Path socketPath = Path.of(System.getProperty("java.io.tmpdir"), "mcp-" + uuid + ".sock");

			ILog.get().info("[MCP Lifecycle] UUID=" + uuid + " socketPath=" + socketPath);

			toolHandler = new EclipseToolHandler();
			server = new McpServer(socketPath, nonce, toolHandler);
			server.start();

			// Write lock file for CLI discovery
			lockFileManager = new LockFileManager();
			List<String> workspaceFolders = EclipseToolHandler.getWorkspaceFolders();
			ILog.get().info("[MCP Lifecycle] Workspace folders: " + workspaceFolders);
			lockFileManager.writeLockFile(uuid, socketPath.toString(), nonce, "Eclipse IDE", workspaceFolders);

			// Start push notifications
			notificationPusher = new NotificationPusher(server, toolHandler);
			notificationPusher.start();

			ILog.get().info("[MCP Lifecycle] MCP server started successfully for Copilot CLI /ide integration");
		} catch (Exception e) {
			ILog.get().error("[MCP Lifecycle] Failed to start MCP server", e);
			stop();
		}
	}

	/**
	 * Stop the MCP server and clean up resources.
	 */
	public synchronized void stop() {
		ILog.get().info("[MCP Lifecycle] Stopping MCP server...");
		if (notificationPusher != null) {
			notificationPusher.stop();
			notificationPusher = null;
		}
		if (lockFileManager != null) {
			lockFileManager.deleteLockFile();
			lockFileManager = null;
		}
		if (server != null) {
			server.stop();
			server = null;
		}
		toolHandler = null;
		uuid = null;
		ILog.get().info("[MCP Lifecycle] MCP server stopped");
	}

	public boolean isRunning() {
		return server != null && server.isRunning();
	}
}
