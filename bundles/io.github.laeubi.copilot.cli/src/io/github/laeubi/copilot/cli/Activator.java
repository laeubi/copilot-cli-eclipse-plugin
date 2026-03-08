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
package io.github.laeubi.copilot.cli;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import io.github.laeubi.copilot.cli.mcp.WorkspaceFolderStyle;

/**
 * Bundle activator – provides the shared plugin instance and initialises
 * preference defaults.
 */
public class Activator extends AbstractUIPlugin {

	/** The plug-in identifier (must match Bundle-SymbolicName in MANIFEST.MF). */
	public static final String PLUGIN_ID = "io.github.laeubi.copilot.cli";

	private static Activator instance;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		instance = null;
		super.stop(context);
	}

	/** Returns the shared plugin instance. */
	public static Activator getDefault() {
		return instance;
	}

	@Override
	protected void initializeDefaultPreferences(org.eclipse.jface.preference.IPreferenceStore store) {
		store.setDefault(WorkspaceFolderStyle.PREF_KEY, WorkspaceFolderStyle.GIT_ROOTS.name());
	}
}
