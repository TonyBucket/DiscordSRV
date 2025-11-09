package com.wrs.multibridge.managers;

import com.wrs.multibridge.MultiBridge;
import com.wrs.multibridge.listeners.DiscordChatListener;
import com.wrs.multibridge.utils.RateLimiter;
import github.scarsz.discordsrv.util.DiscordUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

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
            plugin.getLogger().warning("No bots configured for MultiBridge; defaulting to the main DiscordSRV bot.");
            rawBots = Collections.singletonList(Collections.singletonMap("useDiscordSRVMain", true));
        }

        for (Map<?, ?> rawBot : rawBots) {
            String name = Objects.toString(rawBot.getOrDefault("name", "bot-" + (bots.size() + 1)));
            boolean useMain = Boolean.parseBoolean(String.valueOf(rawBot.getOrDefault("useDiscordSRVMain", false)));
            String token = rawBot.containsKey("token") ? Objects.toString(rawBot.get("token"), "") : "";
            String guildName = rawBot.containsKey("guildName") ? Objects.toString(rawBot.get("guildName"), name) : name;
            List<BridgeChannel> channels = parseChannels(rawBot.get("channels"));

            BridgeBot bot = new BridgeBot(name, guildName, useMain, channels, new RateLimiter(perBotRateLimit, mergeWindowMs));
            bots.put(name.toLowerCase(), bot);

            if (useMain) {
                JDA mainJda = DiscordUtil.getJda();
                if (mainJda == null) {
                    plugin.getLogger().warning("DiscordSRV main JDA is not ready yet; bot " + name + " will attach once available.");
                    continue;
                }
                registerJda(bot, mainJda, true);
                plugin.getLogger().info("Linked profile '" + name + "' to the primary DiscordSRV bot.");
            } else if (!token.isEmpty()) {
                startAdditionalBot(bot, token);
            } else {
                plugin.getLogger().warning("Bot profile '" + name + "' is missing both token and useDiscordSRVMain=true; skipping.");
            }
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
            Map<?, ?> map = (Map<?, ?>) entry;
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
                registerJda(bot, jda, false);
                plugin.getLogger().info("Started additional Discord bot '" + bot.getName() + "' for guild " + bot.getGuildName() + ".");
            } catch (LoginException loginException) {
                plugin.getLogger().log(Level.SEVERE, "Failed to login bot '" + bot.getName() + "': " + loginException.getMessage(), loginException);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error starting bot '" + bot.getName() + "'", ex);
            }
        });
    }

    private void registerJda(BridgeBot bot, JDA jda, boolean primary) {
        if (jda == null) {
            return;
        }
        jda.removeEventListener(discordChatListener);
        jda.addEventListener(discordChatListener);
        bot.setJda(jda);
        jdaLookup.put(jda, bot);
        if (primary) {
            plugin.getLogger().info("Attached Discord listener to main DiscordSRV JDA for profile '" + bot.getName() + "'.");
        }
    }

    public void shutdown() {
        bots.values().forEach(bot -> {
            JDA jda = bot.getJda();
            if (jda != null) {
                jda.removeEventListener(discordChatListener);
                if (!bot.isUsingMainJda()) {
                    jda.shutdownNow();
                }
            }
        });
        bots.clear();
        jdaLookup.clear();
    }

    public Collection<BridgeBot> getRegisteredBots() {
        return Collections.unmodifiableCollection(bots.values());
    }

    public void broadcastMinecraftMessage(String author, String message, Set<String> tags) {
        String sanitized = ChatColor.stripColor(message);
        for (BridgeBot bot : bots.values()) {
            for (BridgeChannel channel : bot.getChannels()) {
                if (!tags.isEmpty() && (channel.getTag().isEmpty() || !tags.contains(channel.getTag()))) {
                    continue;
                }
                if (!bot.tryAcquire()) {
                    continue;
                }
                plugin.getExecutor().execute(() -> sendToDiscord(bot, channel, author, sanitized));
            }
        }
    }

    private void sendToDiscord(BridgeBot bot, BridgeChannel channel, String author, String message) {
        JDA jda = bot.getJda();
        if (jda == null) {
            return;
        }
        TextChannel textChannel = jda.getTextChannelById(channel.getId());
        if (textChannel == null) {
            return;
        }
        String prefix = channel.getPrefix().isEmpty() ? "" : channel.getPrefix() + " ";
        String payload = prefix + author + ChatColor.RESET + " > " + message;
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

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(output);
            }
        });
    }

    public static class BridgeBot {
        private final String name;
        private final String guildName;
        private final boolean usingMainJda;
        private final List<BridgeChannel> channels;
        private final RateLimiter rateLimiter;
        private volatile JDA jda;

        public BridgeBot(String name, String guildName, boolean usingMainJda, List<BridgeChannel> channels, RateLimiter rateLimiter) {
            this.name = name;
            this.guildName = guildName;
            this.usingMainJda = usingMainJda;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
            this.rateLimiter = rateLimiter;
        }

        public String getName() {
            return name;
        }

        public String getGuildName() {
            return guildName;
        }

        public boolean isUsingMainJda() {
            return usingMainJda;
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
