package com.bankvaluechanges;

import javax.inject.Inject;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import java.io.File;
import java.io.IOException;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

@PluginDescriptor(
    name = "Bank Value Changes",
    description = "Notice your bank value dropping or increasing without a perceptible cause? Keep track of the changes in value that your items experience from time to time!",
    tags = {"bank", "price", "value", "tracker"}
)
@Slf4j
public class BankValueChangesPlugin extends Plugin {
    public static final Long HALF_HOUR_IN_MILLIS = 30 * 60 * 1000L;
    public static final Long HOUR_IN_MILLIS = 2 * HALF_HOUR_IN_MILLIS;
    public static final Long HALF_DAY_IN_MILLIS = 12 * HOUR_IN_MILLIS;
    public static final Long DAY_IN_MILLIS = 24 * HOUR_IN_MILLIS;
    public static final Long WEEK_IN_MILLIS = 7 * DAY_IN_MILLIS;
    public static final Long MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS;
    public static final String DATA_FOLDER = "bank-value-changes";
    public static final String DATA_FILE_NAME = "priceHistoryData.json";
    public static final File PRICE_HISTORY_DATA_DIR;
    public static Gson GSON;
    private static Long timeBand;
    private final ArrayList<PriceSnapshot> priceSnapshots = new ArrayList<>();
    private long currentSessionStartedAt;
    public Map<Integer, Double> valueChanges = new HashMap<>();

    static
    {
        PRICE_HISTORY_DATA_DIR = new File(RuneLite.RUNELITE_DIR, DATA_FOLDER);
        PRICE_HISTORY_DATA_DIR.mkdirs();
    }

    @Inject
    private ItemManager itemManager;

    @Inject
    private Client client;

    @Inject
    private BankValueChangesConfig config;

    @Inject
    private BankValueChangesOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private Gson gson;

    @Override
    protected void startUp() throws Exception {
        currentSessionStartedAt = System.currentTimeMillis();
        GSON = gson.newBuilder().create();
        overlayManager.add(overlay);
        loadPriceData();
        setTimeBand(config.chooseTimeScale());
        populateDifferenceMap();
    }

    @Override
    protected void shutDown() throws Exception {
        savePriceData();
        priceSnapshots.clear();
        overlayManager.remove(overlay);
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING) {
            return;
        }

        // Don't update upon opening tabs other than the main one
        if (client.getVarbitValue(VarbitID.BANK_CURRENTTAB) != 0) {
            return;
        }

        if (!priceSnapshots.isEmpty()) {
            // Compare time with latest entry, if within half an hour of latest entry, don't update
            PriceSnapshot latest = priceSnapshots.get(priceSnapshots.size() - 1);
            if (latest.getTimestamp() >= currentSessionStartedAt
                && System.currentTimeMillis() <= latest.getTimestamp() + HALF_HOUR_IN_MILLIS) {
                return;
            }
        }

        final Widget widget = client.getWidget(InterfaceID.Bankmain.ITEMS);
        final ItemContainer itemContainer = client.getItemContainer(InventoryID.BANK);
        final Widget[] children = widget.getChildren();

        if (itemContainer == null || children == null) {
            return;
        }

        PriceSnapshot snapshot = new PriceSnapshot(System.currentTimeMillis());
        for (int i = 0; i < itemContainer.size(); ++i) {
            Widget child = children[i];
            if (child != null && !child.isSelfHidden() && child.getItemId() > -1)
            {
                int id = child.getItemId();
                int gePrice = itemManager.getItemPrice(id);
                if (gePrice > 0) { // Item is tradable
                    snapshot.addPriceData(new PriceData(id, gePrice));
                }
            }
        }

