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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Implements the MCP tool calls using Eclipse platform APIs for editor
 * selection, diagnostics, and diff views.
 */
public class EclipseToolHandler implements McpToolHandler {

	private final ConcurrentHashMap<String, DiffSession> activeDiffs = new ConcurrentHashMap<>();

	@Override
	public Object callTool(String toolName, Map<String, Object> arguments) throws Exception {
		ILog.get().info("[MCP Tool] Calling tool: " + toolName);
		long start = System.currentTimeMillis();
		try {
			Object result = switch (toolName) {
			case "get_vscode_info" -> getIdeInfo();
			case "get_selection" -> getSelection();
			case "get_diagnostics" -> getDiagnostics(arguments);
			case "open_diff" -> openDiff(arguments);
			case "close_diff" -> closeDiff(arguments);
			case "update_session_name" -> updateSessionName(arguments);
			default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
			};
			long elapsed = System.currentTimeMillis() - start;
			ILog.get().info("[MCP Tool] Tool '" + toolName + "' completed in " + elapsed + "ms");
			return result;
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - start;
			ILog.get().error("[MCP Tool] Tool '" + toolName + "' failed after " + elapsed + "ms: " + e.getMessage(), e);
			throw e;
		}
	}

	// --- get_vscode_info ---

	private Map<String, Object> getIdeInfo() {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("ideName", "Eclipse IDE");
		info.put("appName", Platform.getProduct() != null ? Platform.getProduct().getName() : "Eclipse IDE");
		info.put("version", System.getProperty("eclipse.buildId", "unknown"));
		info.put("processId", ProcessHandle.current().pid());
		return info;
	}

	// --- get_selection ---

	private Map<String, Object> getSelection() throws Exception {
		CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

		Display.getDefault().asyncExec(() -> {
			try {
				Map<String, Object> result = new LinkedHashMap<>();
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window == null) {
					result.put("current", false);
					future.complete(result);
					return;
				}
				IWorkbenchPage page = window.getActivePage();
				if (page == null) {
					result.put("current", false);
					future.complete(result);
					return;
				}
				IEditorPart editor = page.getActiveEditor();
				if (editor == null) {
					result.put("current", false);
					future.complete(result);
					return;
				}

				result.put("current", true);

				// Get file path
				File file = getFileFromEditor(editor);
				if (file != null) {
					String absPath = file.getAbsolutePath();
					result.put("filePath", absPath);
					result.put("fileUrl", pathToFileUri(absPath));
				}

				// Get selection
				if (editor instanceof ITextEditor textEditor) {
					ISelection sel = textEditor.getSelectionProvider().getSelection();
					if (sel instanceof ITextSelection textSel) {
						result.put("text", textSel.getText() != null ? textSel.getText() : "");
						Map<String, Object> selection = new LinkedHashMap<>();
						Map<String, Object> start = new LinkedHashMap<>();
						start.put("line", textSel.getStartLine());
						start.put("character", computeCharacterOffset(textEditor, textSel, true));
						Map<String, Object> end = new LinkedHashMap<>();
						end.put("line", textSel.getEndLine());
						end.put("character", computeCharacterOffset(textEditor, textSel, false));
						selection.put("start", start);
						selection.put("end", end);
						selection.put("isEmpty", textSel.getLength() == 0);
						result.put("selection", selection);
					}
				}

				future.complete(result);
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});

		return future.get();
	}

	private int computeCharacterOffset(ITextEditor editor, ITextSelection sel, boolean isStart) {
		try {
			IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
			if (doc != null) {
				int line = isStart ? sel.getStartLine() : sel.getEndLine();
				int lineOffset = doc.getLineOffset(line);
				int offset = isStart ? sel.getOffset() : (sel.getOffset() + sel.getLength());
				return offset - lineOffset;
			}
		} catch (Exception e) {
			// fallback
		}
		return 0;
	}

