# Tactical Shooter for Minecraft Java 26.3: Phase 0 Architecture & Roadmap

Status: **REVISED after review (rev 2). All open questions resolved (§10); awaiting final go-ahead for Phase 1.** No gameplay code has been written yet.
Working names: plugin `Tactical`, data folder `plugins/Tactical/`, resource namespace `tactical:`. All three can be renamed before Phase 1.

---

## 1. Research findings

### 1.1 Server platform: **Paper 26.3** (recommended)

| Option | Verdict |
|---|---|
| **Paper 26.3** | Stable builds since 11 Oct 2026 (build ~258 at the time of writing). Needs **Java 25**. It has the richest maintained API: Adventure text, the data-component API, display entities, Mannequin entities, `PlayerInputEvent`, per-player entity visibility, `AsyncChatEvent` viewer control, the Brigadier command API and the Lifecycle API. |
| Purpur / other Paper forks | They are compatible, but they add nothing we need. We will build against Paper-API only, so the plugin also runs on forks. |
| Folia | Rejected. Regionised threading makes cross-match services such as matchmaking, lobby, HUD and logging much harder, and two 10-player matches do not need it. |
| Spigot | Rejected. Paper hard-forked and no longer mirrors Spigot API. Our design needs Paper-only API. |

Notes on the 26.x platform:
- From 26.1, Mojang ships **unobfuscated** server jars, and Paper removed its remapper. Plugins that touch server internals (NMS) break on every update. **We will not touch NMS.** Anything below the API goes through the packet library.
- Paper changed its dependency versioning in 26.2: the old `-R0.1-SNAPSHOT` suffix is gone. We will pin an exact Paper-API build in Gradle and never use a `26.3.+` range.
- Worlds upgraded to 26.3 cannot be downgraded. Map templates must be authored on 26.3.
- Clients must be **Java 26.3**. Custom fonts, item model definitions and object text components depend on the client version. ViaVersion and Bedrock (Geyser) players are **not supported**.

### 1.2 Packet library: **DECIDED: PacketEvents** (long-term choice)

Why PacketEvents is better for the long run:
- It ships tagged, stable releases quickly after each Minecraft update: 26.1 in 2.12.0, 26.2 in 2.13.0, 26.3 in 2.14.0.
- Its typed packet wrappers mean a change to a packet's fields breaks our build (we see it immediately), instead of failing at runtime through reflection.
- ProtocolLib's 26.x support only exists in an untagged dev build.

Our `PacketService` interface still isolates it completely. If PacketEvents ever stalls on a future version, a ProtocolLib adapter can be dropped in without touching gameplay code.

| | ProtocolLib | PacketEvents |
|---|---|---|
| Latest stable release | 5.4.0. It covers MC 1.21.4–1.21.8 only. | **2.14.0**. Its release notes say it "mainly includes support for Minecraft 26.3". |
| 26.3 support | **Only in the rolling `dev-build` pre-release** ("Update to Minecraft 26.3", "Fix Minecraft 26.3 play packet mappings"). There is no tagged release. | A tagged, stable release. |
| API style | Reflection over raw packet fields. It breaks more easily when packet shapes change. | Typed wrapper classes for each packet. |

You asked me to use ProtocolLib when its compatibility is verified, and to propose an alternative if it falls short. It **is** usable on 26.3, but only through an untagged development build. For a production game server I recommend **PacketEvents 2.14.0** as the implementation.

Both libraries would sit behind our own `PacketService` interface. Every packet use goes through that one module, so a ProtocolLib adapter can be added later without touching gameplay code.

Packet-level work is limited to what the Paper API **cannot** do:
1. **Per-viewer glowing.** The glowing flag is shared entity metadata. Outgoing `ENTITY_METADATA` must be rewritten for each viewer, so only dead teammates and authorised spectators see outlines.
2. **Fake tablist entries.** These give a fixed MCC-style layout with 10 slots, a skull shown in place of the head, and team separators. The Paper API cannot add fake player-info entries.
3. Possibly **spectator click input**. See §1.9.

Everything else uses the Paper API: boss bars, the action bar, per-player scoreboards and team colours, resource packs, entity show/hide, skins and chat.

### 1.3 Simple Voice Chat: compatible
- The Bukkit/Paper plugin **bukkit-2.6.27** (28 Oct 2026) supports 26.3.
- We compile against `de.maxhenkel.voicechat:voicechat-api` and register through `BukkitVoicechatService`. It is a **soft dependency**: the game runs without voice, and logs a warning.
- For each team in each match: `api.groupBuilder().setName(..).setPassword(random).setType(Group.Type.ISOLATED).setPersistent(true).setHidden(true)`. The hidden setter still needs confirming against the current API jar; if it is missing, a random password plus cancelled join events gives the same protection.
  - `ISOLATED` means members hear only their group, and outsiders hear none of it. This covers both "spectators can't listen" and "opponents can't hear each other".
  - The plugin puts players in groups with `VoicechatConnection#setGroup`. Players cannot choose a group themselves: `JoinGroupEvent` and `LeaveGroupEvent` are cancelled for any player in a match, and for anyone trying to join a match group.
  - Groups are removed on match cleanup. Reconnecting players are put back in their group in `PlayerConnectedEvent`.
- We never touch audio packets. Nothing voice-related is recorded.

### 1.4 Custom Model Data in 26.3
Since 1.21.4, item visuals come from **item model definitions** (`assets/<ns>/items/*.json`). The `minecraft:custom_model_data` component is now a compound of `floats` / `flags` / `strings` / `colors` lists, and `select` / `range_dispatch` model definitions read from it. The old integer `custom_model_data=1` predicate does **nothing** on 26.x. The wiki also lists `set_custom_model_data` as changed in a 26.3 snapshot, so the exact JSON will be verified on a live 26.3 client in Phase 1.

Design:
- Every custom item has `model.custom-model-data.strings: ["tactical:<item_id>"]`. You can also add floats, flags or colours for variants.
- The resource-pack builder generates a `select` on `custom_model_data` strings for each base material. Unknown strings fall back to the vanilla model.
- An optional `model.item-model` field sets the `minecraft:item_model` component directly. Use it when one item needs a model that is fully independent of its base material.
- Visuals and mechanics are separate: editing `model:` in YAML or the GUI changes only the look.

**Authoritative identity is never the model.** Each custom item carries Persistent Data Container keys:
`tactical:item_id`, `tactical:instance` (UUID), `tactical:origin` (KIT_BASE / PURCHASED / ADMIN), `tactical:match`, `tactical:round_bought`, `tactical:sig`.
`sig` is an HMAC-SHA256 over those fields, keyed with a server secret stored in `data/secret.key`. An item edited in creative mode, or rebuilt with matching visuals, fails validation and is deleted, and the deletion is logged. Crafting, anvils, grindstones, smithing and enchanting tables are blocked for custom items.

