package com.wrs.multibridge.managers;

import com.wrs.multibridge.MultiBridge;
import com.wrs.multibridge.listeners.DiscordToMinecraftListener;
import com.wrs.multibridge.utils.RateLimiter;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.objects.MessageFormat;
import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.util.MessageUtil;
import github.scarsz.discordsrv.util.PlaceholderUtil;
import github.scarsz.discordsrv.util.TimeUtil;
import github.scarsz.discordsrv.util.WebhookUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.kyori.adventure.text.Component;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import javax.security.auth.login.LoginException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

public class BotManager {

    private final MultiBridge plugin;
    private final DiscordToMinecraftListener discordToMinecraftListener;
    private final Map<String, BridgeBot> bots = new ConcurrentHashMap<>();
    private final Map<JDA, BridgeBot> jdaLookup = new ConcurrentHashMap<>();
    private boolean lowMemoryMode;
    private int perBotRateLimit;
    private int mergeWindowMs;

    public BotManager(MultiBridge plugin) {
        this.plugin = plugin;
        this.discordToMinecraftListener = new DiscordToMinecraftListener(this);
    }

    public void configure(boolean lowMemoryMode, int perBotRateLimit, int mergeWindowMs) {
        this.lowMemoryMode = lowMemoryMode;
        this.perBotRateLimit = Math.max(1, perBotRateLimit);
        this.mergeWindowMs = Math.max(1, mergeWindowMs);
    }

    public synchronized void loadBots(List<Map<?, ?>> rawBots) {
        shutdown();

        if (rawBots == null || rawBots.isEmpty()) {
            plugin.getLogger().info("No additional MultiBridge bots configured; operating solely with DiscordSRV's primary bot.");
            return;
        }

        for (Map<?, ?> rawBot : rawBots) {
            if (rawBot == null) {
                continue;
            }

            Map<String, Object> botConfig = normalizeMap(rawBot);

            String name = Objects.toString(botConfig.getOrDefault("name", "bot-" + (bots.size() + 1)));
            String token = Objects.toString(botConfig.getOrDefault("token", ""), "");
            String guildName = Objects.toString(botConfig.getOrDefault("guildName", name), name);
            List<BridgeChannel> channels = parseChannels(botConfig.get("channels"));

            if (Boolean.parseBoolean(String.valueOf(botConfig.getOrDefault("useDiscordSRVMain", false)))) {
                plugin.getLogger().warning("Bot profile '" + name + "' is configured to use the main DiscordSRV bot, which is managed by DiscordSRV itself. Skipping this profile.");
                continue;
            }

            if (token.isEmpty()) {
                plugin.getLogger().warning("Bot profile '" + name + "' is missing a token; skipping.");
                continue;
            }

            BridgeBot bot = new BridgeBot(name, guildName, channels, new RateLimiter(perBotRateLimit, mergeWindowMs));
            bots.put(name.toLowerCase(), bot);

            startAdditionalBot(bot, token);
        }
    }