	// --- get_diagnostics ---

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getDiagnostics(Map<String, Object> arguments) throws Exception {
		CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();

		Display.getDefault().asyncExec(() -> {
			try {
				String filterUri = (String) arguments.get("uri");
				List<Map<String, Object>> result = new ArrayList<>();

				IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true,
						IResource.DEPTH_INFINITE);

				// Group by resource
				Map<String, List<IMarker>> byResource = new LinkedHashMap<>();
				for (IMarker marker : markers) {
					IResource res = marker.getResource();
					if (res == null)
						continue;
					String path = res.getLocation() != null ? res.getLocation().toOSString() : null;
					if (path == null)
						continue;
					String fileUri = pathToFileUri(path);

					if (filterUri != null && !filterUri.equals(fileUri))
						continue;

					byResource.computeIfAbsent(path, k -> new ArrayList<>()).add(marker);
				}

				for (var entry : byResource.entrySet()) {
					Map<String, Object> fileGroup = new LinkedHashMap<>();
					fileGroup.put("uri", pathToFileUri(entry.getKey()));
					fileGroup.put("filePath", entry.getKey());

					List<Map<String, Object>> diagnostics = new ArrayList<>();
					for (IMarker m : entry.getValue()) {
						Map<String, Object> diag = new LinkedHashMap<>();
						diag.put("message", m.getAttribute(IMarker.MESSAGE, ""));

						int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
						diag.put("severity", switch (severity) {
						case IMarker.SEVERITY_ERROR -> "error";
						case IMarker.SEVERITY_WARNING -> "warning";
						default -> "information";
						});

						int lineNumber = m.getAttribute(IMarker.LINE_NUMBER, 1) - 1; // 0-based
						int charStart = m.getAttribute(IMarker.CHAR_START, 0);
						int charEnd = m.getAttribute(IMarker.CHAR_END, 0);

						Map<String, Object> range = new LinkedHashMap<>();
						range.put("start", Map.of("line", lineNumber, "character", charStart));
						range.put("end", Map.of("line", lineNumber, "character", charEnd));
						diag.put("range", range);

						String source = m.getAttribute("source", (String) null);
						if (source != null) {
							diag.put("source", source);
						}

						String type = m.getType();
						if (type != null && !IMarker.PROBLEM.equals(type)) {
							diag.put("source", type);
						}

						Object code = m.getAttribute("code");
						if (code != null) {
							diag.put("code", String.valueOf(code));
						}

						diagnostics.add(diag);
					}
					fileGroup.put("diagnostics", diagnostics);
					result.add(fileGroup);
				}

				future.complete(result);
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});

		return future.get();
	}

	// --- open_diff ---

	private Map<String, Object> openDiff(Map<String, Object> arguments) throws Exception {
		String originalPath = (String) arguments.get("original_file_path");
		String newContents = (String) arguments.get("new_file_contents");
		String tabName = (String) arguments.get("tab_name");

		if (originalPath == null || newContents == null || tabName == null) {
			throw new IllegalArgumentException("Missing required arguments");
		}

		// Close any existing diff with the same tab name
		DiffSession existing = activeDiffs.get(tabName);
		if (existing != null) {
			existing.resolve("REJECTED", "closed_via_tool");
		}

		CompletableFuture<Map<String, Object>> diffResult = new CompletableFuture<>();
		DiffSession session = new DiffSession(tabName, diffResult);
		activeDiffs.put(tabName, session);

		Display.getDefault().asyncExec(() -> {
			try {
				Path origFile = Path.of(originalPath);
				String originalContent = Files.readString(origFile, StandardCharsets.UTF_8);

				CompareConfiguration config = new CompareConfiguration();
				config.setLeftEditable(false);
				config.setRightEditable(false);
				config.setLeftLabel("Original: " + origFile.getFileName());
				config.setRightLabel("Proposed: " + tabName);

				CompareItem left = new CompareItem(origFile.getFileName().toString(), originalContent,
						System.currentTimeMillis());
				CompareItem right = new CompareItem(tabName, newContents, System.currentTimeMillis());

				CompareEditorInput input = new CompareEditorInput(config) {
					@Override
					protected Object prepareInput(IProgressMonitor monitor) {
						return new DiffNode(Differencer.CHANGE, null, left, right);
					}
				};
				input.setTitle("Copilot Diff: " + tabName);

				session.setEditorInput(input);
				CompareUI.openCompareEditor(input);

				// Listen for editor close
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window != null && window.getActivePage() != null) {
					window.getActivePage().addPartListener(new org.eclipse.ui.IPartListener2() {
						@Override
						public void partClosed(org.eclipse.ui.IWorkbenchPartReference partRef) {
							if (partRef.getPart(false) instanceof IEditorPart editorPart) {
								if (editorPart.getEditorInput() == input) {
									window.getActivePage().removePartListener(this);
									session.resolve("REJECTED", "closed_via_tab");
								}
							}
						}
					});
				}
			} catch (Exception e) {
				session.resolve(null, null);
				diffResult.completeExceptionally(e);
			}
		});

