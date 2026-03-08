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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;

/**
 * Manages lock files in {@code ~/.copilot/ide/} for IDE discovery by the
 * Copilot CLI.
 */
public class LockFileManager implements IResourceChangeListener, IPropertyChangeListener {

	private static final Path LOCK_DIR = Path.of(System.getProperty("user.home"), ".copilot", "ide");

	private final Path lockFilePath;

	private final String uuid;

	private final String nonce;

	private final Path socketPath;

	private final String ideName;

	private final EclipseToolHandler toolHandler;

	static {
		cleanStaleLockFiles();
	}

	public LockFileManager(String ideName, String uuid, String nonce, Path socketPath,
			EclipseToolHandler toolHandler) {
		this.ideName = ideName;
		this.uuid = uuid;
		this.nonce = nonce;
		this.socketPath = socketPath;
		this.toolHandler = toolHandler;
		this.lockFilePath = LOCK_DIR.resolve(uuid + ".lock");
	}

	/**
	 * Write a lock file so Copilot CLI can discover this IDE instance.
	 */
	public void writeLockFile() throws IOException {
		Files.createDirectories(LOCK_DIR);

		Map<String, Object> lock = new LinkedHashMap<>();
		lock.put("socketPath", socketPath);
		lock.put("scheme", "pipe");

		Map<String, Object> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Nonce " + nonce);
		lock.put("headers", headers);

		lock.put("pid", ProcessHandle.current().pid());
		lock.put("ideName", ideName);
		lock.put("timestamp", System.currentTimeMillis());
		List<String> folders = toolHandler.getWorkspaceFolders();
		ILog.get().info("[MCP Lifecycle] Workspace folders: " + folders);
		lock.put("workspaceFolders", folders);
		lock.put("isTrusted", true);

		Files.writeString(lockFilePath, Json.serialize(lock), StandardCharsets.UTF_8);
		ILog.get().info("MCP lock file written: " + lockFilePath);
	}

	/**
	 * Delete the lock file written by this instance.
	 */
	public void deleteLockFile() {
		if (lockFilePath != null) {
			try {
				Files.deleteIfExists(lockFilePath);
				ILog.get().info("MCP lock file removed: " + lockFilePath);
			} catch (IOException e) {
				ILog.get().warn("Failed to delete MCP lock file: " + e.getMessage());
			}
		}
	}

	/**
	 * Clean stale lock files whose PIDs are no longer running.
	 */
	public static void cleanStaleLockFiles() {
		try {
			if (!Files.isDirectory(LOCK_DIR)) {
				return;
			}
			try (var stream = Files.list(LOCK_DIR)) {
				stream.filter(p -> p.toString().endsWith(".lock")).forEach(p -> {
					try {
						String content = Files.readString(p, StandardCharsets.UTF_8);
						Map<String, Object> lock = Json.parseObject(content);
						if (lock != null && lock.containsKey("pid")) {
							long pid = ((Number) lock.get("pid")).longValue();
							if (ProcessHandle.of(pid).isEmpty()) {
								Files.deleteIfExists(p);
								ILog.get().info("Cleaned stale MCP lock file: " + p);
							}
						}
					} catch (Exception e) {
						// ignore malformed lock files
					}
				});
			}
		} catch (IOException e) {
			ILog.get().warn("Error cleaning stale MCP lock files: " + e.getMessage());
		}
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		if (event.getType() != IResourceChangeEvent.POST_CHANGE) {
			return;
		}
		IResourceDelta delta = event.getDelta();
		if (delta == null) {
			return;
		}
		// Check if any top-level project was added, removed, opened, or closed.
		boolean projectsChanged = false;
		for (IResourceDelta child : delta.getAffectedChildren()) {
			if (child.getResource() instanceof IProject) {
				int kind = child.getKind();
				int flags = child.getFlags();
				if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED
						|| (flags & IResourceDelta.OPEN) != 0) {
					projectsChanged = true;
					break;
				}
			}
		}
		if (projectsChanged) {
			ILog.get().info("[MCP Lifecycle] Project set changed, rewriting lock file");
			try {
				writeLockFile();
			} catch (IOException e) {
				ILog.get().warn("[MCP Lifecycle] Failed to rewrite lock file after project change: " + e.getMessage());
			}
		}
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (WorkspaceFolderStyle.PREF_KEY.equals(event.getProperty())) {
			ILog.get().info("[MCP Lifecycle] Workspace folder style changed to: " + event.getNewValue()
					+ " – rewriting lock file");
			try {
				writeLockFile();
			} catch (IOException e) {
				ILog.get().warn(
						"[MCP Lifecycle] Failed to rewrite lock file after preference change: " + e.getMessage());
			}
		}
	}
}

