package com.bankvaluechanges;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bankvaluechanges")
public interface BankValueChangesConfig extends Config
{
	enum TimeScale {
		LAST_LOGOUT,
		HALF_DAY,
		DAY,
		WEEK,
		MONTH
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Price Diff Overlay",
		description = "If you don't want to have the overlay on all the time, uncheck this. Turning off the plugin will disable collecting data points.",
		position = 1
	)
	default boolean showOverlay() {
		return true;
	}

	@ConfigItem(
		keyName = "timeScale",
		name = "Choose a time range",
		description = "The time range for which you wish to inspect your items' value change",
		position = 2
	)
	default TimeScale chooseTimeScale() {
		return TimeScale.HALF_DAY;
	}
}