        priceSnapshots.add(snapshot);
        priceSnapshots.sort(PriceSnapshot::compareTo);
        populateDifferenceMap();
    }

    @Subscribe
    public void onClientShutdown(ClientShutdown event) {
        event.waitFor(executor.submit(this::savePriceData));
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("bankvaluechanges")) {
            return;
        }

        if (event.getNewValue() == null) {
            return;
        }

        if (event.getKey().equals("timeScale")) {
            setTimeBand(config.chooseTimeScale());
            populateDifferenceMap();
        }
    }

    private void setTimeBand(BankValueChangesConfig.TimeScale scale) {
        switch(scale) {
            case LAST_LOGOUT:
                timeBand = null;
                break;
            case HALF_DAY:
                timeBand = HALF_DAY_IN_MILLIS;
                break;
            case DAY:
                timeBand = DAY_IN_MILLIS;
                break;
            case WEEK:
                 timeBand = WEEK_IN_MILLIS;
                 break;
            case MONTH:
                timeBand = MONTH_IN_MILLIS;
                break;
        }
    }

    private void savePriceData() {
        File dataFile = new File(PRICE_HISTORY_DATA_DIR, DATA_FILE_NAME);

        // Remove entries which are outside the longest data viewing window bevo
        priceSnapshots.removeIf(
            snapshot -> snapshot.getTimestamp() + MONTH_IN_MILLIS < System.currentTimeMillis());

        try {
            String jsonData = GSON.toJson(priceSnapshots);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile, StandardCharsets.UTF_8))) {
                writer.write(jsonData);
            }
        } catch (IOException e) {
            log.error("Error while saving price history data: ", e);
        } catch (Exception e) {
            log.error("Error: ", e);
        }
    }

    private void loadPriceData() {
        File dataFile = new File(PRICE_HISTORY_DATA_DIR, DATA_FILE_NAME);

        if (!dataFile.exists()) {
            return;
        }

        try {
            String jsonData = Files.readString(dataFile.toPath(), StandardCharsets.UTF_8);
            List<PriceSnapshot> snapshots = Arrays.asList(GSON.fromJson(jsonData, PriceSnapshot[].class));
            updateSnapshots(snapshots);
        } catch (IOException e) {
            log.error("Error while loading price history data: ", e);
        } catch (Exception e) {
            log.error("Error: ", e);
        }
    }

    private void updateSnapshots(List<PriceSnapshot> snapshots) {
        snapshots.removeIf(Objects::isNull);
        priceSnapshots.addAll(snapshots);
        priceSnapshots.sort(PriceSnapshot::compareTo);
    }

    private void populateDifferenceMap() {
        valueChanges.clear();

        if (priceSnapshots.isEmpty()) {
            return;
        }

        if (config.chooseTimeScale() == BankValueChangesConfig.TimeScale.LAST_LOGOUT) {
            PriceSnapshot lastLogoutSnapshot = getLastLogoutSnapshot();
            PriceSnapshot latest = priceSnapshots.get(priceSnapshots.size() - 1);
            if (lastLogoutSnapshot != null && latest.getTimestamp() >= currentSessionStartedAt) {
                calculatePriceDiffPercentage(lastLogoutSnapshot);
            }
            return;
        }

        int size = priceSnapshots.size();
        // Find the oldest snapshot from within the chosen time band
        for (int i = size - 1; i >= 0; i--) {
            if (priceSnapshots.get(i).getTimestamp() + timeBand < System.currentTimeMillis()) {
                // No snapshots within the timeframe
                if (i == size - 1) {
                    return;
                } else {
                    calculatePriceDiffPercentage(priceSnapshots.get(i + 1));
                }
                return;
            }
        }
        // Didn't find any snapshots outside the given time band
        // so just give the oldest one within it
        calculatePriceDiffPercentage(priceSnapshots.get(0));
    }

    private PriceSnapshot getLastLogoutSnapshot() {
        for (int i = priceSnapshots.size() - 1; i >= 0; i--) {
            PriceSnapshot snapshot = priceSnapshots.get(i);
            if (snapshot.getTimestamp() < currentSessionStartedAt) {
                return snapshot;
            }
        }
        return null;
    }

    private void calculatePriceDiffPercentage(PriceSnapshot oldest) {
        PriceSnapshot latest = priceSnapshots.get(priceSnapshots.size() - 1);

        for (PriceData latestData : latest.getPriceData()) {
            int latestId = latestData.getId();
            int latestPrice = latestData.getPrice();

            for (PriceData item : oldest.getPriceData()) {
                int oldestId = item.getId();
                int olderPrice = item.getPrice();

                if (latestId == oldestId) {
                    valueChanges.put(latestId, percentageChange(olderPrice, latestPrice));
                    break;
                }
            }
        }
    }

    private double percentageChange(int oldPrice, int newPrice) {
        return (double) (newPrice - oldPrice) * 100 / (double) oldPrice;
    }

    @Provides
    BankValueChangesConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BankValueChangesConfig.class);
    }
}
