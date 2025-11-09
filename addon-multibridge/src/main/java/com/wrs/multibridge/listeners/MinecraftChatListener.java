package com.wrs.multibridge.listeners;

import com.wrs.multibridge.MultiBridge;
import com.wrs.multibridge.managers.BotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class MinecraftChatListener implements Listener {

    private final MultiBridge plugin;
    private final BotManager botManager;

    public MinecraftChatListener(MultiBridge plugin, BotManager botManager) {
        this.plugin = plugin;
        this.botManager = botManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String author = event.getPlayer().getDisplayName();
        String message = event.getMessage();
        botManager.broadcastMinecraftMessage(author, message, plugin.getBroadcastTags());
    }
}
