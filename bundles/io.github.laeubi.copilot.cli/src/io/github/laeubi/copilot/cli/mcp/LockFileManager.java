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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;

/**
 * Manages lock files in {@code ~/.copilot/ide/} for IDE discovery by the
 * Copilot CLI.
 */
public class LockFileManager {

	private static final Path LOCK_DIR = Path.of(System.getProperty("user.home"), ".copilot", "ide");

	private Path lockFilePath;

	/**
	 * Write a lock file so Copilot CLI can discover this IDE instance.
	 *
	 * @param uuid             unique session identifier
	 * @param socketPath       path to the Unix domain socket
	 * @param nonce            authentication nonce
	 * @param ideName          human-readable IDE name
	 * @param workspaceFolders list of open workspace folder paths
	 */
	public void writeLockFile(String uuid, String socketPath, String nonce, String ideName,
			List<String> workspaceFolders) throws IOException {
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
		lock.put("workspaceFolders", workspaceFolders);
		lock.put("isTrusted", true);

		lockFilePath = LOCK_DIR.resolve(uuid + ".lock");
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
			lockFilePath = null;
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
}
