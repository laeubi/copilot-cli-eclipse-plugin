package io.github.laeubi.copilot.cli;

import org.eclipse.core.runtime.ILog;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import io.github.laeubi.copilot.cli.mcp.McpLifecycleManager;

/**
 * The activator class controls the plug-in life cycle
 */
public class Activator extends AbstractUIPlugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "io.github.laeubi.copilot.cli";

	// The shared instance
	private static Activator plugin;

	/**
	 * The constructor
	 */
	public Activator() {
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		// Start the MCP server for Copilot CLI /ide integration
		try {
			McpLifecycleManager.getInstance().start();
		} catch (Exception e) {
			ILog.get().error("Failed to start MCP server for Copilot CLI", e);
		}
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		try {
			McpLifecycleManager.getInstance().stop();
		} catch (Exception e) {
			// best effort on shutdown
		}
		plugin = null;
		super.stop(context);
	}

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}

}
