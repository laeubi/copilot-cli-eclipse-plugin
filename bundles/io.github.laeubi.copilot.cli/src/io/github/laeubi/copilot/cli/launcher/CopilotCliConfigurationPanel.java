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
package io.github.laeubi.copilot.cli.launcher;

import java.io.File;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.terminal.view.core.ITerminalsConnectorConstants;
import org.eclipse.terminal.view.ui.launcher.AbstractExtendedConfigurationPanel;
import org.eclipse.terminal.view.ui.launcher.IConfigurationPanelContainer;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

/**
 * Configuration panel for Copilot CLI terminal.
 */
public class CopilotCliConfigurationPanel extends AbstractExtendedConfigurationPanel {

	private Text workingDirText;
	private IResource selectedResource;

	/**
	 * Constructor.
	 */
	public CopilotCliConfigurationPanel(IConfigurationPanelContainer container) {
		super(container);
	}

	@Override
	public void setupPanel(Composite parent) {
		Composite panel = new Composite(parent, SWT.NONE);
		panel.setLayout(new GridLayout());
		panel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		// Create the encoding selection combo
		createEncodingUI(panel, false);

		// Set default UTF-8 encoding for Copilot CLI
		setEncoding("UTF-8");

		// Create working directory section
		Composite workingDirPanel = new Composite(panel, SWT.NONE);
		GridLayout workingDirLayout = new GridLayout(3, false);
		workingDirLayout.marginWidth = 0;
		workingDirLayout.marginHeight = 0;
		workingDirPanel.setLayout(workingDirLayout);
		workingDirPanel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		Label workingDirLabel = new Label(workingDirPanel, SWT.NONE);
		workingDirLabel.setText("Working directory:");
		workingDirLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

		workingDirText = new Text(workingDirPanel, SWT.SINGLE | SWT.BORDER);
		workingDirText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		workingDirText.addModifyListener(new ModifyListener() {
			@Override
			public void modifyText(ModifyEvent e) {
				if (getContainer() != null) {
					getContainer().validate();
				}
			}
		});

		Button browseButton = new Button(workingDirPanel, SWT.PUSH);
		browseButton.setText("Browse...");
		browseButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog dialog = new DirectoryDialog(parent.getShell(), SWT.OPEN);
				dialog.setText("Select Working Directory");
				String currentDir = workingDirText.getText();
				if (currentDir != null && !currentDir.isEmpty()) {
					dialog.setFilterPath(currentDir);
				}
				String selectedDir = dialog.open();
				if (selectedDir != null) {
					workingDirText.setText(selectedDir);
				}
			}
		});

		// Info label
		Label label = new Label(panel, SWT.WRAP);
		label.setText("GitHub Copilot CLI terminal will be opened.\n\n" +
				"Make sure 'copilot' command is available in your PATH.");
		GridData layoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
		layoutData.widthHint = 350;
		layoutData.heightHint = 80;
		label.setLayoutData(layoutData);

		// Try to get initial working directory from current selection
		Bundle bundle = Platform.getBundle("org.eclipse.core.resources");
		if (bundle != null && bundle.getState() != Bundle.UNINSTALLED && bundle.getState() != Bundle.STOPPING) {
			selectedResource = getSelectionResource();
			if (selectedResource != null) {
				String dir = selectedResource.getProject().getLocation().toString();
				workingDirText.setText(dir);
			} else {
				// Default to workspace root
				String workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toString();
				workingDirText.setText(workspaceRoot);
			}
		} else {
			// Fallback to user home
			workingDirText.setText(System.getProperty("user.home"));
		}

		setControl(panel);
	}

	/**
	 * Returns the IResource from the current selection
	 */
	private IResource getSelectionResource() {
		ISelectionService selectionService = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getSelectionService();
		ISelection selection = selectionService != null ? selectionService.getSelection() : null;

		if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
			Object element = ((IStructuredSelection) selection).getFirstElement();
			if (element instanceof IResource) {
				return (IResource) element;
			}
			if (element instanceof IAdaptable) {
				return ((IAdaptable) element).getAdapter(IResource.class);
			}
		}
		return null;
	}

	@Override
	protected void saveSettingsForHost(boolean add) {
		// Nothing to persist
	}

	@Override
	protected void fillSettingsForHost(String host) {
		// Nothing to fill
	}

	@Override
	protected String getHostFromSettings() {
		return null;
	}

	@Override
	public void extractData(Map<String, Object> data) {
		if (data == null) {
			return;
		}
		
		// Set terminal connector ID
		data.put(ITerminalsConnectorConstants.PROP_TERMINAL_CONNECTOR_ID, 
				"io.github.laeubi.copilot.cli.connector");
		
		// Set the encoding
		data.put(ITerminalsConnectorConstants.PROP_ENCODING, getEncoding());
		
		// Set the working directory
		String workingDir = workingDirText.getText().trim();
		if (!workingDir.isEmpty()) {
			data.put(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR, workingDir);
		}
	}

	@Override
	public void setupData(Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			return;
		}
		
		// Restore encoding if available
		String encoding = (String) data.get(ITerminalsConnectorConstants.PROP_ENCODING);
		if (encoding != null) {
			setEncoding(encoding);
		}
		
		// Restore working directory if available
		String workingDir = (String) data.get(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR);
		if (workingDir != null && workingDirText != null) {
			workingDirText.setText(workingDir);
		}
	}

	@Override
	public boolean isValid() {
		// Validate working directory if specified
		String workingDir = workingDirText.getText().trim();
		if (!workingDir.isEmpty()) {
			File dir = new File(workingDir);
			if (!dir.exists() || !dir.isDirectory()) {
				setMessage("Working directory does not exist or is not a directory", INFORMATION);
				return false;
			}
		}
		
		setMessage(null, NONE);
		return true;
	}
}
