<!-- MODRINTH SUMMARY (paste into Project Settings → Summary, max 256 chars) -->
<!-- Keeps selected chunks permanently loaded and fully ticking, so mobs spawn, Redstone fires and villagers work with nobody online. Lightweight, no dependencies. -->

## ChunkTicker

Keeps selected chunks permanently loaded and fully ticking, so farms keep running when nobody is online. Mobs spawn, Redstone fires and villagers work.

The plugin runs on its own and needs no other plugin. Mob spawning calls the vanilla `NaturalSpawner` through NMS and uses a Bukkit based spawner when that fails or spawns nothing.

### Features

- Chunks are held at entity ticking level (Level 31): mob AI, villager AI, Redstone, crop growth and hoppers keep running
- Works with zero players online, so iron golem, creeper and hoglin farms run around the clock
- Natural mob spawning in registered chunks without a player nearby, using the vanilla `NaturalSpawner` for every spawn category except MISC
- Bukkit fallback spawner with biome based mob lists, a global cap, a per-chunk cap and a spawn chance. It skips Mushroom Fields for hostile mobs and Deep Dark completely
- Multi-world support for Overworld, Nether, End and custom worlds
- Radius selection to register one chunk or a whole NxN area in a single command, up to radius 20
- Three ticket modes: `plugin`, `forced` or `both`
- Chunk list is stored in `config.yml` and survives restarts
- Global toggle that removes all tickets without clearing the list
- Per-subcommand permissions, compatible with LuckPerms
- Languages: en, de, ru, es, fr, ja, zh

### Configuration

```yaml
global-enabled: true

# plugin | forced | both (recommended)
ticket-mode: both

# en, de, ru, es, fr, ja, zh
language: en

force-spawning:
  enabled: true
  interval-ticks: 400
  spawn-chance: 0.3
  global-hostile-cap: 15
  global-passive-cap: 12
  max-hostile-per-chunk: 3
  max-passive-per-chunk: 2

# Managed automatically, only edit while the server is stopped
chunks: []
```

The full file with comments is created in `plugins/ChunkTicker/` on the first start. Caps, spawn chance and per-chunk limits only apply to the Bukkit fallback spawner. Changes to the `force-spawning` section need a server restart, everything else is applied by `/ct reload`.

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
| `/ct set [radius]` | Register the chunk you are standing in, plus the given radius |
| `/ct delete [radius]` | Remove the chunk you are standing in, plus the given radius |
| `/ct list` | List all registered chunks (world, X, Z) |
| `/ct on` | Enable ChunkTicker globally |
| `/ct off` | Disable ChunkTicker globally, the chunk list is kept |
| `/ct status` | Show state and registered chunk count per world |
| `/ct reload` | Reload `config.yml` and the language file and re-apply all tickets |

Alias: `/ct`, full command: `/chunksticker`

### Requirements

| | |
|---|---|
| Server | Paper 26.1 to 26.3 with `ChunkTicker_1.2_26.x.jar`, Paper 1.21 to 1.21.x with `ChunkTicker_1.2_1.21.x.jar` (Purpur and Pufferfish should work as Paper forks) |
| Java | 25+ for Paper 26.x, 21+ for Paper 1.21.x |

Spigot and Folia are not supported. Copy the JAR that matches your server into the `plugins/` folder and start the server.

### How it works

Paper exposes two persistent chunk ticket APIs at entity ticking level (Level 31): `addPluginChunkTicket()` and `setChunkForceLoaded()`. With `ticket-mode: both`, ChunkTicker applies both to every registered chunk. Level 31 is the load level Minecraft uses for chunks inside a player's view distance, so entities tick, Redstone runs and villagers work as if a player were standing nearby.

Loaded chunks alone do not spawn mobs without a player, because the vanilla spawn cycle starts from players. Every `interval-ticks` ChunkTicker calls `NaturalSpawner.spawnForChunk` through NMS for each registered chunk, with a spawn state built from the loaded entities. This is the real vanilla spawner, including biome spawn lists, light checks and structure rules such as the Ancient City.

When the NMS call throws, the plugin logs a warning and switches to a Bukkit fallback spawner for the rest of the session. When the vanilla spawner runs without error but spawns nothing in a cycle, which Paper does with `per-player-mob-spawns: true` and no player nearby, the fallback runs for that cycle. It counts hostile and passive mobs across all registered chunks, rolls `spawn-chance` per chunk, and reads the biome at the real spawn height so that Ancient Cities below a different surface biome are still excluded.
