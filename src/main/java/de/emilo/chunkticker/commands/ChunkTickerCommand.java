package de.emilo.chunkticker.commands;

import de.emilo.chunkticker.ChunkManager;
import de.emilo.chunkticker.ChunkManager.ChunkEntry;
import de.emilo.chunkticker.ChunkTicker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ChunkTickerCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[ChunkTicker] ";
    private static final List<String> SUBCOMMANDS =
            Arrays.asList("set", "delete", "list", "reload", "on", "off", "status");

    private final ChunkTicker plugin;
    private final ChunkManager manager;

    public ChunkTickerCommand(ChunkTicker plugin) {
        this.plugin = plugin;
        this.manager = plugin.getChunkManager();
    }

    // -------------------------------------------------------
    //  Execution
    // -------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendInfo(sender, "Verwendung: /" + label + " <set|delete|list|reload|on|off|status> [radius]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set"    -> handleSet(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "list"   -> handleList(sender);
            case "reload" -> handleReload(sender);
            case "on"     -> handleOn(sender);
            case "off"    -> handleOff(sender);
            case "status" -> handleStatus(sender);
            default       -> sendError(sender, "Unbekannter Befehl. Nutze /" + label + " für Hilfe.");
        }
        return true;
    }

    // -------------------------------------------------------
    //  /ct set [radius]
    // -------------------------------------------------------

    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunksticker.set")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sendError(sender, "Dieser Befehl kann nur von Spielern ausgeführt werden.");
            return;
        }

        int radius = parseRadius(sender, args, 1);
        if (radius < 0) return;

        int cx = player.getLocation().getChunk().getX();
        int cz = player.getLocation().getChunk().getZ();
        String world = player.getWorld().getName();

        int added = 0;
        int skipped = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (manager.addChunk(world, cx + dx, cz + dz)) {
                    added++;
                } else {
                    skipped++;
                }
            }
        }
        manager.saveToConfig();

        int total = (radius * 2 + 1) * (radius * 2 + 1);
        if (skipped == total) {
            sendInfo(sender, "Alle " + total + " Chunk(s) waren bereits registriert.");
        } else {
            sendSuccess(sender, added + " Chunk(s) registriert"
                    + (skipped > 0 ? " (" + skipped + " bereits vorhanden)" : "") + ".");
        }
    }

    // -------------------------------------------------------
    //  /ct delete [radius]
    // -------------------------------------------------------

    private void handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunksticker.delete")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sendError(sender, "Dieser Befehl kann nur von Spielern ausgeführt werden.");
            return;
        }

        int radius = parseRadius(sender, args, 1);
        if (radius < 0) return;

        int cx = player.getLocation().getChunk().getX();
        int cz = player.getLocation().getChunk().getZ();
        String world = player.getWorld().getName();

        int removed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (manager.removeChunk(world, cx + dx, cz + dz)) {
                    removed++;
                }
            }
        }
        manager.saveToConfig();

        if (removed == 0) {
            sendInfo(sender, "Keine registrierten Chunks in diesem Bereich gefunden.");
        } else {
            sendSuccess(sender, removed + " Chunk(s) entfernt.");
        }
    }

    // -------------------------------------------------------
    //  /ct list
    // -------------------------------------------------------

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.list")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }

        Set<ChunkEntry> chunks = manager.getRegisteredChunks();
        if (chunks.isEmpty()) {
            sendInfo(sender, "Keine Chunks registriert.");
            return;
        }

        sendInfo(sender, "Registrierte Chunks (" + chunks.size() + "):");
        for (ChunkEntry e : chunks) {
            sender.sendMessage(Component.text("  ").color(NamedTextColor.GRAY)
                    .append(Component.text("Welt: ").color(NamedTextColor.YELLOW))
                    .append(Component.text(e.worldName()).color(NamedTextColor.WHITE))
                    .append(Component.text("  X: ").color(NamedTextColor.YELLOW))
                    .append(Component.text(String.valueOf(e.x())).color(NamedTextColor.WHITE))
                    .append(Component.text("  Z: ").color(NamedTextColor.YELLOW))
                    .append(Component.text(String.valueOf(e.z())).color(NamedTextColor.WHITE)));
        }
    }

    // -------------------------------------------------------
    //  /ct reload
    // -------------------------------------------------------

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.reload")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }
        manager.unloadAllTickets();
        plugin.reloadConfig();
        manager.loadFromConfig();
        sendSuccess(sender, "Config neu geladen. " + manager.getRegisteredChunkCount() + " Chunk(s) aktiv.");
    }

    // -------------------------------------------------------
    //  /ct on
    // -------------------------------------------------------

    private void handleOn(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.toggle")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }
        if (manager.isGlobalEnabled()) {
            sendInfo(sender, "ChunkTicker ist bereits aktiv.");
            return;
        }
        manager.enableGlobal();
        sendSuccess(sender, "ChunkTicker aktiviert. " + manager.getRegisteredChunkCount() + " Chunk(s) geladen.");
    }

    // -------------------------------------------------------
    //  /ct off
    // -------------------------------------------------------

    private void handleOff(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.toggle")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }
        if (!manager.isGlobalEnabled()) {
            sendInfo(sender, "ChunkTicker ist bereits deaktiviert.");
            return;
        }
        manager.disableGlobal();
        sendSuccess(sender, "ChunkTicker deaktiviert. Alle Tickets entfernt (Chunks bleiben gespeichert).");
    }

    // -------------------------------------------------------
    //  /ct status
    // -------------------------------------------------------

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.status")) {
            sendError(sender, "Keine Berechtigung.");
            return;
        }
        boolean enabled = manager.isGlobalEnabled();
        int count = manager.getRegisteredChunkCount();

        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text("Status: ").color(NamedTextColor.YELLOW))
                .append(enabled
                        ? Component.text("AKTIV").color(NamedTextColor.GREEN)
                        : Component.text("INAKTIV").color(NamedTextColor.RED)));
        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text("Registrierte Chunks: ").color(NamedTextColor.YELLOW))
                .append(Component.text(String.valueOf(count)).color(NamedTextColor.WHITE)));

        // Per-world breakdown
        if (count > 0) {
            java.util.Map<String, Long> perWorld = manager.getRegisteredChunks().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            ChunkEntry::worldName, java.util.stream.Collectors.counting()));
            perWorld.forEach((world, n) ->
                    sender.sendMessage(Component.text("  " + world + ": ").color(NamedTextColor.GRAY)
                            .append(Component.text(n + " Chunk(s)").color(NamedTextColor.WHITE))));
        }
    }

    // -------------------------------------------------------
    //  Tab completion
    // -------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String typed = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(typed) && hasPermissionFor(sender, sub)) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("delete"))) {
            String typed = args[1];
            List<String> radii = new ArrayList<>();
            for (String r : new String[]{"0", "1", "2", "3", "5"}) {
                if (r.startsWith(typed)) radii.add(r);
            }
            return radii;
        }
        return List.of();
    }

    private boolean hasPermissionFor(CommandSender sender, String sub) {
        return switch (sub) {
            case "set"    -> sender.hasPermission("chunksticker.set");
            case "delete" -> sender.hasPermission("chunksticker.delete");
            case "list"   -> sender.hasPermission("chunksticker.list");
            case "reload" -> sender.hasPermission("chunksticker.reload");
            case "on", "off" -> sender.hasPermission("chunksticker.toggle");
            case "status" -> sender.hasPermission("chunksticker.status");
            default -> false;
        };
    }

    // -------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------

    /** Parses radius from args[index]. Returns -1 and sends error on invalid input. */
    private int parseRadius(CommandSender sender, String[] args, int index) {
        if (args.length <= index) return 0;
        try {
            int r = Integer.parseInt(args[index]);
            if (r < 0) {
                sendError(sender, "Radius muss >= 0 sein.");
                return -1;
            }
            if (r > 20) {
                sendError(sender, "Radius darf maximal 20 sein (zu viele Chunks auf einmal).");
                return -1;
            }
            return r;
        } catch (NumberFormatException e) {
            sendError(sender, "Ungültiger Radius: '" + args[index] + "' – muss eine Zahl sein.");
            return -1;
        }
    }

    private void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text(message).color(NamedTextColor.GREEN)));
    }

    private void sendError(CommandSender sender, String message) {
        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text(message).color(NamedTextColor.RED)));
    }

    private void sendInfo(CommandSender sender, String message) {
        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text(message).color(NamedTextColor.YELLOW)));
    }
}
