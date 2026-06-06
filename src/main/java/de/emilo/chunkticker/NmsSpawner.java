package de.emilo.chunkticker;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Calls Paper 1.21.8's real NaturalSpawner for a single chunk.
 *
 * Paper 1.21.8 API differences from vanilla:
 *   - ChunkGetter.query(long packedPos, Consumer<LevelChunk>)  — packed long, not int x/z
 *   - spawnForChunk(..., List<MobCategory>)                    — category list, not 3 booleans
 *   - LocalMobCapCalculator in net.minecraft.world.level       — not server.level
 *
 * LocalMobCapCalculator is passed as null: it distributes spawn budget
 * across nearby online players, of which there are none here.
 * Without it, Paper falls back to the plain global-cap check.
 */
public final class NmsSpawner {

    // All spawnable categories except MISC (dropped items, paintings, etc.)
    private static final List<MobCategory> SPAWN_CATEGORIES =
            Arrays.stream(MobCategory.values())
                  .filter(c -> c != MobCategory.MISC)
                  .toList();

    private NmsSpawner() {}

    /**
     * Runs real vanilla mob spawning for one chunk.
     * @return true on success, false on any error (use Bukkit fallback)
     */
    public static boolean spawnForChunk(World world, int cx, int cz, Logger logger) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            LevelChunk  chunk = level.getChunk(cx, cz);

            NaturalSpawner.SpawnState state = NaturalSpawner.createState(
                    1,
                    level.getEntities().getAll(),
                    // ChunkGetter takes packed long pos in Paper 1.21.8
                    (packedPos, consumer) -> {
                        int x = ChunkPos.getX(packedPos);
                        int z = ChunkPos.getZ(packedPos);
                        LevelChunk c = level.getChunkSource().getChunkNow(x, z);
                        if (c != null) consumer.accept(c);
                    },
                    null   // no per-player local cap
            );

            // Paper 1.21.8: takes List<MobCategory> instead of boolean spawnFriendly/spawnHostile/rare
            NaturalSpawner.spawnForChunk(level, chunk, state, SPAWN_CATEGORIES);
            return true;

        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "NmsSpawner failed for " + cx + "/" + cz
                            + " — switching to Bukkit fallback: " + e.getMessage(), e);
            return false;
        }
    }
}
