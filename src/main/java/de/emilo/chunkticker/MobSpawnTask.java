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
 * Method discovery is fully dynamic: methods are found by name only, and
 * invocation arguments are matched by parameter type — not by hardcoded
 * positions or class names. This makes the task compatible with all
 * Paper 1.21.x sub-versions regardless of internal refactors.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MobSpawnTask extends BukkitRunnable {

    private final ChunkTicker plugin;
    private final ChunkManager manager;

    private static Boolean nmsAvailable = null;

    private static Method craftWorldGetHandle;
    private static Method craftEntityGetHandle;
    private static Class<?> craftEntityClass;
    private static Class<?> serverLevelClass;

    private static Method serverLevelGetChunkSource;
    private static Method serverLevelGetChunkIfLoaded;

    private static Field lastSpawnStateField;

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

        Object spawnState = lastSpawnStateField.get(chunkSource);
        if (spawnState == null) {
            spawnState = buildFreshSpawnState(world, serverLevel);
        }
        if (spawnState == null) return;

        Object levelChunk = serverLevelGetChunkIfLoaded.invoke(serverLevel, cx, cz);
        if (levelChunk == null) return;

        invokeSpawnForChunk(serverLevel, levelChunk, spawnState);
    }

    // -------------------------------------------------------
    //  Dynamic invocations
    // -------------------------------------------------------

    /**
     * Builds args for spawnForChunk by type-matching each parameter.
     * Works for both the 4-param and 6-param variants.
     *
     *   ServerLevel  → serverLevel
     *   LevelChunk   → levelChunk
     *   SpawnState   → spawnState
     *   boolean      → true (enable spawning), except last boolean → false (no rare spawns)
     *   anything else → null
     */
    private void invokeSpawnForChunk(Object serverLevel, Object levelChunk, Object spawnState)
            throws Exception {
        Class<?>[] types = naturalSpawnerSpawnForChunk.getParameterTypes();
        Object[] args = new Object[types.length];
        Class<?> spawnStateClass = spawnState.getClass();

        int boolCount = 0;
        for (Class<?> t : types) if (t == boolean.class) boolCount++;
        int boolSeen = 0;

        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (serverLevelClass.isAssignableFrom(t)) {
                args[i] = serverLevel;
            } else if (t.isAssignableFrom(levelChunk.getClass())) {
                args[i] = levelChunk;
            } else if (t.isAssignableFrom(spawnStateClass)) {
                args[i] = spawnState;
            } else if (t == boolean.class) {
                boolSeen++;
                // last boolean = rareSpawn → false; all others → true
                args[i] = boolSeen < boolCount;
            } else {
                args[i] = null;
            }
        }
        naturalSpawnerSpawnForChunk.invoke(null, args);
    }

    /**
     * Builds args for createState by type-matching each parameter.
     *
     *   int              → chunkCount
     *   Iterable         → nmsEntities
     *   chunkGetterInterface → proxy
     *   ServerLevel      → serverLevel (5-param variant)
     *   anything else    → null  (@Nullable LocalMobCapCalculator etc.)
     */
    private Object invokeCreateState(Object serverLevel, List<Object> nmsEntities,
            Object chunkGetterProxy, int chunkCount) throws Exception {
        Class<?>[] types = naturalSpawnerCreateState.getParameterTypes();
        Object[] args = new Object[types.length];

        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == int.class) {
                args[i] = chunkCount;
            } else if (t == Iterable.class) {
                args[i] = nmsEntities;
            } else if (t == chunkGetterInterface) {
                args[i] = chunkGetterProxy;
            } else if (serverLevelClass.isAssignableFrom(t)) {
                args[i] = serverLevel;
            } else {
                args[i] = null; // @Nullable (e.g. LocalMobCapCalculator)
            }
        }
        return naturalSpawnerCreateState.invoke(null, args);
    }

    // -------------------------------------------------------
    //  Fresh SpawnState (no players online)
    // -------------------------------------------------------

    private Object buildFreshSpawnState(World world, Object serverLevel) {
        try {
            List<Object> nmsEntities = new ArrayList<>();
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (craftEntityClass.isInstance(e)) {
                    Object handle = craftEntityGetHandle.invoke(e);
                    if (handle != null) nmsEntities.add(handle);
                }
            }

            Object chunkGetterProxy = Proxy.newProxyInstance(
                    chunkGetterInterface.getClassLoader(),
                    new Class[]{chunkGetterInterface},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "query" -> {
                                long pos = (long) args[0];
                                Consumer cons = (Consumer) args[1];
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
            return invokeCreateState(serverLevel, nmsEntities, chunkGetterProxy, chunkCount);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "MobSpawnTask: SpawnState-Erstellung fehlgeschlagen: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------
    //  NMS reflection – fully dynamic method discovery
    // -------------------------------------------------------

    private boolean initNms() {
        if (nmsAvailable != null) return nmsAvailable;
        try {
            Class<?> craftWorldClass = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            craftEntityClass         = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
            serverLevelClass         = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> chunkCacheClass = Class.forName("net.minecraft.server.level.ServerChunkCache");
            Class<?> naturalSpawner  = Class.forName("net.minecraft.world.level.NaturalSpawner");

            craftWorldGetHandle         = craftWorldClass.getMethod("getHandle");
            craftEntityGetHandle        = craftEntityClass.getMethod("getHandle");
            serverLevelGetChunkSource   = serverLevelClass.getMethod("getChunkSource");
            serverLevelGetChunkIfLoaded = serverLevelClass.getMethod("getChunkIfLoaded", int.class, int.class);

            lastSpawnStateField = chunkCacheClass.getDeclaredField("lastSpawnState");
            lastSpawnStateField.setAccessible(true);

            // createState: find the overload that has a ChunkGetter-like interface param
            // (any interface that is not java.lang.Iterable)
            for (Method m : naturalSpawner.getDeclaredMethods()) {
                if (!m.getName().equals("createState")) continue;
                for (Class<?> paramType : m.getParameterTypes()) {
                    if (paramType.isInterface() && paramType != Iterable.class) {
                        m.setAccessible(true);
                        naturalSpawnerCreateState = m;
                        chunkGetterInterface = paramType;
                        break;
                    }
                }
                if (naturalSpawnerCreateState != null) break;
            }

            // spawnForChunk: take the static overload with the most parameters
            // (more params = more spawn-category control)
            for (Method m : naturalSpawner.getDeclaredMethods()) {
                if (!m.getName().equals("spawnForChunk")) continue;
                if (!Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                if (naturalSpawnerSpawnForChunk == null
                        || m.getParameterCount() > naturalSpawnerSpawnForChunk.getParameterCount()) {
                    naturalSpawnerSpawnForChunk = m;
                }
            }

            if (naturalSpawnerCreateState == null)
                throw new NoSuchMethodException("createState mit ChunkGetter-Param nicht gefunden");
            if (naturalSpawnerSpawnForChunk == null)
                throw new NoSuchMethodException("spawnForChunk (static) nicht gefunden");
            if (chunkGetterInterface == null)
                throw new NoSuchMethodException("ChunkGetter-Interface nicht erkannt");

            nmsAvailable = true;
            plugin.getLogger().info("MobSpawnTask bereit (createState="
                    + naturalSpawnerCreateState.getParameterCount() + " params, spawnForChunk="
                    + naturalSpawnerSpawnForChunk.getParameterCount() + " params).");
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
