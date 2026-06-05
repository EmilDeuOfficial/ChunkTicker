package de.emilo.chunkticker;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns mobs in registered chunks without requiring nearby players.
 *
 * HOSTILE (dark locations, block-light = 0):
 *   Overworld        – Zombie, Skeleton, Creeper, Spider, Witch, Enderman
 *   Crimson Forest   – Hoglin, Piglin
 *   Nether Wastes    – Zombified Piglin, Piglin, Wither Skeleton
 *   Soul Sand Valley – Skeleton, Wither Skeleton
 *   Basalt Deltas    – Magma Cube
 *   Warped Forest    – Enderman
 *   The End          – Enderman
 *
 * PASSIVE (lit surface locations):
 *   Default Overworld – Sheep, Pig, Cow, Chicken, Rabbit
 *   Plains/Meadow     – + Horse
 *   Savanna           – Horse, Llama, Cow, Sheep
 *   Cold/Snow         – Polar Bear, Rabbit, Sheep
 *   Jungle            – Chicken, Pig, Cow
 *   Desert/Badlands   – Rabbit, Chicken
 *   Swamp             – Frog, Chicken, Pig
 *   Mushroom Fields   – Mooshroom
 *   Beach             – Turtle, Chicken
 *
 * IRON GOLEMS: spawn through villager AI (panic/sleep) — not this task.
 *   Entity-ticking (Level 31) is active in all registered chunks, so
 *   iron golem farms work automatically without any extra code here.
 */
public class MobSpawnTask extends BukkitRunnable {

    private final ChunkTicker plugin;
    private final ChunkManager manager;
    private final int maxHostilePerChunk;
    private final int maxPassivePerChunk;

    public MobSpawnTask(ChunkTicker plugin, ChunkManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.maxHostilePerChunk  = plugin.getConfig().getInt("force-spawning.max-mobs-per-chunk",         6);
        this.maxPassivePerChunk  = plugin.getConfig().getInt("force-spawning.max-passive-mobs-per-chunk", 4);
    }

    // -------------------------------------------------------
    //  Main loop
    // -------------------------------------------------------

    @Override
    public void run() {
        if (!manager.isGlobalEnabled()) return;

        for (ChunkManager.ChunkEntry entry : manager.getRegisteredChunks()) {
            World world = Bukkit.getWorld(entry.worldName());
            if (world == null) continue;
            try {
                spawnInChunk(world, entry.x(), entry.z());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "MobSpawnTask Fehler [" + entry.worldName()
                                + " " + entry.x() + "/" + entry.z() + "]", e);
            }
        }
    }

    private void spawnInChunk(World world, int cx, int cz) {
        World.Environment env = world.getEnvironment();
        Biome biome = world.getBiome(cx * 16 + 8, 64, cz * 16 + 8);

        // Count existing mobs
        int hostiles = 0, passives = 0;
        for (Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (isHostile(e))       hostiles++;
            else if (e instanceof Animals) passives++;
        }

        // --- Hostile spawn ---
        if (hostiles < maxHostilePerChunk) {
            Location loc = findHostileLocation(world, cx, cz, env);
            if (loc != null) {
                EntityType type = pickHostileMob(biome, env);
                if (type != null)
                    world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
            }
        }

        // --- Passive spawn (Overworld only; Nether/End have no passive surface mobs) ---
        if (env == World.Environment.NORMAL && passives < maxPassivePerChunk) {
            Location loc = findSurfaceLocation(world, cx, cz);
            if (loc != null) {
                EntityType type = pickPassiveMob(biome);
                if (type != null)
                    world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
            }
        }
    }

    // -------------------------------------------------------
    //  Mob selection – hostile
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
                                                    EntityType.PIGLIN,
                                                    EntityType.WITHER_SKELETON);
            };
        }

        if (env == World.Environment.THE_END) return EntityType.ENDERMAN;

        return pick(rand, EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.CREEPER, EntityType.SPIDER,
                EntityType.WITCH,   EntityType.ENDERMAN);
    }

    // -------------------------------------------------------
    //  Mob selection – passive
    // -------------------------------------------------------

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
                                                                            EntityType.COW,   EntityType.SHEEP);
            case DESERT, BADLANDS,
                 WOODED_BADLANDS, ERODED_BADLANDS              -> pick(rand, EntityType.RABBIT, EntityType.CHICKEN);
            case JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE          -> pick(rand, EntityType.CHICKEN, EntityType.PIG, EntityType.COW);
            case FROZEN_OCEAN, COLD_OCEAN,
                 FROZEN_RIVER, FROZEN_PEAKS, JAGGED_PEAKS,
                 SNOWY_PLAINS, SNOWY_SLOPES, SNOWY_TAIGA       -> pick(rand, EntityType.POLAR_BEAR, EntityType.RABBIT, EntityType.SHEEP);
            case SWAMP, MANGROVE_SWAMP                         -> pick(rand, EntityType.FROG, EntityType.CHICKEN, EntityType.PIG);
            default                                            -> pick(rand, EntityType.SHEEP, EntityType.PIG,
                                                                            EntityType.COW,   EntityType.CHICKEN,
                                                                            EntityType.RABBIT);
        };
    }

    // -------------------------------------------------------
    //  Location finding
    // -------------------------------------------------------

    /** Dark underground location (block light = 0) for hostile mobs. */
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

    /** Lit surface location for passive mobs. */
    private Location findSurfaceLocation(World world, int cx, int cz) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < 8; attempt++) {
            int x = cx * 16 + rand.nextInt(16);
            int z = cz * 16 + rand.nextInt(16);
            int y = world.getHighestBlockYAt(x, z);

            Block floor = world.getBlockAt(x, y,     z);
            Block feet  = world.getBlockAt(x, y + 1, z);
            Block head  = world.getBlockAt(x, y + 2, z);

            if (!floor.getType().isSolid())      continue;
            if (feet.getType() != Material.AIR)  continue;
            if (head.getType() != Material.AIR)  continue;

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