### 1.5 HUD feasibility
| Requirement | Approach | Feasibility |
|---|---|---|
| Money counter at the hunger bar position | The action bar is drawn centred above the hotbar. A custom font with an ascent offset moves the line down, and negative-advance `space` glyphs shift it right over the hunger area. Offsets live in `hud.yml`. | ✅ It moves correctly with GUI scale, because font pixels scale with the GUI. |
| Hiding the hunger bar | **DECIDED: the hunger-effect method.** See §5.9. Every player has a permanent, hidden Hunger effect. The resource pack makes **only the Hunger-effect variants** of the food sprites transparent (`hud/food_empty_hunger`, `hud/food_half_hunger`, `hud/food_full_hunger`). The normal food textures are untouched. | ✅ |
| Bossbar match panel | Use 1–3 Adventure `BossBar`s for each viewer. The bar graphic is made transparent through the pack (only for the colour we reserve), and the panel is drawn entirely as text in custom fonts with negative spacing. The API is enough here; no packets are needed. | ✅ |
| Player heads inline (bossbar, tab, chat, text displays) | **Object text components** (added in 1.21.9): `player` heads and atlas `sprite`s rendered inline at glyph height. Adventure supports them. | ✅ To be confirmed with a Phase 1 spike in each render location. |
| Kit, currency and skull icons, 16×16 | Bitmap font glyphs (16×16 source, `height: 8`, `ascent: 7`) in the Private Use Area. The pack builder assigns code points automatically from config. | ✅ |
| One central action-bar owner | `HudService` owns the action bar for each player. Other systems send it "segments" with priority and a time to live, and it composes them so nothing overwrites the money display. | ✅ |

### 1.6 NPCs, heads and entity displays
- **NPCs:** the **vanilla `minecraft:mannequin` entity** (added in 1.21.9) through Paper's Mannequin API. It is a player-shaped entity with a configurable profile and skin. There is **no NPC library dependency** and no packets. Skins are resolved with `Bukkit.createProfile(name).update()` off the main thread, and the texture value and signature are cached in `npc/npcs.yml`. FancyNpcs (which supports 26.3) is the fallback if Mannequins turn out to be unsuitable.
- **Bomb, leaderboards, holograms:** `ItemDisplay` and `TextDisplay`, with no collision. `Interaction` entities make leaderboard rows clickable. Per-viewer lines use `Entity#setVisibleByDefault(false)` together with `Player#showEntity`.

### 1.7 Other relevant 26.x mechanics
- **Claymore 1.8-style blocking:** the `minecraft:blocks_attacks` component (damage reduction, block sound, disable time) together with a `consumable` component using the `block` animation. Knockback reduction is applied in `EntityKnockbackEvent`.
- **Defuse input:** `PlayerInputEvent` gives the client's real sneak and movement key state, which is better than only watching `PlayerToggleSneakEvent`. Stationary is checked with **server-side position deltas** and never with client input alone.
- **Slower attack speed, extra damage:** attribute modifiers through the data-component API.

### 1.8 Dependencies (final list)
| Dependency | Version | Type |
|---|---|---|
| Paper | 26.3 (pinned build) | server |
| Java | 25 | runtime |
| PacketEvents | 2.14.0 | required plugin |
| Simple Voice Chat | bukkit-2.6.27 | optional plugin |
| LuckPerms | any 26.3 build | optional; our own role system works without it |
| SQLite JDBC, HikariCP | latest | shaded |
| Gson, SnakeYAML/Configurate | Paper-bundled / shaded | |

The build container has Java 21. Gradle toolchains will provision Java 25 for compiling.

### 1.9 Technical limitations found (and workarounds)
1. **Hunger bar hiding:** resolved with the hunger-effect sprite variants (§5.9).
2. **Hunger drain:** resolved. Food is locked, so sprint can never be lost, and regeneration is simulated by the plugin (§5.9).
3. **Spectator left/right-click cycling.** A player in spectator mode who is spectating an entity may not send swing or use packets. Plan: dead players use the vanilla spectator camera (`setSpectatorTarget`), and we test click detection at packet level in a Phase 1 spike. If clicks do not arrive, the fallback is the hotbar scroll and number keys (`PlayerItemHeldEvent`, which still fires) together with the sneak key (`PlayerStopSpectatingEntityEvent`, which is cancelled and treated as "next"). I'll tell you which one works before building on it.
4. **Outside spectators** need flight **with** block collision, and spectator mode has none. They are therefore Adventure-mode, flying, invisible, non-collidable players hidden from participants, not vanilla spectators.
5. **Simultaneous matches on the same map:** Quickplay and Competitive can vote the same map. Every match therefore runs on its **own world instance**, copied from a map template (§4.5).
6. **Mid-round recovery after a crash** cannot restore entities, projectiles or the world state exactly. Recovery resumes at the round boundary instead (§7.3).
7. **Automated 10-player tests:** no headless client library is verified for 26.3. Gameplay logic is designed to be testable without Bukkit (§8), and real-client playtests are needed for visuals and feel.

---

## 2. High-level architecture

```
tactical/                      (Gradle multi-module, Kotlin DSL, Java 25)
├── tactical-api/              Public interfaces, events, immutable definitions (for future add-ons)
├── tactical-engine/           PURE JAVA, no Bukkit: state machines, timers, round rules, bomb/defuse
│                              logic, economy math, matchmaking solver, combat score, XP, AFK rules,
│                              YAML schema validation. 100% unit-testable.
├── tactical-paper/            The Paper plugin: adapters that bind the engine to Bukkit
├── tactical-packets/          PacketService interface + PacketEvents implementation
├── tactical-voice/            Simple Voice Chat integration (loaded only if SVC is present)
├── resourcepack/              Placeholder assets + pack builder inputs
└── docs/                      Installation, configuration reference, schemas
```
**One deployable jar**, `Tactical.jar`. The voice and packet modules are packaged inside it but only loaded when their dependency is present.

### 2.1 Services and match-scoped components
```
Global services (singletons, ServiceRegistry)          Match-scoped (one set per MatchId)
─────────────────────────────────────────────          ──────────────────────────────────
ConfigService      (registries, reload, migration)     Match           (state machine, MatchContext)
StorageService     (DB, async executor)                RoundController (round loop, win conditions)
PlayerService      (profiles, sessions, UUID → state)  TeamRoster      (sides, original identity)
PartyService                                           BombController  (carrier, plant, defuse)
MatchmakingService (queues, solver, map vote)          MatchEconomy    (wallets, loss streaks)
MatchService       (create/lookup/cleanup matches)     MatchInventory  (base vs purchased items)
MapService         (templates, instances, editor)      MatchHud        (bossbar, actionbar, tab)
KitService / ItemService / ShopService                 MatchSpectators (camera, glow rules)
HudService / TabService / ChatService                  MatchVoice      (2 SVC groups)
VoiceService / SoundService                            MatchRecorder   (JSONL writer)
StatsService / ProgressionService / Leaderboards       MatchWorld      (instance, block journal)
AdminService / ModerationService / ReportService       MatchEventBus   (typed, synchronous)
RecorderService / BackupService / RecoveryService
GuiService / NpcService / LobbyService / Metrics
```
Rules:
- Modules talk only through interfaces in `tactical-api`. Match-scoped components publish typed events on their match's **MatchEventBus**: `PlayerKilledEvent`, `BombPlantedEvent`, `RoundEndedEvent` and so on. HUD, stats, the recorder, sounds and the economy **subscribe**. The round logic does not know the HUD exists, which prevents circular dependencies.
- **Isolation:** every match-scoped object holds its `MatchId`. Bukkit listeners resolve `player → MatchSession` once through an O(1) map in `PlayerService`, then hand off to that match. A handler that finds actors in different matches (or a match and the lobby) **cancels** the action. This blocks damage, projectiles, item pickup, interaction, chat visibility and entity visibility across matches.
- **Config snapshots:** all YAML is parsed into **immutable records** (`MapDefinition`, `KitDefinition`, `ItemDefinition`, `ShopDefinition`, `EconomyRules`…). A reload builds new registries and swaps them atomically. A match captures its definitions when it is created, so reloads and edits only affect future matches.
- **One global tick loop** (one Bukkit task every tick) calls `tick()` on each active match in turn. There are no per-feature repeating tasks to leak. Timers are counters owned by the match, so **pause** is simply "don't advance this match's timers".

