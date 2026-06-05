package de.emilo.chunkticker.commands;

import de.emilo.chunkticker.ChunkManager;
import de.emilo.chunkticker.ChunkManager.ChunkEntry;
import de.emilo.chunkticker.ChunkTicker;
import de.emilo.chunkticker.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ChunkTickerCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[ChunkTicker] ";
    private static final List<String> SUBCOMMANDS =
            Arrays.asList("set", "delete", "list", "reload", "on", "off", "status");

    private final ChunkTicker plugin;
    private final ChunkManager manager;

    public ChunkTickerCommand(ChunkTicker plugin) {
        this.plugin  = plugin;
        this.manager = plugin.getChunkManager();
    }

    // -------------------------------------------------------
    //  Execution
    // -------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendInfo(sender, Lang.get("usage", "label", label));
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
            default       -> sendError(sender, Lang.get("unknown-command", "label", label));
        }
        return true;
    }

    // -------------------------------------------------------
    //  /ct set [radius]
    // -------------------------------------------------------

    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunksticker.set")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sendError(sender, Lang.get("player-only"));
            return;
        }

        int radius = parseRadius(sender, args, 1);
        if (radius < 0) return;

        int cx    = player.getLocation().getChunk().getX();
        int cz    = player.getLocation().getChunk().getZ();
        String world = player.getWorld().getName();

        int added = 0, skipped = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (manager.addChunk(world, cx + dx, cz + dz)) added++;
                else skipped++;
            }
        }
        manager.saveToConfig();

        int total = (radius * 2 + 1) * (radius * 2 + 1);
        if (skipped == total) {
            sendInfo(sender, Lang.get("set.all-existing", "total", String.valueOf(total)));
        } else {
            String skippedSuffix = skipped > 0
                    ? Lang.get("set.skipped-suffix", "n", String.valueOf(skipped)) : "";
            sendSuccess(sender, Lang.get("set.success",
                    "added",   String.valueOf(added),
                    "skipped", skippedSuffix));
        }
    }

    // -------------------------------------------------------
    //  /ct delete [radius]
    // -------------------------------------------------------

    private void handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunksticker.delete")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sendError(sender, Lang.get("player-only"));
            return;
        }

        int radius = parseRadius(sender, args, 1);
        if (radius < 0) return;

        int cx    = player.getLocation().getChunk().getX();
        int cz    = player.getLocation().getChunk().getZ();
        String world = player.getWorld().getName();

        int removed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (manager.removeChunk(world, cx + dx, cz + dz)) removed++;
            }
        }
        manager.saveToConfig();

        if (removed == 0) {
            sendInfo(sender, Lang.get("delete.none-found"));
        } else {
            sendSuccess(sender, Lang.get("delete.success", "removed", String.valueOf(removed)));
        }
    }

    // -------------------------------------------------------
    //  /ct list
    // -------------------------------------------------------

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.list")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }

        Set<ChunkEntry> chunks = manager.getRegisteredChunks();
        if (chunks.isEmpty()) {
            sendInfo(sender, Lang.get("list.empty"));
            return;
        }

        sendInfo(sender, Lang.get("list.header", "count", String.valueOf(chunks.size())));
        String wLabel = Lang.get("list.world");
        String xLabel = Lang.get("list.x");
        String zLabel = Lang.get("list.z");

        for (ChunkEntry e : chunks) {
            sender.sendMessage(Component.text("  ").color(NamedTextColor.GRAY)
                    .append(Component.text(wLabel + ": ").color(NamedTextColor.YELLOW))
                    .append(Component.text(e.worldName()).color(NamedTextColor.WHITE))
                    .append(Component.text("  " + xLabel + ": ").color(NamedTextColor.YELLOW))
                    .append(Component.text(String.valueOf(e.x())).color(NamedTextColor.WHITE))
                    .append(Component.text("  " + zLabel + ": ").color(NamedTextColor.YELLOW))
                    .append(Component.text(String.valueOf(e.z())).color(NamedTextColor.WHITE)));
        }
    }

    // -------------------------------------------------------
    //  /ct reload
    // -------------------------------------------------------

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.reload")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }
        manager.unloadAllTickets();
        plugin.reloadConfig();
        Lang.load(plugin);
        manager.loadFromConfig();
        sendSuccess(sender, Lang.get("reload.success",
                "count", String.valueOf(manager.getRegisteredChunkCount())));
    }

    // -------------------------------------------------------
    //  /ct on
    // -------------------------------------------------------

    private void handleOn(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.toggle")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }
        if (manager.isGlobalEnabled()) {
            sendInfo(sender, Lang.get("on.already-active"));
            return;
        }
        manager.enableGlobal();
        sendSuccess(sender, Lang.get("on.success",
                "count", String.valueOf(manager.getRegisteredChunkCount())));
    }

    // -------------------------------------------------------
    //  /ct off
    // -------------------------------------------------------

    private void handleOff(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.toggle")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }
        if (!manager.isGlobalEnabled()) {
            sendInfo(sender, Lang.get("off.already-disabled"));
            return;
        }
        manager.disableGlobal();
        sendSuccess(sender, Lang.get("off.success"));
    }

    // -------------------------------------------------------
    //  /ct status
    // -------------------------------------------------------

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("chunksticker.status")) {
            sendError(sender, Lang.get("no-permission"));
            return;
        }
        boolean enabled = manager.isGlobalEnabled();
        int count = manager.getRegisteredChunkCount();

        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text(Lang.get("status.label-status") + " ").color(NamedTextColor.YELLOW))
                .append(enabled
                        ? Component.text(Lang.get("status.active")).color(NamedTextColor.GREEN)
                        : Component.text(Lang.get("status.inactive")).color(NamedTextColor.RED)));

        sender.sendMessage(Component.text(PREFIX).color(NamedTextColor.GOLD)
                .append(Component.text(Lang.get("status.label-chunks") + " ").color(NamedTextColor.YELLOW))
                .append(Component.text(String.valueOf(count)).color(NamedTextColor.WHITE)));

        if (count > 0) {
            String chunkSuffix = Lang.get("status.chunk-suffix");
            Map<String, Long> perWorld = manager.getRegisteredChunks().stream()
                    .collect(Collectors.groupingBy(ChunkEntry::worldName, Collectors.counting()));
            perWorld.forEach((world, n) ->
                    sender.sendMessage(Component.text("  " + world + ": ").color(NamedTextColor.GRAY)
                            .append(Component.text(n + " " + chunkSuffix).color(NamedTextColor.WHITE))));
        }
    }

    // -------------------------------------------------------
    //  Tab completion
    // -------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            String typed = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(typed) && hasPermissionFor(sender, sub)) result.add(sub);
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("delete"))) {
            List<String> radii = new ArrayList<>();
            for (String r : new String[]{"0", "1", "2", "3", "5"}) {
                if (r.startsWith(args[1])) radii.add(r);
            }
            return radii;
        }
        return List.of();
    }

    private boolean hasPermissionFor(CommandSender sender, String sub) {
        return switch (sub) {
            case "set"       -> sender.hasPermission("chunksticker.set");
            case "delete"    -> sender.hasPermission("chunksticker.delete");
            case "list"      -> sender.hasPermission("chunksticker.list");
            case "reload"    -> sender.hasPermission("chunksticker.reload");
            case "on", "off" -> sender.hasPermission("chunksticker.toggle");
            case "status"    -> sender.hasPermission("chunksticker.status");
            default          -> false;
        };
    }

    // -------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------

    private int parseRadius(CommandSender sender, String[] args, int index) {
        if (args.length <= index) return 0;
        try {
            int r = Integer.parseInt(args[index]);
            if (r < 0)  { sendError(sender, Lang.get("radius.too-small")); return -1; }
            if (r > 20) { sendError(sender, Lang.get("radius.too-large")); return -1; }
            return r;
        } catch (NumberFormatException e) {
            sendError(sender, Lang.get("radius.invalid", "value", args[index]));
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
