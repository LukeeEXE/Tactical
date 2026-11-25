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

### Playtest feedback round 3
| Report | Change |
|---|---|
| F (swap hands) opened the shop | Removed: F swaps hands normally before the round, so bows and items can go in the off hand |
| Arrows passing through teammates made enemy hits feel odd | Off by default (vanilla: arrows stop on teammates and deal no damage). Optional: `matches.projectiles-pass-through-teammates: true` |
| Shop box too small | 2.5 × 4.0 blocks, centred on the eyes (configurable: `matches.shop-hitbox`) |
| Heads in the kit menu not the real player | Heads use the login skin. If the login profile has none (offline mode, or a proxy not forwarding skins), the skin is looked up from Mojang by UUID, then by name, in the background. `/game debug heads` shows where each online player's skin came from |
| Add the Pinch font with vertical offsets | TTF fonts in `resourcepack/fonts/` generate `tactical:<name>` plus `_down_<n>` / `_up_<n>` variants; Pinch is shipped with attribution |

### HUD redesign (playtest round 4)
| Request | Change |
|---|---|
| Pinch only in the boss bar | Pinch is not the default font; only the boss bar text and scores use it (`hud.yml → bossbar.font`) |
| Boss bar without role names | Boss bar: score, a translucent team-coloured tile per player with their head (skull on a dark tile when dead), timer panel, enemy tiles, enemy score. Teams stay red/blue by identity |
| Money like the gem counter | Action-bar money widget on the hunger-bar row: dark rounded panel, coin, right-aligned amount, positioned with horizontal (Negative Space Font) and vertical (shifted font) offsets. A central action-bar owner keeps status messages (plant/defuse progress, shop errors) from erasing it |
| Timer like the round-start timer | `ROUND 4 STARTS IN: 00:10` in Pinch, white label and yellow time, on a dark panel sized to the text; the boss bar is a thin yellow progress line underneath |
| Chat like the reference | `[icon] message` with original icons (kill, round, win/loss, bomb, defuse, timer, warning, coin, kit, info). Players appear as team icon + head + name in the team colour. No crown or rank icons. Adds a kill feed and plant/defuse/disconnect messages; all texts in `messages/en_US.yml` (`chat.*`) |

All positions are estimates and need a client check; every value is adjustable in `hud/hud.yml`.

### HUD round 5
| Request | Change |
|---|---|
| Results like the reference, each team separately, no score | Red separators, a centred "<team> won the match!" line, then one block per team: rank, head, name, dotted leader, and right-aligned columns for kills, deaths, assists, K/D and damage. Each column has a **placeholder icon** (`stat_kills`, `stat_deaths`, `stat_assists`, `stat_kdr`, `stat_damage`); replace the PNGs in `resourcepack/assets/tactical/textures/hud/`. MVP gets a star |
| Score next to the timer | Boss bar row: tiles, score, timer panel, score, tiles (your team on the left) |
| Text not centred on its background | The panel row is laid out symmetrically (fixed-width tile groups and score cells), so the panel is centred on the screen. The timer text is drawn on a second, invisible boss bar and moved up one row (`hud.yml → bossbar.row-spacing`), so the client centres it exactly too, independent of font measurement |
| Square corners | All panels and tiles have square corners |
| Round breakdown | After the winner line, one row per team: `[team icon]` (the provided 16x16 icon, tinted red/blue) then a W/L box per round (placeholders `round_win.png` / `round_loss.png`) with dividers at halftime and before sudden death |
| Team icon everywhere | The 16x16 grey team icon, tinted red/blue, replaces every team shield: kill feed, player names, team names, winner line, team headers and round history |

### HUD round 6
| Request | Change |
|---|---|
| Remove text shadows (NoShadow shader) | That shader no longer works on 1.21.4+, so the built-in `shadow_color` text property is used instead: every boss bar, action bar and HUD background is sent with no shadow. No shader needed |
| Dead players can't reopen the kit menu | When a kit selection opens (start, halftime, sudden death), everyone is teleported to their spawn, alive. Right-click (the same invisible hitbox as the shop) opens the kit menu; the nether-star item is gone |
| Money offset moves with other action-bar text | Every action-bar part (money, hint box, status messages) now has zero width, so each one is positioned independently of the others |
| Map pickup items | `/game map pickup add <item> [amount]`, `removenearest`, `clear`; green particles in the editor. They respawn every round, never despawn or merge, and only alive players can pick them up. Verified on the test server (golden apple spawned at round start) |
| Boss bar width changes / heads move | Every section has a fixed width: the timer panel is sized for the longest possible label (`bossbar.timer-width`), score boxes for two digits, strips for a full team |
| One rectangle behind the heads, bigger score with a background | One translucent team-coloured strip per team; scores use a double-size Pinch font in a dark box |
| Action-bar hint box | Dark two-line box: orange "Right-click to select a kit" / white "No kit selected" (or "Selected: X" / "Locked: X"), and "Right-click to open the shop" / "Buy phase ends in m:ss" in the buy phase. Status messages (plant/defuse progress) replace the second line |
| Remove kill/round reasons | "(fell out of bounds)", "(bomb defused)" etc. are gone from chat |
| Breakdown lines | Light gray instead of red |

