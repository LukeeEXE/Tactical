# Implementation status

Legend:
- ✅ done and tested
- 🟡 partial (what is missing is listed)
- ⬜ not started (planned phase in brackets)

## Phase 1: Core infrastructure

| Area | Status | Notes |
|---|---|---|
| Gradle multi-module build, Java 25, pinned deps | ✅ | `tactical-engine` (pure Java) + `tactical-paper` |
| Service registry (ordered enable / reverse disable, fail-safe startup) | ✅ | |
| Config loading, validation with file:line errors, unknown-key warnings | ✅ | |
| Never overwriting admin files; previous version kept on invalid reload | ✅ | Verified on a live server |
| Config schema versioning and migration chain (with backup) | ✅ | No migrations exist yet (everything is v1). Migrated files lose YAML comments; documented. |
| Match-specific config snapshots | ✅ | Immutable records captured at match creation |
| SQLite storage, single writer thread, WAL, versioned SQL migrations, checksum guard, pre-migration backup | ✅ | |
| MySQL / MariaDB | ⬜ | Config rejects anything other than `sqlite` with a clear error |
| Player records and name history (offline lookup), sessions with context | ✅ | `SessionContext` drives the future chat, tab and HUD routing |
| Teams: original identity A/B and current side | ✅ | Identity never changes; sides swap at halftime |
| Match state machine with a legal-transition table | ✅ | Every state can reach CLOSED (tested) |
| Round management: buy → live → planted → end, precedence rules | ✅ | Unit-tested; the real bomb arrived in Phase 2 |
| Halftime, sudden death (4-4 / 8-8), match end, postgame, cleanup | ✅ | Full Quickplay and Competitive flows tested |
| Kit selection ×3 (start, halftime, sudden death 1st/2nd-half kit), no teammate duplicates, deadlock-proof locking, auto-fill | ✅ | Logic and console only; the GUI comes in Phase 4 |
| Pause / resume with countdown; timers, bomb and kit timers frozen | 🟡 | Engine freeze done and tested live. **Missing (Phase 6):** freezing player movement and projectiles, and restoring them |
| Match isolation (cross-match / lobby damage and interaction blocked, friendly fire off) | ✅ | Basic listener; map geometry and items come later |
| Multiple simultaneous matches | ✅ | Tested 2 concurrently on a live server |
| Disconnect → eliminated; reconnect within window → dead until next round | ✅ | Engine plus listener. Voice regroup and spectator come in Phase 4 |
| JSONL match recording: header, footer, unique ids, async writer, fsync of critical events, never overwrite | ✅ | |
| Error isolation: a failing match aborts alone, ERROR event with stack trace, ids in console logs | ✅ | |
| Audit log of admin actions (DB + console) | ✅ | |
| `/game` commands: match test/list/info/end, round end, pause, resume, reload, logs recent/info/errors, debug | ✅ | Permission nodes checked server-side |
| `/game admin add/remove` roles | ⬜ | Phase 6. Phase 1 uses permission nodes (default: op) |
| Restart recovery (checkpoints) | ⬜ | Phase 6. Phase 1 ends running matches as INTERRUPTED on shutdown, with a complete recording |

### Tests
- **Engine unit tests (~335):**
  - full Quickplay and Competitive matches
  - all round-end precedence cases
  - defuse on the detonation tick, plant on the expiry tick
  - pause and multiple pauses
  - disconnect and reconnect
  - halftime skip rules
  - sudden-death kit options
  - a 300-run kit-deadlock fuzz test
  - config errors with line numbers, duplicate keys, the migration chain
  - recording completeness and uniqueness, and refusal to overwrite
- **Live Paper 26.3 (build 259) test with virtual players:**
  - two simultaneous matches
  - full test-mode match to sudden death (4-5)
  - plant, pause during the bomb (timer frozen at 43 s), all attackers dead while planted (round continued), defuse
  - admin end; invalid reload (rejected with line numbers)
  - shutdown mid-match (INTERRUPTED, complete footer)
  - restart (no defaults overwritten, migration not re-run, ids continued)

## Feasibility checks for later phases (server side, verified against the 26.3 jars)
| Feature | Result |
|---|---|
| Mannequin NPC entity with a settable `ResolvableProfile` (skin) | ✅ API present |
| `PlayerInputEvent` (real sneak/movement input, for defuse) | ✅ |
| Data components: `ITEM_MODEL`, `CUSTOM_MODEL_DATA` (strings/floats/flags/colors), `CONSUMABLE`, `BLOCKS_ATTACKS`, `PROFILE` | ✅ |
| Inline object text components: `PlayerHeadObjectContents`, `SpriteObjectContents` (Adventure 5.2) | ✅ API present |
| Per-viewer entity visibility (`setVisibleByDefault`, `showEntity`) | ✅ |
| Cancellable `PlayerStopSpectatingEntityEvent` | ✅ |
| Simple Voice Chat 2.6.27: `Group.Builder#setHidden/setType/setPassword/setPersistent`, `VoicechatConnection#setGroup` | ✅ |
| PacketEvents 2.14.0 wrappers: `EntityMetadata` (per-viewer glow), `PlayerInfoUpdate` (fake tab entries) | ✅ |