---

## 3. Match state machine

```
CREATED → LOADING (instance world) → KIT_SELECT(start) ─┐
                                                        ▼
        ┌──────────── BUY_PHASE (30s) → ROUND_LIVE (100s) ──┬→ ROUND_END (5s) ──┐
        │                                  │ plant          │                   │
        │                                  ▼                │                   │
        │                           BOMB_PLANTED (45s) ─────┘                   │
        │                                                                       │
        │ next round ◄──────────────────────────────────────────────────────────┤
        │                                                                       │
        │      half reached → HALFTIME (60s, kit select + skip vote) ───────────┤
        │      4-4 / 8-8    → SUDDEN_DEATH_KIT_SELECT (1st/2nd-half kit) → BUY_PHASE ─────────────────┤
        └───── target wins reached → MATCH_END → POSTGAME (results) → CLEANUP → CLOSED
Any state ──admin/fatal──► ABORTED → CLEANUP        PAUSED = overlay flag, not a state
```
- Allowed transitions are declared in one table in `tactical-engine`. Any other transition throws `IllegalStateTransition`, which is logged with the match ID and the last event ID, and the match is aborted safely. Corrupt states are never silently accepted.
- Mode rules come from data:

```yaml
# modes/competitive.yml
rounds-to-win: 9
halftime-after-round: 8
sudden-death-at: 8      # score 8-8 → one decider
```
  Quickplay is the same shape with `5 / 4 / 4`.
- **Kit selection:** happens **twice per match**:
  - before round 1;
  - during halftime, before the second half's first round: round 5 in Quickplay, round 9 in Competitive.

  Before sudden death there is a **third, restricted selection** (`SUDDEN_DEATH_KIT_SELECT`, default 20 s, configurable):
  - Each player may choose only between **their first-half kit and their second-half kit**. If both were the same kit, it is locked automatically.
  - The no-duplicate-teammates rule still applies. Kits stay publicly visible to both teams.
  - **Deadlock-proof locking:** a valid assignment always exists, because the first-half kits are already a duplicate-free set. So the server checks every lock request with a bipartite matching of players to their two options. A lock is accepted only if every still-unlocked teammate can still get one of their options. Greyed-out GUI buttons show options that would break this.
  - When the timer runs out, or a player is disconnected, the server fills the gaps with a valid matching. It prefers the kit the player last hovered or selected, then their second-half kit.
  - Both teams can skip the remaining time once everyone has locked.

### 3.1 Round-end precedence (deterministic, evaluated each tick in this order)
1. Bomb **defused** this tick → defenders win. A defuse that finishes on the same tick as detonation counts as a win for defenders.
2. Bomb **detonated** → attackers win.
3. Bomb planted and **all defenders dead** → attackers win (the bomb can no longer be defused).
4. Bomb **not** planted and all attackers dead → defenders win. A plant still in progress is cancelled when the planter dies.
5. Bomb planted and all attackers dead → **the round continues**. Defenders must still defuse, or they lose when it detonates.
6. Bomb not planted and all defenders dead → attackers win.
7. Round timer expires before a plant → defenders win. A plant that completes on the expiry tick counts as planted.

Disconnected and AFK-eliminated players count as dead for every rule above.

### 3.2 Bomb, plant, defuse
- **Carrier:** CS-style. At buy-phase end a random attacker receives the bomb item. It drops on death or disconnect as an `ItemDisplay` with an `Interaction` hitbox, and only attackers can pick it up (this is where the "Bomb pickup" event comes from). The bomb cannot be dropped inside spawn barriers or out of bounds.
- **Plant:** hold right-click with the bomb inside a configured site region for 5 s. The `consumable` component provides the client animation, but the **server** counts the ticks and checks the region, ground contact, sneak/movement rules and the live state. Interruptions are release, leaving the site, death and round end.
- **Planted bomb:** an `ItemDisplay` that is not collidable, plus a ring of dust particles (radius 3.5) drawn every 10 ticks with a configurable point count. Only participants see it, through per-viewer visibility, and the particle budget is capped.
- **Defuse (10 s, checkpoint at 5 s, both configurable):**
  - Requirements: a defender who is alive, within `defuse-radius`, sneaking (by `PlayerInputEvent` and `isSneaking`), **and** has moved less than 0.05 blocks horizontally per tick (server position).
  - Interrupted by: releasing sneak, any horizontal movement, leaving the radius, death, disconnect, round end, the match pausing (which freezes rather than interrupts), and switching to the bomb-site item. Taking damage interrupts only if `interrupt-on-damage: true` (default false).
  - Only one active defuser at a time; the first valid one wins.
  - Progress belongs to the **bomb**: `progressTicks` and `checkpointReached`. On interruption: if the checkpoint was reached, progress resets to the checkpoint (5 s), otherwise to 0. The next defender continues from there.

---

## 4. Data model & configuration

### 4.1 Directory layout
```
plugins/Tactical/
├── config.yml              general, storage, resource pack hosting, debug
├── modes/                  quickplay.yml, competitive.yml, test.yml
├── maps/<id>.yml           map definitions (+ maps/templates/<id>/ world folders)
├── map-pools/              quickplay.yml, competitive.yml (any number)
├── kits/<id>.yml
├── items/                  weapons.yml, utility.yml, abilities.yml, … (any *.yml)
├── shops/                  default.yml, <custom>.yml
├── economy/economy.yml
├── progression/            xp.yml, challenges.yml, achievements.yml, cosmetics.yml
├── combat-score.yml
├── hud/hud.yml             offsets, fonts, layout
├── sounds/sounds.yml
├── npc/npcs.yml
├── lobby/lobby.yml         spawn, leaderboard positions, announcements
├── messages/en_US.yml      every player-facing string (MiniMessage)
├── resourcepack/           assets/ you drop in; build/ generated pack zip
├── logs/matches/           MATCH-*.jsonl(.gz)
├── backups/                config/, database/, stats-reset/
└── data/                   tactical.db (SQLite), secret.key, recovery/
```
Every YAML file starts with `schema-version: N`. `ConfigMigrator` upgrades old files step by step, **backing up the original to `backups/config/<timestamp>/` first**. Bundled defaults are written **only if a file does not exist**, so your edits are never overwritten. Invalid files are **rejected, not partially applied**: the previous valid version stays active and errors are reported as `file:line: path.to.key — message`. Every key is documented in `docs/config-reference.md`, which is generated from the schema classes so the documentation cannot drift.

