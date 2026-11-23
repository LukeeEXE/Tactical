# Building, installing and running

## Requirements
| What | Version |
|---|---|
| Server | Paper **26.3** (tested with build 259) |
| Java (server and build) | **25** |
| Client | Minecraft Java **26.3** |

Later phases add **PacketEvents 2.14.0** (required) and **Simple Voice Chat bukkit-2.6.27** (optional).

## Build
```bash
./gradlew build
```
- **Output:** `tactical-paper/build/libs/Tactical-<version>.jar`. This is the shaded plugin jar, and the only file you install.
- **Tests:** `./gradlew test` runs the engine unit tests (pure Java, no server needed).
- **Dependency versions:** all pinned in `gradle/libs.versions.toml`. Never use version ranges.

## Install
1. Copy `Tactical-<version>.jar` into `plugins/`.
2. Start the server once. It creates `plugins/Tactical/` with documented default files.
3. Edit the files, then run `/game reload [config|modes|kits|messages|all]`. No restart is needed. Running matches keep the settings they started with.

## Updating the plugin
- Replace the jar and restart.
- Your files are **never** overwritten. Files with an older `schema-version` are migrated automatically, and the original is first copied to `backups/config/<timestamp>/`.
- Before a database migration runs, the database is backed up to `backups/database/pre-migration-<timestamp>.db`.

## Project layout
| Module | Purpose |
|---|---|
| `tactical-engine` | Pure-Java game logic: state machine, rounds, kit selection, config validation, recording. **No Bukkit imports.** |
| `tactical-paper` | Paper plugin: services, storage, commands, listeners. Binds the engine to Minecraft. |

Notes:
- `sqlite-jdbc` is shaded **without relocation**, because it loads its native library by package name. If another plugin ships a different `org.sqlite` version, the first one loaded wins. This is normally harmless.
- The jar is about 12 MB, mostly SQLite native libraries for all platforms. They can be trimmed later to your server's OS only.

## Local test server (developers)
`run/` is git-ignored.
1. Put `paper-26.3-<build>.jar` there and accept the EULA.
2. Copy in the plugin jar.
3. Run it with Java 25. Console-only test matches with virtual players work without any client:

```
game match test test 5          # fast test-mode match, 5 virtual players per team, plays itself to the end
game match test quickplay 5     # real Quickplay timers
game debug skip <id>            # lock all kits / vote skip
game debug eliminate <id> B 5   # eliminate 5 players of team B
game debug plant <id> | game debug defuse <id>
game pause <id> [reason] | game resume <id>
game round end <id> attackers|defenders
game match end <id> [reason]
game logs recent | game logs info <id> | game logs errors
```
