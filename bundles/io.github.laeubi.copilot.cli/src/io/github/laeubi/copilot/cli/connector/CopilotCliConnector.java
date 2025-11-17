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

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.terminal.connector.ISettingsPage;
import org.eclipse.terminal.connector.process.ProcessConnector;
import org.eclipse.terminal.connector.process.ProcessSettings;

/**
 * Copilot CLI terminal connector implementation.
 * 
 * This connector extends ProcessConnector to provide a terminal interface
 * for GitHub Copilot CLI.
 */
public class CopilotCliConnector extends ProcessConnector implements IAdaptable {

	private final ProcessSettings settings;

	/**
	 * Constructor.
	 */
	public CopilotCliConnector() {
		this(new ProcessSettings());
	}

	/**
	 * Constructor with settings.
	 */
	public CopilotCliConnector(ProcessSettings settings) {
		super(settings);
		this.settings = settings;
		// Pre-configure with copilot command
		settings.setImage("copilot");
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getAdapter(Class<T> adapter) {
		if (adapter == ISettingsPage.class) {
			return (T) new CopilotCliSettingsPage(settings);
		}
		return null;
	}
}