### 4.2 Map YAML (sketch)
```yaml
schema-version: 1
id: ascent_mc
name: "Ascent"
enabled: true
version: 7                       # auto-increments on publish; recorded in match logs
status: PUBLISHED                # DRAFT | VALIDATED | PUBLISHED
template-world: ascent_mc        # folder under maps/templates/
modes: [quickplay, competitive]
bounds: { min: [-120, 40, -90], max: [140, 120, 110] }
spawns:
  attackers: [{ x: 10.5, y: 64, z: -80.5, yaw: 0, pitch: 0 }, ...]
  defenders: [...]
spawn-barriers: [{ name: atk_wall, min: [...], max: [...], block: GLASS, remove-at: BUY_PHASE_END }]
bomb-sites:
  A: { region: { min: [...], max: [...] }, display-name: "A" }
  B: { region: { ... } }
construction:
  allowed-regions: [ ... ]       # empty = anywhere inside bounds except spawns/sites if configured
  max-per-player: 8
kits: { mode: ALL }              # or WHITELIST/BLACKLIST with list
shop: default                    # or a map-specific shop id
overrides: { prices: { utility_orb: 250 } }
```

### 4.3 Item YAML: behaviour types + parameters
**New items of an existing behaviour type need no code. A new behaviour *type* needs code.** That is the boundary of what is data-driven.
```yaml
schema-version: 1
items:
  frag_orb:
    type: impact_orb            # impact_orb | timed_orb | lingering_orb | spark | stationary_trap |
                                # construction_block | weapon | claymore | heavy_crossbow | bomb | tool
    material: SNOWBALL
    name: "<red>Frag Orb"
    lore: ["Explodes on impact."]
    model: { custom-model-data: { strings: ["tactical:frag_orb"] } }
    icon-glyph: auto
    price: 300
    max-carry: 2
    cooldown-ticks: 0
    params: { radius: 3.5, velocity: 1.4 }
    effects:                    # composable effect list, shared by all utility types
      - { type: damage, amount: 8, falloff: linear, affects: ENEMIES }
      - { type: potion, effect: SLOWNESS, amplifier: 1, duration-ticks: 60, affects: ALL }
      - { type: particles, preset: explosion_small }
      - { type: sound, event: utility.frag.explode }
```
Effect types available from the start: `damage`, `heal`, `potion`, `knockback`, `reveal` (a temporary glow, for allowed viewers only), `blind_flash`, `particles` and `sound`. Spark items use `targets: { left-click: ENEMY|ALLY|ANY, right-click: SELF }`.

### 4.4 Kit and shop YAML (sketch)
```yaml
# kits/vanguard.yml
schema-version: 1
id: vanguard
enabled: true
name: "Vanguard"
description: ["Frontline initiator."]
icon: { glyph: auto, texture: kits/vanguard.png }
equipment:
  head: { material: LEATHER_HELMET, color: "#aa3333", unbreakable: true }
  chest: ...
starting-items:
  - { slot: 0, material: IRON_SWORD, enchantments: { sharpness: 1 } }
  - { slot: 1, item: heavy_crossbow }
  - { slot: 2, item: frag_orb, amount: 1, refill: EACH_ROUND }   # EACH_ROUND | ONCE_PER_HALF | NEVER
abilities: [ { item: vanguard_spark, charges: 1, refill: EACH_ROUND } ]
shop: { inherit: default, add: [ vanguard_extra_orb ], remove: [ ], price-overrides: {} }
maps: { mode: ALL }
```
Base-kit items are tagged `origin=KIT_BASE`. They cannot be dropped, moved into containers or sold, and they are restored to their configured state each round. Purchased items are tagged `PURCHASED` with `round_bought`.

### 4.5 Map instancing
- Templates are small void worlds edited directly in the map editor (`/game map edit <id>` teleports you into the template).
- At match start, `MapService` copies `maps/templates/<id>/` to `worlds/instances/<matchId>/` **asynchronously**, then loads it on the main thread with a void generator, no spawn chunks kept loaded and autosave off.
- **Per-round reset** uses a `BlockJournal`. Every block change made by game mechanics (construction blocks, removed glass, round-start block changes) is recorded with its original state and reverted in reverse order at round end. This is much cheaper than reloading the world. The journal is also logged to the recording, which serves browser replays.
- On match cleanup the world is unloaded and deleted.

### 4.6 Database schema (SQLite by default, MySQL/MariaDB optional)
A single-writer async executor with WAL mode, and versioned migrations (`V1__init.sql` …) applied inside a transaction.
```
players(uuid PK, last_name, first_seen, last_seen, xp, level, cosmetics_json)
player_names(uuid, name, seen_at)                      -- report lookup for offline players
stats_lifetime(uuid PK, kills, deaths, assists, rounds_won, rounds_played, games_won,
               games_played, damage_dealt, damage_taken, plants, defuses, utility_used, combat_score_sum)
stats_period(uuid, period_type[DAY|MONTH], period_key, ...same counters...)  -- daily/monthly boards
stats_map(uuid, map_id, ...) / stats_kit(uuid, kit_id, ...)
matches(match_id PK, mode, map_id, map_version, started_at, ended_at, score_a, score_b,
        winner, end_reason, is_test)
match_players(match_id, uuid, original_team, kit_first_half, kit_second_half, k, d, a,
              damage, combat_score, plants, defuses, xp_awarded, rewards_granted BOOL)
roles(uuid PK, role[ADMIN|MODERATOR], granted_by, granted_at)
queue_bans(id, uuid, reason, staff_uuid, created_at, expires_at NULL, revoked_by, revoked_at)
mutes(... same shape ...)
reports(id, reporter_uuid, reporter_name, target_uuid, target_name, reason, created_at,
        match_id, mode, map_id, status[PENDING|REVIEWED|DISMISSED], handled_by, handled_at)
report_notes(report_id, staff_uuid, note, created_at)
audit_log(id, actor_uuid, actor_name, action, target, details_json, created_at)
challenges_progress(uuid, challenge_id, period_key, progress, completed_at)
achievements(uuid, achievement_id, unlocked_at)
stats_resets(id, actor_uuid, actor_name, at, categories, backup_path, result)
match_seq(day PK, seq)                                  -- MATCH-YYYYMMDD-NNNNNN
```
`rewards_granted` is set inside the same transaction that writes stats, so a match can never be rewarded twice. **Test matches** (`is_test=1`) skip stats, XP, leaderboards, challenges and the economy records, but **are still recorded** to JSONL for debugging.

---

## 5. Gameplay systems design

