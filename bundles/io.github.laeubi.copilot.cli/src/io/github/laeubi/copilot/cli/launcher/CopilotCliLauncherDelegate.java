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
import java.util.concurrent.CompletableFuture;

import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.terminal.connector.ISettingsStore;
import org.eclipse.terminal.connector.ITerminalConnector;
import org.eclipse.terminal.connector.InMemorySettingsStore;
import org.eclipse.terminal.connector.TerminalConnectorExtension;
import org.eclipse.terminal.connector.process.ProcessSettings;
import org.eclipse.terminal.view.core.ILineSeparatorConstants;
import org.eclipse.terminal.view.core.ITerminalServiceOutputStreamMonitorListener;
import org.eclipse.terminal.view.core.ITerminalsConnectorConstants;
import org.eclipse.terminal.view.ui.launcher.AbstractLauncherDelegate;
import org.eclipse.terminal.view.ui.launcher.IConfigurationPanel;
import org.eclipse.terminal.view.ui.launcher.IConfigurationPanelContainer;
import org.eclipse.ui.WorkbenchEncoding;

/**
 * Copilot CLI launcher delegate implementation.
 */
public class CopilotCliLauncherDelegate extends AbstractLauncherDelegate {

	@Override
	public boolean needsUserConfiguration() {
		return true;
	}

	@Override
	public IConfigurationPanel getPanel(IConfigurationPanelContainer container) {
		return new CopilotCliConfigurationPanel(container);
	}

	@Override
	public CompletableFuture<?> execute(Map<String, Object> properties) {
		Assert.isNotNull(properties);

		// Set the terminal tab title
		String terminalTitle = getDefaultTerminalTitle(properties);
		if (terminalTitle == null) {
			terminalTitle = "Copilot CLI";
		}
		properties.put(ITerminalsConnectorConstants.PROP_TITLE, terminalTitle);

		// Set encoding - default to UTF-8 for Copilot CLI
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_ENCODING)) {
			String encoding = "UTF-8";
			if (encoding != null && !"".equals(encoding)) {
				properties.put(ITerminalsConnectorConstants.PROP_ENCODING, encoding);
			}
		}

		// Force a new terminal tab each time
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_FORCE_NEW)) {
			properties.put(ITerminalsConnectorConstants.PROP_FORCE_NEW, Boolean.TRUE);
		}

		try {
			return getTerminalService().openConsole(properties);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public ITerminalConnector createTerminalConnector(Map<String, Object> properties) throws CoreException {
		Assert.isNotNull(properties);

		// Check for the terminal connector id
		String connectorId = (String) properties.get(ITerminalsConnectorConstants.PROP_TERMINAL_CONNECTOR_ID);
		if (connectorId == null) {
			connectorId = "io.github.laeubi.copilot.cli.connector";
		}

		// Use "copilot" as the command to execute
		String image = "copilot";

		// Determine if a PTY will be used
		boolean isUsingPTY = (properties.get(ITerminalsConnectorConstants.PROP_PROCESS_OBJ) == null
				&& PTY.isSupported(PTY.Mode.TERMINAL))
				|| properties.get(ITerminalsConnectorConstants.PROP_PTY_OBJ) instanceof PTY;

		// Local echo configuration
		boolean localEcho = false;
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_LOCAL_ECHO)
				|| !(properties.get(ITerminalsConnectorConstants.PROP_LOCAL_ECHO) instanceof Boolean)) {
			// On Windows, turn on local echo by default if no PTY is used
			if (Platform.OS_WIN32.equals(Platform.getOS())) {
				localEcho = !isUsingPTY;
			}
		} else {
			localEcho = ((Boolean) properties.get(ITerminalsConnectorConstants.PROP_LOCAL_ECHO)).booleanValue();
		}

		// Line separator configuration
		String lineSeparator = null;
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_LINE_SEPARATOR)
				|| !(properties.get(ITerminalsConnectorConstants.PROP_LINE_SEPARATOR) instanceof String)) {
			// No line separator will be set if a PTY is used
			if (!isUsingPTY) {
				lineSeparator = Platform.OS_WIN32.equals(Platform.getOS()) ? ILineSeparatorConstants.LINE_SEPARATOR_CRLF
						: ILineSeparatorConstants.LINE_SEPARATOR_LF;
			}
		} else {
			lineSeparator = (String) properties.get(ITerminalsConnectorConstants.PROP_LINE_SEPARATOR);
		}

		Process process = (Process) properties.get(ITerminalsConnectorConstants.PROP_PROCESS_OBJ);
		PTY pty = (PTY) properties.get(ITerminalsConnectorConstants.PROP_PTY_OBJ);
		ITerminalServiceOutputStreamMonitorListener[] stdoutListeners = (ITerminalServiceOutputStreamMonitorListener[]) properties
				.get(ITerminalsConnectorConstants.PROP_STDOUT_LISTENERS);
		ITerminalServiceOutputStreamMonitorListener[] stderrListeners = (ITerminalServiceOutputStreamMonitorListener[]) properties
				.get(ITerminalsConnectorConstants.PROP_STDERR_LISTENERS);
		String workingDir = (String) properties.get(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR);

		String[] envp = null;
		if (properties.containsKey(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT)
				&& properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT) != null
				&& properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT) instanceof String[]) {
			envp = (String[]) properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT);
		}

		Assert.isTrue(image != null || process != null);

		// Construct the terminal settings store
		ISettingsStore store = new InMemorySettingsStore();

		// Construct the process settings
		ProcessSettings processSettings = new ProcessSettings();
		processSettings.setImage(image);
		processSettings.setArguments(null); // No arguments for copilot CLI
		processSettings.setProcess(process);
		processSettings.setPTY(pty);
		processSettings.setLocalEcho(localEcho);
		processSettings.setLineSeparator(lineSeparator);
		processSettings.setStdOutListeners(stdoutListeners);
		processSettings.setStdErrListeners(stderrListeners);
		processSettings.setWorkingDir(workingDir);
		processSettings.setEnvironment(envp);

		if (properties.containsKey(ITerminalsConnectorConstants.PROP_PROCESS_MERGE_ENVIRONMENT)) {
			Object value = properties.get(ITerminalsConnectorConstants.PROP_PROCESS_MERGE_ENVIRONMENT);
			processSettings.setMergeWithNativeEnvironment(value instanceof Boolean b ? b.booleanValue() : false);
		}

		// And save the settings to the store
		processSettings.save(store);

		// Construct the terminal connector instance
		ITerminalConnector connector = TerminalConnectorExtension.makeTerminalConnector(connectorId);
		// Apply default settings
		connector.setDefaultSettings();
		// And load the real settings
		connector.load(store);
		return connector;
	}
}
