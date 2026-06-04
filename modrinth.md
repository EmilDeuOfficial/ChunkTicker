## ⏱️ ChunkTicker

Want your farms to keep running when no players are online? This lightweight Paper plugin keeps selected chunks permanently loaded and **fully ticking** — mobs spawn, Redstone fires, villagers work.

No libraries, no bloat. Mark a chunk and go.

---

### ✨ Features

- **Full entity ticking** — mobs spawn, villager AI runs, Redstone executes, crops grow
- **Works without players** — iron golem, creeper & hoglin farms run 24/7
- **Multi-world support** — Overworld, Nether, End and custom worlds
- **Radius selection** — register one chunk or an entire N×N area in one command
- **Persistent** — chunk list survives server restarts via `config.yml`
- **Global toggle** — pause all tickets without losing your list
- **LuckPerms-compatible permissions** — per-subcommand control

---

### ⚙️ Configuration

```yaml
# Whether ChunkTicker is globally active
global-enabled: true

# Ticket mode: plugin | forced | both (recommended)
#   plugin  → addPluginChunkTicket()  (TicketType.PLUGIN,  Level 31)
#   forced  → setChunkForceLoaded()   (TicketType.FORCED,  Level 31)
#   both    → both tickets applied — most reliable for mob farms
ticket-mode: both

# Managed automatically — only edit while the server is stopped
chunks: []
```

---

### 🔑 Permissions

| Permission | Description | Default |
|---|---|---|
| `chunksticker.admin` | Grants all permissions below | `op` |
| `chunksticker.set` | Use `/ct set` | `op` |
| `chunksticker.delete` | Use `/ct delete` | `op` |
| `chunksticker.list` | Use `/ct list` | `op` |
| `chunksticker.toggle` | Use `/ct on` and `/ct off` | `op` |
| `chunksticker.reload` | Use `/ct reload` | `op` |
| `chunksticker.status` | Use `/ct status` | `op` |

**LuckPerms examples**
```
/lp group admin permission set chunksticker.admin true
/lp user Steve permission set chunksticker.set true
```

---

### 💬 Commands

| Command | Description |
|---|---|
| `/ct set [radius]` | Register the chunk you're standing in (+ radius) |
| `/ct delete [radius]` | Remove the chunk you're standing in (+ radius) |
| `/ct list` | List all registered chunks (world, X, Z) |
| `/ct on` / `off` | Enable or disable ChunkTicker globally |
| `/ct reload` | Reload config and re-apply all tickets |
| `/ct status` | Show active state and chunk count per world |

Alias: `/ct` — full command: `/chunksticker`

---

### 📋 Requirements

| | |
|---|---|
| Server | **Paper 1.21 – 1.21.x** (Purpur & Pufferfish also work) |
| Java | 21+ |

> ⚠️ **Spigot and Folia are not supported.**

---

### 🔧 How it works

Paper exposes two persistent chunk-ticket APIs at entity-ticking level (Level 31): `addPluginChunkTicket()` and `setChunkForceLoaded()`. With `ticket-mode: both`, ChunkTicker applies both to every registered chunk. Level 31 is the same load level used for chunks inside a player's view distance — entities tick, mobs spawn via the natural spawning cycle, and Redstone runs exactly as if a player were standing nearby.

---

### 📦 Source

[github.com/EmilDeuOfficial/ChunkTicker](https://github.com/EmilDeuOfficial/ChunkTicker)
