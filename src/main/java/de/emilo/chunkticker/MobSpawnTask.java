package de.emilo.chunkticker;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns mobs in registered chunks without requiring nearby players.
 *
 * Why not NaturalSpawner (NMS) directly?
 *   paper-mojangapi (compile-time NMS access) is not published to Paper's
 *   Maven repo for 1.21. Pure reflection works but has the same version-
 *   fragility we already debugged. The Bukkit approach below is stable
 *   across all 1.21.x builds.
 *
 * Rate control:
 *   1. Global hostile + passive cap across ALL registered chunks at once.
 *      Animals that wander between chunks still count — prevents overflow.
 *   2. Per-chunk secondary cap.
 *   3. Random spawn-chance per chunk per cycle (vanilla feel).
 *   4. Biome exclusions:
 *        MUSHROOM_FIELDS — no hostile mobs (vanilla)
 *        DEEP_DARK       — no mobs at all; Warden is summoned, not spawned
 *   5. Biome checked at the actual spawn Y-coordinate, not at Y=64, so
 *      Ancient Cities (Deep Dark, ~Y=-51) are correctly excluded even when
 *      the surface biome is something else.
 */
public class MobSpawnTask extends BukkitRunnable {

    private final ChunkTicker plugin;
    private final ChunkManager manager;

    private final double spawnChance;
    private final int globalHostileCap;
    private final int globalPassiveCap;
    private final int maxHostilePerChunk;
    private final int maxPassivePerChunk;

    public MobSpawnTask(ChunkTicker plugin, ChunkManager manager) {
        this.plugin             = plugin;
        this.manager            = manager;
        this.spawnChance        = plugin.getConfig().getDouble("force-spawning.spawn-chance",          0.3);
        this.globalHostileCap   = plugin.getConfig().getInt   ("force-spawning.global-hostile-cap",    15);
        this.globalPassiveCap   = plugin.getConfig().getInt   ("force-spawning.global-passive-cap",    12);
        this.maxHostilePerChunk = plugin.getConfig().getInt   ("force-spawning.max-hostile-per-chunk",  3);
        this.maxPassivePerChunk = plugin.getConfig().getInt   ("force-spawning.max-passive-per-chunk",  2);
    }

    // -------------------------------------------------------
    //  Main loop
    // -------------------------------------------------------

