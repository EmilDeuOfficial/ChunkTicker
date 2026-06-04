package de.emilo.chunkticker;

import de.emilo.chunkticker.commands.ChunkTickerCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ChunkTicker extends JavaPlugin {

    private static ChunkTicker instance;
    private ChunkManager chunkManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        chunkManager = new ChunkManager(this);
        chunkManager.loadFromConfig();

        ChunkTickerCommand cmd = new ChunkTickerCommand(this);
        PluginCommand command = getCommand("chunksticker");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }

        int count = chunkManager.getRegisteredChunkCount();
        getLogger().info("ChunkTicker aktiviert."
                + (chunkManager.isGlobalEnabled() && count > 0
                   ? " " + count + " Chunk(s) geladen." : ""));
    }

    @Override
    public void onDisable() {
        if (chunkManager != null) {
            chunkManager.unloadAllTickets();
        }
        getLogger().info("ChunkTicker deaktiviert. Alle Chunk-Tickets entfernt.");
    }

    public static ChunkTicker getInstance() {
        return instance;
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }
}
