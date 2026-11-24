package de.emilo.chunkticker;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NmsSpawner {
    private static final List<MobCategory> SPAWN_CATEGORIES =
            Arrays.stream(MobCategory.values())
                  .filter(c -> c != MobCategory.MISC)
                  .toList();

    private static final Method CREATE_STATE = findCreateState();

    private NmsSpawner() {}

    private static Method findCreateState() {
        for (Method method : NaturalSpawner.class.getMethods()) {
            if (method.getName().equals("createState") && method.getParameterCount() == 4) {
                return method;
            }
        }
        return null;
    }

    public static boolean spawnForChunk(World world, int cx, int cz, Logger logger) {
        try {
            if (CREATE_STATE == null) {
                throw new IllegalStateException("NaturalSpawner.createState not found");
            }
            ServerLevel level = ((CraftWorld) world).getHandle();
            LevelChunk chunk = level.getChunk(cx, cz);

            NaturalSpawner.ChunkGetter chunkGetter = (packedPos, consumer) -> {
                LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(packedPos), ChunkPos.getZ(packedPos));
                if (c != null) consumer.accept(c);
            };

            // 26.3 passes the level itself, older versions pass the entity list
            Object source = CREATE_STATE.getParameterTypes()[1].isInstance(level)
                    ? level
                    : level.getEntities().getAll();

            NaturalSpawner.SpawnState state =
                    (NaturalSpawner.SpawnState) CREATE_STATE.invoke(null, 1, source, chunkGetter, null);

            NaturalSpawner.spawnForChunk(level, chunk, state, SPAWN_CATEGORIES);
            return true;

        } catch (Exception | LinkageError e) {
            logger.log(Level.WARNING,
                    "NmsSpawner failed for " + cx + "/" + cz
                            + " - switching to Bukkit fallback: " + e.getMessage(), e);
            return false;
        }
    }
}
