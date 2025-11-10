package com.wrs.multibridge.managers;

import com.wrs.multibridge.MultiBridge;
import com.wrs.multibridge.listeners.DiscordChatListener;
import com.wrs.multibridge.utils.RateLimiter;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.util.DiscordUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

import javax.security.auth.login.LoginException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    private final DiscordChatListener discordChatListener;
    private final Map<String, BridgeBot> bots = new ConcurrentHashMap<>();
    private final Map<JDA, BridgeBot> jdaLookup = new ConcurrentHashMap<>();
    private boolean lowMemoryMode;
    private int perBotRateLimit;
    private int mergeWindowMs;

    public BotManager(MultiBridge plugin) {
        this.plugin = plugin;
        this.discordChatListener = new DiscordChatListener(this);
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
        Map<String, Object> normalized = new java.util.HashMap<>();
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
                builder.addEventListeners(discordChatListener);
                JDA jda = builder.build();
                registerJda(bot, jda);
                plugin.getLogger().info("Started additional Discord bot '" + bot.getName() + "' for guild " + bot.getGuildName() + ".");
            } catch (LoginException loginException) {
                plugin.getLogger().log(Level.SEVERE, "Failed to login bot '" + bot.getName() + "': " + loginException.getMessage(), loginException);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error starting bot '" + bot.getName() + "'", ex);
            }
        });
    }

    private void registerJda(BridgeBot bot, JDA jda) {
        if (jda == null) {
            return;
        }
        jda.removeEventListener(discordChatListener);
        jda.addEventListener(discordChatListener);
        bot.setJda(jda);
        jdaLookup.put(jda, bot);
    }

    public void shutdown() {
        bots.values().forEach(bot -> {
            JDA jda = bot.getJda();
            if (jda != null) {
                jda.removeEventListener(discordChatListener);
                jda.shutdownNow();
            }
        });
        bots.clear();
        jdaLookup.clear();
    }

    public Collection<BridgeBot> getRegisteredBots() {
        return Collections.unmodifiableCollection(bots.values());
    }

    public void broadcastProcessedGameMessage(String processedMessage, Set<String> tags, String sourceChannel) {
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!tags.isEmpty() && (channel.getTag().isEmpty() || !tags.contains(channel.getTag()))) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendToDiscord(bot, channel, processedMessage, sourceChannel));
            }
        }
    }

    private void sendToDiscord(BridgeBot bot, BridgeChannel channel, String processedMessage, String sourceChannel) {
        JDA jda = bot.getJda();
        if (jda == null) {
            return;
        }
        TextChannel textChannel = jda.getTextChannelById(channel.getId());
        if (textChannel == null) {
            return;
        }
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        String channelPrefix = sourceChannel != null && !sourceChannel.isEmpty() ? "[" + sourceChannel + "] " : "";
        String payload = prefix + channelPrefix + processedMessage;
        textChannel.sendMessage(payload).queue(null, throwable -> plugin.getLogger().log(Level.WARNING, "Failed to send message to channel " + channel.getId() + " for bot '" + bot.getName() + "'", throwable));
    }

    public Optional<BridgeBot> getBotByJda(JDA jda) {
        return Optional.ofNullable(jdaLookup.get(jda));
    }

    public Optional<BridgeChannel> findChannel(JDA jda, long channelId) {
        return getBotByJda(jda).flatMap(bot -> bot.getChannel(channelId));
    }

    public void handleDiscordMessage(MessageReceivedEvent event, BridgeBot bot, BridgeChannel channel) {
        String author = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        String content = DiscordUtil.convertMentionsToNames(event.getMessage().getContentDisplay());
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        String output = prefix + author + " > " + content;

        Bukkit.getScheduler().runTask(plugin, () -> DiscordSRV.getPlugin().broadcastMessageToMinecraftServer(null, Component.text(output), event.getAuthor()));
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
