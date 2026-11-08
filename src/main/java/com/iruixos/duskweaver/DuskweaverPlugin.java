package com.iruixos.duskweaver;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DuskweaverPlugin extends JavaPlugin implements Listener {
    private final Map<String, WorldClock> clocks = new HashMap<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private BukkitTask clockTask;
    private BukkitTask sleepTask;
    private double dayStep;
    private double nightStep;
    private boolean sleepEnabled;
    private boolean onePlayerSleep;
    private boolean majorityVote;
    private boolean broadcast;
    private int countdownSeconds;
    private long idleTimeoutMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!configure()) {
            getLogger().severe("Invalid configuration. Disabling Duskweaver.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) lastActivity.put(player.getUniqueId(), now);
        Bukkit.getPluginManager().registerEvents(this, this);
        clockTask = Bukkit.getScheduler().runTaskTimer(this, this::tickClocks, 1L, 1L);
        sleepTask = Bukkit.getScheduler().runTaskTimer(this, this::tickSleep, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (clockTask != null) clockTask.cancel();
        if (sleepTask != null) sleepTask.cancel();
        releaseAll();
        lastActivity.clear();
    }

    private boolean configure() {
        reloadConfig();
        double day = getConfig().getDouble("day-length-minutes", -1);
        double night = getConfig().getDouble("night-length-minutes", -1);
        boolean enabled = getConfig().getBoolean("sleep.enabled", true);
        boolean single = getConfig().getBoolean("sleep.one-player-sleep", false);
        boolean majority = getConfig().getBoolean("sleep.majority-vote", true);
        int countdown = getConfig().getInt("sleep.countdown-seconds", -1);
        double idleMinutes = getConfig().getDouble("sleep.inactivity-timeout-minutes", -1);
        if (!Double.isFinite(day) || !Double.isFinite(night) || day <= 0 || night <= 0
                || !Double.isFinite(idleMinutes) || idleMinutes < 0 || idleMinutes > 100000
                || countdown < 0 || countdown > 86400 || (enabled && single && majority)) {
            getLogger().severe("Invalid day/night lengths, sleep settings, or both sleep modes enabled.");
            return false;
        }
        double nextDayStep = 12000.0 / (day * 1200.0);
        double nextNightStep = 12000.0 / (night * 1200.0);
        if (!Double.isFinite(nextDayStep) || !Double.isFinite(nextNightStep)) return false;
        Set<String> wanted = new HashSet<>(getConfig().getStringList("worlds"));
        // Snapshot the ORIGINAL rules before releasing worlds on reload.
        Map<String, Boolean> oldDayRules = new HashMap<>();
        Map<String, Integer> oldSleepRules = new HashMap<>();
        for (var entry : clocks.entrySet()) {
            oldDayRules.put(entry.getKey(), entry.getValue().originalDaylightCycle);
            oldSleepRules.put(entry.getKey(), entry.getValue().originalSleepingPercentage);
        }
        releaseAll();
        dayStep = nextDayStep;
        nightStep = nextNightStep;
        sleepEnabled = enabled;
        onePlayerSleep = single;
        majorityVote = majority;
        countdownSeconds = countdown;
        idleTimeoutMillis = (long) (idleMinutes * 60000.0);
        broadcast = getConfig().getBoolean("sleep.broadcast-messages", true);
        for (String name : wanted) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                getLogger().warning("World not loaded: " + name);
                continue;
            }
            Boolean daylight = world.getGameRuleValue(GameRules.ADVANCE_TIME);
            Integer sleeping = world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
            if (daylight == null || sleeping == null) {
                getLogger().warning("Could not read gamerules for " + name);
                continue;
            }
            WorldClock clock = new WorldClock(oldDayRules.getOrDefault(name, daylight),
                    oldSleepRules.getOrDefault(name, sleeping), world.getFullTime());
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            // 101% prevents vanilla from skipping before our countdown expires.
            // When both custom modes are off, we still use the original percentage
            // to calculate quorum but retain control of the countdown.
            world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 101);
            clocks.put(name, clock);
        }
        getLogger().info("Managing " + clocks.size() + " worlds; day=" + day + "m, night=" + night
                + "m, sleeping=" + (enabled ? (single ? "one-player" : majority ? "majority" : "vanilla threshold") : "disabled") + ".");
        return true;
    }

    private void tickClocks() {
        for (var entry : clocks.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) continue;
            WorldClock clock = entry.getValue();
            long current = world.getFullTime();
            if (current != clock.lastFullTime) {
                clock.fraction = 0;
                clock.lastFullTime = current;
            }
            long time = Math.floorMod(current, 24000L);
            clock.fraction += time < 12000 ? dayStep : nightStep;
            long advance = (long) Math.floor(clock.fraction);
            if (advance > 0) {
                clock.fraction -= advance;
                world.setFullTime(current + advance);
                clock.lastFullTime = current + advance;
            }
        }
    }

    private void tickSleep() {
        long now = System.currentTimeMillis();
        for (var entry : clocks.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) continue;
            WorldClock clock = entry.getValue();
            long time = Math.floorMod(world.getFullTime(), 24000L);
            // A player may sleep during a thunderstorm in daytime; we only skip nights.
            if (!sleepEnabled || time < 12000 || time >= 24000) {
                cancelCountdown(world, clock);
                continue;
            }
            int eligible = 0;
            int sleeping = 0;
            for (Player player : world.getPlayers()) {
                if (player.getGameMode() == GameMode.SPECTATOR) continue;
                boolean asleep = player.isSleeping();
                if (asleep) sleeping++;
                if (asleep || idleTimeoutMillis == 0
                        || now - lastActivity.getOrDefault(player.getUniqueId(), now) <= idleTimeoutMillis) eligible++;
            }
            int required;
            if (onePlayerSleep) required = 1;
            else if (majorityVote) required = eligible <= 2 ? 1 : eligible / 2 + 1;
            else required = Math.max(1, (int) Math.ceil(eligible * clock.originalSleepingPercentage / 100.0));
            if (eligible == 0 || sleeping == 0 || sleeping < required) {
                cancelCountdown(world, clock);
                continue;
            }
            if (clock.countdownStart < 0) {
                clock.countdownStart = now;
                if (broadcast) {
                    String message = "§6[Duskweaver] §e" + sleeping + "/" + required
                            + " sleeping. Skipping night in " + countdownSeconds + " seconds...";
                    for (Player player : world.getPlayers()) player.sendMessage(message);
                }
            }
            if (now - clock.countdownStart >= countdownSeconds * 1000L) {
                long fullTime = world.getFullTime();
                long remaining = 24000L - Math.floorMod(fullTime, 24000L);
                world.setFullTime(fullTime + remaining);
                clock.lastFullTime = world.getFullTime();
                clock.fraction = 0;
                clock.countdownStart = -1;
                if (broadcast) world.getPlayers().forEach(p -> p.sendMessage("§6[Duskweaver] §aGood morning!"));
            }
        }
    }

    private void cancelCountdown(World world, WorldClock clock) {
        if (clock.countdownStart < 0) return;
        clock.countdownStart = -1;
        if (broadcast) world.getPlayers().forEach(p -> p.sendMessage("§6[Duskweaver] §cNight skip cancelled."));
    }

    private void releaseAll() {
        for (var entry : clocks.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) continue;
            world.setGameRule(GameRules.ADVANCE_TIME, entry.getValue().originalDaylightCycle);
            world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, entry.getValue().originalSleepingPercentage);
        }
        clocks.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        var from = event.getFrom();
        var to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()
                || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /" + label + " reload");
            return true;
        }
        if (!sender.hasPermission("duskweaver.reload")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }
        if (configure()) sender.sendMessage("Duskweaver configuration reloaded.");
        else sender.sendMessage("Invalid configuration; existing settings remain active. Check console.");
        return true;
    }

    private static final class WorldClock {
        final boolean originalDaylightCycle;
        final int originalSleepingPercentage;
        long lastFullTime;
        double fraction;
        long countdownStart = -1;

        WorldClock(boolean daylight, int sleepingPercentage, long fullTime) {
            originalDaylightCycle = daylight;
            originalSleepingPercentage = sleepingPercentage;
            lastFullTime = fullTime;
        }
    }
}
