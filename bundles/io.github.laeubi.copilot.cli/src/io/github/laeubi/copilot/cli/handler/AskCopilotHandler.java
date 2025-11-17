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
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.terminal.view.core.ITerminalsConnectorConstants;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.laeubi.copilot.cli.launcher.CopilotCliLauncherDelegate;

/**
 * Handler for the "Ask Copilot" context menu command that opens a Copilot CLI terminal
 * for the selected resource's Git repository root.
 */
public class AskCopilotHandler extends AbstractHandler {

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		try {
			// Get the working directory from selection
			String workingDir = getWorkingDirectory(event);
			if (workingDir == null) {
				// Fallback to user home
				workingDir = System.getProperty("user.home");
			}

			// Show prompt dialog
			InputDialog dialog = new InputDialog(
				Display.getDefault().getActiveShell(),
				"Ask Copilot",
				"Enter your prompt for GitHub Copilot:",
				"",
				null
			);

			if (dialog.open() == Window.OK) {
				String prompt = dialog.getValue();
				// Open the Copilot terminal with the determined working directory
				openCopilotTerminal(workingDir, prompt);
			}

		} catch (Exception e) {
			ILog.get().error("Error opening Copilot terminal", e);
		}
		return null;
	}

	/**
	 * Determine the working directory from the current selection
	 */
	private String getWorkingDirectory(ExecutionEvent event) throws ExecutionException {
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
	private void openCopilotTerminal(String workingDir, String prompt) {
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
			} else if (prompt != null && !prompt.trim().isEmpty()) {
				// Copy prompt to clipboard for easy pasting
				Display.getDefault().asyncExec(() -> {
					try {
						org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(Display.getDefault());
						org.eclipse.swt.dnd.TextTransfer textTransfer = org.eclipse.swt.dnd.TextTransfer.getInstance();
						clipboard.setContents(new Object[] { prompt }, new org.eclipse.swt.dnd.Transfer[] { textTransfer });
						clipboard.dispose();
					} catch (Exception e) {
						ILog.get().error("Error copying prompt to clipboard", e);
					}
				});
			}
		});
	}
}
