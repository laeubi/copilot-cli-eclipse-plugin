/*******************************************************************************
 * Copyright (c) 2026 Siemens AG.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.laeubi.copilot.cli.mcp;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;

final class DisposedDisplays {

	private DisposedDisplays() {
	}

	/**
	 * Runs a display action, ignoring only a display disposed concurrently after
	 * it was checked.
	 */
	static void ignoreDisposed(Runnable action) {
		try {
			action.run();
		} catch (SWTException e) {
			if (e.code != SWT.ERROR_DEVICE_DISPOSED) {
				throw e;
			}
		}
	}
}
