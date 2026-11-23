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
| Round management: buy → live → planted → end, precedence rules | ✅ | Unit-tested; plant and defuse are currently fed by debug commands (real bomb in Phase 2) |
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
