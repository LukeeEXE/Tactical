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
| `matches.placeholder-loadout` | iron_sword, bow, arrow:16 | **Temporary** (until kits in Phase 3): items given at round start, `<item>[:<amount>]`. |
| `health.locked-food-level` | `20` | Food is locked at this level (7–20), so it never drains and sprint is never lost. |
| `health.regeneration.enabled` | `true` | Simulated regeneration (vanilla regeneration is off in match worlds). |
| `health.regeneration.amount` | `1.0` | Health per pulse (1.0 = half a heart). |
| `health.regeneration.interval-ticks` | `80` | Ticks between pulses (80 = vanilla full-food rate). |
| `health.regeneration.combat-delay-ticks` | `0` | Pause regeneration after taking damage. |
| `resource-pack.enabled` | `true` | Build, host and send the pack. |
| `resource-pack.hosting` | `builtin` | `builtin` = the plugin serves the pack on `port`; `external` = you upload `resourcepack/build/tactical-pack.zip` and set `external-url` (no extra port needed). |
| `resource-pack.external-url` | `""` | (external) Direct download link to the uploaded .zip. |
| `resource-pack.port` | `25601` | (builtin) HTTP port the pack is served on; any port your host lets you open. |
| `resource-pack.public-url` | `""` | Address players download from, e.g. `http://play.example.com:25601`. |
| `resource-pack.required` | `true` | Players who decline are disconnected by their client. |
| `resource-pack.prompt` | (text) | MiniMessage prompt shown with the pack request. |
| `debug.log-state-changes` | `true` | Log every match state transition to the console. |
| `debug.log-spectator-input` | `true` | Log which inputs dead spectators send (to choose the spectator controls). |

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

### `bomb:` section
| Key | Default | Meaning |
|---|---|---|
| `defuse-radius` | 3.5 | Defuse range; the particle ring is drawn at exactly this radius. |
| `pickup-radius` | 1.5 | Distance at which attackers pick up a dropped bomb. |
| `interrupt-defuse-on-damage` | false | Whether taking damage interrupts a defuse. |
| `detonation-kill-radius` | 0 | Players within this radius die on detonation (0 = no damage). |

`modes/test.yml` is a very fast, unranked mode for administrator test matches.

## `maps/<id>.yml`, `map-pools/<id>.yml`, `blocks/*.yml`
Normally edited in-game; see `MAPS.md` and `RESOURCEPACK.md` for every key.

## `lobby/lobby.yml`
Lobby spawn (`world` key, `x y z yaw pitch`), written by `/game lobby setspawn`.

## `kits/<id>.yml` (placeholder until Phase 3)
| Key | Meaning |
|---|---|
| `id` | Must match the file name. Allowed characters: `a-z 0-9 _`. |
| `name` | Display name. |
| `description` | Short description. |
| `enabled` | Disabled kits are not offered in kit selection. |

## `messages/en_US.yml`
These are player-facing MiniMessage strings. Placeholder values such as `<round>` are inserted as plain text, so player names can never inject formatting.

## `economy/economy.yml`
| Key | Default | Meaning |
|---|---|---|
| `start-money` | 800 | Money at round 1 and after halftime. |
| `max-money` | 9000 | Cap. |
| `round-win` | 3000 | Every member of the winning team. |
| `round-loss` | [1900, 2400, 2900] | 1st, 2nd and 3rd+ consecutive loss. **Each must be below `round-win`** (validated). A win lowers the streak by one. |
| `kill` / `assist` | 200 / 50 | Assist = a teammate of the killer damaged the victim in the last 10 s. |
| `plant-team` | 300 | Every attacker, when the bomb is planted. |
| `defuse` | 300 | The defuser. |
| `survived-loss-factor` | 0.5 | Attackers who lose alive without planting get this share of the loss payout (anti-saving). |
| `halftime.reset-money` / `halftime.clear-purchases` | true / true | Second pistol round. |
| `sudden-death-floor` | 5000 | Everyone is topped up to at least this before sudden death; nobody loses money. |
| `item-retention-rounds` | 2 | Unused purchases last this many extra rounds. |
| `sell-back-same-round` | true | Right-click in the shop refunds a purchase made this round. |

## `shops/<id>.yml`
- `categories: {key: "Display name"}`: one menu row each, in this order.
- `items: {entry: {...}}`: each entry has `category`, `name`, `price`, `max` (carry limit), `enabled`, and **either**:
  - `give: {material, amount}`: armor is equipped directly; or
  - `enchant: {enchantment, level, applies-to: [items]}`.
- A map picks its shop with `shop:` in `maps/<id>.yml` (default `default`).
- Changes apply to matches started after `/game reload shops`. Running matches keep their snapshot.

## `gui/icons.yml`
`icons: {key: {material: <item>}}`: the placeholder item for each menu icon. Every icon also carries `custom_model_data` `tactical:icon/<key>` for resource-pack textures.
Kit icons use `icon.material` in `kits/<id>.yml`, plus `tactical:kit/<id>` and `state:<available|selected|locked|taken|unavailable>`.

## `hud/hud.yml`
Values marked (pack) are baked into the resource pack, so run `/game pack rebuild` after changing them. The rest apply after `/game reload hud`.
To preview, run `/game match test quickplay none 4`: you plus 4 virtual players per team, no map needed.

| Key | Default | Meaning |
|---|---|---|
| `bossbar.font` | pinch | TTF font (from `resourcepack/fonts/`) for the boss bar, hint box and money text. |
| `bossbar.text-offset-y` | 0 | Moves the timer text down (+) or up (-) in pixels. |
| `bossbar.tile-ascent` (pack) | 9 | Team strip (12 px) behind the heads; 9 = 2 px above and below the heads. |
| `bossbar.panel-ascent` (pack) | 9 | Timer panel (13 px); 9 = 4 px around the text. |
| `bossbar.score-ascent` (pack) | 12 | Square score box (18 px), centred on the heads. |
| `bossbar.time-row` (pack) | 12 | Row of the 1 px timer bar under the panel. |
| `bossbar.head-advance` / `head-slot` / `strip-padding` | 9 / 10 / 3 | Head width, space per head, strip margin. |
| `bossbar.section-gap` / `panel-padding` | 6 / 4 | Gap between sections; timer panel margin left/right of the text. |
| `bossbar.score-offset-y` | 2 | Moves the score digits down (+) / up (-). |
| `bossbar.timer-width` | 0 | Space reserved for the timer panel. 0 = the longest label, so heads and scores never move. |
| `bossbar.row-spacing` | 19 | Distance between boss bars (vanilla 19). |
| `actionbar.panel-ascent` (pack) | 16 | Hint box (21 px); 16 = clear of the held item's name. |
| `actionbar.hint-width` | 160 | Fixed hint box width; only grows if a text does not fit. 0 = fit the text. |
| `actionbar.line1-offset-y` / `line2-offset-y` | -7 / 2 | Pinch shift of the two hint lines. |
| `money.enabled` | true | Show the money widget during matches. |
| `money.offset-y` (pack) | 33 | Pixels from the action-bar line down to the hunger-bar row. |
| `money.text-offset-y` | 32 | Pinch shift of the amount. |
| `money.left` | 45 | Pixels right of the screen centre where the panel starts (45 = ends with the hunger bar). |
