package com.wrs.multibridge.listeners;

import com.wrs.multibridge.managers.BotManager;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class DiscordToMinecraftListener extends ListenerAdapter {

    private final BotManager botManager;

    public DiscordToMinecraftListener(BotManager botManager) {
        this.botManager = botManager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        if (event.getAuthor().isBot() || event.getAuthor().isSystem() || event.isWebhookMessage()) {
            return;
        }
        botManager.handleDiscordMessage(event);
    }
}