### 5.1 Economy (all values in `economy/economy.yml`)
| Source | Default |
|---|---|
| Starting money (pistol rounds 1 and halftime) | 800 |
| Round win | 3000 |
| Round loss: streak 1 / 2 / 3+ | 1900 / 2400 / 2900 |
| Kill | 200 |
| Assist | 50 |
| Plant (whole attacking team, even if they lose) | 300 |
| Defuse (defuser) | 300 |
| Attacker survived a lost round without planting ("anti-save") | loss bonus × 0.5 |
| Maximum money | 9000 |
| Sudden death | No reset. Everyone keeps their funds plus a **floor top-up to 5000**. |

- Every possible loss payout stays **below** the win payout, so losing on purpose never pays. The loss streak drops by one on a win instead of resetting to 0, which damps economic snowballing.
- Halftime resets money to the starting amount and clears purchased items (a second pistol round). This is configurable: `halftime: { reset-money: true, clear-purchases: true }`.
- **Item retention:** an item bought in round N is usable in rounds N, N+1 and N+2. If it is unused, it is removed at the start of round N+3. Dying with purchased items loses them (they are deleted, not dropped). Retention is checked against `round_bought` in the PDC and the server-side ledger together, so moving an item between slots cannot reset its age.
- **Purchases** are validated server-side against the `MatchEconomy` ledger: buy phase active, player alive and in spawn, enough money, under `max-carry`, item enabled, item in this kit's and map's shop. Each purchase is atomic: debit, give, log. If giving the item fails, the purchase rolls back.

### 5.2 Disconnect compensation
- The player must still be disconnected **at the start of the next round's buy phase**. A disconnect followed by a reconnect inside the same round pays nothing.
- **Once per player UUID per match**, tracked in a set, so disconnect/reconnect cycles cannot farm it.
- `grant = base(1000) × roundFactor × (1 + 0.25 × (concurrentDisconnects − 1))`.
  - `roundFactor` is 0.5 in a pistol round and 1.0 otherwise.
  - The grant is split equally among connected teammates and clamped to maximum money. AFK eliminations never qualify.
  - It is logged as `ECONOMY_COMPENSATION`.

### 5.3 AFK rule
"Meaningful movement" in a round means reaching at least 2.5 blocks (configurable) horizontally from that round's spawn point at any time. A player holding a position still had to walk there, so defensive holders are never penalised. Looking around, opening inventories and using items do not count.
- Rounds 1 and 2 without movement → streak = 2.
- Round 3 starts → if `afk-grace-seconds` (default 20) of the live phase pass and the player still hasn't moved, their inactivity spans **more than** two consecutive rounds. They are marked AFK and eliminated (`AFK_ELIMINATION`, no compensation).
- Any later movement clears the flag, and they play normally from the next round.

### 5.4 Matchmaking solver (in `tactical-engine`, pure function)
The input is the queue of parties (sizes 1–3) ordered by enqueue time.
1. Depth-first search over the age-ordered list, always trying to include the oldest parties first, for a subset whose sizes sum to exactly 10 **and** which splits into two teams of exactly 5. A team of 5 can be made from 3+2, 3+1+1, 2+2+1, 2+1+1+1 or 1×5. The search space is tiny: at most 10 parties per match.
2. Among valid splits for the chosen subset, pick one **uniformly at random**. Parties are never split.
3. Four trios (12 players) → no valid 10 exists, so the queue waits for solos and duos.
4. Aging: parties waiting longer than `priority-boost-seconds` are forced into the next candidate set first.

Any party change (join, leave, disconnect, leader change) **dequeues and re-enqueues the party with its original timestamp**, provided the new party is still valid. A party that is assembling a match is locked: changes cancel the assembly and return the other players to the queue with their age kept.

**Map vote:** draw 3 maps uniformly at random from the pool's valid, enabled maps, excluding the last *k* maps played in that mode (if enough remain). Votes are shown live. Voting lasts 20 s (configurable) and ends early when all 10 have voted. Ties are broken with `SecureRandom` among the tied maps. Players who don't vote don't count; disconnecting removes your vote. GUI slot order is shuffled for each viewer, so position gives no bias.

### 5.5 Spectating and glow
- **Dead participants:** spectator mode, locked onto alive teammates through `setSpectatorTarget`, with cycling as described in §1.9. Shift-to-exit is cancelled. When the viewed player dies they switch automatically. If no teammate is alive they stay on the last view until the round ends.
- **Glow:** `GlowService` keeps a `viewer → set of visible-glow targets` map for each match. Only *dead participants* and *authorised spectators* get entries. The packet listener sets the glowing bit in outgoing metadata only for those viewers. Each viewer gets their own scoreboard teams, coloured **red for the original attackers and blue for the original defenders**. Alive players never have entries, so even a modified client receives no glow data.
- **Outside spectators:** `/game spectate <matchId|player>` (permission-gated). Compass GUI teleports to sites and players, with checks that the target is in the same match instance and the bounds.

### 5.6 Chat
`AsyncChatEvent#viewers()` is rewritten per audience:
- Participant message → all participants plus that match's spectators.
- Spectator message → spectators only.
- Lobby message → lobby players only.

Mutes cancel player messages. System announcements go through `MatchAnnouncer`, which mutes never block.

### 5.7 Combat score (configurable, `combat-score.yml`)
Per round:
- `damage × 1.0`
- `+ kill × 150`, plus `first-blood 50` and `multikill (2+) 50` per extra kill
- `+ assist × 50`
- `+ plant 100`, `+ defuse 150`
- `+ clutch (last alive, won) 100`
- `+ survived 20`

**The match combat score is the average over rounds played**, which keeps it comparable across match lengths.
- **MVP** = the highest score on the winning team.
- **SVP** = the highest score among everyone except the MVP, on either team.
- Ties are broken by kills, then fewer deaths, then damage.

### 5.8 XP & levels
- Match XP:
  - `100 participation + 15 × rounds played`
  - `+ 300 win / 100 loss`
  - `+ 20 × kills + 10 × assists + 30 × (plants + defuses)`
  - `+ combat-score bonus (score / 2, capped at 150)`
  - × 1.25 for Competitive
- A match where you left and didn't return gives 0. Test matches give nothing.
- Level curve: `xpToNext(level) = min(500 + 100 × (level − 1), 3000)`.
- Daily and weekly challenges and achievements are YAML-defined, using counters that come from match events.
- **Cosmetics are visual-only**: titles, name colours, kill-effect particles and lobby trails. They never affect gameplay.

### 5.9 Health, hunger and regeneration (applies everywhere on the server)
Hunger is not a mechanic anywhere in this game. The effect therefore applies in **every context**:
- lobby and queues
- map vote and loading
- every match phase: alive, dead or spectating, paused
- halftime and post-game
- outside spectators
- map editor and test matches

The only exception is players with the `tactical.bypass.hunger` permission while they are in creative mode, so builders keep a normal HUD. That exception is configurable.

- **Permanent Hunger effect:** amplifier 0, infinite duration, `ambient=false`, no particles, no HUD icon.
  - It is re-applied on join, respawn, round start, world change and after milk or any other effect clear. `EntityPotionEffectEvent` removals of it are cancelled unless the plugin requested them.
  - Its only purpose is to switch the client to the Hunger-effect food sprites, which the pack makes transparent.
