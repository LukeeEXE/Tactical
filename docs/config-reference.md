# Configuration reference (Phase 1)

General rules:
- Every file starts with `schema-version`.
- Unknown keys produce a **warning** that names the allowed keys.
- Invalid values produce an **error** in the form `file:line: key.path - message`. The file is then rejected, and its previous valid version stays active.
- Durations in mode files are in **seconds**; decimals are allowed.

## `config.yml`
| Key | Default | Meaning |
|---|---|---|
| `storage.type` | `sqlite` | Storage backend. Only `sqlite` is implemented; MySQL/MariaDB are planned. |
| `storage.sqlite-file` | `data/tactical.db` | Database file, relative to `plugins/Tactical/`. |
| `recording.folder` | `logs/matches` | Folder for per-match JSONL recordings. |
| `recording.position-interval-ticks` | `4` | Position/rotation sample interval (used from Phase 2). |
| `recording.queue-soft-limit` | `50000` | Above this many waiting lines, low-priority samples are dropped and counted. Events are never dropped. |
| `recording.flush-interval-ms` | `1000` | Maximum time buffered lines wait before reaching disk. Round and match results are fsynced immediately. |
| `matches.rejoin-window-seconds` | `180` | How long a disconnected participant may rejoin. After that, or once the match ends, the association is cleared. |
| `matches.resume-countdown-seconds` | `3` | Countdown between `/game resume` and play continuing. The match stays frozen during it. |
| `debug.log-state-changes` | `true` | Log every match state transition to the console. |

## `modes/<id>.yml`
`id` must match the file name.

| Key | Quickplay | Competitive | Meaning |
|---|---|---|---|
| `display-name` | `<aqua>Quickplay` | `<gold>Competitive` | MiniMessage name. |
| `team-size` | 5 | 5 | Players per team (1–5). |
| `rounds-to-win` | 5 | 9 | First to this many round wins takes the match. |
| `halftime-after-round` | 4 | 8 | Sides swap after this round. Kit selection opens for round 5 / round 9. |
| `sudden-death-at` | 4 | 8 | At this score for both teams, one decider round is played. Each player then picks between their 1st- and 2nd-half kit. |
| `sudden-death-sides` | random | random | Sides in the decider: `keep`, `swap` or `random` (fair coin). |
| `skip-vote` | majority | majority | How a team agrees to skip halftime: `majority`, `all` or `any` of its connected players. |
| `ranked` | true | true | Whether results count for stats, XP and leaderboards. Test matches never count. |
| `timers.kit-select` | 30 | 30 | Kit selection before round 1. Cut to 3 s once everyone has locked. |
| `timers.buy-phase` | 30 | 30 | Shop phase. |
| `timers.round` | 100 | 100 | Time to plant. |
| `timers.bomb` | 45 | 45 | Fuse after plant. |
| `timers.plant` / `defuse` / `defuse-checkpoint` | 5 / 10 / 5 | same | Checkpoint progress belongs to the bomb. |
| `timers.round-end` | 5 | 5 | Pause after a round. |
| `timers.halftime` | 60 | 60 | Halftime break, including kit selection. |
| `timers.sudden-death-kit-select` | 20 | 20 | Kit choice before sudden death. |
| `timers.postgame` | 15 | 15 | Results screen. |

`modes/test.yml` is a very fast, unranked mode for administrator test matches.

## `kits/<id>.yml` (placeholder until Phase 3)
| Key | Meaning |
|---|---|
| `id` | Must match the file name. Allowed characters: `a-z 0-9 _`. |
| `name` | Display name. |
| `description` | Short description. |
| `enabled` | Disabled kits are not offered in kit selection. |

## `messages/en_US.yml`
These are player-facing MiniMessage strings. Placeholder values such as `<round>` are inserted as plain text, so player names can never inject formatting.