### Round 7
| Request | Change |
|---|---|
| Team colours | Red `#b03232`, blue `#204eab` everywhere (boss bar strips, scores, names, chat, results) |
| Kill messages show the method | `chat.kill.<method>`: melee "was slain by", bow "was shot by", trident "was impaled by", other projectiles "was hit by", TNT/explosions "was blown up by", fire, magic, fall, void, thorns. TNT now credits the player who lit it (and teammates' TNT no longer hurts you; your own still does). Custom utility names its own method with `KillMethods.tag(entity, "flashbang", owner)` or `KillMethods.pending(victim, "flashbang", attacker)` and gets a `chat.kill.flashbang` message; unknown methods fall back to `chat.kill`. Deaths without a killer can get `chat.died.<method>` lines (default: "was eliminated.") |

### Round 8
| Report | Change |
|---|---|
| Head backgrounds not one piece | Every background (team strip, timer panel, score boxes, hint box) was 1-8 translucent power-of-two pieces side by side; they are now ONE glyph of the exact width (the pack has every width 1-256 px per background), so there are no seams |
| Action bar width moves | The hint box has a fixed width (`hud.yml → actionbar.hint-width`, default 160) and only grows if a text does not fit |
| Shadows on the boss bar | Verified the server sends `shadow_color: 0` (`/game debug json`) and that the 26.3 client multiplies the shadow alpha by it (no shadow drawn). Still to be confirmed in game |
| Supplied position_tex_color shader | Ported to the 26.3 shader interface (its uniform block layout was from an older version) and shipped in the pack. Note: in 26.3 GUI text and its shadows use the `text` shader, not `position_tex_color`, so this shader only affects GUI sprites containing its two marker colours |

### Round 9
| Request | Change |
|---|---|
| Smoother Glowing shader | The 26.3 variant (post_effect/entity_outline.json + shaders/post/entityglow.fsh) is in the pack; compiles against the 26.3 interface |
| Bow pre-round eats arrows | The cancelled shot no longer uses the arrow (thrown items likewise), and the client's inventory is resynced |
| Timer bar as font icons | The supplied `time/0..10.png` segments (10x1, yellow #FFFF00 on 50 % black) form the bar under the timer panel; the boss bar sprite itself is invisible. Only these icons were taken from the supplied font file |
| Backgrounds centred, +1 px, rounded | Everything is laid out around the player heads (rows 0-7, they cannot be moved): strip 2 px around the heads, timer panel 4 px around the text, square 18 px score boxes; corner pixels removed from every background |
| Timer panel without dead space | The panel fits the text; the space reserved for it stays fixed, so heads and scores do not move |
| Scores | White, double-size Pinch, centred in square boxes |
| Strip centred on the heads | Sized to the team's actual players, placed against the centre |
| Hint box over the item name | Moved up (rows -9..11, clear of the held item's name at row 13); both lines in Pinch |
| Money | Panel 46 px (ends with the hunger bar), amount in Pinch, right-aligned |
| Team colours | Red #d73b3b, blue #2d63d9 |
| Round breakdown | `[W]` light green / `[L]` light red in Pinch |
| Bomb planted message | The planting team sees the site; everyone else only "The bomb has been planted!" |
| Bomb glow | Dropped and planted bomb glow only for the attacking team (defenders see a plain bomb) |
| Bomb planted on the boss bar | No "BOMB PLANTED:" text (only the red time); a third boss bar shows a large bomb icon that flashes with every beep |
| Team icon | The supplied team_flag.png, tinted per team |
| Old hud.yml | Layout keys changed meaning: an older `hud/hud.yml` is backed up and replaced with the new defaults automatically |
| Pack no longer loads after adding the glow shader | The supplied entityglow.fsh initialised globals from uniforms, which strict GLSL compilers (AMD, Intel, macOS) reject, and Minecraft then drops the whole pack. Rewritten to standard GLSL 3.30 (same look). `config.yml → resource-pack.smooth-glow: false` leaves it out if needed |
| Pack still failed (client log) | Cause was the ported position_tex_color.vsh: 26.3 compiles shaders with Vulkan rules, where `gl_VertexID` is `gl_VertexIndex`. Fixed; all bundled shaders now checked with `glslangValidator -V` (Vulkan mode), which reproduces the client's error |

### Round 10
| Request | Change |
|---|---|
| Boss bar at the top of the screen | Content is on the first boss bar again. The game draws inline heads 2 px from the top (rows -1..6, measured from the screenshot), so the strip and boxes start at row -2 (1 px from the edge) to keep 1 px around the heads |
| Timer text behind its background | The text bar is now drawn after the panel bar (moved up instead of down) |
| Heads centred / strip aligned with the score boxes | Strip 1 px around the heads; strip, score boxes and timer panel share the same top; skulls drawn on the same rows as heads; test-player dots centred in the head cell |
| Big gap | Shorter timer labels shrink the reserved timer space; section gap 4 px |
| Timer line sticks out / must match the text | The line is two exact-width pieces (filled #FFFF00, empty 50 % black) exactly as wide as the text |
| Panel width twitching between 00:11 and 00:12 | Widths are measured with every digit at its widest |
| "ROUND 4 STARTS IN:" | Now "STARTING IN:" |
| Planting visual | No text; a bar of the supplied empty/half/full 2x7 icons (20 segments, fills pixel by pixel) on a dark box; defusing uses the same bar |
| Dust particles | The two supplied shapes replace the dust particle (large while young, small when fading) |
| hud.yml | Schema 3: older files are backed up and replaced automatically |

### Round 11
| Request | Change |
|---|---|
| Boss bar bomb icon | Supplied 16x16 C4 icon (drawn at 32 px) |
| Breakdown icons | Kills = sword, deaths = skull, assists = supplied icon; K/D line icon white |
| Announcement icons | Every match announcement (and the round reward / return-to-lobby lines) uses the supplied warning icon; kill and death lines use the skull |
| Boss bar skulls | Supplied skull, centred in the 8 px head cell like a head |
| Plant bar not centred | Simulated against the built pack: horizontally exact (box -24..24, bar -20..20, net advance 0); it sat 1 px low and is now vertically centred too (rows 1..7 in the rows -2..10 box) |

### Round 12
| Request | Change |
|---|---|
| Pointed team backgrounds | Each team strip has its outer end cut at 45 degrees to a point (top row longest); the end next to the score box stays square with rounded corners |
| New team icon | Supplied 14x14 shield, tinted per team |
| Chat icons too big | Large chat icons are drawn 7 px tall (chat capital height), cropped to their ink; each still advances 9 px so the results columns stay aligned |
| Team chat and game chat | Planned for Phase 4 together with voice chat (roadmap updated) |

### Round 13
| Request | Change |
|---|---|
| Gap between the team icon and "]" | Large chat icons are now exactly 7 px wide with the usual 1 px letter gap (verified against the built pack); results layout uses each icon's real width, computed from its pixels |
| Swap assist and bomb icons | Swapped |
| Warning icon in the message colour | Tinted with the first colour the message template gives its own text (player/team placeholders do not count); round reward gold, lobby countdown gray |
| Timer line too short | Now exactly as wide as the timer panel |

### Custom items: Shots (Phase 3)
| Item | Status | Notes |
|---|---|---|
| Custom item framework: `items/*.yml`, item id in persistent data, `/game item list/give`, reload target `items` | 🟡 | Works on the server (items load, shop references validated). Tamper-proof (HMAC) ids come with the exploit checklist |
| Effect registry: pluggable effect types, `potion` (any vanilla effect, polarity from its category) | ✅ | Custom effects register a new type |
| Shot (formerly "spark"): right-click self, left-click teammate (positive) / opponent (negative) with no damage, otherwise a normal punch; used up; 1 s shared cooldown; coloured particle ring + trail | 🟡 | **Needs a client test** |
| Shop sells custom items (`give: { item: <id> }`); ledger counts by item id | 🟡 | Needs a client test |
| Item lore: name, category tag box (UTILITY / WEAPON / ARMOR / ENCHANT / AMMO), description with `<duration>`/`<level>`, mouse usage lines; on shots, loadout and shop weapons/armor, and shop icons | 🟡 | Needs a client test. Mouse icons are placeholder art |

### Kit base kits and kit shops (Phase 3)
| Item | Status | Notes |
|---|---|---|
| Base kit per kit (`base-kit:` in kits/<id>.yml), given every round, vanilla or custom items, fixed slots / armor | 🟡 | Live-tested on the server (no errors); **needs a client test** |
| Shop per kit (`shop:` with melee / utility / ranged), same on every map; map shops removed | 🟡 | Needs a client test |
| Old placeholder kit files (schema 1) are backed up and replaced with the new defaults | ✅ | Verified on the test server |
| Custom shop screen: header/body/footer textures + cart icon as title glyphs, "SHOP" in Pinch, money ingot, exit door, items centred per row; "Inventory" label blanked in all 145 languages | 🟡 | Positions verified against the built pack with the layout simulator; **needs a client test** |
| Default kits | 🟡 | Placeholder contents (wooden sword, pickaxe, a shot; same shop for all) until the real kit designs |

