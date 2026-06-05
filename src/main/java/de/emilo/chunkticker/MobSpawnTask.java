package de.emilo.chunkticker;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns mobs in registered chunks using only the Bukkit API.
 *
 * Mob selection is biome-aware:
 *   Overworld   – ZOMBIE, SKELETON, CREEPER, SPIDER, WITCH, ENDERMAN
 *   Crimson Forest  – HOGLIN, PIGLIN
 *   Nether Wastes   – ZOMBIFIED_PIGLIN, WITHER_SKELETON, PIGLIN
 *   Soul Sand Valley – SKELETON, GHAST (open-air), WITHER_SKELETON
 *   Basalt Deltas   – MAGMA_CUBE
 *   Warped Forest   – ENDERMAN
 *   The End         – ENDERMAN, SHULKER (end highlands)
 *
 * Iron golems are NOT spawned here — they spawn through villager AI
 * (panic / sleep cycles), which already works because ChunkTicker keeps
 * the chunk entity-ticking. No extra code needed for iron golem farms.
 */
public class MobSpawnTask extends BukkitRunnable {

    private final ChunkTicker plugin;
    private final ChunkManager manager;
    private final int maxMobsPerChunk;

    public MobSpawnTask(ChunkTicker plugin, ChunkManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.maxMobsPerChunk = plugin.getConfig().getInt("force-spawning.max-mobs-per-chunk", 6);
    }

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
        Chunk chunk = world.getChunkAt(cx, cz);

        int monsters = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Monster || e instanceof Hoglin || e instanceof Piglin) monsters++;
        }
        if (monsters >= maxMobsPerChunk) return;

        Biome biome = world.getBiome(cx * 16 + 8, 64, cz * 16 + 8);
        boolean darkSpawn = requiresDarkness(biome, world.getEnvironment());

        Location loc = findSpawnLocation(world, cx, cz, darkSpawn);
        if (loc == null) return;

        EntityType type = pickMobType(biome, world.getEnvironment());
        if (type == null) return;

        world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
    }

    // -------------------------------------------------------
    //  Mob selection
    // -------------------------------------------------------

    private EntityType pickMobType(Biome biome, World.Environment env) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        // Nether biomes
        if (env == World.Environment.NETHER) {
            return switch (biome) {
                case CRIMSON_FOREST   -> rand.nextBoolean() ? EntityType.HOGLIN : EntityType.PIGLIN;
                case SOUL_SAND_VALLEY -> pick(rand, EntityType.SKELETON, EntityType.WITHER_SKELETON);
                case BASALT_DELTAS    -> EntityType.MAGMA_CUBE;
                case WARPED_FOREST    -> EntityType.ENDERMAN;
                default               -> pick(rand, EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN, EntityType.WITHER_SKELETON);
            };
        }

        // End
        if (env == World.Environment.THE_END) {
            return EntityType.ENDERMAN;
        }

        // Overworld – default hostile mobs
        return pick(rand, EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.CREEPER, EntityType.SPIDER, EntityType.WITCH, EntityType.ENDERMAN);
    }

    /** Dark spawning required for overworld hostile mobs (light level 0). */
    private boolean requiresDarkness(Biome biome, World.Environment env) {
        return env == World.Environment.NORMAL;
    }

    // -------------------------------------------------------
    //  Location finding
    // -------------------------------------------------------

    /**
     * Finds a valid spawn location in the chunk.
     *
     * darkSpawn=true  → block light must be 0 (overworld hostile mobs)
     * darkSpawn=false → any light level, just needs solid floor + 2 air (nether/end)
     */
    private Location findSpawnLocation(World world, int cx, int cz, boolean darkSpawn) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        int minY  = world.getMinHeight() + 2;
        int maxY  = world.getMaxHeight() - 3;

        for (int attempt = 0; attempt < 16; attempt++) {
            int x = cx * 16 + rand.nextInt(16);
            int z = cz * 16 + rand.nextInt(16);

            int startY;
            if (darkSpawn) {
                // Search underground (below surface) for darkness
                startY = Math.min(Math.max(world.getHighestBlockYAt(x, z) - 1, minY), maxY);
            } else {
                // Nether / End: search from a mid-height down
                startY = Math.min(maxY, 100);
            }

            for (int y = startY; y >= minY; y--) {
                Block floor  = world.getBlockAt(x, y - 1, z);
                Block feet   = world.getBlockAt(x, y,     z);
                Block head   = world.getBlockAt(x, y + 1, z);
                Block above2 = world.getBlockAt(x, y + 2, z);

                if (!floor.getType().isSolid())         continue;
                if (feet.getType()   != Material.AIR)   continue;
                if (head.getType()   != Material.AIR)   continue;
                if (above2.getType() != Material.AIR)   continue;
                if (darkSpawn && feet.getLightFromBlocks() > 0) continue;

                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    // -------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------

    @SafeVarargs
    private static <T> T pick(ThreadLocalRandom rand, T... options) {
        return options[rand.nextInt(options.length)];
    }
}
