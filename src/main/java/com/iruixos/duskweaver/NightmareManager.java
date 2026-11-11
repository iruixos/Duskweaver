package com.iruixos.duskweaver;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional Nightmare encounters; all entity and world operations run on the primary server thread. */
final class NightmareManager implements Listener {
    private final DuskweaverPlugin plugin;
    private final NamespacedKey mobKey;
    private final NamespacedKey beamKey;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Integer> stages = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private final Map<UUID, World> pendingWorlds = new HashMap<>();
    private int startDelaySeconds;
    private boolean debug;
    private final Map<UUID, UUID> lastAttackers = new HashMap<>();
    private enum Outcome { VICTORY, DEFEAT, CANCELLED, TIMEOUT, ERROR }
    private void trace(String message) { if (debug) plugin.getLogger().info("[Nightmare debug] " + message); }
    private final File progressFile;
    private BukkitTask maintenance;
    private boolean enabled;
    private double chance;
    private long cooldownMillis;
    private int timeoutSeconds;
    private int radius, vertical;
    private BukkitTask beamTask;
    private boolean beamEnabled;
    private boolean spawnExplosionEnabled;
    private boolean outcomeBroadcastEnabled;
    private int beamHeight;
    private int beamIntervalTicks;
    private final Map<UUID, UUID> beamDisplays = new HashMap<>();
    private boolean normalBeds, strawBeds;
    private final Random random = new Random();

    private static final class Encounter {
        UUID mob;
        BossBar bar;
        long started;
        int tier;
        World world;
        boolean transitioning;
        Location origin;
        Encounter(UUID mob, BossBar bar, long started, int tier, World world) {
            this.mob = mob; this.bar = bar; this.started = started; this.tier = tier; this.world = world;
        }
        UUID mob() { return mob; }
        BossBar bar() { return bar; }
        long started() { return started; }
    }

    boolean hasActiveNightmare(World world) {
        if (!nightmaresAllowed(world)) return false;
        return pendingWorlds.containsValue(world) || encounters.values().stream().anyMatch(e -> e.world.equals(world));
    }

    /** Pending encounters block night skipping but must not reveal the victim. */
    boolean hasStartedNightmare(World world) {
        return encounters.values().stream().anyMatch(e -> e.world.equals(world));
    }

    String affectedPlayer(World world) {
        for (var entry : encounters.entrySet()) {
            if (entry.getValue().world.equals(world)) {
                Player p = Bukkit.getPlayer(entry.getKey());
                return p == null ? "A player" : p.getName();
            }
        }
        for (var entry : pendingWorlds.entrySet()) if (entry.getValue().equals(world)) {
            Player p = Bukkit.getPlayer(entry.getKey());
            return p == null ? "A player" : p.getName();
        }
        return "A player";
    }

    /** Easy and Peaceful disable the entire module, including world sleep locks. */
    private boolean nightmaresAllowed(World world) {
        Difficulty difficulty = world.getDifficulty();
        return enabled && plugin.isManagedWorld(world)
                && difficulty != Difficulty.EASY && difficulty != Difficulty.PEACEFUL;
    }

    private int rewardMultiplier(World world) {
        String key = switch (world.getDifficulty()) {
            case HARD -> "hard";
            case NORMAL -> "normal";
            default -> null;
        };
        if (key == null) return 0;
        String path = "nightmares.rewards.difficulty-multipliers." + key;
        if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return 1;
        return Math.max(0, Math.min(64, plugin.getConfig().getInt(path + ".multiplier",
                world.getDifficulty() == Difficulty.HARD ? 2 : 1)));
    }

