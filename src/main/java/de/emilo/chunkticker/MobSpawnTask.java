package de.emilo.chunkticker;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns mobs in registered chunks using only the Bukkit API.
 * No NMS / reflection required — works on all Paper 1.21.x builds.
 *
 * Per spawn cycle (every interval-ticks):
 *  - Checks per-chunk hostile entity count against max-mobs-per-chunk
 *  - Searches for a valid dark location (block light = 0, solid floor, 3 air above)
 *  - Spawns a biome-appropriate hostile mob with SpawnReason.NATURAL
 *  - Respects Paper's built-in spawn event / mob-cap pipeline
 */
public class MobSpawnTask extends BukkitRunnable {

    private static final EntityType[] OVERWORLD_MOBS = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER
    };
    private static final EntityType[] NETHER_MOBS = {
            EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER_SKELETON
    };

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

        // Count existing hostile mobs in this chunk
        int monsters = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Monster) monsters++;
        }
        if (monsters >= maxMobsPerChunk) return;

        Location loc = findDarkLocation(world, cx, cz);
        if (loc == null) return;

        EntityType type = pickMobType(world, loc);
        if (type == null) return;

        world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
    }

    /**
     * Scans random positions in the chunk for a valid hostile-mob spawn location:
     * block light level 0, solid block below, 3 air blocks above.
     */
    private Location findDarkLocation(World world, int cx, int cz) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        int minY = world.getMinHeight() + 2;
        int maxY = world.getMaxHeight() - 3;

        for (int attempt = 0; attempt < 12; attempt++) {
            int x = cx * 16 + rand.nextInt(16);
            int z = cz * 16 + rand.nextInt(16);

            // Start below surface to find darkness
            int surfaceY = Math.min(world.getHighestBlockYAt(x, z) - 1, maxY);
            int startY = Math.max(minY, surfaceY);

            for (int y = startY; y >= minY; y--) {
                Block floor  = world.getBlockAt(x, y - 1, z);
                Block feet   = world.getBlockAt(x, y,     z);
                Block head   = world.getBlockAt(x, y + 1, z);
                Block above2 = world.getBlockAt(x, y + 2, z);

                if (!floor.getType().isSolid())           continue;
                if (feet.getType()   != Material.AIR)     continue;
                if (head.getType()   != Material.AIR)     continue;
                if (above2.getType() != Material.AIR)     continue;
                if (feet.getLightFromBlocks() > 0)        continue; // block light must be 0

                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private EntityType pickMobType(World world, Location loc) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        return switch (world.getEnvironment()) {
            case NETHER  -> NETHER_MOBS[rand.nextInt(NETHER_MOBS.length)];
            case THE_END -> EntityType.ENDERMAN;
            default      -> OVERWORLD_MOBS[rand.nextInt(OVERWORLD_MOBS.length)];
        };
    }
}
