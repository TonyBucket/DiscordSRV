package com.wrs.multibridge.listeners;

import com.wrs.multibridge.MultiBridge;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AchievementMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.ConfigReloadedEvent;
import github.scarsz.discordsrv.api.events.DeathMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent;
import org.bukkit.Bukkit;

public class DiscordSRVHookListener {

    private final MultiBridge plugin;
    private final MinecraftChatListener minecraftChatListener;
    private boolean registered;

    public DiscordSRVHookListener(MultiBridge plugin, MinecraftChatListener minecraftChatListener) {
        this.plugin = plugin;
        this.minecraftChatListener = minecraftChatListener;
    }

    public void register() {
        if (registered) {
            return;
        }
        try {
            DiscordSRV.api.subscribe(this);
            registered = true;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to subscribe to DiscordSRV API: " + exception.getMessage());
        }
    }

    public void unregister() {
        if (!registered) {
            return;
        }
        DiscordSRV.api.unsubscribe(this);
        registered = false;
    }

    @Subscribe
    public void onDiscordSRVConfigReload(ConfigReloadedEvent event) {
        Bukkit.getScheduler().runTask(plugin, plugin::reloadBridge);
    }

    @Subscribe
    public void onGameChatProcessed(GameChatMessagePostProcessEvent event) {
        minecraftChatListener.forwardChatEvent(event);
    }

    @Subscribe
    public void onDeathMessageProcessed(DeathMessagePostProcessEvent event) {
        minecraftChatListener.forwardDeathEvent(event);
    }

    @Subscribe
    public void onAdvancementMessageProcessed(AchievementMessagePostProcessEvent event) {
        minecraftChatListener.forwardAdvancementEvent(event);
    }
}