		// Block until user acts (no timeout for open_diff)
		return diffResult.get();
	}

	// --- close_diff ---

	private Map<String, Object> closeDiff(Map<String, Object> arguments) {
		String tabName = (String) arguments.get("tab_name");
		if (tabName == null) {
			throw new IllegalArgumentException("Missing required argument: tab_name");
		}

		DiffSession session = activeDiffs.remove(tabName);
		if (session == null) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("already_closed", true);
			result.put("tab_name", tabName);
			result.put("message", "No active diff with tab name: " + tabName);
			result.put("error", null);
			return result;
		}

		session.resolve("REJECTED", "closed_via_tool");

		// Close the editor on the UI thread
		Display.getDefault().asyncExec(() -> {
			CompareEditorInput input = session.getEditorInput();
			if (input != null) {
				closeCompareEditor(input);
			}
		});

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("success", true);
		result.put("already_closed", false);
		result.put("tab_name", tabName);
		result.put("message", "Diff \"" + tabName + "\" closed and changes rejected");
		result.put("error", null);
		return result;
	}

	private void closeCompareEditor(CompareEditorInput input) {
		try {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null && window.getActivePage() != null) {
				for (IEditorReference ref : window.getActivePage().getEditorReferences()) {
					IEditorPart editor = ref.getEditor(false);
					if (editor != null && editor.getEditorInput() == input) {
						window.getActivePage().closeEditor(editor, false);
						break;
					}
				}
			}
		} catch (Exception e) {
			ILog.get().warn("Error closing compare editor: " + e.getMessage());
		}
	}

	// --- update_session_name ---

	private Map<String, Object> updateSessionName(Map<String, Object> arguments) {
		return Map.of("success", true);
	}

	// --- Selection tracking (for push notifications) ---

	/**
	 * Get the current selection data (called from notification pusher).
	 */
	public Map<String, Object> getCurrentSelection() {
		CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

		Display.getDefault().asyncExec(() -> {
			try {
				Map<String, Object> result = new LinkedHashMap<>();
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window == null || window.getActivePage() == null) {
					future.complete(null);
					return;
				}
				IEditorPart editor = window.getActivePage().getActiveEditor();
				if (editor == null) {
					future.complete(null);
					return;
				}

				File file = getFileFromEditor(editor);
				if (file != null) {
					String absPath = file.getAbsolutePath();
					result.put("filePath", absPath);
					result.put("fileUrl", pathToFileUri(absPath));
				}

				if (editor instanceof ITextEditor textEditor) {
					ISelection sel = textEditor.getSelectionProvider().getSelection();
					if (sel instanceof ITextSelection textSel) {
						result.put("text", textSel.getText() != null ? textSel.getText() : "");
						Map<String, Object> selection = new LinkedHashMap<>();
						Map<String, Object> start = new LinkedHashMap<>();
						start.put("line", textSel.getStartLine());
						start.put("character", computeCharacterOffset(textEditor, textSel, true));
						Map<String, Object> end = new LinkedHashMap<>();
						end.put("line", textSel.getEndLine());
						end.put("character", computeCharacterOffset(textEditor, textSel, false));
						selection.put("start", start);
						selection.put("end", end);
						selection.put("isEmpty", textSel.getLength() == 0);
						result.put("selection", selection);
					}
				}

				future.complete(result);
			} catch (Exception e) {
				future.complete(null);
			}
		});

		try {
			return future.get();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolve the diff with user's action: accept saves the file, reject discards.
	 */
	public void acceptDiff(String tabName) {
		DiffSession session = activeDiffs.get(tabName);
		if (session != null) {
			session.resolve("SAVED", "accepted_via_button");
		}
	}

	// --- Utilities ---

	private File getFileFromEditor(IEditorPart editor) {
		IEditorInput input = editor.getEditorInput();
		if (input instanceof IPathEditorInput pathInput) {
			var path = pathInput.getPath();
			if (path != null) {
				return path.toFile();
			}
		}
		var file = input.getAdapter(org.eclipse.core.resources.IFile.class);
		if (file != null) {
			var location = file.getLocation();
			if (location != null) {
				return location.toFile();
			}
		}
		return null;
	}

	static String pathToFileUri(String absPath) {
		try {
			return new File(absPath).toURI().toString();
		} catch (Exception e) {
			return "file://" + absPath;
		}
	}

	/**
	 * Get workspace folder paths for the lock file.
	 */
	public static List<String> getWorkspaceFolders() {
		List<String> folders = new ArrayList<>();
		try {
			IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
			for (IProject project : projects) {
				if (project.isOpen() && project.getLocation() != null) {
					folders.add(project.getLocation().toOSString());
				}
			}
		} catch (Exception e) {
			// fallback
		}
		if (folders.isEmpty()) {
			String ws = ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
			folders.add(ws);
		}
		return folders;
	}

	// --- Compare editor helpers ---

	private static class CompareItem implements ITypedElement, IStreamContentAccessor, IModificationDate {
		private final String name;
		private final String content;
		private final long modificationDate;

		CompareItem(String name, String content, long modificationDate) {
			this.name = name;
			this.content = content;
			this.modificationDate = modificationDate;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			int dot = name.lastIndexOf('.');
			return dot >= 0 ? name.substring(dot + 1) : ITypedElement.TEXT_TYPE;
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public long getModificationDate() {
			return modificationDate;
		}
	}

	// --- Diff session tracking ---

	private static class DiffSession {
		private final String tabName;
		private final CompletableFuture<Map<String, Object>> future;
		private volatile CompareEditorInput editorInput;

		DiffSession(String tabName, CompletableFuture<Map<String, Object>> future) {
			this.tabName = tabName;
			this.future = future;
		}

		void setEditorInput(CompareEditorInput input) {
			this.editorInput = input;
		}

		CompareEditorInput getEditorInput() {
			return editorInput;
		}

		void resolve(String resultValue, String trigger) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", resultValue != null);
			result.put("result", resultValue != null ? resultValue : "REJECTED");
			result.put("trigger", trigger != null ? trigger : "closed_via_tab");
			result.put("tab_name", tabName);
			result.put("message", resultValue != null
					? "User " + (resultValue.equals("SAVED") ? "accepted" : "rejected") + " changes for " + tabName
					: null);
			result.put("error", null);
			future.complete(result);
		}
	}
}
