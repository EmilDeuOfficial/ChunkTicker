package de.emilo.chunkticker;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Manages which chunks are kept loaded and entity-ticking.
 *
 * Ticket strategy (configurable via "ticket-mode" in config.yml):
 *
 *   plugin  – World.addPluginChunkTicket()  → TicketType.PLUGIN  at level 31
 *   forced  – World.setChunkForceLoaded()   → TicketType.FORCED  at level 31
 *   both    – both tickets applied (most aggressive, default)
 *
 * Level 31 = entity-ticking → all entities run, mobs spawn, Redstone ticks.
 * This covers iron-golem farms (villager AI runs), creeper/hoglin farms
 * (natural mob spawning eligible), and Redstone contraptions.
 *
 * Note: TicketType.UNKNOWN has timeout=1 tick and is intentionally NOT used
 * here because it requires constant re-application and is designed for
 * transient vanilla operations, not persistent plugin-managed loading.
 */
public class ChunkManager {

    public enum TicketMode { PLUGIN, FORCED, BOTH }

    private final ChunkTicker plugin;
    private final LinkedHashSet<ChunkEntry> registeredChunks = new LinkedHashSet<>();
    private boolean globalEnabled = true;

    public ChunkManager(ChunkTicker plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------
    //  Config I/O
    // -------------------------------------------------------

    public void loadFromConfig() {
        FileConfiguration cfg = plugin.getConfig();
        globalEnabled = cfg.getBoolean("global-enabled", true);
        registeredChunks.clear();

        List<Map<?, ?>> list = cfg.getMapList("chunks");
        for (Map<?, ?> entry : list) {
            String worldName = (String) entry.get("world");
            if (worldName == null) continue;
            int x = ((Number) Objects.requireNonNull(entry.get("x"))).intValue();
            int z = ((Number) Objects.requireNonNull(entry.get("z"))).intValue();
            registeredChunks.add(new ChunkEntry(worldName, x, z));
        }

        if (globalEnabled) {
            applyAllTickets();
        }
    }

    public void saveToConfig() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("global-enabled", globalEnabled);

        List<Map<String, Object>> list = new ArrayList<>();
        for (ChunkEntry e : registeredChunks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", e.worldName());
            map.put("x", e.x());
            map.put("z", e.z());
            list.add(map);
        }
        cfg.set("chunks", list);
        plugin.saveConfig();
    }

    // -------------------------------------------------------
    //  Add / Remove
    // -------------------------------------------------------

    /** Returns true if the entry was new (not already registered). */
    public boolean addChunk(String worldName, int x, int z) {
        ChunkEntry entry = new ChunkEntry(worldName, x, z);
        boolean added = registeredChunks.add(entry);
        if (added && globalEnabled) {
            applyTicket(entry);
        }
        return added;
    }

    /** Returns true if the entry existed and was removed. */
    public boolean removeChunk(String worldName, int x, int z) {
        ChunkEntry entry = new ChunkEntry(worldName, x, z);
        boolean removed = registeredChunks.remove(entry);
        if (removed) {
            removeTicket(entry);
        }
        return removed;
    }

    public boolean isRegistered(String worldName, int x, int z) {
        return registeredChunks.contains(new ChunkEntry(worldName, x, z));
    }

    // -------------------------------------------------------
    //  Global toggle
    // -------------------------------------------------------

    public void enableGlobal() {
        globalEnabled = true;
        applyAllTickets();
        saveToConfig();
    }

    public void disableGlobal() {
        globalEnabled = false;
        unloadAllTickets();
        saveToConfig();
    }

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    // -------------------------------------------------------
    //  Ticket lifecycle
    // -------------------------------------------------------

    public void applyAllTickets() {
        for (ChunkEntry e : registeredChunks) {
            applyTicket(e);
        }
    }

    public void unloadAllTickets() {
        for (ChunkEntry e : registeredChunks) {
            removeTicket(e);
        }
    }

    private TicketMode getMode() {
        String raw = plugin.getConfig().getString("ticket-mode", "both");
        return switch (raw.toLowerCase()) {
            case "plugin" -> TicketMode.PLUGIN;
            case "forced" -> TicketMode.FORCED;
            default       -> TicketMode.BOTH;
        };
    }

    private void applyTicket(ChunkEntry e) {
        World world = Bukkit.getWorld(e.worldName());
        if (world == null) return;

        TicketMode mode = getMode();
        if (mode == TicketMode.PLUGIN || mode == TicketMode.BOTH) {
            world.addPluginChunkTicket(e.x(), e.z(), plugin);
        }
        if (mode == TicketMode.FORCED || mode == TicketMode.BOTH) {
            world.setChunkForceLoaded(e.x(), e.z(), true);
        }
    }

    private void removeTicket(ChunkEntry e) {
        World world = Bukkit.getWorld(e.worldName());
        if (world == null) return;

        // Always remove both, regardless of current mode, to clean up any previously set tickets
        world.removePluginChunkTicket(e.x(), e.z(), plugin);
        world.setChunkForceLoaded(e.x(), e.z(), false);
    }

    // -------------------------------------------------------
    //  Accessors
    // -------------------------------------------------------

    public int getRegisteredChunkCount() {
        return registeredChunks.size();
    }

    public Set<ChunkEntry> getRegisteredChunks() {
        return Collections.unmodifiableSet(registeredChunks);
    }

    // -------------------------------------------------------
    //  ChunkEntry
    // -------------------------------------------------------

    public record ChunkEntry(String worldName, int x, int z) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkEntry other)) return false;
            return x == other.x && z == other.z && worldName.equals(other.worldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldName, x, z);
        }
    }
}
