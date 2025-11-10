package com.wrs.multibridge.listeners;

import com.wrs.multibridge.managers.BotManager;
import com.wrs.multibridge.managers.BotManager.BridgeBot;
import com.wrs.multibridge.managers.BotManager.BridgeChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Optional;

public class DiscordChatListener extends ListenerAdapter {

    private final BotManager botManager;

    public DiscordChatListener(BotManager botManager) {
        this.botManager = botManager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            return;
        }

        Optional<BridgeBot> botOptional = botManager.getBotByJda(event.getJDA());
        if (!botOptional.isPresent()) {
            return;
        }

        BridgeBot bot = botOptional.get();
        Optional<BridgeChannel> channelOptional = bot.getChannel(event.getChannel().getIdLong());
        if (!channelOptional.isPresent()) {
            return;
        }

        botManager.handleDiscordMessage(event, bot, channelOptional.get());
    }
}
