package com.wrs.multibridge.listeners;

import com.wrs.multibridge.MultiBridge;
import com.wrs.multibridge.managers.BotManager;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.events.AchievementMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.DeathMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent;
import github.scarsz.discordsrv.objects.MessageFormat;
import github.scarsz.discordsrv.util.GamePermissionUtil;
import github.scarsz.discordsrv.util.PlayerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public class MinecraftChatListener implements Listener {

    private final MultiBridge plugin;
    private final BotManager botManager;
    private boolean registered;

    public MinecraftChatListener(MultiBridge plugin, BotManager botManager) {
        this.plugin = plugin;
        this.botManager = botManager;
    }

    public void register() {
        if (registered) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registered = true;
    }

    public void unregister() {
        if (!registered) {
            return;
        }
        HandlerList.unregisterAll(this);
        registered = false;
    }

    public void forwardChatEvent(GameChatMessagePostProcessEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        botManager.broadcastProcessedGameMessage(player, event.getProcessedMessage(), plugin.getBroadcastTags(), event.getChannel());
    }

    public void forwardDeathEvent(DeathMessagePostProcessEvent event) {
        if (event.isCancelled()) {
            return;
        }
        botManager.broadcastDeathMessage(event, plugin.getBroadcastTags());
    }

    public void forwardAdvancementEvent(AchievementMessagePostProcessEvent event) {
        if (event.isCancelled()) {
            return;
        }
        botManager.broadcastAdvancementMessage(event, plugin.getBroadcastTags());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (PlayerUtil.isVanished(player)) {
            return;
        }
        if (GamePermissionUtil.hasPermission(player, "discordsrv.silentjoin")) {
            return;
        }
        MessageFormat format = player.hasPlayedBefore()
                ? DiscordSRV.getPlugin().getMessageFromConfiguration("MinecraftPlayerJoinMessage")
                : DiscordSRV.getPlugin().getMessageFromConfiguration("MinecraftPlayerFirstJoinMessage");
        if (format == null || !format.isAnyContent()) {
            return;
        }
        String joinMessage = Objects.toString(event.getJoinMessage(), "");
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.getExecutor().execute(() -> botManager.broadcastJoinMessage(player, joinMessage, format)), 20L);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (PlayerUtil.isVanished(player)) {
            return;
        }
        if (GamePermissionUtil.hasPermission(player, "discordsrv.silentquit")) {
            return;
        }
        MessageFormat format = DiscordSRV.getPlugin().getMessageFromConfiguration("MinecraftPlayerLeaveMessage");
        if (format == null || !format.isAnyContent()) {
            return;
        }
        String quitMessage = Objects.toString(event.getQuitMessage(), "");
        plugin.getExecutor().execute(() -> botManager.broadcastQuitMessage(player, quitMessage, format));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!DiscordSRV.config().getBoolean("MinecraftPlayerDeathMessagesEnabled")) {
            return;
        }
        // Bukkit death handling is mirrored via DiscordSRV API events; ensure they are fired.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        // Advancement formatting is handled through DiscordSRV's post process event hooks.
    }
}
