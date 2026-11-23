# Building maps

All map work happens in-game (permission `tactical.admin.maps`; ops have it by default).
Every change is saved immediately to `plugins/Tactical/maps/<id>.yml`. The previous version is kept in `backups/maps/<id>/`.

## 1. Create and build
```
/game map create ascent          # empty void template world; you are teleported in, in creative mode
```
- Build the map normally. The template world (`tactical:map_ascent`) is only ever used for editing.
- `/game map edit <id>` comes back to it later; `/game map leave` returns to the lobby.
- Particle previews: white = boundaries, red = bomb sites, aqua = barriers, lime = construction zones, yellow = your selection. Red and blue columns mark attacker and defender spawns. Toggle with `/game map preview`.

## 2. Configure
Get the selection wand with `/game map wand`: left-click a block for corner 1, right-click a block for corner 2.

| Command | What it does |
|---|---|
| `/game map bounds` | Sets the map boundaries from the selection. Leaving them (or falling below `void-y`) eliminates the player. |
| `/game map voidy <y>` | Height below which players are eliminated. |
| `/game map spawn add attackers` / `defenders` | Adds a spawn at your feet, facing your direction rounded to 15°. Add one per player (5 per team). |
| `/game map spawn removenearest` / `spawn clear <team>` | Removes spawns. |
| `/game map site add A` / `site remove A` | Sets a bomb site from the selection. Two or three sites are normal. |
| `/game map barrier add atk_wall glass-only` | Spawn wall: the blocks in the selection are removed when the buy phase ends and restored every round. Add `glass-only` to only remove glass in the region. |
| `/game map barrier remove <name>` | Removes a barrier. |
| `/game map construction add` / `clear` | Zones where construction blocks may be placed (used from Phase 3). |
| `/game map kits all` / `whitelist <kits…>` / `blacklist <kits…>` | Map-specific kit availability. |
| `/game map modes quickplay competitive` | Which modes may use the map. |
| `/game map rename <name…>` | Display name. |
| `/game map info [id]` | Shows everything configured. |

Any layout change increases the map's **version** and puts it back to **DRAFT**. The version is recorded in every match log.

## 3. Validate, publish, enable
```
/game map validate          # checks everything; on success takes a SNAPSHOT of the template (status VALIDATED)
/game map publish           # same checks, status PUBLISHED (selectable by matchmaking)
/game map enable ascent
/game pool add quickplay ascent
```
Validation checks:
- both teams have spawns, and spawns are inside the bounds and not inside blocks;
- there are bomb sites, inside the bounds, not overlapping, each with a spot a player can stand on;
- barriers are inside the bounds and contain removable blocks;
- a **reset round-trip** succeeds (barriers removed and restored identically);
- kits are valid, with enough allowed kits for 5 distinct picks.

Errors are listed one per line.

Matches copy the **snapshot**, never the live template. You can keep editing a published map without affecting matches until you publish again.

## 4. Test
```
/game match test quickplay ascent 4                 # you + 4 virtual players per team
/game match test test ascent 0 Alice,Bob,Carol      # real players split A/B, fast 'test' mode
```
- Test matches never count for stats.
- Virtual players have no body. They exist so you can play a round alone, and they can be eliminated with `/game debug eliminate <match> <A|B> <n>`.
- `VALIDATED` maps are allowed in test matches; matchmaking only uses `PUBLISHED` + enabled maps.

## Pools
| Command | What it does |
|---|---|
| `/game pool list` | Every pool, with the reason each map is or isn't selectable. |
| `/game pool create <id> <mode>`, `/game pool delete <id>`, `/game pool rename <id> <new>` | Manage pools. |
| `/game pool add <pool> <map>`, `/game pool remove <pool> <map>`, `/game pool toggle <pool> <map> on|off` | Manage a pool's maps. |
| `/game pool enable|disable <pool>` | Turn a pool on or off. |

Pool changes affect future matches only. A pool with no selectable maps is reported at startup and reload.

## Manual editing
- Hand-editing `maps/<id>.yml` is fine; run `/game reload maps` afterwards.
- A hand-edited layout is automatically put back to DRAFT until validated again.
- Deleting a map (`/game map delete <id> confirm`) moves its file to `backups/maps/` and removes its template world and snapshot.

## Where files live
| What | Location |
|---|---|
| Definitions | `plugins/Tactical/maps/<id>.yml` |
| Template worlds | `<level>/dimensions/tactical/map_<id>/`. Back these up: they are your builds. |
| Snapshots | `plugins/Tactical/maps/snapshots/<id>/` |
| Match instances | `<level>/dimensions/tactical/inst_<matchid>/`. Temporary; deleted after each match and on startup. |