    NightmareManager(DuskweaverPlugin plugin) {
        this.plugin = plugin;
        mobKey = new NamespacedKey(plugin, "nightmare_mob");
        beamKey = new NamespacedKey(plugin, "nightmare_beam");
        ownerKey = new NamespacedKey(plugin, "nightmare_owner");
        progressFile = new File(plugin.getDataFolder(), "nightmare-progress.yml");
        load();
        configure();
        recoverOrphans();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        maintenance = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Remove Nightmare entities that survived an unclean server termination.
     * Loaded chunks are scanned immediately; later chunks are cleaned when loaded.
     */
    private void recoverOrphans() {
        for (World world : Bukkit.getWorlds())
            for (Chunk chunk : world.getLoadedChunks())
                removeOrphans(chunk);
    }

    private void removeOrphans(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity.getPersistentDataContainer().has(mobKey, PersistentDataType.INTEGER)
                    || entity.getPersistentDataContainer().has(beamKey, PersistentDataType.BYTE)) {
                entity.remove();
                trace("Removed orphaned Nightmare entity " + entity.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // The active encounter entity must not be removed on an ordinary chunk load.
        for (Entity entity : event.getChunk().getEntities()) {
            boolean active = encounters.values().stream().anyMatch(e -> e.mob.equals(entity.getUniqueId()))
                    || beamDisplays.containsValue(entity.getUniqueId());
            if (!active && (entity.getPersistentDataContainer().has(mobKey, PersistentDataType.INTEGER)
                    || entity.getPersistentDataContainer().has(beamKey, PersistentDataType.BYTE)))
                entity.remove();
        }
    }

    void configure() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("nightmares.enabled", true);
        debug = cfg.getBoolean("nightmares.debug", false);
        validateConfiguration();
        chance = Math.max(0, Math.min(100, cfg.getDouble("nightmares.spawn-chance-percent", 5)));
        cooldownMillis = Math.max(0L, cfg.getLong("nightmares.cooldown-minutes", 120)) * 60000L;
        timeoutSeconds = Math.max(10, cfg.getInt("nightmares.encounter-timeout-seconds", 300));
        startDelaySeconds = Math.max(0, Math.min(30, cfg.getInt("nightmares.start-delay-seconds", 5)));
        radius = Math.max(2, Math.min(24, cfg.getInt("nightmares.search-radius", 12)));
        vertical = Math.max(1, Math.min(8, cfg.getInt("nightmares.vertical-search-range", 4)));
        normalBeds = cfg.getBoolean("nightmares.regular-beds", true);
        strawBeds = cfg.getBoolean("nightmares.straw-beds", true);
        beamEnabled = cfg.getBoolean("nightmares.visuals.beam.enabled", true);
        spawnExplosionEnabled = cfg.getBoolean("nightmares.visuals.spawn-explosion.enabled", true);
        outcomeBroadcastEnabled = cfg.getBoolean("nightmares.broadcasts.outcomes.enabled", true);
        beamHeight = Math.max(2, Math.min(32, cfg.getInt("nightmares.visuals.beam.height-blocks", 12)));
        beamIntervalTicks = Math.max(1, Math.min(40, cfg.getInt("nightmares.visuals.beam.interval-ticks", 5)));
        if (beamTask != null) beamTask.cancel();
        if (!beamEnabled) clearBeams();
        if (beamEnabled) beamTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderBeams,
                beamIntervalTicks, beamIntervalTicks);
        if (!enabled) {
            for (UUID id : new ArrayList<>(pending.keySet())) cancelPending(id);
            for (UUID id : new ArrayList<>(encounters.keySet())) finish(id, true, Outcome.CANCELLED);
        }
    }

    private void validateConfiguration() {
        var cfg = plugin.getConfig();
        for (int tier = 1; tier <= 3; tier++) {
            String path = "nightmares.tiers." + tier;
            double hp = cfg.getDouble(path + ".health", 30);
            if (!Double.isFinite(hp) || hp <= 0)
                plugin.getLogger().warning("Invalid Nightmare tier " + tier + " health; use a positive finite number.");
            String rewards = "nightmares.rewards.tiers." + tier;
            ConfigurationSection table = cfg.getConfigurationSection(rewards);
            if (table == null) continue;
            for (String material : table.getKeys(false)) {
                if (Material.matchMaterial(material) == null
                        || table.getLong(material + ".weight", 0) <= 0
                        || table.getLong(material + ".min", 1) < 1
                        || table.getLong(material + ".max", 1) < table.getLong(material + ".min", 1))
                    plugin.getLogger().warning("Invalid Nightmare reward entry: " + rewards + "." + material);
            }
        }
    }

