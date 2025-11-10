package com.wrs.multibridge;

import com.wrs.multibridge.listeners.DiscordSRVHookListener;
import com.wrs.multibridge.listeners.MinecraftChatListener;
import com.wrs.multibridge.managers.BotManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiBridge extends JavaPlugin {

    private ExecutorService executor;
    private BotManager botManager;
    private DiscordSRVHookListener hookListener;
    private MinecraftChatListener minecraftChatListener;
    private Set<String> broadcastTags = Collections.emptySet();
    private boolean lowMemoryMode;
    private int perBotRateLimit;
    private int mergeWindowMs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        ensureExecutor(readThreadPoolSize());

        botManager = new BotManager(this);
        minecraftChatListener = new MinecraftChatListener(this, botManager);
        hookListener = new DiscordSRVHookListener(this, minecraftChatListener);

        minecraftChatListener.register();
        reloadBridge();

        hookListener.register();

        getLogger().info("DiscordSRV-MultiBridge enabled with " + botManager.getRegisteredBots().size() + " bot profiles.");
    }

    @Override
    public void onDisable() {
        if (hookListener != null) {
            hookListener.unregister();
        }
        if (minecraftChatListener != null) {
            minecraftChatListener.unregister();
        }
        if (botManager != null) {
            botManager.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void reloadBridge() {
        reloadConfig();
        loadSettings();
        botManager.configure(lowMemoryMode, perBotRateLimit, mergeWindowMs);
        botManager.loadBots(getConfig().getMapList("bots"));
    }

    private void loadSettings() {
        ConfigurationSection settings = getConfig().getConfigurationSection("settings");
        List<String> configuredTags = settings != null ? settings.getStringList("broadcastTags") : Collections.emptyList();
        if (configuredTags == null || configuredTags.isEmpty()) {
            broadcastTags = Collections.emptySet();
        } else {
            broadcastTags = new HashSet<>(configuredTags);
        }
        lowMemoryMode = settings != null && settings.getBoolean("lowMemoryMode", false);
        perBotRateLimit = settings != null ? settings.getInt("perBotRateLimit", 50) : 50;
        mergeWindowMs = settings != null ? settings.getInt("mergeWindowMs", 100) : 100;
        int desiredSize = Math.max(1, settings != null ? settings.getInt("threadPoolSize", 4) : 4);
        ensureExecutor(desiredSize);
    }

    private int readThreadPoolSize() {
        ConfigurationSection settings = getConfig().getConfigurationSection("settings");
        return Math.max(1, settings != null ? settings.getInt("threadPoolSize", 4) : 4);
    }

    private void ensureExecutor(int desiredSize) {
        if (executor != null) {
            executor.shutdownNow();
        }
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "MultiBridge-Async-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(Math.max(1, desiredSize), factory);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public Set<String> getBroadcastTags() {
        return Collections.unmodifiableSet(broadcastTags);
    }

    public boolean isLowMemoryMode() {
        return lowMemoryMode;
    }

    public int getPerBotRateLimit() {
        return perBotRateLimit;
    }

    public int getMergeWindowMs() {
        return mergeWindowMs;
    }
}