- **Food locked:** food level is kept at a constant `locked-food-level` (default 20, always ≥ 7 so sprinting is never blocked) with saturation 0.
  - `FoodLevelChangeEvent` is cancelled, and exhaustion is reset each tick for participants, so neither the Hunger effect nor sprinting ever drains food.
  - Eating is disabled, since players get no food.
- **Vanilla regeneration off:** gamerule `natural_regeneration = false` in the lobby and every match instance world. This guarantees food and the Hunger effect have **no effect on health**.
- **Simulated natural regeneration** (`RegenService`, run by the global tick loop, settings in `config.yml → health.regeneration`):
  ```yaml
  regeneration:
    enabled: true
    amount: 1.0            # half-hearts per pulse (1.0 = vanilla food≥18 rate)
    interval-ticks: 80     # 4 s, vanilla food≥18 rate
    combat-delay-ticks: 0  # optional: pause regen N ticks after taking/dealing damage (0 = vanilla-like)
    in-lobby: true
    contexts: [ROUND_LIVE, BOMB_PLANTED, BUY_PHASE]
  ```
  It heals only alive, non-paused participants (and lobby players), fires `EntityRegainHealthEvent` with reason `CUSTOM` so other systems can see it, and is logged as aggregated `HEAL` stats rather than one event per pulse.
- Limitation: the Hunger effect still appears in the inventory's effect list (the HUD icon is hidden). It has no gameplay effect.

### 5.10 Context-dependent tablist (single server)
All games and the lobby run on **one Paper server**. `TabService` picks a layout per viewer from their context:
- **Lobby layout:** lobby players only. Shows level and name, party marker, queue status in the header, and announcements in the footer. Match participants are hidden from it.
- **Match layout:** the 10 players of *that* match split by team (fake fixed slots through `PacketService`). Shows head or skull, name, kit icon, K/D/A, ping and alive/dead/disconnected state. The header and footer show map, mode and score.
- **Spectator layout:** the match layout for the match being watched.
- Players in other matches or in the lobby are never listed to a match participant. Switching context (queue → match → post-game → lobby) swaps the layout in one update.

---

## 5.11 Resource pack & texture workflow (admin-facing)
- **Your art goes in** `plugins/Tactical/resourcepack/assets/tactical/textures/`:
  - `kit/<kit_id>.png`
  - `map/<map_id>.png`
  - `item/<item_id>.png`
  - `icon/<icon key with dots as folders>.png`
  - `hud/*.png`

  A file whose name matches an ID replaces that placeholder automatically; no config edit is needed. Images must be square: 16×16 up to 128×128. `.png.mcmeta` animation files are supported.
- **Optional files:**
  - `models/item/<item_id>.json` (Blockbench export) is used instead of the flat item image.
  - Every editor has an optional `texture:` override, so one image can serve several entries.
- **`resourcepack/overrides/`** is copied into the pack verbatim and takes precedence. Use it for sounds, menu art and other raw pack content.
- **`/game pack rebuild`:**
  1. merges your art with generated placeholder tiles (colour plus initials);
  2. generates the item model definitions, font providers (16×16 glyphs), sounds.json and the transparent hunger-variant and bossbar sprites;
  3. zips the pack and computes its SHA-1;
  4. sends it to every online player with `Player#sendResourcePacks` (required pack, with a prompt message).

  No restart is needed.
- **Hosting:** a built-in HTTP server on a configurable port (default), or an external URL in `config.yml` for hosts without a spare port.
- **`/game pack status`** lists every texture slot as custom or placeholder. The admin editors preview the current icon.
- **Update safety:** plugin updates never write into `resourcepack/` except `resourcepack/build/`.

### 5.11.1 Vanilla block textures, models and blockstates
- **Vanilla overrides (first-class):** anything under `resourcepack/assets/minecraft/` is merged into the pack, using standard vanilla pack paths:
  - `textures/block/stone.png`
  - `models/block/*.json`
  - `blockstates/*.json`
  - `textures/entity/...`, `textures/gui/...`, `particles/`, `shaders/` and so on

  This changes the block's look **server-wide**, in the lobby and every map, because the client cannot texture by location.
- **Validation on rebuild:**
  - JSON syntax is checked.
  - Every model, texture and parent a blockstate or model refers to must exist, either in your files or as a vanilla asset.
  - Your files are checked for collisions with generated files. On a collision, your file wins, unless it overrides a sprite the HUD depends on (the hunger-variant sprites or the reserved bossbar sprite). That case is rejected with an explanation, because it would break the HUD.
- **Primary custom-block method: double slabs and glazed terracotta** (`blocks/*.yml`, managed through `/game blocks`). These states are **stable**: no neighbour update, redstone, tuning or physics ever changes them. That makes them safe without the server-side state protection described below.
  - **Double slabs:**
    - Every vanilla slab has a `type=double` state. Normally it just looks like the full block, so a custom texture or model on it is a free new full block.
    - The builder generates the slab's blockstate file with **only `type=double` replaced**, leaving `bottom` and `top` on vanilla models. Single slabs and the real full block (for example `oak_planks`) keep their vanilla look.
    - A built-in table covers every 26.3 slab type with its vanilla model names, and it is checked on rebuild.
    - About 60 slab types gives about 60 custom blocks.
  - **Glazed terracotta:**
    - 16 colours × 4 `facing` values. Each facing can map to a **different model with no rotation**, giving up to **64 independent custom blocks**.
    - You can also keep a colour as one block that rotates. The default is a replacement texture that still rotates like vanilla.
    - Any colour or facing you repurpose loses its vanilla look server-wide.
  - **YAML example:**
    ```yaml
    blocks:
      concrete_panel_a:   { state: "minecraft:stone_brick_slab[type=double]", texture: block/concrete_panel_a.png }
      tile_floor_n:       { state: "minecraft:cyan_glazed_terracotta[facing=north]", model: block/tile_floor_n.json }
      tile_floor_e:       { state: "minecraft:cyan_glazed_terracotta[facing=east]",  texture: block/tile_floor_e.png }
    ```
  - **Placing them:** `/game blocks` opens a palette GUI that gives "exact state" builder items. On place, the plugin sets the exact `BlockData`: double slab in one click, and the chosen terracotta facing regardless of which way you look. The palette's own icons are placeholders until you add art.
  - **Sounds matter for a tactical shooter.** Break, step and hit sounds come from the *vanilla* block, so footsteps on a custom double slab sound like that slab's material. Rebuild and the editor warn about this. Pick a slab whose sound fits the texture: wooden slabs for wood-looking blocks, stone slabs for concrete, and so on. Glazed terracotta always has stone-type sounds.
  - Validation warns if two entries claim the same state, or if a state name is invalid for 26.3. The block-state list is read from the server's own block registry, so it stays correct on future versions.
