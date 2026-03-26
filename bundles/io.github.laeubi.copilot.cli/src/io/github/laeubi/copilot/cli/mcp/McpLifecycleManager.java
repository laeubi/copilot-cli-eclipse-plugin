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

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import io.github.laeubi.copilot.cli.Activator;

/**
 * Manages the MCP server lifecycle: starts the Unix domain socket server,
 * writes the lock file for CLI discovery, registers notification listeners, and
 * tears everything down on shutdown.
 */
@Component(service = {})
public class McpLifecycleManager {

	private final String uuid = UUID.randomUUID().toString();
	private final LockFileManager lockFileManager;
	private final EclipseToolHandler toolHandler;
	private final NotificationPusher notificationPusher;
	private final McpServer server;
	private final IWorkspace workspace;

	@Activate
	public McpLifecycleManager(@Reference IWorkspace workspace) throws IOException {
		this.workspace = workspace;
		this.toolHandler = new EclipseToolHandler(workspace);
		String nonce = UUID.randomUUID().toString();
		Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
		try {
			// On Windows, java.io.tmpdir may contain an 8.3 abbreviated username
			// (e.g. MYLONGU~1). toRealPath() resolves it to the full long path so
			// that the socket path written to the lock file is usable by the CLI.
			tmpDir = tmpDir.toRealPath();
		} catch (IOException e) {
			// fall back to the unresolved path if the directory does not exist
		}
		Path socketPath = tmpDir.resolve("mcp-" + uuid + ".sock");
		this.server = new McpServer(socketPath, nonce, toolHandler);
		this.notificationPusher = new NotificationPusher(server, toolHandler);
		this.lockFileManager = new LockFileManager("Eclipse IDE", uuid, nonce, socketPath, toolHandler);

		ILog.get().info("[MCP Lifecycle] Starting MCP server infrastructure...");
		ILog.get().info("[MCP Lifecycle] UUID=" + uuid + " socketPath=" + socketPath);
		try {
			server.start();
			workspace.addResourceChangeListener(lockFileManager);
			IPreferenceStore prefs = getPreferenceStore();
			if (prefs != null) {
				prefs.addPropertyChangeListener(lockFileManager);
			}
			lockFileManager.writeLockFile();
			notificationPusher.start();
			ILog.get().info("[MCP Lifecycle] MCP server started successfully for Copilot CLI /ide integration");
		} catch (IOException e) {
			ILog.get().error("[MCP Lifecycle] Failed to start MCP server", e);
			stop();
			throw e;
		}
	}

	@Deactivate
	public void stop() {
		ILog.get().info("[MCP Lifecycle] Stopping MCP server...");
		IPreferenceStore prefs = getPreferenceStore();
		if (prefs != null) {
			prefs.removePropertyChangeListener(lockFileManager);
		}
		workspace.removeResourceChangeListener(lockFileManager);
		lockFileManager.deleteLockFile();
		notificationPusher.stop();
		server.stop();
		ILog.get().info("[MCP Lifecycle] MCP server stopped");
	}

	private static IPreferenceStore getPreferenceStore() {
		Activator activator = Activator.getDefault();
		return activator != null ? activator.getPreferenceStore() : null;
	}
}