    @Override
    public void run() {
        if (!manager.isGlobalEnabled()) return;

        List<ChunkManager.ChunkEntry> entries = new ArrayList<>(manager.getRegisteredChunks());
        if (entries.isEmpty()) return;

        // Count ALL mobs across ALL registered chunks once per cycle.
        // Animals wander between chunks, so a local-only count would
        // trigger re-spawning every time a mob leaves its origin chunk.
        int totalHostile = 0, totalPassive = 0;
        for (ChunkManager.ChunkEntry entry : entries) {
            World w = Bukkit.getWorld(entry.worldName());
            if (w == null) continue;
            for (Entity e : w.getChunkAt(entry.x(), entry.z()).getEntities()) {
                if (isHostile(e))            totalHostile++;
                else if (e instanceof Animals) totalPassive++;
            }
        }

        boolean hostileFull = totalHostile >= globalHostileCap;
        boolean passiveFull = totalPassive >= globalPassiveCap;
        if (hostileFull && passiveFull) return;

        Collections.shuffle(entries);
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        for (ChunkManager.ChunkEntry entry : entries) {
            if (rand.nextDouble() >= spawnChance) continue;

            World world = Bukkit.getWorld(entry.worldName());
            if (world == null) continue;

            try {
                int[] delta = spawnInChunk(world, entry.x(), entry.z(), hostileFull, passiveFull);
                totalHostile += delta[0];
                totalPassive += delta[1];
                hostileFull   = totalHostile >= globalHostileCap;
                passiveFull   = totalPassive >= globalPassiveCap;
                if (hostileFull && passiveFull) break;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "MobSpawnTask error [" + entry.worldName()
                                + " " + entry.x() + "/" + entry.z() + "]", e);
            }
        }
    }

    private int[] spawnInChunk(World world, int cx, int cz,
                                boolean globalHostileFull, boolean globalPassiveFull) {
        World.Environment env = world.getEnvironment();
        int hostileSpawned = 0, passiveSpawned = 0;

        int chunkHostile = 0, chunkPassive = 0;
        for (Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (isHostile(e))            chunkHostile++;
            else if (e instanceof Animals) chunkPassive++;
        }

        // Hostile — biome is read at the ACTUAL spawn Y, not at Y=64.
        // This correctly handles Deep Dark (Ancient City, ~Y=-51).
        if (!globalHostileFull && chunkHostile < maxHostilePerChunk) {
            Location loc = findHostileLocation(world, cx, cz, env);
            if (loc != null) {
                Biome spawnBiome = world.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                if (allowsHostileMobs(spawnBiome)) {
                    EntityType type = pickHostileMob(spawnBiome, env);
                    if (type != null) {
                        world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
                        hostileSpawned = 1;
                    }
                }
            }
        }

        // Passive (Overworld only)
        if (!globalPassiveFull && chunkPassive < maxPassivePerChunk
                && env == World.Environment.NORMAL) {
            Location loc = findSurfaceLocation(world, cx, cz);
            if (loc != null) {
                Biome surfaceBiome = world.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                EntityType type = pickPassiveMob(surfaceBiome);
                if (type != null) {
                    world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
                    passiveSpawned = 1;
                }
            }
        }

        return new int[]{hostileSpawned, passiveSpawned};
    }

    // -------------------------------------------------------
    //  Biome rules
    // -------------------------------------------------------

    private static boolean allowsHostileMobs(Biome biome) {
        return biome != Biome.MUSHROOM_FIELDS && biome != Biome.DEEP_DARK;
    }

    // -------------------------------------------------------
    //  Mob selection
    // -------------------------------------------------------

    private EntityType pickHostileMob(Biome biome, World.Environment env) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        if (env == World.Environment.NETHER) {
            return switch (biome) {
                case CRIMSON_FOREST   -> rand.nextBoolean() ? EntityType.HOGLIN : EntityType.PIGLIN;
                case SOUL_SAND_VALLEY -> pick(rand, EntityType.SKELETON, EntityType.WITHER_SKELETON);
                case BASALT_DELTAS    -> EntityType.MAGMA_CUBE;
                case WARPED_FOREST    -> EntityType.ENDERMAN;
                default               -> pick(rand, EntityType.ZOMBIFIED_PIGLIN,
                                                    EntityType.PIGLIN, EntityType.WITHER_SKELETON);
            };
        }
        if (env == World.Environment.THE_END) return EntityType.ENDERMAN;
        return pick(rand, EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.CREEPER, EntityType.SPIDER, EntityType.WITCH, EntityType.ENDERMAN);
    }

    private EntityType pickPassiveMob(Biome biome) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        return switch (biome) {
            case MUSHROOM_FIELDS                               -> EntityType.MOOSHROOM;
            case BEACH, STONY_SHORE                            -> pick(rand, EntityType.TURTLE, EntityType.CHICKEN);
            case SNOWY_BEACH                                   -> pick(rand, EntityType.RABBIT, EntityType.CHICKEN);
            case PLAINS, SUNFLOWER_PLAINS, MEADOW, CHERRY_GROVE
                                                               -> pick(rand, EntityType.SHEEP, EntityType.COW,
                                                                            EntityType.HORSE, EntityType.PIG,
                                                                            EntityType.CHICKEN);
            case SAVANNA, SAVANNA_PLATEAU, WINDSWEPT_SAVANNA   -> pick(rand, EntityType.HORSE, EntityType.LLAMA,
                                                                            EntityType.COW, EntityType.SHEEP);
            case DESERT, BADLANDS,
                 WOODED_BADLANDS, ERODED_BADLANDS              -> pick(rand, EntityType.RABBIT, EntityType.CHICKEN);
            case JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE          -> pick(rand, EntityType.CHICKEN,
                                                                            EntityType.PIG, EntityType.COW);
            case FROZEN_OCEAN, COLD_OCEAN, FROZEN_RIVER,
                 FROZEN_PEAKS, JAGGED_PEAKS, SNOWY_PLAINS,
                 SNOWY_SLOPES, SNOWY_TAIGA                     -> pick(rand, EntityType.POLAR_BEAR,
                                                                            EntityType.RABBIT, EntityType.SHEEP);
            case SWAMP, MANGROVE_SWAMP                         -> pick(rand, EntityType.FROG,
                                                                            EntityType.CHICKEN, EntityType.PIG);
            default                                            -> pick(rand, EntityType.SHEEP, EntityType.PIG,
                                                                            EntityType.COW, EntityType.CHICKEN,
                                                                            EntityType.RABBIT);
        };
    }

    // -------------------------------------------------------
    //  Location finders
    // -------------------------------------------------------

    private Location findHostileLocation(World world, int cx, int cz, World.Environment env) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        int minY = world.getMinHeight() + 2;
        int maxY = world.getMaxHeight() - 3;
        boolean requireDark = (env == World.Environment.NORMAL);

        for (int attempt = 0; attempt < 16; attempt++) {
            int x = cx * 16 + rand.nextInt(16);
            int z = cz * 16 + rand.nextInt(16);
            int startY = requireDark
                    ? Math.min(Math.max(world.getHighestBlockYAt(x, z) - 1, minY), maxY)
                    : Math.min(maxY, 100);

            for (int y = startY; y >= minY; y--) {
                Block floor  = world.getBlockAt(x, y - 1, z);
                Block feet   = world.getBlockAt(x, y,     z);
                Block head   = world.getBlockAt(x, y + 1, z);
                Block above2 = world.getBlockAt(x, y + 2, z);
                if (!floor.getType().isSolid())       continue;
                if (feet.getType()   != Material.AIR) continue;
                if (head.getType()   != Material.AIR) continue;
                if (above2.getType() != Material.AIR) continue;
                if (requireDark && feet.getLightFromBlocks() > 0) continue;
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private Location findSurfaceLocation(World world, int cx, int cz) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = cx * 16 + rand.nextInt(16);
            int z = cz * 16 + rand.nextInt(16);
            int y = world.getHighestBlockYAt(x, z);
            Block floor = world.getBlockAt(x, y,     z);
            Block feet  = world.getBlockAt(x, y + 1, z);
            Block head  = world.getBlockAt(x, y + 2, z);
            if (!floor.getType().isSolid())     continue;
            if (feet.getType() != Material.AIR) continue;
            if (head.getType() != Material.AIR) continue;
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    // -------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------

    private static boolean isHostile(Entity e) {
        return e instanceof Monster || e instanceof Hoglin || e instanceof Piglin;
    }

    @SafeVarargs
    private static <T> T pick(ThreadLocalRandom rand, T... options) {
        return options[rand.nextInt(options.length)];
    }
}
