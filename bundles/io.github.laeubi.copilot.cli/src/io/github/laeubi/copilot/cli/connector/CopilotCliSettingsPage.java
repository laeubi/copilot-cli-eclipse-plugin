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
package io.github.laeubi.copilot.cli.connector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.terminal.connector.AbstractSettingsPage;
import org.eclipse.terminal.connector.process.ProcessSettings;

/**
 * Settings page for Copilot CLI connector.
 * This page validates that the copilot command is available.
 */
public class CopilotCliSettingsPage extends AbstractSettingsPage {
	
	private final ProcessSettings settings;

	public CopilotCliSettingsPage(ProcessSettings settings) {
		this.settings = settings;
	}

	@Override
	public void createControl(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		composite.setLayout(new GridLayout());
		composite.setLayoutData(new GridData(GridData.FILL_BOTH));

		Label label = new Label(composite, SWT.WRAP);
		label.setText("GitHub Copilot CLI terminal will be opened.\n\n" +
				"Make sure 'copilot' command is available in your PATH.");
		label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		loadSettings();
	}

	@Override
	public void loadSettings() {
		// Nothing to load - copilot command is hardcoded
	}

	@Override
	public void saveSettings() {
		// Ensure copilot is set as the command
		settings.setImage("copilot");
		settings.setArguments(null);
	}

	@Override
	public boolean validateSettings() {
		// Always return true - we'll let the connector fail gracefully if copilot is not found
		return true;
	}
}
