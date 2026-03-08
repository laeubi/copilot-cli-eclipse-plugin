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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Pushes debounced {@code selection_changed} and {@code diagnostics_changed}
 * SSE notifications to connected Copilot CLI clients.
 */
public class NotificationPusher {

	private static final long DEBOUNCE_MS = 200;

	private final McpServer server;
	private final EclipseToolHandler toolHandler;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "MCP-NotificationDebounce");
		t.setDaemon(true);
		return t;
	});

	private ScheduledFuture<?> pendingSelection;
	private ScheduledFuture<?> pendingDiagnostics;
	private String lastSelectionFingerprint = "";
	private String lastDiagnosticsHash = "";

	private ISelectionListener selectionListener;
	private IPartListener2 partListener;
	private IResourceChangeListener resourceChangeListener;
	private IWindowListener windowListener;
	private boolean started = false;

	public NotificationPusher(McpServer server, EclipseToolHandler toolHandler) {
		this.server = server;
		this.toolHandler = toolHandler;
	}

	public void start() {
		if (started) {
			return;
		}
		started = true;
		ILog.get().info("[MCP Notifications] Starting notification listeners...");

		Display.getDefault().asyncExec(() -> {
			try {
				registerSelectionListener();
				registerDiagnosticsListener();
				ILog.get().info("[MCP Notifications] Listeners registered successfully");
			} catch (Exception e) {
				ILog.get().warn("[MCP Notifications] Failed to register listeners: " + e.getMessage());
			}
		});
	}

	public void stop() {
		started = false;
		ILog.get().info("[MCP Notifications] Stopping notification listeners...");
		scheduler.shutdownNow();

		Display.getDefault().asyncExec(() -> {
			try {
				unregisterListeners();
				ILog.get().info("[MCP Notifications] Listeners unregistered");
			} catch (Exception e) {
				// ignore on shutdown
			}
		});
	}

	private void registerSelectionListener() {
		selectionListener = (part, selection) -> debounceSelection();

		partListener = new IPartListener2() {
			@Override
			public void partActivated(IWorkbenchPartReference partRef) {
				debounceSelection();
			}

			@Override
			public void partBroughtToTop(IWorkbenchPartReference partRef) {
				debounceSelection();
			}
		};

		// Register on active window
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null) {
			addListenersToWindow(window);
		}

		// Listen for window changes
		windowListener = new IWindowListener() {
			@Override
			public void windowActivated(IWorkbenchWindow window) {
				addListenersToWindow(window);
			}

			@Override
			public void windowDeactivated(IWorkbenchWindow window) {
			}

			@Override
			public void windowClosed(IWorkbenchWindow window) {
			}

			@Override
			public void windowOpened(IWorkbenchWindow window) {
				addListenersToWindow(window);
			}
		};
		PlatformUI.getWorkbench().addWindowListener(windowListener);
	}

	private void addListenersToWindow(IWorkbenchWindow window) {
		if (window == null)
			return;
		window.getSelectionService().addPostSelectionListener(selectionListener);
		IWorkbenchPage page = window.getActivePage();
		if (page != null) {
			page.addPartListener(partListener);
		}
	}

	private void registerDiagnosticsListener() {
		resourceChangeListener = event -> {
			if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
				debounceDiagnostics();
			}
		};
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceChangeListener,
				IResourceChangeEvent.POST_CHANGE);
	}

	private void unregisterListeners() {
		try {
			if (windowListener != null) {
				PlatformUI.getWorkbench().removeWindowListener(windowListener);
			}
			for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
				if (selectionListener != null) {
					window.getSelectionService().removePostSelectionListener(selectionListener);
				}
				if (partListener != null) {
					IWorkbenchPage page = window.getActivePage();
					if (page != null) {
						page.removePartListener(partListener);
					}
				}
			}
		} catch (Exception e) {
			// ignore
		}
		try {
			if (resourceChangeListener != null) {
				ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceChangeListener);
			}
		} catch (Exception e) {
			// ignore
		}
	}

	private void debounceSelection() {
		if (!server.isRunning())
			return;
		if (pendingSelection != null) {
			pendingSelection.cancel(false);
		}
		pendingSelection = scheduler.schedule(this::pushSelectionChanged, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private void debounceDiagnostics() {
		if (!server.isRunning())
			return;
		if (pendingDiagnostics != null) {
			pendingDiagnostics.cancel(false);
		}
		pendingDiagnostics = scheduler.schedule(this::pushDiagnosticsChanged, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private void pushSelectionChanged() {
		try {
			Map<String, Object> data = toolHandler.getCurrentSelection();
			if (data == null)
				return;

			// Deduplication
			String fingerprint = buildSelectionFingerprint(data);
			if (fingerprint.equals(lastSelectionFingerprint))
				return;
			lastSelectionFingerprint = fingerprint;

			ILog.get().info("[MCP Notifications] Pushing selection_changed: " + fingerprint);
			server.pushNotification("selection_changed", data);
		} catch (Exception e) {
			ILog.get().warn("[MCP Notifications] Error pushing selection_changed: " + e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private String buildSelectionFingerprint(Map<String, Object> data) {
		String path = (String) data.getOrDefault("filePath", "");
		Map<String, Object> sel = (Map<String, Object>) data.get("selection");
		if (sel == null)
			return path;
		Map<String, Object> start = (Map<String, Object>) sel.get("start");
		Map<String, Object> end = (Map<String, Object>) sel.get("end");
		return path + ":" + start.get("line") + ":" + start.get("character") + ":" + end.get("line") + ":"
				+ end.get("character") + ":" + sel.get("isEmpty");
	}

	private void pushDiagnosticsChanged() {
		try {
			IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true,
					IResource.DEPTH_INFINITE);

			// Build notification payload
			Map<String, List<Map<String, Object>>> byUri = new LinkedHashMap<>();

			for (IMarker m : markers) {
				IResource res = m.getResource();
				if (res == null || res.getLocation() == null)
					continue;
				String path = res.getLocation().toOSString();
				String uri = EclipseToolHandler.pathToFileUri(path);

				byUri.computeIfAbsent(uri, k -> new ArrayList<>()).add(buildDiagnostic(m));
			}

			List<Map<String, Object>> uris = new ArrayList<>();
			for (var entry : byUri.entrySet()) {
				Map<String, Object> fileEntry = new LinkedHashMap<>();
				fileEntry.put("uri", entry.getKey());
				fileEntry.put("diagnostics", entry.getValue());
				uris.add(fileEntry);
			}

			// Deduplication via hash
			String hash = String.valueOf(uris.hashCode());
			if (hash.equals(lastDiagnosticsHash))
				return;
			lastDiagnosticsHash = hash;

			ILog.get().info("[MCP Notifications] Pushing diagnostics_changed: " + markers.length + " markers, "
					+ uris.size() + " files");

			Map<String, Object> params = new LinkedHashMap<>();
			params.put("uris", uris);
			server.pushNotification("diagnostics_changed", params);
		} catch (Exception e) {
			ILog.get().warn("[MCP Notifications] Error pushing diagnostics_changed: " + e.getMessage());
		}
	}

	private Map<String, Object> buildDiagnostic(IMarker m) {
		Map<String, Object> diag = new LinkedHashMap<>();
		try {
			diag.put("message", m.getAttribute(IMarker.MESSAGE, ""));
			int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
			diag.put("severity", switch (severity) {
			case IMarker.SEVERITY_ERROR -> "error";
			case IMarker.SEVERITY_WARNING -> "warning";
			default -> "information";
			});

			int line = m.getAttribute(IMarker.LINE_NUMBER, 1) - 1;
			int charStart = m.getAttribute(IMarker.CHAR_START, 0);
			int charEnd = m.getAttribute(IMarker.CHAR_END, 0);
			diag.put("range", Map.of("start", Map.of("line", line, "character", charStart), "end",
					Map.of("line", line, "character", charEnd)));

			Object code = m.getAttribute("code");
			if (code != null) {
				diag.put("code", String.valueOf(code));
			}
		} catch (Exception e) {
			// best effort
		}
		return diag;
	}
}
