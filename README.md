# Bank Value Changes plugin

This plugin allows you to track the changing value of specific items you have in your bank through
simply opening up your bank interface! This plugin only uses local tracking, so historical
data from the time before the installation of the plugin will not be available for viewing.

![](https://i.imgur.com/iA88DEs.png)

## Using the Bank Value Changes plugin

The configuration is scarce so it's easy to get going. Pick a time range over which you want to see
the change in price your items have experienced. The plugin will show the difference of current item
prices to the oldest datapoint within your selected time frame. The percentage value shown is a difference
in UNIT price of the item, not the evolution of how the entire stack's price has changed.

You can also choose `LAST_LOGOUT` to compare your current bank prices with the most recent saved
bank snapshot from the previous RuneLite session.

The plugin works by creating a timestamp of your bank every time you open up the main tab, up to a maximum
of once per hour. This data is saved in .runelite\bank-value-changes\priceHistoryData.json

![](https://i.imgur.com/epR8NkH.png)
