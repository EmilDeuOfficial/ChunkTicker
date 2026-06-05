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
 * spawn-interval ticks — bypassing the vanilla restriction that mob spawning
 * only runs in chunks within a player's mobSpawnRange.
 *
 * Methods are discovered by name + parameter count rather than by hardcoded
 * internal class names so this works across all Paper 1.21.x sub-versions.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MobSpawnTask extends BukkitRunnable {

    private final ChunkTicker plugin;
    private final ChunkManager manager;

    private static Boolean nmsAvailable = null;

    private static Method craftWorldGetHandle;
    private static Method craftEntityGetHandle;
    private static Class<?> craftEntityClass;

    private static Method serverLevelGetChunkSource;
    private static Method serverLevelGetChunkIfLoaded;

    private static Field lastSpawnStateField;

    // Discovered at runtime from method signatures – no hardcoded internal names
    private static Method naturalSpawnerCreateState;
    private static Method naturalSpawnerSpawnForChunk;
    private static Class<?> chunkGetterInterface;

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
                        "MobSpawnTask Fehler [" + entry.worldName()
                                + " " + entry.x() + "/" + entry.z() + "]: " + e.getMessage());
            }
        }
    }

    private void spawnInChunk(World world, int cx, int cz) throws Exception {
        Object serverLevel = craftWorldGetHandle.invoke(world);
        Object chunkSource = serverLevelGetChunkSource.invoke(serverLevel);

        // Reuse the spawn state already computed this tick (available when players are
        // online). If null (no players online), build one from scratch.
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

    private Object buildFreshSpawnState(World world, Object serverLevel) {
        try {
            // Collect NMS entity handles (for mob-cap accounting)
            List<Object> nmsEntities = new ArrayList<>();
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (craftEntityClass.isInstance(e)) {
                    Object handle = craftEntityGetHandle.invoke(e);
                    if (handle != null) nmsEntities.add(handle);
                }
            }

            // Proxy for NaturalSpawner.ChunkGetter (functional interface):
            // void query(long chunkPosLong, Consumer<LevelChunk> consumer)
            Object chunkGetterProxy = Proxy.newProxyInstance(
                    chunkGetterInterface.getClassLoader(),
                    new Class[]{chunkGetterInterface},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "query" -> {
                                long pos  = (long) args[0];
                                Consumer cons = (Consumer) args[1];
                                // ChunkPos: x = lower 32 bits, z = upper 32 bits
                                int x = (int) (pos & 0xFFFFFFFFL);
                                int z = (int) (pos >>> 32);
                                try {
                                    Object chunk = serverLevelGetChunkIfLoaded.invoke(serverLevel, x, z);
                                    if (chunk != null) cons.accept(chunk);
                                } catch (Exception ignored) {}
                            }
                            case "equals"   -> { return proxy == (args != null ? args[0] : null); }
                            case "hashCode" -> { return System.identityHashCode(proxy); }
                            case "toString" -> { return "ChunkGetterProxy"; }
                        }
                        return null;
                    }
            );

            int chunkCount = Math.max(1, manager.getRegisteredChunkCount());
            // Pass null for LocalMobCapCalculator → global mob cap, no per-player tracking
            return naturalSpawnerCreateState.invoke(null, chunkCount, nmsEntities,
                    chunkGetterProxy, null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "MobSpawnTask: SpawnState-Erstellung fehlgeschlagen: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------
    //  NMS reflection – version-resilient method discovery
    // -------------------------------------------------------

    private boolean initNms() {
        if (nmsAvailable != null) return nmsAvailable;
        try {
            // Bukkit bridge classes
            Class<?> craftWorldClass = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            craftEntityClass         = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
            craftWorldGetHandle      = craftWorldClass.getMethod("getHandle");
            craftEntityGetHandle     = craftEntityClass.getMethod("getHandle");

            // ServerLevel
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            serverLevelGetChunkSource   = serverLevelClass.getMethod("getChunkSource");
            serverLevelGetChunkIfLoaded = serverLevelClass.getMethod("getChunkIfLoaded", int.class, int.class);

            // ServerChunkCache.lastSpawnState (Paper addition – stable field name)
            Class<?> chunkCacheClass = Class.forName("net.minecraft.server.level.ServerChunkCache");
            lastSpawnStateField = chunkCacheClass.getDeclaredField("lastSpawnState");
            lastSpawnStateField.setAccessible(true);

            // NaturalSpawner – discover methods by name + param count, not by internal class names.
            // This avoids hard dependencies on LocalMobCapCalculator / ChunkGetter class names
            // that may differ across Paper 1.21.x sub-versions.
            Class<?> naturalSpawner = Class.forName("net.minecraft.world.level.NaturalSpawner");

            for (Method m : naturalSpawner.getDeclaredMethods()) {
                if (m.getName().equals("createState") && m.getParameterCount() == 4
                        && m.getParameterTypes()[0] == int.class
                        && m.getParameterTypes()[1] == Iterable.class) {
                    m.setAccessible(true);
                    naturalSpawnerCreateState = m;
                    // Parameter [2] IS the ChunkGetter interface – grab it dynamically
                    chunkGetterInterface = m.getParameterTypes()[2];
                    break;
                }
            }

            for (Method m : naturalSpawner.getDeclaredMethods()) {
                if (m.getName().equals("spawnForChunk") && m.getParameterCount() == 6) {
                    m.setAccessible(true);
                    naturalSpawnerSpawnForChunk = m;
                    break;
                }
            }

            if (naturalSpawnerCreateState == null || naturalSpawnerSpawnForChunk == null
                    || chunkGetterInterface == null) {
                throw new NoSuchMethodException(
                        "createState(4) oder spawnForChunk(6) nicht in NaturalSpawner gefunden");
            }

            nmsAvailable = true;
            plugin.getLogger().info(
                    "MobSpawnTask bereit – Mobs spawnen in registrierten Chunks auch ohne Spieler.");
        } catch (Exception e) {
            nmsAvailable = false;
            plugin.getLogger().log(Level.WARNING,
                    "MobSpawnTask NMS-Init fehlgeschlagen (" + e.getMessage() + "). "
                  + "Mob-Spawning ohne Spieler ist nicht verfügbar.");
            this.cancel();
        }
        return nmsAvailable;
    }
}