- **Extra custom blocks (optional, later, only if the two families above run out)**, for new solid blocks on maps without losing vanilla ones. They reuse **block states that vanilla never shows in normal play**:
  - note block instrument × note × powered (~1,150 states)
  - mushroom stem and cap face combinations
  - tripwire states, for non-solid blocks

  The builder generates the `blockstates/*.json` entries that map each state to your model. The plugin assigns states automatically and stores the mapping permanently, so a block keeps its state across rebuilds. You place them with an editor item from `/game blocks`.

  The server protects the claimed states:
  - It cancels note-block tuning and redstone updates on claimed note blocks.
  - It cancels mushroom-face recalculation (block physics) for claimed mushroom blocks.
  - It stops neighbouring updates from reverting them.

  The plain vanilla note block and mushroom blocks keep their normal look. Only unused states are claimed.
- **Decorative props (alternative):** non-colliding decoration such as signs, clutter or hologram art can use `ItemDisplay` entities with an item model placed in the map editor. These need no blockstate hacking and are reset with the map.
- **Map validation** warns if a map uses a custom block whose model or texture is missing from the pack.
- **Phase:** a minimal pack builder ships in **Phase 2**, alongside map templates, so maps can be built with them from the start. It covers vanilla overrides, double-slab and glazed-terracotta blocks, the `/game blocks` palette, validation and hosting. The full HUD, font and icon generation stays in Phase 5. Note-block, mushroom and tripwire blocks are later and optional.

---

## 6. GUI framework (our own; no external library)
- `Menu` (the logic) is separate from `MenuView` (the appearance: title, slot layout and icon definitions in YAML under `gui/`). Each menu is a class with `render(viewer)` and handlers bound to **logical buttons**, not raw slots, so a resource-pack restyle never touches logic.
- A single `InventoryHolder` subclass identifies our menus. One listener handles `InventoryClickEvent`, `InventoryDragEvent`, `InventoryCloseEvent` and `PlayerQuitEvent`. It **cancels every event by default** and only allows actions on slots explicitly marked editable.
  - **Blocked:** shift-click, number-key swap, offhand swap (F), double-click collect, drag, drops, creative middle-click.
  - **Editable slots** (in the kit and item editors) take a validated *copy* of the cursor item. The real stack is never moved between the menu and the player inventory without a server-side validation step.
- Every button declares a `requiredPermission`, which is checked **again on click**, not only when the menu is drawn.
- Available features: pagination, back-stack navigation, confirmation dialogs, anvil/sign text input, player-head icons, custom-model-data icons and live refresh (menus subscribe to state changes, for example vote counts).
- Closing a menu or disconnecting clears per-viewer menu state. Pending confirmations expire after 30 s.

**Placeholder icons everywhere: the icon registry (required for every GUI).**
Menu code never creates a hardcoded `ItemStack`. Every slot shows an **icon key** that `IconRegistry` resolves to an item. Each icon is a placeholder until you supply art.
- **`gui/icons.yml`** holds one entry per UI icon. Examples: `ui.back`, `ui.next_page`, `ui.confirm`, `ui.cancel`, `ui.locked`, `queue.quickplay`, `queue.competitive`, `vote.unvoted`, `vote.voted`, `kit.locked_by_teammate`, `filler`. Each entry has:
  - `placeholder-material` (for example `PAPER` or `GRAY_STAINED_GLASS_PANE`)
  - `custom-model-data.strings: ["tactical:icon/<key>"]`
  - an optional `item-model`
  - name and lore message keys
- **Kit logos:** each kit YAML has `icon: { gui: {...}, glyph: auto }`. Its GUI item gets the CMD string `tactical:kit/<kit_id>`. It is used in kit selection (all three selections), the shop header, profiles, the post-game results and the kit editor.
- **Map logos:** each map YAML has `icon: { gui: {...}, glyph: auto }` with CMD string `tactical:map/<map_id>`. It is used in map voting, the queue hover lists, pool management, the map editor and match history.
- **States are separate icons**, so each can be textured: selected, locked, taken by a teammate, unavailable, winning vote, and so on. They are never just a lore change.
- **The resource-pack builder generates a placeholder texture** for every icon, kit and map automatically: a coloured tile with initials. To replace one, drop a PNG at the matching path, for example `resourcepack/assets/tactical/textures/kit/vanguard.png` or `.../map/ascent_mc.png`, then run `/game pack rebuild`. No YAML or code changes are needed. Creating a new kit or map in-game gives it a placeholder icon straight away.
- **Inline (text) versions** of kit and map logos use the same image as a 16×16 font glyph, so one PNG feeds both the GUI item and the bossbar, tab and chat icon. A separate high-res GUI texture can be configured optionally.
- **Enforcement:** a unit test scans menu classes and fails if any GUI builds an item without `IconRegistry`.

Menus planned: Queue, Party, Profile, Match history, Leaderboard detail, Map vote, Kit select, Shop, Admin panel (Maps, Map editor, Pools, Kits, Kit editor, Items, Shops, Prices, Economy, Matches, Queues, Players, Logs), Moderation (Reports, Bans, Mutes, History), Stats-reset confirmation.

**Map editor:** a GUI plus a "wand" hotbar tool set. You set spawns at your position and choose regions with a two-corner selection, previewed with particle outlines. The editor can validate and publish, and can start a test match.

---

## 7. Logging, recording, recovery

### 7.1 JSONL recording schema (v1)
One file per match: `logs/matches/MATCH-20261123-000001.jsonl`. Every line:
```json
{"v":1,"event_id":"MATCH-20261123-000001#000042","match_id":"MATCH-20261123-000001",
 "ts":"2026-11-23T15:32:10.250Z","elapsed_ms":125000,"tick":2500,"round":4,
 "type":"PLAYER_KILL","actors":["uuid1","uuid2"],"team":"A",
 "data":{...type-specific...},"result":"OK"}
```
- **Event types** (each with a documented `data` schema in `docs/recording-schema.md`):
  - `MATCH_HEADER` (mode, map id and version, settings snapshot, plugin version), `MATCH_STATE`, `ROUND_START`, `ROUND_END`, `HALFTIME`, `SUDDEN_DEATH`, `MATCH_END`, `MATCH_FOOTER` (completeness marker)
  - `PLAYER_JOIN`, `PLAYER_DISCONNECT`, `PLAYER_RECONNECT`, `PLAYER_LEAVE`, `KIT_SELECT`, `KIT_LOCK`
  - `SNAPSHOT_POSITIONS` (all players: position, yaw, pitch, health, armor, alive flag, held item; every `position-interval-ticks`, default 4), `SNAPSHOT_FULL` (every 5 s, plus inventories and economy)
  - `ATTACK`, `DAMAGE`, `PLAYER_KILL`, `ASSIST`, `PROJECTILE_LAUNCH`, `PROJECTILE_PATH` (sampled), `PROJECTILE_HIT`, `UTILITY_USE`, `UTILITY_EFFECT`
  - `BOMB_ASSIGN`, `BOMB_DROP`, `BOMB_PICKUP`, `PLANT_START`, `PLANT_CANCEL`, `BOMB_PLANTED`, `DEFUSE_START`, `DEFUSE_PROGRESS`, `DEFUSE_CHECKPOINT`, `DEFUSE_CANCEL`, `BOMB_DEFUSED`, `BOMB_EXPLODED`
  - `BLOCK_CHANGE` (construction placement and break, glass removal and restore), `PURCHASE`, `PURCHASE_FAILED`, `ECONOMY_CHANGE`, `INVENTORY_CHANGE`, `ITEM_EXPIRED`
  - `AFK_ELIMINATION`, `ECONOMY_COMPENSATION`, `PAUSE`, `RESUME`, `ADMIN_ACTION`, `TIMER_CHANGE`, `VALIDATION_ERROR`, `ERROR` (exception and stack trace), `WARNING`