### Still needs a real 26.3 client (cannot be tested in this environment)
- Whether left/right clicks arrive while spectating a teammate. The fallback plan is in the architecture doc.
- Rendering of heads and sprites inline in the bossbar, tablist and action bar, and font offsets at GUI scales 1–4.
- The exact item-model JSON for `custom_model_data` strings on the 26.3 client.

These will be tested on your client when the HUD and resource pack are built.

## Phase 2: Maps and core gameplay

| Area | Status | Notes |
|---|---|---|
| Map definitions (YAML, schema v1), versioning, atomic saves and backups | ✅ | Hand edits trigger a DRAFT re-validation |
| In-game editor: create, edit, leave, wand selection, particle preview, bounds, spawns, sites, barriers, construction zones, kits, modes, rename, delete | ✅ | Commands; the editor GUI comes with the GUI framework (Phase 4) |
| Validation: structural + world checks (spawns not in blocks, standable sites, barrier contents, reset round-trip) | ✅ | "Accessible" = has a standable spot. Path-finding from spawn is not done. |
| Validate/publish snapshots (matches never use the live template) | ✅ | |
| Per-match world instances (copy snapshot → load → delete), stale cleanup on startup | ✅ | 56–74 ms per instance for a small map |
| Map pools: create, delete, rename, add, remove, toggle, enable, disable, per-pool reasons | ✅ | Voting and matchmaking use them in Phase 4 |
| Glass and barrier removal at buy-phase end, restore every round (block journal) | ✅ | Live-tested |
| Generic "round-start block changes" beyond barriers | ⬜ | Barriers cover spawn walls; other scripted changes are not implemented |
| Spawns per side, adventure mode, placeholder loadout | 🟡 | The loadout is a config placeholder until kits (Phase 3) |
| Death → instant spectator of alive teammate (no death screen or drops), no enemy spectating, no spectator teleports | 🟡 | Implemented; **needs a client test**. Cycling inputs are logged; final controls in Phase 4 |
| Boundaries and void elimination | ✅ | Every 10 ticks; not client-tested |
| Bomb: carrier assignment, drop/pickup, plant (hold use in site, server-side ground check), planted display without collision, defuse ring, beeps, detonation | 🟡 | Implemented and compiled; **needs a client test** (no player can join from this environment) |
| Defuse: sneak + stationary (server positions), radius, checkpoint owned by the bomb, interruption reasons recorded | ✅ | Logic unit-tested; Bukkit side needs a client test |
| Halftime, sudden death, match completion on maps | ✅ | Full matches on a map instance live-tested |
| Hunger hidden (permanent Hunger effect + transparent sprites), food locked, simulated regeneration | 🟡 | Implemented; visual result needs a client test |
| Locator bar disabled in match worlds | ✅ | It would reveal player directions |
| Lobby spawn (`/game lobby setspawn`), return to lobby after matches, rescue from deleted instances | ✅ | |
| Position snapshots (`SNAPSHOT_POSITIONS`), `DAMAGE`, `PLAYER_KILL`, bomb events, `BLOCK_CHANGE` | ✅ | |
| Resource pack builder: vanilla overrides, overrides folder, double slab + glazed terracotta blocks, validation, deterministic zip | ✅ | Unit-tested and live-built |
| Pack hosting (built-in HTTP), auto-send on join and rebuild, pack format detected from the server | ✅ | HTTP tested with curl; client download untested |
| Construction block = retextured infested stone (pack model override + placeholder texture), silverfish spawns cancelled server-wide | 🟡 | Texture and no-silverfish done; silverfish block live-tested with a TNT control run. The item, placement and breaking come in Phase 3 |
| `/game blocks list` and `give` (exact-state placement) | 🟡 | Implemented; the palette GUI comes in Phase 4 |
| Test matches with real players and a map | 🟡 | Implemented; only virtual players tested |