    private List<BridgeChannel> parseChannels(Object rawChannels) {
        if (!(rawChannels instanceof List)) {
            return Collections.emptyList();
        }
        List<?> list = (List<?>) rawChannels;
        List<BridgeChannel> channels = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<String, Object> map = normalizeMap((Map<?, ?>) entry);
            Object idObj = map.get("id");
            if (!(idObj instanceof Number) && !(idObj instanceof String)) {
                continue;
            }
            long id;
            try {
                id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseUnsignedLong(idObj.toString());
            } catch (NumberFormatException ex) {
                plugin.getLogger().log(Level.WARNING, "Invalid channel id in MultiBridge config: " + idObj);
                continue;
            }
            String tag = Objects.toString(map.getOrDefault("tag", ""), "");
            String prefix = Objects.toString(map.getOrDefault("prefix", ""), "");
            channels.add(new BridgeChannel(id, tag, prefix));
        }
        return channels;
    }

    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> normalized = new HashMap<>();
        if (source == null) {
            return normalized;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    private void startAdditionalBot(BridgeBot bot, String token) {
        ExecutorService executor = plugin.getExecutor();
        executor.execute(() -> {
            try {
                JDABuilder builder = JDABuilder.createDefault(token);
                builder.enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS);
                if (lowMemoryMode) {
                    builder.disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.SCHEDULED_EVENTS, CacheFlag.EMOJI, CacheFlag.STICKER);
                    builder.setMemberCachePolicy(MemberCachePolicy.NONE);
                    builder.setChunkingFilter(ChunkingFilter.NONE);
                }
                builder.addEventListeners(discordToMinecraftListener);
                JDA jda = builder.build();
                registerJda(bot, jda);
                plugin.getLogger().info("Started additional Discord bot '" + bot.getName() + "' for guild " + bot.getGuildName() + ".");
            } catch (Exception ex) {
                if (ex instanceof LoginException) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to login bot '" + bot.getName() + "': " + ex.getMessage(), ex);
                } else {
                    plugin.getLogger().log(Level.SEVERE, "Unexpected error starting bot '" + bot.getName() + "'", ex);
                }
            }
        });
    }

    private void registerJda(BridgeBot bot, JDA jda) {
        if (jda == null) {
            return;
        }
        jda.removeEventListener(discordToMinecraftListener);
        jda.addEventListener(discordToMinecraftListener);
        bot.setJda(jda);
        jdaLookup.put(jda, bot);
    }

    public void shutdown() {
        bots.values().forEach(bot -> {
            JDA jda = bot.getJda();
            if (jda != null) {
                jda.removeEventListener(discordToMinecraftListener);
                jda.shutdownNow();
            }
        });
        bots.clear();
        jdaLookup.clear();
    }

    public Collection<BridgeBot> getRegisteredBots() {
        return Collections.unmodifiableCollection(bots.values());
    }

    public void broadcastProcessedGameMessage(Player player, String processedMessage, Set<String> tags, String sourceChannel) {
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!shouldSendToChannel(channel, tags)) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendChatToDiscord(bot, channel, player, processedMessage, sourceChannel));
            }
        }
    }

    public void broadcastDeathMessage(github.scarsz.discordsrv.api.events.DeathMessagePostProcessEvent event, Set<String> tags) {
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!shouldSendToChannel(channel, tags)) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendFormattedMessage(bot, channel,
                        event.getDiscordMessage().getContentRaw(), event.getDiscordMessage().getEmbeds(),
                        event.isUsingWebhooks(), event.getWebhookName(), event.getWebhookAvatarUrl(), event.getChannel()));
            }
        }
    }

    public void broadcastAdvancementMessage(github.scarsz.discordsrv.api.events.AchievementMessagePostProcessEvent event, Set<String> tags) {
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!shouldSendToChannel(channel, tags)) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendFormattedMessage(bot, channel,
                        event.getDiscordMessage().getContentRaw(), event.getDiscordMessage().getEmbeds(),
                        event.isUsingWebhooks(), event.getWebhookName(), event.getWebhookAvatarUrl(), event.getChannel()));
            }
        }
    }

    public void broadcastJoinMessage(Player player, String joinMessage, MessageFormat format) {
        if (format == null || !format.isAnyContent()) {
            return;
        }
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!shouldSendToChannel(channel, plugin.getBroadcastTags())) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendMessageFormat(bot, channel, player, joinMessage, format));
            }
        }
    }

    public void broadcastQuitMessage(Player player, String quitMessage, MessageFormat format) {
        if (format == null || !format.isAnyContent()) {
            return;
        }
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!shouldSendToChannel(channel, plugin.getBroadcastTags())) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendMessageFormat(bot, channel, player, quitMessage, format));
            }
        }
    }

    public void handleDiscordMessage(MessageReceivedEvent event) {
        Optional<BridgeBot> botOptional = Optional.ofNullable(jdaLookup.get(event.getJDA()));
        if (!botOptional.isPresent()) {
            return;
        }
        BridgeBot bot = botOptional.get();
        Optional<BridgeChannel> channelOptional = bot.getChannel(event.getChannel().getIdLong());
        if (!channelOptional.isPresent()) {
            return;
        }
        BridgeChannel channel = channelOptional.get();
        if (!shouldSendToChannel(channel, plugin.getBroadcastTags())) {
            return;
        }
        String author = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        String content = DiscordUtil.convertMentionsToNames(event.getMessage().getContentDisplay());
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        String formatted = ChatColor.DARK_AQUA + "[" + bot.getGuildName() + "]" + ChatColor.GRAY + " " + author + ChatColor.RESET + " > " + content;
        String output = prefix + formatted;
        Bukkit.getScheduler().runTask(plugin, () ->
                DiscordSRV.getPlugin().broadcastMessageToMinecraftServer(null, Component.text(output), event.getAuthor()));
    }

    private boolean shouldSendToChannel(BridgeChannel channel, Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        String tag = channel.getTag();
        return StringUtils.isNotBlank(tag) && tags.contains(tag);
    }

    private void sendChatToDiscord(BridgeBot bot, BridgeChannel channel, Player player, String processedMessage, String sourceChannel) {
        TextChannel textChannel = resolveChannel(bot, channel);
        if (textChannel == null) {
            return;
        }
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        String channelPrefix = StringUtils.isNotBlank(sourceChannel) ? "[" + sourceChannel + "] " : "";
        String payload = prefix + channelPrefix + processedMessage;
        if (DiscordSRV.config().getBoolean("Experiment_WebhookChatMessageDelivery")) {
            WebhookUtil.deliverMessage(textChannel, player, payload);
        } else {
            textChannel.sendMessage(payload).queue(null, throwable ->
                    plugin.getLogger().log(Level.WARNING, "Failed to send chat message to channel " + channel.getId() + " for bot '" + bot.getName() + "'", throwable));
        }
    }

    private void sendFormattedMessage(BridgeBot bot, BridgeChannel channel, String content, List<MessageEmbed> embeds,
                                      boolean usingWebhooks, String webhookName, String webhookAvatarUrl, String sourceChannel) {
        TextChannel textChannel = resolveChannel(bot, channel);
        if (textChannel == null) {
            return;
        }
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        String channelPrefix = StringUtils.isNotBlank(sourceChannel) ? "[" + sourceChannel + "] " : "";
        String payload = prefix + channelPrefix + StringUtils.defaultString(content, "");
        MessageEmbed embed = embeds.stream().findFirst().orElse(null);
        if (usingWebhooks) {
            WebhookUtil.deliverMessage(textChannel, webhookName, webhookAvatarUrl, payload, embed);
            return;
        }
        MessageCreateBuilder builder = new MessageCreateBuilder();
        if (StringUtils.isNotBlank(payload)) {
            builder.setContent(payload);
        }
        if (embed != null) {
            builder.addEmbeds(embed);
        }
        textChannel.sendMessage(builder.build()).queue(null, throwable ->
                plugin.getLogger().log(Level.WARNING, "Failed to send formatted message to channel " + channel.getId() + " for bot '" + bot.getName() + "'", throwable));
    }

    private void sendMessageFormat(BridgeBot bot, BridgeChannel channel, Player player, String message, MessageFormat format) {
        TextChannel textChannel = resolveChannel(bot, channel);
        if (textChannel == null) {
            return;
        }
        String sanitizedMessage = StringUtils.defaultString(message, "");
        String displayName = StringUtils.isNotBlank(player.getDisplayName()) ? MessageUtil.strip(player.getDisplayName()) : "";
        String avatarUrl = DiscordSRV.getAvatarUrl(player);
        String botAvatarUrl = textChannel.getJDA().getSelfUser().getEffectiveAvatarUrl();
        String botName = textChannel.getGuild().getSelfMember().getEffectiveName();
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        java.util.function.BiFunction<String, Boolean, String> translator = (content, needsEscape) -> {
            if (content == null) {
                return null;
            }
            content = content
                    .replaceAll("%time%|%date%", TimeUtil.timeStamp())
                    .replace("%message%", MessageUtil.strip(needsEscape ? DiscordUtil.escapeMarkdown(sanitizedMessage) : sanitizedMessage))
                    .replace("%username%", MessageUtil.strip(needsEscape ? DiscordUtil.escapeMarkdown(player.getName()) : player.getName()))
                    .replace("%displayname%", needsEscape ? DiscordUtil.escapeMarkdown(displayName) : displayName)
                    .replace("%usernamenoescapes%", player.getName())
                    .replace("%displaynamenoescapes%", displayName)
                    .replace("%embedavatarurl%", avatarUrl)
                    .replace("%botavatarurl%", botAvatarUrl)
                    .replace("%botname%", botName);
            content = DiscordUtil.translateEmotes(content, textChannel.getGuild());
            content = PlaceholderUtil.replacePlaceholdersToDiscord(content, player);
            return content;
        };

        net.dv8tion.jda.api.entities.Message discordMessage = DiscordSRV.translateMessage(format, translator);
        if (discordMessage == null) {
            return;
        }
        String webhookName = translator.apply(format.getWebhookName(), false);
        String webhookAvatar = translator.apply(format.getWebhookAvatarUrl(), false);
        String payload = prefix + StringUtils.defaultString(discordMessage.getContentRaw(), "");
        MessageEmbed embed = discordMessage.getEmbeds().stream().findFirst().orElse(null);

        if (format.isUseWebhooks()) {
            WebhookUtil.deliverMessage(textChannel, webhookName, webhookAvatar, payload, embed);
        } else {
            MessageCreateBuilder builder = new MessageCreateBuilder();
            if (StringUtils.isNotBlank(payload)) {
                builder.setContent(payload);
            }
            if (embed != null) {
                builder.addEmbeds(embed);
            }
            textChannel.sendMessage(builder.build()).queue(null, throwable ->
                    plugin.getLogger().log(Level.WARNING, "Failed to send message format to channel " + channel.getId() + " for bot '" + bot.getName() + "'", throwable));
        }
    }

    private TextChannel resolveChannel(BridgeBot bot, BridgeChannel channel) {
        JDA jda = bot.getJda();
        if (jda == null) {
            return null;
        }
        TextChannel textChannel = jda.getTextChannelById(channel.getId());
        if (textChannel == null) {
            plugin.getLogger().warning("Bot '" + bot.getName() + "' cannot find channel id " + channel.getId());
        }
        return textChannel;
    }

    public static class BridgeBot {
        private final String name;
        private final String guildName;
        private final List<BridgeChannel> channels;
        private final RateLimiter rateLimiter;
        private volatile JDA jda;

        public BridgeBot(String name, String guildName, List<BridgeChannel> channels, RateLimiter rateLimiter) {
            this.name = name;
            this.guildName = guildName;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
            this.rateLimiter = rateLimiter;
        }

        public String getName() {
            return name;
        }

        public String getGuildName() {
            return guildName;
        }

        public List<BridgeChannel> getChannels() {
            return channels;
        }

        public Optional<BridgeChannel> getChannel(long id) {
            return channels.stream().filter(channel -> channel.getId() == id).findFirst();
        }

        public boolean tryAcquire() {
            return rateLimiter.tryAcquire();
        }

        public JDA getJda() {
            return jda;
        }

        public void setJda(JDA jda) {
            this.jda = jda;
        }
    }

    public static class BridgeChannel {
        private final long id;
        private final String tag;
        private final String prefix;

        public BridgeChannel(long id, String tag, String prefix) {
            this.id = id;
            this.tag = tag == null ? "" : tag;
            this.prefix = prefix == null ? "" : prefix;
        }

        public long getId() {
            return id;
        }

        public String getTag() {
            return tag;
        }

        public String getPrefix() {
            return prefix;
        }
    }
}
