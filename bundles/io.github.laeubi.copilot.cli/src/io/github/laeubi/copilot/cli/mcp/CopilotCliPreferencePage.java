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

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.laeubi.copilot.cli.Activator;

/**
 * Preference page for GitHub Copilot CLI IDE integration settings.
 *
 * <p>
 * Currently lets the user choose how the <em>workspace folders</em> that are
 * reported to the Copilot CLI in the MCP lock file are determined.
 */
public class CopilotCliPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private Button rbWorkspace;
	private Button rbProjects;
	private Button rbHierarchical;
	private Button rbGitRoots;

	public CopilotCliPreferencePage() {
		super("GitHub Copilot CLI");
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
		setDescription("Configure the GitHub Copilot CLI IDE integration.");
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite root = new Composite(parent, SWT.NONE);
		root.setLayout(new GridLayout(1, false));

		Group group = new Group(root, SWT.NONE);
		group.setText("Workspace Folders");
		group.setLayout(new GridLayout(1, false));

	rbWorkspace = createRadio(group,
				"Use workspace folder (single Eclipse workspace root)",
				WorkspaceFolderStyle.WORKSPACE);
		rbProjects = createRadio(group,
				"Use projects (one entry per open project)",
				WorkspaceFolderStyle.PROJECTS);
		rbHierarchical = createRadio(group,
				"Use hierarchical projects (root projects only, hide nested children)",
				WorkspaceFolderStyle.HIERARCHICAL);
		rbGitRoots = createRadio(group,
				"Use Git roots (one unique Git repository root per project)",
				WorkspaceFolderStyle.GIT_ROOTS);

		// Initialise selection from current preference value
		setSelection(WorkspaceFolderStyle.fromPreference(getPreferenceStore()));

		return root;
	}

	private Button createRadio(Composite parent, String label, WorkspaceFolderStyle value) {
		Button btn = new Button(parent, SWT.RADIO);
		btn.setText(label);
		btn.setData(value);
		return btn;
	}

	private void setSelection(WorkspaceFolderStyle value) {
		if (value == null) {
			value = WorkspaceFolderStyle.DEFAULT;
		}
		rbWorkspace.setSelection(value == WorkspaceFolderStyle.WORKSPACE);
		rbProjects.setSelection(value == WorkspaceFolderStyle.PROJECTS);
		rbHierarchical.setSelection(value == WorkspaceFolderStyle.HIERARCHICAL);
		rbGitRoots.setSelection(value == WorkspaceFolderStyle.GIT_ROOTS);
	}

	private WorkspaceFolderStyle getSelectedValue() {
		if (rbWorkspace.getSelection()) return WorkspaceFolderStyle.WORKSPACE;
		if (rbHierarchical.getSelection()) return WorkspaceFolderStyle.HIERARCHICAL;
		if (rbGitRoots.getSelection()) return WorkspaceFolderStyle.GIT_ROOTS;
		return WorkspaceFolderStyle.PROJECTS;
	}

	@Override
	protected void performDefaults() {
		setSelection(WorkspaceFolderStyle.DEFAULT);
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		getPreferenceStore().setValue(WorkspaceFolderStyle.PREF_KEY, getSelectedValue().name());
		return true;
	}
}