- **Writer:** a bounded queue drained by one dedicated thread per server, with buffered writes. `ROUND_END`, `MATCH_END`, errors and admin actions force a flush and `fsync`. If the queue is full, position snapshots are dropped first (counted in the footer); events are never dropped.
- Files are opened with `CREATE_NEW`, so an existing recording is never overwritten. A recording with no `MATCH_FOOTER` is reported as **incomplete** by `/game logs info`.
- **Retention:** gzip files after `compress-after-days`, delete after `delete-after-days` (0 means keep forever).
- Never recorded: voice, IP addresses, tokens, chat content (opt-in through config).
- Every server-log error includes `[match=… event=…]`.

### 7.2 Pause
`PAUSED` freezes the match's timer counters, the bomb, plant and defuse progress, kit, shop and halftime timers. While paused:
- Movement is frozen: a position lock with `PlayerMoveEvent` cancelled, except head rotation.
- Damage, projectiles, item use, block changes, purchases and drops are all cancelled.
- Projectiles already in flight are frozen (velocity stored and zeroed, gravity off) and restored on resume.

Resume counts down (default 3 s) and then restores everything. Pauses nest safely through an id stack and are logged both to the recording and to `audit_log`.

### 7.3 Restart recovery
At every state transition and every round end, a compact `MatchCheckpoint` is written to `data/recovery/<matchId>.json`: scores, round number, sides, kits, wallets, loss streaks, purchased-item ledger, compensation set, pause flag. On startup:
- A checkpoint found **at a round boundary or in buy/halftime/kit-select** → the match is offered for resume. It is recreated on a fresh instance once at least `recovery-min-players` (default 8) participants reconnect within the rejoin window. **The interrupted round is replayed** with the equipment and money they had at its start.
- A checkpoint from mid-round, a bomb plant or a defuse → the same, with the round **voided** and replayed. Bomb progress is not restored, because the world state it depended on is gone.
- The window expires, or the map version changed → the match is terminated as `INTERRUPTED`. It gets no stats (or partial stats; configurable), its recording is finalised with a footer, and its players return to the lobby on join.
- Nobody is left stuck: every join checks for stale match associations and clears them.

### 7.4 Backups
- Scheduled SQLite `VACUUM INTO backups/database/<timestamp>.db` and YAML snapshot zips, kept for N days. Restoring is an admin command that requires the server to be idle and stops matchmaking first.
- `/game resetstats` → two confirmation steps (chat confirm code, then a GUI) → backup → one DB transaction that truncates stats, period, map and kit tables and resets XP and level → a row in `stats_resets` → leaderboards rebuilt. If anything fails, the whole transaction rolls back.

---

## 8. Testing strategy
- **Unit tests (JUnit 5):** the round state machine, every round-end precedence case, the defuse checkpoint, AFK edge cases, the economy across sequences of rounds, the matchmaking solver (including fuzz testing over random queues), map vote fairness (a statistical test), combat score, XP, config validation and migration.
- **Integration tests:** a real Paper 26.3 server started in CI from the build. A plugin test harness creates matches with *virtual participants* (engine-level players with no client) to drive full matches, simultaneous Quickplay and Competitive matches, pause/resume and recovery after a killed server process.
- **Manual playtest checklists** for anything that needs a real client: HUD alignment at GUI scales 1–4, the tab list, glow visibility, voice groups, GUI exploit attempts.

---

## 9. Roadmap (each phase ends with tests plus a status report: done / partial / not done)

| Phase | Deliverables | Exit criteria |
|---|---|---|
| **1 Core infrastructure** | Gradle multi-module, Java 25 toolchain; `ServiceRegistry`; config loading, validation, migration and snapshots; SQLite and migrations; `PlayerService` with sessions; teams; `MatchService`; state machine and global tick loop; `MatchEventBus`; base JSONL recorder; audit log. **Plus feasibility spikes:** object components in bossbar, tab and chat; spectator click input; 26.3 CMD JSON; Mannequin skins; SVC group API; PacketEvents glow rewrite. | Unit tests pass; the plugin loads on Paper 26.3; a "headless" virtual match runs through every state; spike results reported to you. |
| **2 Core gameplay** | Map templates and instancing, block journal, spawns, barriers, bomb carrier, plant, defuse, timers, round precedence, halftime, sudden death, match end, test matches through commands. | Full 5v5 virtual match and a real-client test match with few players; both modes run at once. |
| **3 Kits, items, economy** | Item registry, PDC and HMAC identity, CMD visuals, all behaviour types (orbs, timed, lingering, spark, trap/detection bottle, construction, heavy crossbow, claymore), effects, kits, shops, prices, enchant purchases, retention, death loss, compensation. | Each item has its own test scenario; exploit checklist (duplication, offhand, anvils, creative spoofing). |
| **4 Multiplayer** | GUI framework (pulled forward; needed here), parties, queues, solver, map vote, lobby, Mannequin NPCs, spectators, glow, reconnect, AFK, chat routing, voice. | Solver fuzz tests; party/disconnect matrix; 2 simultaneous matches. |
| **5 Presentation and progression** | Resource-pack builder and hosting, money HUD, bossbar panel, tablist, icons, sounds registry, stats, XP and levels, profiles, history, leaderboards, post-game results, MVP/SVP, challenges, achievements, cosmetics. | HUD checked at GUI scales 1–4; leaderboard update cost measured. |
| **6 Admin and reliability** | Admin and moderation GUIs, roles, reports, bans, mutes, pause, resetstats, log commands and filters, backups, recovery, metrics (`/game debug perf`). | Restart-recovery tests; permission tests with non-op accounts. |
| **7 Testing and release** | Full test pass, migration tests, performance run, install, configuration and maintenance docs. | Release candidate. |

Order note: the GUI framework moves to the start of Phase 4 because the map editor, kit editor and queue screens all depend on it. Before Phase 4, admin functions are available through commands.

---

## 10. Resolved decisions (rev 2)
| # | Question | Decision |
|---|---|---|
| Q1 | Packet library | **PacketEvents**, behind `PacketService` (§1.2) |
| Q2 | Hunger bar | Permanent hidden Hunger effect + transparent Hunger-variant sprites. Food locked so sprint is never lost; vanilla regeneration off; plugin-simulated regeneration (§5.9) |
| Q3 | Deployment | **Single Paper server** for lobby and all matches. The tablist changes with context: lobby, match or spectator (§5.10) |
| Q4 | Kit selection | Before round 1, and before the second half (QP round 5, Comp round 9). Before sudden death, each player picks between their first-half and second-half kit (§3) |
| Q5 | Hunger effect scope | Applied server-wide at all times (§5.9) |
