<!-- MODRINTH SUMMARY (paste into Project Settings → Summary, max 256 chars) -->
<!-- Keeps selected chunks permanently loaded and fully ticking, so mobs spawn, Redstone fires and villagers work with nobody online. Lightweight, no dependencies. -->

## ChunkTicker

A lightweight Paper plugin that keeps selected chunks permanently loaded and fully ticking, so your farms keep running when nobody is online. Mobs spawn, Redstone fires, villagers work.

### Features

- Full entity ticking: mobs spawn, villager AI runs, Redstone executes, crops grow
- Works with zero players online, so iron golem, creeper and hoglin farms run 24/7
- Multi-world support for Overworld, Nether, End and custom worlds
- Radius selection to register one chunk or a whole NxN area in a single command
- Chunk list is stored in `config.yml` and survives restarts
- Global toggle that pauses all tickets without clearing your list
- Per-subcommand permissions, compatible with LuckPerms

### Configuration

```yaml
# Whether ChunkTicker is globally active
global-enabled: true

# Ticket mode: plugin | forced | both (recommended)
#   plugin  -> addPluginChunkTicket()  (TicketType.PLUGIN, Level 31)
#   forced  -> setChunkForceLoaded()   (TicketType.FORCED, Level 31)
#   both    -> both tickets applied, most reliable for mob farms
ticket-mode: both

# Managed automatically, only edit while the server is stopped
chunks: []
```

### Permissions

| Permission | Description | Default |
|---|---|---|
| `chunksticker.admin` | Grants all permissions below | `op` |
| `chunksticker.set` | Use `/ct set` | `op` |
| `chunksticker.delete` | Use `/ct delete` | `op` |
| `chunksticker.list` | Use `/ct list` | `op` |
| `chunksticker.toggle` | Use `/ct on` and `/ct off` | `op` |
| `chunksticker.reload` | Use `/ct reload` | `op` |
| `chunksticker.status` | Use `/ct status` | `op` |

LuckPerms examples:

```
/lp group admin permission set chunksticker.admin true
/lp user Steve permission set chunksticker.set true
```

### Commands

| Command | Description |
|---|---|
| `/ct set [radius]` | Register the chunk you're standing in, plus radius |
| `/ct delete [radius]` | Remove the chunk you're standing in, plus radius |
| `/ct list` | List all registered chunks (world, X, Z) |
| `/ct on` / `off` | Enable or disable ChunkTicker globally |
| `/ct reload` | Reload config and re-apply all tickets |
| `/ct status` | Show active state and chunk count per world |

Alias: `/ct`, full command: `/chunksticker`

### Requirements

| | |
|---|---|
| Server | Paper 1.21 to 1.21.x (Purpur and Pufferfish also work) |
| Java | 21+ |

Spigot and Folia are not supported.

### How it works

Paper exposes two persistent chunk-ticket APIs at entity-ticking level (Level 31): `addPluginChunkTicket()` and `setChunkForceLoaded()`. With `ticket-mode: both`, ChunkTicker applies both to every registered chunk.

Level 31 is the same load level Minecraft uses for chunks inside a player's view distance. Entities tick, mobs spawn through the normal spawning cycle, and Redstone runs exactly as if a player were standing nearby.
