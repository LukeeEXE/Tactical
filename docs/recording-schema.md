# Match recording schema (v1)

## Files
- **One file per match:** `plugins/Tactical/logs/matches/<MATCH_ID>.jsonl`, with one JSON object per line.
- **ID format:** `MATCH-YYYYMMDD-NNNNNN`, or `TEST-...` for test matches. The number comes from a per-day database sequence. An ID whose file already exists is skipped, and files are created with `CREATE_NEW`, so a recording is **never overwritten**.

## Common fields (every line)
| Field | Type | Meaning |
|---|---|---|
| `v` | int | Schema version (1). |
| `event_id` | string | `<match_id>-E<seq>`. Globally unique, strictly increasing within a match. |
| `match_id` | string | Match id. |
| `ts` | string | ISO-8601 UTC wall-clock time. |
| `elapsed_ms` | long | Milliseconds since the recording started. Includes paused time. |
| `tick` | long | Match clock: un-paused server ticks (20/s). Use this to synchronise replays. |
| `round` | int | Current round number (0 before round 1). |
| `type` | string | Event type (see below). |
| `actors` | string[] | UUIDs of the players involved. |
| `team` | string? | Original team `A` (started as attackers) or `B`, where relevant. |
| `data` | object | Fields specific to the event type. |
| `result` | string | `OK` or `FAILED`. |

## Completeness
- The **first** line is `MATCH_HEADER` and the **last** is `MATCH_FOOTER` (`data.complete = true`).
- A file without a footer was cut short by a crash or kill. `/game logs info` reports it as *INCOMPLETE*.
- Round results, state changes, plants, defuses, pauses, admin actions and errors are **fsynced** as soon as they are written.

## Event types implemented in Phase 1
| Type | `data` |
|---|---|
| `MATCH_HEADER` | `schema`, `mode`, `test`, `ranked`, `map` (null until Phase 2), `map_version`, `rules{...}`, `kit_pool[]`, `participants[{uuid,name,original_team,virtual}]`, `plugin_version`, `server_version`, `position_interval_ticks` |
| `MATCH_STATE` | `from`, `to`, `duration_ticks` |
| `KIT_SELECTION_OPEN` / `KIT_SELECTION_CLOSE` | `phase` (`MATCH_START` / `SECOND_HALF` / `SUDDEN_DEATH`), `duration_ticks` / `kits{uuid:kit}` |
| `KIT_HOVER`, `KIT_LOCK` | `kit`, `automatic` (lock only) |
| `SKIP_VOTE` | `team_agrees` |
| `ROUND_START` | `round`, `sudden_death`, `sides{A,B}` |
| `ROUND_LIVE` | `round` |
| `ELIMINATION` | `victim`, `killer?`, `cause` (`KILLED`, `SUICIDE`, `ENVIRONMENT`, `BOMB`, `DISCONNECT`, `AFK`, `ADMIN`, `OUT_OF_BOUNDS`) |
| `PLAYER_KILL` | `killer`, `victim`, `weapon`, `location{x,y,z,world}` |
| `BOMB_PLANTED` / `BOMB_DEFUSED` / `BOMB_EXPLODED` | `site` / – / – |
| `ROUND_END` | `round`, `winner_side`, `winner_team`, `reason`, `score_a`, `score_b` |
| `HALFTIME` / `SUDDEN_DEATH` | `after_round`, `sides` / `sides` |
| `MATCH_END` | `winner?`, `reason` (`COMPLETED`, `ADMIN_ENDED`, `ERROR`, `INTERRUPTED`, `FORFEIT`), `score_a`, `score_b`, `detail` |
| `PAUSE` / `RESUME_COUNTDOWN` / `RESUME` | `reason` / `countdown_ticks` / `paused_ticks` |
| `PLAYER_DISCONNECT` / `PLAYER_RECONNECT` / `PLAYER_LEAVE` | `was_alive` / – / `reason` |
| `TIMER_CHANGE` | `state`, `old_ticks`, `new_ticks`, `reason` |
| `ADMIN_ACTION` | `action`, `actor_name`, `details` |
| `ERROR` | `message`, `state`, `exception`, `stacktrace` |
| `MATCH_FOOTER` | `reason`, `events`, `low_priority_events`, `complete` |

## Added in Phase 2
| Type | `data` |
|---|---|
| `MATCH_HEADER` (extra) | `map`, `map_version`, `map_name`, `map_sites{id:{min,max}}` |
| `SNAPSHOT_POSITIONS` | `players[{id,x,y,z,yaw,pitch,hp,alive,sneak?}]`, every `position-interval-ticks` (low priority) |
| `DAMAGE` | `victim`, `attacker?`, `cause`, `amount`, `health_before`, `lethal` |
| `PLAYER_KILL` | `killer`, `victim`, `weapon`, `projectile`, `location{x,y,z}` |
| `BOMB_ASSIGN` / `BOMB_PICKUP` | `x,y,z` (carrier position, if real) |
| `BOMB_DROP` | `x,y,z`, `reason` (`dropped`, `carrier_eliminated`, `carrier_disconnected`) |
| `PLANT_START` / `PLANT_CANCEL` | `x,y,z,site` / `reason` (`released`, `left_site`, `not_on_ground`) |
| `BOMB_PLANT_LOCATION` | `x,y,z,site` (ground position of the planted bomb) |
| `DEFUSE_START` / `DEFUSE_PROGRESS` / `DEFUSE_CHECKPOINT` / `DEFUSE_CANCEL` | `x,y,z,from_ticks` / `progress_ticks` (every second) / `progress_ticks` / `reason`, `progress_ticks`, `checkpoint_reached` |
| `BLOCK_CHANGE` | `action` (`barriers_removed` with `barriers[{barrier,blocks}]`, or `round_reset` with `blocks`) |

## Reserved for later phases
These types are already defined in `EventTypes` but not yet emitted:
- **Combat:** `SNAPSHOT_FULL`, `ATTACK`, `ASSIST`, `PROJECTILE_LAUNCH`, `PROJECTILE_PATH`, `PROJECTILE_HIT`, `UTILITY_USE`, `UTILITY_EFFECT`
- **World and economy:** `PURCHASE`, `PURCHASE_FAILED`, `ECONOMY_CHANGE`, `ECONOMY_COMPENSATION`, `INVENTORY_CHANGE`, `ITEM_EXPIRED`
- **System:** `AFK_ELIMINATION`, `VALIDATION_ERROR`, `WARNING`

Their field lists will be added here as each one is implemented.
