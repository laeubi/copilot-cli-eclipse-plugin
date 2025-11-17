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

import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.terminal.view.core.ITerminalsConnectorConstants;
import org.eclipse.terminal.view.ui.launcher.AbstractExtendedConfigurationPanel;
import org.eclipse.terminal.view.ui.launcher.IConfigurationPanelContainer;
import org.eclipse.ui.WorkbenchEncoding;

/**
 * Configuration panel for Copilot CLI terminal.
 */
public class CopilotCliConfigurationPanel extends AbstractExtendedConfigurationPanel {

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

		// Info label
		Label label = new Label(panel, SWT.WRAP);
		label.setText("GitHub Copilot CLI terminal will be opened.\n\n" +
				"Make sure 'copilot' command is available in your PATH.");
		GridData layoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
		layoutData.widthHint = 300;
		layoutData.heightHint = 80;
		label.setLayoutData(layoutData);

		setControl(panel);
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
	}

	@Override
	public boolean isValid() {
		// Always valid - copilot command will be checked at runtime
		return true;
	}
}