### Phase 2 live tests (Paper 26.3 build 259, console only)
- The pack was built and served over HTTP (bad paths return 404), and its blockstates were inspected.
- Map validated and published, and the snapshot taken. Validation failures were reported, including a spawn inside a block.
- A full test-mode match on a map instance reached sudden death (4-5):
  - barriers removed 21 + 21 blocks;
  - the glass-only filter skipped a wooden plank;
  - each round reset restored 42 blocks;
  - the instance was deleted at cleanup.
- A Quickplay and a Competitive match ran **on the same map at the same time**, in separate instances.
- Two bugs were found and fixed:
  - copied worlds kept Paper's world UUID metadata, so Paper refused to load them;
  - a match that failed during loading never reached cleanup.

See `docs/PLAYTEST-PHASE2.md` for the client checklist.

## Phase 3 (in progress): menus, kit selection, economy, shop

| Area | Status | Notes |
|---|---|---|
| Own GUI framework: cancel-first click handling (shift-click, number keys, off-hand swap, double-click collect, drag, hopper moves all blocked), permission re-check on click, rate limiting, live refresh, clean close/disconnect | ✅ | Pulled forward from Phase 4. Not client-tested yet |
| Icon registry: every menu item is a placeholder carrying `custom_model_data` strings (`tactical:icon/<key>`, `tactical:kit/<id>` + `state:<state>`, `tactical:shop/<entry>`), materials in `gui/icons.yml` | ✅ | |
| Kit selection menu (start, halftime, sudden death): opens automatically, live teammate and enemy picks, kit states (available, selected, locked, taken, unavailable), lock button, skip vote, reopen item | 🟡 | Implemented; **needs a client test** |
| Economy engine: start money, cap, win/loss payouts with loss streak (win lowers it by one), kill, assist, plant, defuse, reduced payout for saving attackers, halftime reset, sudden-death floor | ✅ | Unit-tested; payouts verified in a live virtual match |
| Assists (damage in the last 10 s by a teammate of the killer) | ✅ | |
| Shop (`shops/default.yml`): categories, item and enchantment entries, price, max carry, enabled flag, per-map shop | 🟡 | Placeholder vanilla items until custom items. Kit-specific shops and map price overrides are still to do |
| Shop menu: buy, sell back (same round), money display, reasons for refusals | 🟡 | Implemented; **needs a client test** |
| Purchase ledger: retention (round N → usable through N+2), used items disappear, death loses all purchases, halftime clears, all-or-nothing purchase with refund | ✅ | |
| Temporary money display on the action bar during the buy phase | 🟡 | Replaced by the custom HUD in Phase 5 |
| Kits with equipment and abilities, custom items (orbs, sparks, traps, construction blocks, heavy crossbow, claymore), kit editor, shop/price editor GUIs | ⬜ | Rest of Phase 3 |

### Playtest feedback round 1 (fixed)
| # | Report | Fix |
|---|---|---|
| 1 | No proper game ending | VICTORY/DEFEAT title, chat results (per team K/D/A, damage, provisional MVP), countdown back to the lobby |
| 2 | No bossbar | Boss bar for each team: score, alive heads (a skull for the dead), alive count, phase timer (BUY / live / BOMB / HALFTIME / SUDDEN DEATH / PAUSED); the bar shows remaining time. The custom-font HUD comes in Phase 5 |
| 3 | Bought arrows don't stack | Purchases are plain items that stack. The ledger counts materials (minus the loadout) instead of tagging items |
| 4 | Armor invisible after winning a round | Equipment is re-sent to every viewer 2 ticks after the round-start refill |
| 5 | Protection lost on death, couldn't buy it again | Enchantments are limited by the item, not by past purchases. Dying loses them; new armor can be enchanted again |
| 6 | Durability loss | Match items are unbreakable, and durability damage is cancelled in matches |
| 7 | Couldn't drop the bomb | Drop now runs after the cancelled vanilla drop. The bomb is thrown 1.5 blocks forward, and the dropper can't pick it back up for 2 s |
| 8 | Shop opening | During the buy phase each buyer has an invisible Interaction hitbox around their body that only they can see. Any right-click (empty hand, any item, into the air) targets it and opens the shop. It is hidden while either hand holds an item whose right-click does something itself: blocks, bows/crossbows/tridents, throwables and potions, food and drink, shields and blocking weapons, armor and heads, spawn eggs, buckets and other usable items, plus anything the custom item framework registers. F also opens the shop |
| 9 | Shooting before the round | Bow and crossbow shots and all thrown projectiles are blocked outside live rounds. Drawing and charging still work |
| 10 | Arrows blocked by teammates | Projectiles pass through teammates and dead players |
| 11 | Team names | Messages and HUD say Attackers/Defenders, coloured by team identity (red = started attacking, blue = started defending) |
| 12 | Space after the head | Tab-list names in matches get a space after the head and the team colour |