    private boolean isBed(Block block) {
        String type = block.getType().name();
        if (type.contains("STRAW") && type.contains("BED")) return strawBeds;
        return normalBeds && type.endsWith("_BED");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        // Do not override vanilla bed spawn handling: it respects final cancellation,
        // dimension rules and the bed's obstruction state.
        if (!nightmaresAllowed(event.getPlayer().getWorld()) || !plugin.isManagedWorld(event.getPlayer().getWorld()) || !isBed(event.getBed())) return;
        if (hasActiveNightmare(event.getPlayer().getWorld())) {
            Player sleeper = event.getPlayer();
            if (hasStartedNightmare(sleeper.getWorld()))
                sleeper.sendMessage(prefix().append(Component.text(
                        affectedPlayer(sleeper.getWorld()) + " has been swallowed by the Nightmare! " +
                        "You must dispel the shadows tormenting them before the night can pass.", NamedTextColor.LIGHT_PURPLE)));
            event.setCancelled(true);
            return;
        }
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (hasActiveNightmare(player.getWorld()) || encounters.containsKey(id) || System.currentTimeMillis() < cooldowns.getOrDefault(id, 0L)) return;
        Block bed = event.getBed();
        Location spawn = findUnsafeSpawn(bed);
        if (spawn == null || random.nextDouble() * 100 >= chance || pending.containsKey(id)) return;
        pendingWorlds.put(id, player.getWorld());
        trace("Pending Nightmare for " + player.getName() + " at " + bed.getLocation());
        Bukkit.broadcast(prefix().append(Component.text(
                "Something doesn't seem quite right this evening...", NamedTextColor.RED)));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            World originalWorld = pendingWorlds.remove(id);
            if (originalWorld == null || !player.isOnline() || player.isDead()
                    || !nightmaresAllowed(player.getWorld())
                    || !player.getWorld().equals(originalWorld) || encounters.containsKey(id)
                    || hasActiveNightmare(originalWorld)
                    || System.currentTimeMillis() < cooldowns.getOrDefault(id, 0L)
                    || !bed.getType().name().endsWith("_BED") || !player.isSleeping()
                    || player.getLocation().distanceSquared(bed.getLocation()) > 16) {
                trace("Pending Nightmare cancelled for " + player.getName());
                return;
            }
            Location safeSpawn = validateSpawn(spawn) ? spawn : findUnsafeSpawn(bed);
            if (safeSpawn == null) {
                trace("Pending Nightmare has no safe spawn for " + player.getName());
                return;
            }
            begin(player, safeSpawn);
        }, startDelaySeconds * 20L + 1L);
        pending.put(id, task);
    }

    /** Conservative security approximation: only dark, spawnable locations with a direct unobstructed walk to bed qualify. */
    private Location findUnsafeSpawn(Block bed) {
        World world = bed.getWorld();
        Location base = bed.getLocation();
        if (!nightmaresAllowed(world)) return null;
        List<Location> options = new ArrayList<>();
        // Limit sampling to loaded chunks, and require a clear, ground-level approach to the bed.
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius || dx * dx + dz * dz < 9) continue;
            int x = bed.getX() + dx, z = bed.getZ() + dz;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = -vertical; dy <= vertical; dy++) {
                int y = bed.getY() + dy;
                if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;
                Block feet = world.getBlockAt(x, y, z);
                if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()
                        || !feet.getRelative(0, -1, 0).getType().isSolid()) continue;
                if (feet.getLightFromBlocks() > 0) continue;
                if (!clearApproach(feet, bed)) continue;
                options.add(feet.getLocation().add(0.5, 0, 0.5));
            }
        }
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    private boolean clearApproach(Block from, Block bed) {
        // Straight-line walkability is deliberately stricter than Minecraft pathfinding:
        // a solid wall, closed door, or a height discontinuity prevents an encounter.
        int steps = Math.max(Math.abs(from.getX() - bed.getX()), Math.abs(from.getZ() - bed.getZ()));
        if (steps < 1 || Math.abs(from.getY() - bed.getY()) > 1) return false;
        World w = from.getWorld();
        for (int i = 1; i < steps; i++) {
            int x = (int) Math.round(from.getX() + (bed.getX() - from.getX()) * (i / (double) steps));
            int z = (int) Math.round(from.getZ() + (bed.getZ() - from.getZ()) * (i / (double) steps));
            Block feet = w.getBlockAt(x, from.getY(), z);
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()
                    || !feet.getRelative(0, -1, 0).getType().isSolid()) return false;
        }
        return true;
    }

    private boolean validateSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block feet = loc.getBlock();
        return loc.getWorld().isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)
                && feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                && feet.getRelative(0, -1, 0).getType().isSolid()
                && feet.getType() != Material.LAVA && feet.getType() != Material.WATER;
    }

    private void begin(Player player, Location spawn) {
        UUID id = player.getUniqueId();
        int tier = 1;
        spawnWave(player, spawn, tier);
    }

    private void spawnWave(Player player, Location spawn, int tier) {
        UUID id = player.getUniqueId();
        EntityType type = chooseMonster(tier);
        if (!validateSpawn(spawn)) {
            Encounter previous = encounters.get(id);
            if (previous != null && previous.origin != null) {
                spawn = findUnsafeSpawn(previous.origin.getBlock());
            }
            if (!validateSpawn(spawn)) {
                trace("No valid spawn for tier " + tier + " owner " + id);
                failSpawn(player);
                return;
            }
        }
        LivingEntity mob;
        try {
            mob = (LivingEntity) spawn.getWorld().spawnEntity(spawn, type);
        } catch (Exception ex) {
            plugin.getLogger().warning("Nightmare stage " + tier + " spawn failed for " + player.getName()
                    + ": " + ex.getMessage());
            failSpawn(player);
            return;
        }
        if (!mob.isValid()) {
            plugin.getLogger().warning("Nightmare stage " + tier + " spawn produced an invalid entity for " + player.getName());
            failSpawn(player);
            return;
        }
        // Visual and audio only: no TNT, explosions, entity damage, knockback or block changes.
        if (spawnExplosionEnabled) {
            Location effectAt = mob.getLocation().clone().add(0, mob.getHeight() * 0.5, 0);
            effectAt.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, effectAt, 1);
            effectAt.getWorld().playSound(effectAt, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.0f);
        }
        mob.getPersistentDataContainer().set(mobKey, PersistentDataType.INTEGER, tier);
        mob.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, id.toString());
        double hp = plugin.getConfig().getDouble("nightmares.tiers." + tier + ".health", tier == 1 ? 30 : tier == 2 ? 50 : 60);
        if (mob.getAttribute(Attribute.MAX_HEALTH) != null) {
            mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(Math.max(1, hp));
            mob.setHealth(Math.max(1, hp));
        }
        if (tier >= 2 && mob instanceof Mob m) {
            EntityEquipment eq = m.getEquipment();
            String prefix = tier == 2 ? "IRON" : "DIAMOND";
            int protection = plugin.getConfig().getInt(
                    "nightmares.tiers." + tier + ".protection-level", tier == 2 ? 1 : 2);
            int thorns = plugin.getConfig().getInt(
                    "nightmares.tiers." + tier + ".thorns-level", tier == 3 ? 1 : 0);
            if (eq != null) {
                eq.setHelmet(armorPiece(Material.valueOf(prefix + "_HELMET"), protection, thorns));
                eq.setChestplate(armorPiece(Material.valueOf(prefix + "_CHESTPLATE"), protection, thorns));
                eq.setLeggings(armorPiece(Material.valueOf(prefix + "_LEGGINGS"), protection, thorns));
                eq.setBoots(armorPiece(Material.valueOf(prefix + "_BOOTS"), protection, thorns));
                if (tier == 3 && type == EntityType.HUSK) {
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                    eq.setItemInMainHandDropChance(0.0f);
                }
                eq.setHelmetDropChance(0); eq.setChestplateDropChance(0);
                eq.setLeggingsDropChance(0); eq.setBootsDropChance(0);
            }
        }
        if (mob instanceof Mob m) m.setTarget(player);
        BossBar bar = null;
        if (tier <= 2) {
            String name = plugin.getConfig().getString("nightmares.tiers." + tier + ".display-name", "Nightmare Shadow");
            mob.customName(Component.text(name == null || name.isBlank() ? "Nightmare Shadow" : name, NamedTextColor.DARK_PURPLE));
            mob.setCustomNameVisible(true);
        }
        if (tier == 3) {
            // Administrators can replace either pool independently. Empty lists fall back
            // to a safe generic entry, avoiding nextInt(0) and preserving boss-bar creation.
            List<String> names = plugin.getConfig().getStringList("nightmares.tiers.3.names").stream()
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
            List<String> titles = plugin.getConfig().getStringList("nightmares.tiers.3.titles").stream()
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
            if (names.isEmpty()) names = List.of("Nightmare Horror");
            if (titles.isEmpty()) titles = List.of("the Nameless");
            String name = names.get(random.nextInt(names.size())) + ", "
                    + titles.get(random.nextInt(titles.size()));
            mob.customName(Component.text(name, NamedTextColor.DARK_PURPLE));
            mob.setCustomNameVisible(true);
            if (plugin.getConfig().getBoolean("nightmares.tiers.3.boss-bar.enabled", true)) {
                bar = Bukkit.createBossBar(name, BarColor.PURPLE, BarStyle.SOLID);
                bar.addPlayer(player);
            }
        }
        removeBeam(id);
        Encounter encounter = encounters.get(id);
        if (encounter != null) lastAttackers.remove(encounter.mob);
        if (encounter == null) {
            Encounter created = new Encounter(mob.getUniqueId(), bar, System.currentTimeMillis(), tier, player.getWorld());
            created.origin = spawn.clone();
            encounters.put(id, created);
        } else {
            if (encounter.bar != null) encounter.bar.removeAll();
            encounter.mob = mob.getUniqueId();
            encounter.bar = bar;
            encounter.tier = tier;
            // Timeout is measured across the entire encounter, not per wave.
            encounter.transitioning = false;
        }
        // Wake the player after the sleep transition, before the countdown can complete.
        if (player.isSleeping()) player.wakeup(false);
        if (tier == 1) {
            Bukkit.broadcast(prefix().append(Component.text(
                    player.getName() + " has been swallowed by the Nightmare! " +
                    "You must dispel the shadows tormenting them before the night can pass.",
                    NamedTextColor.LIGHT_PURPLE)));
            player.sendMessage(prefix().append(Component.text("The Nightmare has begun...", NamedTextColor.DARK_PURPLE)));
        }
        else player.sendMessage(prefix().append(Component.text("The Nightmare deepens...", NamedTextColor.DARK_PURPLE)));
        String omen = switch (tier) {
            case 1 -> "A sinister presence stirs beyond your dreams.";
            case 2 -> "The shadows gather, and your restless slumber grows darker.";
            default -> "The veil between nightmare and reality begins to shatter.";
        };
        player.sendMessage(Component.text(omen + " (Stage " + tier + " of 3)", NamedTextColor.LIGHT_PURPLE));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.6f);
    }

    private void failSpawn(Player player) {
        if (encounters.containsKey(player.getUniqueId())) finish(player.getUniqueId(), true, Outcome.ERROR);
        player.sendMessage(prefix().append(Component.text(
                "The Nightmare fades unexpectedly. You may rest again.", NamedTextColor.GRAY)));
    }

    private ItemStack armorPiece(Material material, int protection, int thorns) {
        ItemStack item = new ItemStack(material);
        if (protection > 0) item.addUnsafeEnchantment(Enchantment.PROTECTION, protection);
        if (thorns > 0) item.addEnchantment(Enchantment.THORNS, thorns);
        return item;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        Integer tier = mob.getPersistentDataContainer().get(mobKey, PersistentDataType.INTEGER);
        String owner = mob.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (tier == null || owner == null) return;
        UUID id;
        try { id = UUID.fromString(owner); } catch (IllegalArgumentException ex) { return; }
        event.getDrops().clear(); // Prevent farming Nightmare equipment or normal drops.
        event.setDroppedExp(0);
        Encounter encounter = encounters.get(id);
        if (encounter == null || !encounter.mob().equals(mob.getUniqueId())) return;
        Player killer = mob.getKiller();
        UUID last = lastAttackers.remove(mob.getUniqueId());
        boolean success = (killer != null && killer.getUniqueId().equals(id))
                || id.equals(last);
        Player recipient = Bukkit.getPlayer(id);
        if (success) {
            if (recipient != null) award(recipient, tier, mob.getLocation());
            if (tier == 3) {
                if (recipient != null) recipient.sendMessage(prefix().append(Component.text(
                        "You've awakened from the Nightmare!", NamedTextColor.LIGHT_PURPLE)));
                if (recipient != null) recipient.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("You've escaped the Nightmare", NamedTextColor.GOLD),
                        Component.text("...this time.", NamedTextColor.LIGHT_PURPLE),
                        net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(500),
                                java.time.Duration.ofSeconds(4),
                                java.time.Duration.ofSeconds(1))));
                if (recipient != null) recipient.playSound(recipient.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
            if (tier < 3) {
                // Keep the encounter registered (and sleep locked) between waves.
                // Spawn next wave on the next tick, after the death event completes.
                // Maintain the world's sleep lock while the next stage is being prepared.
                encounter.transitioning = true;
                Location nextSpawn = mob.getLocation().clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Encounter active = encounters.get(id);
                    if (active == null || !active.mob.equals(mob.getUniqueId())) return;
                    Player ownerPlayer = Bukkit.getPlayer(id);
                    if (ownerPlayer == null || !ownerPlayer.isValid()) {
                        finish(id, false, Outcome.CANCELLED); return;
                    }
                    spawnWave(ownerPlayer, nextSpawn, tier + 1);
                });
                return;
            }
        }
        finish(id, false, success && tier == 3 ? Outcome.VICTORY : Outcome.DEFEAT);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNightmareDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob)
                || !mob.getPersistentDataContainer().has(mobKey, PersistentDataType.INTEGER)) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter)
            attacker = shooter;
        if (attacker instanceof Tameable tame && tame.getOwner() instanceof Player owner)
            attacker = owner;
        if (attacker instanceof Player player)
            lastAttackers.put(mob.getUniqueId(), player.getUniqueId());
    }

    private void award(Player player, int tier, Location at) {
        if (!plugin.getConfig().getBoolean("nightmares.rewards.enabled", true)) {
            plugin.getLogger().info("Nightmare rewards disabled by configuration for " + player.getName());
            return;
        }
        int multiplier = rewardMultiplier(at.getWorld());
        if (multiplier <= 0) {
            plugin.getLogger().warning("Nightmare rewards skipped: difficulty multiplier is 0 for " + at.getWorld().getName());
            return;
        }
        String tablePath = "nightmares.rewards.tiers." + tier;
        ConfigurationSection table = plugin.getConfig().getConfigurationSection(tablePath);
        if (table == null || table.getKeys(false).isEmpty()) {
            // Existing installations may have a pre-rewards config.yml; use the bundled
            // loot table instead of silently awarding nothing.
            try (var stream = plugin.getResource("config.yml")) {
                if (stream != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(stream, StandardCharsets.UTF_8));
                    table = defaults.getConfigurationSection(tablePath);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Unable to load bundled Nightmare rewards: " + ex.getMessage());
            }
            plugin.getLogger().warning("Nightmare reward table " + tablePath
                    + " missing or empty in active config; using bundled defaults.");
        }
        if (table == null) {
            plugin.getLogger().severe("Nightmare rewards unavailable: missing " + tablePath);
            player.sendMessage(prefix().append(Component.text(
                    "Nightmare rewards could not be loaded; please contact an administrator.", NamedTextColor.RED)));
            return;
        }
        // Weighted selection without replacement: each material can be awarded at most once.
        Map<String, Integer> available = new LinkedHashMap<>();
        for (String key : table.getKeys(false)) {
            if (Material.matchMaterial(key) == null) continue;
            int weight = Math.max(0, table.getInt(key + ".weight", 0));
            if (weight > 0) available.put(key, weight);
        }
        int requested = Math.max(0, plugin.getConfig().getInt(
                "nightmares.rewards.rolls-per-tier." + tier, tier));
        if (available.isEmpty()) {
            plugin.getLogger().warning("Nightmare reward table " + tablePath
                    + " has no valid weighted items. Check material names and weights.");
            player.sendMessage(prefix().append(Component.text(
                    "Nightmare rewards are misconfigured; please contact an administrator.", NamedTextColor.RED)));
            return;
        }
        if (requested == 0) {
            plugin.getLogger().warning("Nightmare reward rolls set to zero for tier " + tier);
        }
        for (int n = 0; n < requested && !available.isEmpty(); n++) {
            long total = available.values().stream().mapToLong(Integer::longValue).sum();
            if (total <= 0) break;
            long roll = random.nextLong(total);
            String selected = null;
            for (var entry : available.entrySet()) {
                roll -= entry.getValue();
                if (roll < 0) { selected = entry.getKey(); break; }
            }
            if (selected == null) break;
            available.remove(selected);
            int min = Math.max(1, table.getInt(selected + ".min", 1));
            int max = Math.max(min, table.getInt(selected + ".max", min));
            int amount = min + random.nextInt(Math.min(10000, max - min + 1));
            // Existing reward quantity behaviour is unchanged in this reliability release.
            amount = (int) Math.min(100000L, (long) amount * multiplier);
            int awarded = amount;
            Material material = Material.matchMaterial(selected);
            while (amount > 0) {
                int stack = Math.min(amount, material.getMaxStackSize());
                // Deliver to inventory first; drop only portions that do not fit.
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, stack));
                for (ItemStack leftover : overflow.values()) {
                    if (leftover != null && leftover.getAmount() > 0)
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                amount -= stack;
            }
            player.sendMessage(prefix().append(Component.text(
                    "Nightmare reward: " + selected + " x" + awarded, NamedTextColor.GOLD)));
        }
    }

    /** A narrow luminous glass BlockDisplay approximates a continuous beacon shaft.
     * No real blocks are placed or modified. The display teleports with its mob. */
    private void renderBeams() {
        Set<UUID> active = new HashSet<>();
        for (var entry : encounters.entrySet()) {
            UUID owner = entry.getKey();
            Encounter encounter = entry.getValue();
            if (encounter.transitioning) { removeBeam(owner); continue; }
            Entity entity = Bukkit.getEntity(encounter.mob);
            if (!(entity instanceof LivingEntity mob) || !mob.isValid() || mob.isDead()) {
                removeBeam(owner); continue;
            }
            active.add(owner);
            Location loc = mob.getLocation().add(0, mob.getHeight() + 0.15, 0);
            UUID displayId = beamDisplays.get(owner);
            Entity existing = displayId == null ? null : Bukkit.getEntity(displayId);
            BlockDisplay display;
            if (existing instanceof BlockDisplay blockDisplay && existing.isValid()) {
                display = blockDisplay;
            } else {
                removeBeam(owner);
                display = loc.getWorld().spawn(loc, BlockDisplay.class, d -> {
                    d.getPersistentDataContainer().set(beamKey, PersistentDataType.BYTE, (byte) 1);
                    d.setBlock(Bukkit.createBlockData(Material.LIGHT_BLUE_STAINED_GLASS));
                    d.setTransformation(new Transformation(
                            new Vector3f(-0.16f, 0f, -0.16f), new Quaternionf(),
                            new Vector3f(0.32f, beamHeight, 0.32f), new Quaternionf()));
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(1.5f);
                    d.setPersistent(false);
                    d.setInterpolationDuration(Math.max(1, beamIntervalTicks));
                    d.setTeleportDuration(Math.min(beamIntervalTicks, 59));
                });
                beamDisplays.put(owner, display.getUniqueId());
            }
            display.teleport(loc);
        }
        for (UUID owner : new HashSet<>(beamDisplays.keySet())) {
            if (!active.contains(owner)) removeBeam(owner);
        }
    }

    private void removeBeam(UUID owner) {
        UUID id = beamDisplays.remove(owner);
        if (id != null) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    private void clearBeams() {
        for (UUID owner : new HashSet<>(beamDisplays.keySet())) removeBeam(owner);
    }

    /** Reject non-hostile and unsupported entity types, with a safe default per tier. */
    private static final Set<EntityType> SUPPORTED_MONSTERS = EnumSet.of(
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.HUSK, EntityType.WITHER_SKELETON, EntityType.STRAY,
            EntityType.BOGGED, EntityType.DROWNED, EntityType.CAVE_SPIDER,
            EntityType.ZOMBIE_VILLAGER);

    private EntityType chooseMonster(int tier) {
        String path = "nightmares.tiers." + tier + ".monsters";
        List<String> configured = plugin.getConfig().getStringList(path);
        if (configured.isEmpty()) configured = tier == 1
                ? List.of("ZOMBIE", "SKELETON", "SPIDER")
                : tier == 2 ? List.of("ZOMBIE", "SKELETON") : List.of("WITHER_SKELETON", "HUSK");
        List<EntityType> allowed = new ArrayList<>();
        for (String value : configured) {
            try {
                EntityType type = EntityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
                Class<? extends Entity> entityClass = type.getEntityClass();
                if (SUPPORTED_MONSTERS.contains(type) && type.isSpawnable()
                        && entityClass != null && Monster.class.isAssignableFrom(entityClass)) {
                    allowed.add(type);
                } else plugin.getLogger().warning("Invalid Nightmare monster for tier " + tier + ": " + value);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown Nightmare monster for tier " + tier + ": " + value);
            }
        }
        if (allowed.isEmpty()) {
            plugin.getLogger().warning("No valid monsters configured for Nightmare tier " + tier + "; using ZOMBIE.");
            return EntityType.ZOMBIE;
        }
        return allowed.get(random.nextInt(allowed.size()));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        cancelPending(event.getEntity().getUniqueId());
        if (encounters.containsKey(event.getEntity().getUniqueId()))
            finish(event.getEntity().getUniqueId(), true, Outcome.DEFEAT);
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent event) {
        cancelPending(event.getPlayer().getUniqueId());
    }

    private void cancelPending(UUID id) {
        BukkitTask task = pending.remove(id);
        pendingWorlds.remove(id);
        if (task != null) task.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelPending(event.getPlayer().getUniqueId());
        if (encounters.containsKey(event.getPlayer().getUniqueId()))
            finish(event.getPlayer().getUniqueId(), true, Outcome.CANCELLED);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && (!event.getFrom().getWorld().equals(event.getTo().getWorld())
                || event.getFrom().distanceSquared(event.getTo()) > 16))
            cancelPending(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        cancelPending(event.getPlayer().getUniqueId());
        if (encounters.containsKey(event.getPlayer().getUniqueId()))
            finish(event.getPlayer().getUniqueId(), true, Outcome.CANCELLED);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        String owner = event.getEntity().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner == null || event.getTarget() == null) return;
        if (!(event.getTarget() instanceof Player p) || !p.getUniqueId().toString().equals(owner)) event.setCancelled(true);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(pending.keySet())) {
            World world = pendingWorlds.get(id);
            if (world == null || !nightmaresAllowed(world)) cancelPending(id);
        }
        for (UUID id : new ArrayList<>(encounters.keySet())) {
            Encounter encounter = encounters.get(id);
            if (encounter == null) continue;
            if (!nightmaresAllowed(encounter.world)) {
                finish(id, true, Outcome.CANCELLED);
                continue;
            }
            Entity entity = Bukkit.getEntity(encounter.mob());
            if (now - encounter.started() >= timeoutSeconds * 1000L) {
                finish(id, true, Outcome.TIMEOUT); continue;
            }
            // The one-tick transition between stages must remain active and keep sleep locked.
            if (encounter.transitioning) continue;
            if (!(entity instanceof LivingEntity mob) || !mob.isValid() || mob.isDead()) {
                // Do not leave the world indefinitely locked if an entity was removed
                // by another plugin or an unexpected event.
                finish(id, false, Outcome.ERROR);
                continue;
            }
            if (encounter.bar() != null) {
                double max = mob.getAttribute(Attribute.MAX_HEALTH) == null ? 1 : mob.getAttribute(Attribute.MAX_HEALTH).getValue();
                encounter.bar().setProgress(Math.max(0, Math.min(1, mob.getHealth() / max)));
            }
        }
    }

    private void finish(UUID id, boolean removeMob) {
        finish(id, removeMob, Outcome.CANCELLED);
    }

    private void finish(UUID id, boolean removeMob, Outcome outcome) {
        Encounter encounter = encounters.remove(id);
        if (encounter == null) return;
        trace("Finished Nightmare for " + id + ": " + outcome);
        if (outcomeBroadcastEnabled && (outcome == Outcome.VICTORY || outcome == Outcome.DEFEAT)) {
            Player player = Bukkit.getPlayer(id);
            String name = player != null ? player.getName() : Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = "A player";
            String message = outcome == Outcome.VICTORY
                    ? name + " has conquered the Nightmare and escaped the shadows!"
                    : name + " has succumbed to the Nightmare. The shadows claim another victim...";
            Bukkit.broadcast(prefix().append(Component.text(message,
                    outcome == Outcome.VICTORY ? NamedTextColor.GREEN : NamedTextColor.RED)));
        }
        if (encounter.bar() != null) encounter.bar().removeAll();
        removeBeam(id);
        lastAttackers.remove(encounter.mob());
        if (removeMob) {
            Entity entity = Bukkit.getEntity(encounter.mob());
            if (entity != null) entity.remove();
        }
        if (outcome == Outcome.VICTORY || outcome == Outcome.DEFEAT || outcome == Outcome.TIMEOUT)
            cooldowns.put(id, System.currentTimeMillis() + cooldownMillis);
        stages.put(id, 1);
        save();
    }

    private Component prefix() {
        return Component.text("[", NamedTextColor.DARK_PURPLE)
                .append(Component.text("Duskweaver", NamedTextColor.GOLD))
                .append(Component.text("] ", NamedTextColor.DARK_PURPLE));
    }

    private void load() {
        if (!progressFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(progressFile);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) try {
            UUID id = UUID.fromString(key);
            stages.put(id, Math.max(1, Math.min(3, section.getInt(key + ".stage", 1))));
            cooldowns.put(id, section.getLong(key + ".cooldown-until", 0));
        } catch (IllegalArgumentException ignored) { }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        Set<UUID> ids = new HashSet<>(stages.keySet()); ids.addAll(cooldowns.keySet());
        for (UUID id : ids) {
            yaml.set("players." + id + ".stage", stages.getOrDefault(id, 1));
            yaml.set("players." + id + ".cooldown-until", cooldowns.getOrDefault(id, 0L));
        }
        try { yaml.save(progressFile); }
        catch (IOException ex) { plugin.getLogger().severe("Could not save Nightmare progress: " + ex.getMessage()); }
    }

    void shutdown() {
        outcomeBroadcastEnabled = false;
        for (UUID id : new ArrayList<>(pending.keySet())) cancelPending(id);
        if (maintenance != null) maintenance.cancel();
        if (beamTask != null) beamTask.cancel();
        clearBeams();
        for (UUID id : new ArrayList<>(encounters.keySet())) finish(id, true);
        save();
    }
}
