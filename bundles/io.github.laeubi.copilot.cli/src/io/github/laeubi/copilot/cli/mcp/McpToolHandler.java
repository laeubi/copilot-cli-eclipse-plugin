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
package io.github.laeubi.copilot.cli.mcp;

import java.util.Map;

/**
 * Interface for handling MCP tool calls. Implementations provide the actual IDE
 * integration logic.
 */
public interface McpToolHandler {

	/**
	 * Handle a tool call.
	 *
	 * @param toolName  the tool name (e.g. "get_selection", "open_diff")
	 * @param arguments the tool arguments as a map
	 * @return the result object (Map or List) to be serialized as JSON
	 * @throws Exception if the tool call fails
	 */
	Object callTool(String toolName, Map<String, Object> arguments) throws Exception;
}
