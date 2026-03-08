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

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * The four workspace-folder reporting styles for the MCP lock file. The
 * preference value is stored as the enum's {@link #name()} (uppercase).
 */
public enum WorkspaceFolderStyle {

	/** Single entry: the Eclipse workspace root directory. */
	WORKSPACE,

	/** One entry per open project (classic behaviour). */
	PROJECTS,

	/**
	 * Like {@link #PROJECTS} but only root projects – those whose location is not
	 * contained inside another project's location (nested project layout).
	 */
	HIERARCHICAL,

	/**
	 * One unique Git repository root per open project. Projects with no Git root
	 * fall back to their own location.
	 */
	GIT_ROOTS;

	/** Preference key in the plugin preference store. */
	public static final String PREF_KEY = "workspaceFolderStyle";

	/** Default style when no preference has been set. */
	public static final WorkspaceFolderStyle DEFAULT = GIT_ROOTS;

	/**
	 * Read the current style from the given store. Returns {@link #DEFAULT} if the
	 * store is {@code null} or the stored value is unrecognised.
	 */
	public static WorkspaceFolderStyle fromPreference(IPreferenceStore store) {
		if (store == null) {
			return DEFAULT;
		}
		String value = store.getString(PREF_KEY);
		if (value == null || value.isBlank()) {
			return DEFAULT;
		}
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return DEFAULT;
		}
	}
}

