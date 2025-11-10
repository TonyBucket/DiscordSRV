package com.wrs.multibridge.listeners;

import com.wrs.multibridge.MultiBridge;
import com.wrs.multibridge.managers.BotManager;
public class MinecraftChatListener {

    private final MultiBridge plugin;
    private final BotManager botManager;

    public MinecraftChatListener(MultiBridge plugin, BotManager botManager) {
        this.plugin = plugin;
        this.botManager = botManager;
    }

    public void forwardProcessedMessage(String sourceChannel, String processedMessage) {
        botManager.broadcastProcessedGameMessage(processedMessage, plugin.getBroadcastTags(), sourceChannel);
    }
}
