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
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.resources.IFile;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Implements the MCP tool calls using Eclipse platform APIs for editor
 * selection, diagnostics, and diff views.
 */
public class EclipseToolHandler implements McpToolHandler {

	private static final ILog LOG = ILog.get();

	private final ConcurrentHashMap<String, DiffSession> activeDiffs = new ConcurrentHashMap<>();

	@Override
	public Object callTool(String toolName, Map<String, Object> arguments) throws Exception {
		LOG.info("[MCP Tool] Calling tool: " + toolName);
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
			LOG.info("[MCP Tool] Tool '" + toolName + "' completed in " + elapsed + "ms");
			return result;
		} catch (Throwable t) {
			long elapsed = System.currentTimeMillis() - start;
			LOG.error("[MCP Tool] Tool '" + toolName + "' failed after " + elapsed + "ms: " + t.getMessage(),
					t instanceof Exception e ? e : new RuntimeException(t));
			throw t instanceof Exception e ? e : new RuntimeException(t);
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
		LOG.info("[MCP Tool] getSelection: starting");
		Map<String, Object> result = new LinkedHashMap<>();
		Throwable[] error = { null };

		Display display = getDisplay();
		if (display == null) {
			LOG.warn("[MCP Tool] getSelection: display unavailable");
			result.put("current", false);
			return result;
		}

		LOG.info("[MCP Tool] getSelection: dispatching to UI thread via syncExec");
		display.syncExec(() -> {
			try {
				LOG.info("[MCP Tool] getSelection: running on UI thread");
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window == null) {
					LOG.info("[MCP Tool] getSelection: no active workbench window");
					result.put("current", false);
					return;
				}
				IWorkbenchPage page = window.getActivePage();
				if (page == null) {
					LOG.info("[MCP Tool] getSelection: no active page");
					result.put("current", false);
					return;
				}
				IEditorPart editor = page.getActiveEditor();
				if (editor == null) {
					LOG.info("[MCP Tool] getSelection: no active editor");
					result.put("current", false);
					return;
				}

				LOG.info("[MCP Tool] getSelection: active editor=" + editor.getClass().getName()
						+ " title=" + editor.getTitle());
				result.put("current", true);

				// Get file path
				File file = getFileFromEditor(editor);
				if (file != null) {
					String absPath = file.getAbsolutePath();
					result.put("filePath", absPath);
					result.put("fileUrl", pathToFileUri(absPath));
					LOG.info("[MCP Tool] getSelection: filePath=" + absPath);
				} else {
					LOG.info("[MCP Tool] getSelection: could not extract file from editor");
				}

				// Get selection - try adapter pattern first for complex editors
				ITextEditor textEditor = resolveTextEditor(editor);
				if (textEditor != null) {
					LOG.info("[MCP Tool] getSelection: resolved ITextEditor=" + textEditor.getClass().getName());
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
						LOG.info("[MCP Tool] getSelection: selection captured, isEmpty=" + (textSel.getLength() == 0));
					} else {
						LOG.info("[MCP Tool] getSelection: selection is not ITextSelection: "
								+ (sel == null ? "null" : sel.getClass().getName()));
					}
				} else {
					LOG.info("[MCP Tool] getSelection: editor is not ITextEditor and has no adapter");
				}
			} catch (Throwable t) {
				LOG.error("[MCP Tool] getSelection: error on UI thread: " + t.getMessage(),
						t instanceof Exception e ? e : new RuntimeException(t));
				error[0] = t;
			}
		});

		LOG.info("[MCP Tool] getSelection: syncExec completed, result keys=" + result.keySet());

		if (error[0] != null) {
			throw error[0] instanceof Exception ex ? ex : new RuntimeException(error[0]);
		}

		return result;
	}

	/**
	 * Resolve an ITextEditor from an editor part, using adapter pattern for
	 * multi-page editors (e.g., PDE editors, XML editors).
	 */
	private ITextEditor resolveTextEditor(IEditorPart editor) {
		if (editor instanceof ITextEditor te) {
			return te;
		}
		// Try adapter pattern for multi-page editors
		ITextEditor adapted = editor.getAdapter(ITextEditor.class);
		if (adapted != null) {
			return adapted;
		}
		return null;
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
		LOG.info("[MCP Tool] getDiagnostics: starting");
		List<Map<String, Object>> result = new ArrayList<>();
		Throwable[] error = { null };

		Display display = getDisplay();
		if (display == null) {
			LOG.warn("[MCP Tool] getDiagnostics: display unavailable");
			return result;
		}

		display.syncExec(() -> {
			try {
				String filterUri = (String) arguments.get("uri");
				LOG.info("[MCP Tool] getDiagnostics: filterUri=" + filterUri);

				IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true,
						IResource.DEPTH_INFINITE);
				LOG.info("[MCP Tool] getDiagnostics: found " + markers.length + " markers");

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

				LOG.info("[MCP Tool] getDiagnostics: returning " + result.size() + " file groups");
			} catch (Throwable t) {
				LOG.error("[MCP Tool] getDiagnostics: error: " + t.getMessage(),
						t instanceof Exception e ? e : new RuntimeException(t));
				error[0] = t;
			}
		});

		if (error[0] != null) {
			throw error[0] instanceof Exception ex ? ex : new RuntimeException(error[0]);
		}
		return result;
	}

	// --- open_diff ---

	private Map<String, Object> openDiff(Map<String, Object> arguments) throws Exception {
		String originalPath = (String) arguments.get("original_file_path");
		String newContents = (String) arguments.get("new_file_contents");
		String tabName = (String) arguments.get("tab_name");

		if (originalPath == null || newContents == null || tabName == null) {
			throw new IllegalArgumentException("Missing required arguments");
		}

		LOG.info("[MCP Tool] openDiff: originalPath=" + originalPath + " tabName=" + tabName);

		// Close any existing diff with the same tab name
		DiffSession existing = activeDiffs.get(tabName);
		if (existing != null) {
			LOG.info("[MCP Tool] openDiff: closing existing diff for tabName=" + tabName);
			existing.resolve("REJECTED", "closed_via_tool");
		}

		CompletableFuture<Map<String, Object>> diffResult = new CompletableFuture<>();
		DiffSession session = new DiffSession(tabName, originalPath, newContents, diffResult);
		activeDiffs.put(tabName, session);

		Display display = getDisplay();
		if (display == null) {
			LOG.error("[MCP Tool] openDiff: display unavailable");
			throw new IllegalStateException("Display not available");
		}

		LOG.info("[MCP Tool] openDiff: dispatching to UI thread");
		display.asyncExec(() -> {
			try {
				LOG.info("[MCP Tool] openDiff: running on UI thread");

				Path origFile = Path.of(originalPath);
				String originalContent = Files.readString(origFile, StandardCharsets.UTF_8);

				CompareConfiguration config = new CompareConfiguration();
				config.setLeftEditable(false);
				config.setRightEditable(false);
				config.setLeftLabel("Original: " + origFile.getFileName());
				config.setRightLabel("Proposed: " + tabName);

				CompareItem left = new CompareItem(origFile.getFileName().toString(), originalContent);
				CompareItem right = new CompareItem(tabName, newContents);

				CompareEditorInput input = new CompareEditorInput(config) {
					@Override
					protected Object prepareInput(IProgressMonitor monitor) {
						return new DiffNode(Differencer.CHANGE, null, left, right);
					}

					@Override
					public Control createContents(Composite parent) {
						Composite container = new Composite(parent, SWT.NONE);
						GridLayout layout = new GridLayout(1, false);
						layout.marginHeight = 0;
						layout.marginWidth = 0;
						layout.verticalSpacing = 0;
						container.setLayout(layout);

						// Action bar at top with Accept/Reject buttons
						Composite actionBar = new Composite(container, SWT.NONE);
						actionBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
						RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
						rowLayout.marginTop = 6;
						rowLayout.marginBottom = 6;
						rowLayout.marginLeft = 10;
						rowLayout.spacing = 10;
						rowLayout.center = true;
						actionBar.setLayout(rowLayout);

						Label label = new Label(actionBar, SWT.NONE);
						label.setText("Copilot CLI: Review proposed changes");

						CompareEditorInput thisInput = this;

						Button acceptBtn = new Button(actionBar, SWT.PUSH);
						acceptBtn.setText("✓ Accept");
						acceptBtn.addListener(SWT.Selection, e -> {
							LOG.info("[MCP Tool] openDiff: user clicked Accept for " + tabName);
							handleDiffAccept(session, thisInput);
						});

						Button rejectBtn = new Button(actionBar, SWT.PUSH);
						rejectBtn.setText("✗ Reject");
						rejectBtn.addListener(SWT.Selection, e -> {
							LOG.info("[MCP Tool] openDiff: user clicked Reject for " + tabName);
							session.resolve("REJECTED", "rejected_via_button");
							activeDiffs.remove(tabName);
							closeCompareEditor(thisInput);
						});

						Label sep = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
						sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

						// Compare viewer below the action bar
						Control compareContents = super.createContents(container);
						compareContents.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

						return container;
					}
				};
				input.setTitle("Copilot Diff: " + tabName);

				session.setEditorInput(input);

				LOG.info("[MCP Tool] openDiff: opening compare editor");
				CompareUI.openCompareEditor(input);
				LOG.info("[MCP Tool] openDiff: compare editor opened");

				// Listen for editor close (user closes diff tab directly)
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window != null && window.getActivePage() != null) {
					LOG.info("[MCP Tool] openDiff: registering part listener");
					IPartListener2 closeListener = new IPartListener2() {
						@Override
						public void partClosed(IWorkbenchPartReference partRef) {
							if (partRef.getPart(false) instanceof IEditorPart editorPart) {
								if (editorPart.getEditorInput() == input) {
									LOG.info("[MCP Tool] openDiff: editor tab closed by user for " + tabName);
									window.getActivePage().removePartListener(this);
									session.resolve("REJECTED", "closed_via_tab");
									activeDiffs.remove(tabName);
								}
							}
						}
					};
					window.getActivePage().addPartListener(closeListener);
				} else {
					LOG.warn("[MCP Tool] openDiff: no active window/page for part listener");
				}

				LOG.info("[MCP Tool] openDiff: UI setup complete, waiting for user action");
			} catch (Throwable t) {
				LOG.error("[MCP Tool] openDiff: error on UI thread: " + t.getMessage(),
						t instanceof Exception e ? e : new RuntimeException(t));
				session.resolve(null, null);
			}
		});

		// Block until user accepts, rejects, or closes the diff (no timeout per spec)
		LOG.info("[MCP Tool] openDiff: blocking on diffResult.get()");
		Map<String, Object> result = diffResult.get();
		LOG.info("[MCP Tool] openDiff: got result: " + result);
		return result;
	}

	private void handleDiffAccept(DiffSession session, CompareEditorInput input) {
		try {
			// Write proposed changes to the original file
			Files.writeString(Path.of(session.originalPath), session.newContents, StandardCharsets.UTF_8);
			LOG.info("[MCP Tool] openDiff: wrote changes to " + session.originalPath);

			// Refresh Eclipse workspace to pick up external file change
			IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
					.findFilesForLocationURI(Path.of(session.originalPath).toUri());
			for (IFile file : files) {
				try {
					file.refreshLocal(IResource.DEPTH_ZERO, null);
				} catch (CoreException ce) {
					LOG.warn("[MCP Tool] openDiff: error refreshing " + file + ": " + ce.getMessage());
				}
			}

			session.resolve("SAVED", "accepted_via_button");
			activeDiffs.remove(session.tabName);
			closeCompareEditor(input);
		} catch (Exception ex) {
			LOG.error("[MCP Tool] openDiff: error accepting changes: " + ex.getMessage(), ex);
			session.resolve("REJECTED", "rejected_via_button");
			activeDiffs.remove(session.tabName);
			closeCompareEditor(input);
		}
	}

	// --- close_diff ---

	private Map<String, Object> closeDiff(Map<String, Object> arguments) {
		String tabName = (String) arguments.get("tab_name");
		if (tabName == null) {
			throw new IllegalArgumentException("Missing required argument: tab_name");
		}

		LOG.info("[MCP Tool] closeDiff: tabName=" + tabName);

		DiffSession session = activeDiffs.remove(tabName);
		if (session == null) {
			LOG.info("[MCP Tool] closeDiff: no active diff for tabName=" + tabName);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("already_closed", true);
			result.put("tab_name", tabName);
			result.put("message", "No active diff with tab name: " + tabName);
			result.put("error", null);
			return result;
		}

		session.resolve("REJECTED", "closed_via_tool");

		Display display = getDisplay();
		if (display != null) {
			display.asyncExec(() -> {
				CompareEditorInput input = session.getEditorInput();
				if (input != null) {
					closeCompareEditor(input);
				}
			});
		}

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
			LOG.warn("[MCP Tool] Error closing compare editor: " + e.getMessage());
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
		Map<String, Object> result = new LinkedHashMap<>();

		Display display = getDisplay();
		if (display == null) {
			return null;
		}

		display.syncExec(() -> {
			try {
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window == null || window.getActivePage() == null) {
					return;
				}
				IEditorPart editor = window.getActivePage().getActiveEditor();
				if (editor == null) {
					return;
				}

				File file = getFileFromEditor(editor);
				if (file != null) {
					String absPath = file.getAbsolutePath();
					result.put("filePath", absPath);
					result.put("fileUrl", pathToFileUri(absPath));
				}

				ITextEditor textEditor = resolveTextEditor(editor);
				if (textEditor != null) {
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
			} catch (Throwable t) {
				// best effort for notifications
			}
		});

		return result.isEmpty() ? null : result;
	}

	// --- Utilities ---

	private Display getDisplay() {
		try {
			Display display = PlatformUI.getWorkbench().getDisplay();
			if (display != null && !display.isDisposed()) {
				return display;
			}
		} catch (Throwable t) {
			// fallback
		}
		Display display = Display.getDefault();
		return (display != null && !display.isDisposed()) ? display : null;
	}

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
			var location = ((IFile) file).getLocation();
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

		CompareItem(String name, String content) {
			this.name = name;
			this.content = content;
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
			return 0;
		}
	}

	// --- Diff session tracking ---

	private static class DiffSession {
		final String tabName;
		final String originalPath;
		final String newContents;
		private final CompletableFuture<Map<String, Object>> future;
		private volatile CompareEditorInput editorInput;

		DiffSession(String tabName, String originalPath, String newContents,
				CompletableFuture<Map<String, Object>> future) {
			this.tabName = tabName;
			this.originalPath = originalPath;
			this.newContents = newContents;
			this.future = future;
		}

		void setEditorInput(CompareEditorInput input) {
			this.editorInput = input;
		}

		CompareEditorInput getEditorInput() {
			return editorInput;
		}

		void resolve(String resultValue, String trigger) {
			LOG.info("[MCP Tool] DiffSession.resolve: tabName=" + tabName + " result=" + resultValue
					+ " trigger=" + trigger);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", resultValue != null);
			result.put("result", resultValue != null ? resultValue : "REJECTED");
			result.put("trigger", trigger != null ? trigger : "closed_via_tab");
			result.put("tab_name", tabName);
			result.put("message", resultValue != null
					? "User " + (resultValue.equals("SAVED") ? "accepted" : "rejected") + " changes for " + tabName
					: null);
			result.put("error", null);
			boolean completed = future.complete(result);
			LOG.info("[MCP Tool] DiffSession.resolve: future completed=" + completed);
		}
	}
}
