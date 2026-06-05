package de.emilo.chunkticker;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Triggers NaturalSpawner.spawnForChunk() for every registered chunk each
 * spawn-interval ticks. This bypasses the vanilla restriction that mob spawning
 * only runs in chunks within a player's mobSpawnRange.
 *
 * Falls back gracefully (logs a warning, task self-cancels) if NMS reflection
 * fails on an incompatible server version.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MobSpawnTask extends BukkitRunnable {

    private final ChunkTicker plugin;
    private final ChunkManager manager;

    // One-time NMS reflection cache
    private static Boolean nmsAvailable = null;

    private static Method craftWorldGetHandle;
    private static Method craftEntityGetHandle;
    private static Class<?> craftEntityClass;

    private static Method serverLevelGetChunkSource;
    private static Method serverLevelGetChunkIfLoaded;

    private static Field lastSpawnStateField;

    private static Method naturalSpawnerCreateState;
    private static Method naturalSpawnerSpawnForChunk;
    private static Class<?> chunkGetterInterface;
    private static Class<?> localMobCapClass;

    public MobSpawnTask(ChunkTicker plugin, ChunkManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void run() {
        if (!manager.isGlobalEnabled()) return;
        if (!initNms()) return;

        for (ChunkManager.ChunkEntry entry : manager.getRegisteredChunks()) {
            World world = Bukkit.getWorld(entry.worldName());
            if (world == null) continue;
            try {
                spawnInChunk(world, entry.x(), entry.z());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "MobSpawnTask Fehler in Chunk [" + entry.worldName()
                                + " " + entry.x() + "/" + entry.z() + "]: " + e.getMessage());
            }
        }
    }

    private void spawnInChunk(World world, int cx, int cz) throws Exception {
        Object serverLevel = craftWorldGetHandle.invoke(world);
        Object chunkSource = serverLevelGetChunkSource.invoke(serverLevel);

        // Reuse the spawn state the server already computed this tick (available when
        // players are online). If no players are online the field will be null – in
        // that case we build a minimal spawn state ourselves.
        Object spawnState = lastSpawnStateField.get(chunkSource);
        if (spawnState == null) {
            spawnState = buildFreshSpawnState(world, serverLevel);
        }
        if (spawnState == null) return;

        Object levelChunk = serverLevelGetChunkIfLoaded.invoke(serverLevel, cx, cz);
        if (levelChunk == null) return;

        // spawnForChunk(ServerLevel, LevelChunk, SpawnState, spawnFriendlies, spawnEnemies, rareSpawn)
        naturalSpawnerSpawnForChunk.invoke(null, serverLevel, levelChunk, spawnState,
                true, true, false);
    }

    /**
     * Builds a SpawnState from scratch when no players are online.
     * Uses the Bukkit entity list (mapped to NMS) for mob-cap accounting.
     */
    private Object buildFreshSpawnState(World world, Object serverLevel) {
        try {
            // Collect NMS entity handles from Bukkit entities (for mob cap count)
            List<Object> nmsEntities = new ArrayList<>();
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (craftEntityClass.isInstance(e)) {
                    Object handle = craftEntityGetHandle.invoke(e);
                    if (handle != null) nmsEntities.add(handle);
                }
            }

            // Dynamic proxy implementing the NaturalSpawner.ChunkGetter interface:
            // query(long chunkPosLong, Consumer<LevelChunk> consumer)
            Object chunkGetterProxy = Proxy.newProxyInstance(
                    chunkGetterInterface.getClassLoader(),
                    new Class[]{chunkGetterInterface},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "query" -> {
                                long pos      = (long) args[0];
                                Consumer cons = (Consumer) args[1];
                                // ChunkPos encodes x in lower 32 bits, z in upper 32 bits
                                int x = (int) (pos & 0xFFFFFFFFL);
                                int z = (int) (pos >>> 32);
                                try {
                                    Object chunk = serverLevelGetChunkIfLoaded.invoke(serverLevel, x, z);
                                    if (chunk != null) cons.accept(chunk);
                                } catch (Exception ignored) {}
                            }
                            case "equals"   -> { return proxy == (args != null ? args[0] : null); }
                            case "hashCode" -> { return System.identityHashCode(proxy); }
                            case "toString" -> { return "MobSpawnTask$ChunkGetterProxy"; }
                        }
                        return null;
                    }
            );

            int chunkCount = Math.max(1, manager.getRegisteredChunkCount());
            // null for LocalMobCapCalculator → uses global mob cap, skips per-player cap
            return naturalSpawnerCreateState.invoke(null, chunkCount, nmsEntities,
                    chunkGetterProxy, null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "MobSpawnTask: SpawnState konnte nicht erstellt werden: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------
    //  NMS reflection initialisation
    // -------------------------------------------------------

    private boolean initNms() {
        if (nmsAvailable != null) return nmsAvailable;
        try {
            Class<?> craftWorldClass  = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            craftEntityClass          = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> chunkCacheClass  = Class.forName("net.minecraft.server.level.ServerChunkCache");
            Class<?> naturalSpawner   = Class.forName("net.minecraft.world.level.NaturalSpawner");
            Class<?> spawnStateClass  = Class.forName("net.minecraft.world.level.NaturalSpawner$SpawnState");
            Class<?> levelChunkClass  = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
            localMobCapClass          = Class.forName("net.minecraft.server.level.LocalMobCapCalculator");
            chunkGetterInterface      = Class.forName("net.minecraft.world.level.NaturalSpawner$ChunkGetter");

            craftWorldGetHandle         = craftWorldClass.getMethod("getHandle");
            craftEntityGetHandle        = craftEntityClass.getMethod("getHandle");
            serverLevelGetChunkSource   = serverLevelClass.getMethod("getChunkSource");
            serverLevelGetChunkIfLoaded = serverLevelClass.getMethod("getChunkIfLoaded", int.class, int.class);

            lastSpawnStateField = chunkCacheClass.getDeclaredField("lastSpawnState");
            lastSpawnStateField.setAccessible(true);

            naturalSpawnerCreateState = naturalSpawner.getMethod("createState",
                    int.class, Iterable.class, chunkGetterInterface, localMobCapClass);
            naturalSpawnerSpawnForChunk = naturalSpawner.getMethod("spawnForChunk",
                    serverLevelClass, levelChunkClass, spawnStateClass,
                    boolean.class, boolean.class, boolean.class);

            nmsAvailable = true;
            plugin.getLogger().info(
                    "MobSpawnTask bereit – Mobs spawnen in registrierten Chunks auch ohne Spieler.");
        } catch (Exception e) {
            nmsAvailable = false;
            plugin.getLogger().log(Level.WARNING,
                    "MobSpawnTask NMS-Init fehlgeschlagen (" + e.getMessage() + "). "
                  + "Mob-Spawning ohne Spieler ist nicht verfügbar. "
                  + "Stelle sicher, dass Paper 1.21.x verwendet wird.");
            this.cancel();
        }
        return nmsAvailable;
    }
}
