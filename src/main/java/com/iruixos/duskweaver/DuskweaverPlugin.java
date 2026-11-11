package com.iruixos.duskweaver;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import java.io.IOException;
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
    private NightmareManager nightmares;
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
    private boolean clearWeatherOnSkip;
    private int clearWeatherDurationTicks;

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
        nightmares = new NightmareManager(this);
        printStartupBanner();
    }

    private void printStartupBanner() {
        String version = getPluginMeta().getVersion();
        String[] lines = {
                "",
                "+--------------------------------+",
                String.format("| %-30s |", "[DW] Duskweaver v" + version),
                "|      by iruixos                |",
                "|      Paper 26.3 / MC 26.3      |",
                "+--------------------------------+",
                ""
        };
        for (String line : lines) {
            getLogger().info(line);
        }
    }

    @Override
    public void onDisable() {
        if (clockTask != null) clockTask.cancel();
        if (sleepTask != null) sleepTask.cancel();
        if (nightmares != null) nightmares.shutdown();
        releaseAll();
        lastActivity.clear();
    }

    private boolean configure() {
        // Reject malformed YAML before replacing the active configuration.
        if (getDataFolder().toPath().resolve("config.yml").toFile().isFile()) {
            try {
                new YamlConfiguration().load(getDataFolder().toPath().resolve("config.yml").toFile());
            } catch (IOException | InvalidConfigurationException ex) {
                getLogger().severe("Invalid config.yml; previous runtime settings retained: " + ex.getMessage());
                return false;
            }
        }
        reloadConfig();
        double day = getConfig().getDouble("day-length-minutes", -1);
        double night = getConfig().getDouble("night-length-minutes", -1);
        boolean enabled = getConfig().getBoolean("sleep.enabled", true);
        boolean single = getConfig().getBoolean("sleep.one-player-sleep", false);
        boolean majority = getConfig().getBoolean("sleep.majority-vote", true);
        int countdown = getConfig().getInt("sleep.countdown-seconds", -1);
        double idleMinutes = getConfig().getDouble("sleep.inactivity-timeout-minutes", -1);
        double clearMinutes = getConfig().getDouble("sleep.clear-weather-duration-minutes", -1);
        if (!Double.isFinite(clearMinutes) || clearMinutes < 0 || clearMinutes > Integer.MAX_VALUE / 1200.0) {
            getLogger().severe("Invalid clear-weather-duration-minutes; must be nonnegative and fit in integer ticks.");
            return false;
        }
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
        clearWeatherOnSkip = getConfig().getBoolean("sleep.clear-weather-on-skip", true);
        clearWeatherDurationTicks = (int) Math.round(clearMinutes * 1200.0);
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
        if (nightmares != null) nightmares.configure();
        getLogger().info("Managing " + clocks.size() + " worlds; day=" + day + "m, night=" + night
                + "m, sleeping=" + (enabled ? (single ? "one-player" : majority ? "majority" : "vanilla threshold") : "disabled") + ".");
        return true;
    }

    boolean isManagedWorld(World world) { return clocks.containsKey(world.getName()); }

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
            // An active or pending Nightmare may run past the normal night length.
            // Freeze immediately before dawn; resume on the first tick after resolution.
            if (nightmares != null && nightmares.hasActiveNightmare(world)
                    && time >= 12000L && time >= 23999L) {
                clock.fraction = 0;
                continue;
            }
            clock.fraction += time < 12000 ? dayStep : nightStep;
            long advance = (long) Math.floor(clock.fraction);
            if (advance > 0) {
                if (nightmares != null && nightmares.hasActiveNightmare(world)
                        && time >= 12000L)
                    advance = Math.min(advance, 23999L - time);
                if (advance == 0) {
                    clock.fraction = 0;
                    continue;
                }
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
            if (nightmares != null && nightmares.hasActiveNightmare(world)) {
                cancelCountdown(world, clock);
                long nowMessage = System.currentTimeMillis();
                if (nightmares.hasStartedNightmare(world)
                        && world.getPlayers().stream().anyMatch(Player::isSleeping)
                        && nowMessage - clock.lastNightmareWarning >= 10000L) {
                    clock.lastNightmareWarning = nowMessage;
                    Component warning = prefix().append(Component.text(
                            nightmares.affectedPlayer(world) + " has been swallowed by the Nightmare! " +
                            "You must dispel the shadows tormenting them before the night can pass.", NamedTextColor.LIGHT_PURPLE));
                    for (Player player : world.getPlayers()) if (player.isSleeping()) player.sendMessage(warning);
                }
                continue;
            }
            long time = Math.floorMod(world.getFullTime(), 24000L);
            // During a daytime thunderstorm, vanilla allows players to sleep.
            // Treat this as a weather skip, not a night skip: preserve the clock.
            boolean daytimeThunderstorm = time < 12000 && world.isThundering();
            boolean nighttime = time >= 12000;
            if (!sleepEnabled || (!nighttime && !daytimeThunderstorm)) {
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
                    Component message = prefix()
                            .append(Component.text(sleeping + "/" + required, NamedTextColor.LIGHT_PURPLE))
                            .append(Component.text(" sleeping. ", NamedTextColor.GRAY))
                            .append(Component.text(daytimeThunderstorm ? "Clearing thunderstorm" : "Skipping night", NamedTextColor.DARK_PURPLE))
                            .append(Component.text(" in ", NamedTextColor.GRAY))
                            .append(Component.text(countdownSeconds, NamedTextColor.YELLOW))
                            .append(Component.text(" seconds...", NamedTextColor.GRAY));
                    for (Player player : world.getPlayers()) player.sendMessage(message);
                }
            }
            if (now - clock.countdownStart >= countdownSeconds * 1000L) {
                if (nightmares != null && nightmares.hasActiveNightmare(world)) {
                    cancelCountdown(world, clock);
                    continue;
                }
                // Re-evaluate at completion: a storm can end during the countdown,
                // or a world clock can cross from day into night.
                long fullTime = world.getFullTime();
                long currentTime = Math.floorMod(fullTime, 24000L);
                boolean stormSkip = currentTime < 12000 && world.isThundering();
                if (currentTime < 12000 && !stormSkip) {
                    cancelCountdown(world, clock);
                    continue;
                }
                if (!stormSkip) {
                    long remaining = 24000L - currentTime;
                    world.setFullTime(fullTime + remaining);
                    clock.lastFullTime = world.getFullTime();
                    clock.fraction = 0;
                }
                clearWeatherAfterSleep(world);
                clock.countdownStart = -1;
                if (broadcast) {
                    Component success = prefix().append(Component.text(
                            stormSkip ? "Thunderstorm cleared!" : "Good morning!", NamedTextColor.GOLD));
                    for (Player player : world.getPlayers()) player.sendMessage(success);
                }
            }
        }
    }

    /** Clears active rain/snow and thunder after a successful sleep skip only. */
    private void clearWeatherAfterSleep(World world) {
        if (!clearWeatherOnSkip || (!world.hasStorm() && !world.isThundering())) return;
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(0);
        world.setThunderDuration(0);
        // Minecraft weather durations use game ticks (20 ticks/second at 20 TPS).
        // A zero duration permits the normal weather cycle to resume immediately.
        if (clearWeatherDurationTicks > 0) {
            world.setClearWeatherDuration(clearWeatherDurationTicks);
        }
    }

    private Component prefix() {
        return Component.text("[", NamedTextColor.DARK_PURPLE)
                .append(Component.text("Duskweaver", NamedTextColor.GOLD))
                .append(Component.text("] ", NamedTextColor.DARK_PURPLE));
    }

    private void cancelCountdown(World world, WorldClock clock) {
        if (clock.countdownStart < 0) return;
        clock.countdownStart = -1;
        if (broadcast) {
            Component message = prefix().append(Component.text("Sleep countdown cancelled.", NamedTextColor.GRAY));
            for (Player player : world.getPlayers()) player.sendMessage(message);
        }
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
        long lastNightmareWarning = 0L;

        WorldClock(boolean daylight, int sleepingPercentage, long fullTime) {
            originalDaylightCycle = daylight;
            originalSleepingPercentage = sleepingPercentage;
            lastFullTime = fullTime;
        }
    }
}
