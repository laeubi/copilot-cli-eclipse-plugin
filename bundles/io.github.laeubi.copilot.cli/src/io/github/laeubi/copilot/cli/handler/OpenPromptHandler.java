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
package io.github.laeubi.copilot.cli.handler;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.terminal.view.core.ITerminalsConnectorConstants;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.laeubi.copilot.cli.launcher.CopilotCliLauncherDelegate;

/**
 * Handler for the "Open Prompt" command that opens a Copilot CLI terminal
 * for the current editor context. It finds the Git repository root and
 * either focuses an existing terminal or creates a new one.
 */
public class OpenPromptHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		try {
			// Get the working directory from active editor or selection
			String workingDir = getWorkingDirectory(event);
			if (workingDir == null) {
				// Fallback to user home
				workingDir = System.getProperty("user.home");
			}

			// Open the Copilot terminal with the determined working directory
			openCopilotTerminal(workingDir);

		} catch (Exception e) {
			ILog.get().error("Error opening Copilot terminal", e);
		}
		return null;
	}

	/**
	 * Determine the working directory from the current context
	 */
	private String getWorkingDirectory(ExecutionEvent event) throws ExecutionException {
		// Try to get from active editor first
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
		if (page != null) {
			IEditorPart editor = page.getActiveEditor();
			if (editor != null) {
				File file = getFileFromEditor(editor);
				if (file != null) {
					File gitRoot = findGitRoot(file);
					return gitRoot != null ? gitRoot.getAbsolutePath() : file.getParent();
				}
			}
		}

		// Try to get from selection
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
			Object element = ((IStructuredSelection) selection).iterator().next();
			
			// Try to adapt to IResource
			IResource resource = null;
			if (element instanceof IResource) {
				resource = (IResource) element;
			} else if (element instanceof IAdaptable) {
				resource = ((IAdaptable) element).getAdapter(IResource.class);
			}
			if (resource == null) {
				resource = Platform.getAdapterManager().getAdapter(element, IResource.class);
			}

			if (resource != null) {
				IPath location = resource.getLocation();
				if (location != null) {
					File file = location.toFile();
					if (file.exists()) {
						File gitRoot = findGitRoot(file);
						return gitRoot != null ? gitRoot.getAbsolutePath() 
								: (file.isDirectory() ? file.getAbsolutePath() : file.getParent());
					}
				}
			}
		}

		return null;
	}

	/**
	 * Extract the file from the active editor
	 */
	private File getFileFromEditor(IEditorPart editor) {
		IEditorInput input = editor.getEditorInput();
		
		// Try IPathEditorInput first (most common)
		if (input instanceof IPathEditorInput) {
			IPath path = ((IPathEditorInput) input).getPath();
			if (path != null) {
				return path.toFile();
			}
		}
		
		// Try to adapt to IFile
		IFile file = input.getAdapter(IFile.class);
		if (file != null) {
			IPath location = file.getLocation();
			if (location != null) {
				return location.toFile();
			}
		}
		
		return null;
	}

	/**
	 * Find the Git repository root by searching for .git directory
	 */
	private File findGitRoot(File file) {
		File current = file.isDirectory() ? file : file.getParentFile();
		
		while (current != null) {
			File gitDir = new File(current, ".git");
			if (gitDir.exists() && gitDir.isDirectory()) {
				return current;
			}
			current = current.getParentFile();
		}
		
		return null;
	}

	/**
	 * Open a new Copilot terminal for the given working directory
	 */
	private void openCopilotTerminal(String workingDir) {
		if (workingDir == null) {
			workingDir = System.getProperty("user.home");
		}

		// Create properties for the terminal
		Map<String, Object> properties = new HashMap<>();
		properties.put(ITerminalsConnectorConstants.PROP_TERMINAL_CONNECTOR_ID, 
				"io.github.laeubi.copilot.cli.connector");
		properties.put(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR, workingDir);
		properties.put(ITerminalsConnectorConstants.PROP_DELEGATE_ID, 
				"io.github.laeubi.copilot.cli.launcher");
		
		// Set title based on directory name
		String dirName = new Path(workingDir).lastSegment();
		if (dirName == null || dirName.isEmpty()) {
			dirName = workingDir;
		}
		properties.put(ITerminalsConnectorConstants.PROP_TITLE, "Copilot - " + dirName);
		
		// Use PROP_DATA to store the working directory as a key for reuse
		// This allows the terminal service to find and reuse existing terminals
		properties.put(ITerminalsConnectorConstants.PROP_DATA, workingDir);
		
		// Don't force new - reuse existing terminal if one exists for this directory
		properties.put(ITerminalsConnectorConstants.PROP_FORCE_NEW, Boolean.FALSE);

		// Execute through the delegate
		CopilotCliLauncherDelegate delegate = new CopilotCliLauncherDelegate();
		delegate.execute(properties).whenComplete((result, error) -> {
			if (error != null) {
				ILog.get().error("Error opening Copilot terminal", error);
			}
		});
	}
}
