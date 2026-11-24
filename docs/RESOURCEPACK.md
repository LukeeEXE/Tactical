# Resource pack and custom blocks

The plugin builds and hosts one resource pack and sends it to every player on join and after each rebuild.
Commands need `tactical.admin.pack`.

```
/game pack rebuild     # rebuild after adding files; shows every problem found
/game pack status      # sha1, size, download URL, error count
/game pack send        # resend to yourself
```

## Hosting (`config.yml → resource-pack`)
There are two ways to deliver the pack. Changing `hosting` or `port` needs a server restart.

### `hosting: builtin` (default)
- The plugin serves the pack over HTTP on `port` (default 25601). **Any port works**, as long as your host lets players reach it. On Pterodactyl-style panels, add an extra port allocation and use that number.
- Set `public-url` to `http://<your server address>:<port>`.

### `hosting: external` (no extra port needed)
1. Run `/game pack rebuild`. The pack is saved as `plugins/Tactical/resourcepack/build/tactical-pack.zip`.
2. Upload that file to any site that gives a **direct download link** to the .zip. mc-packs.net and GitHub release assets work. Dropbox and Google Drive "share" pages do not, because they aren't direct downloads.
3. Set `external-url: "https://..."` to that link and restart (or reload).
4. `/game pack check` downloads the uploaded file and compares it with the current build. `/game pack status` shows the result:
   - **up to date**: everything matches.
   - **OUTDATED**: you rebuilt but haven't uploaded the new zip. Players still get the old upload, so nothing breaks, but upload the new file.
   - **cannot download / not a .zip**: the link is wrong or not a direct link. The pack isn't sent until this is fixed.

After every rebuild, re-upload the zip and run `/game pack check`.

The pack is deterministic: if nothing changed, the SHA-1 stays the same and players don't re-download.

## Where your files go (`plugins/Tactical/resourcepack/`)
| Path | Purpose |
|---|---|
| `assets/tactical/textures/block/*.png` | Textures for custom blocks |
| `assets/tactical/models/block/*.json` | Optional Blockbench models for custom blocks |
| `assets/minecraft/...` | Vanilla overrides with normal pack paths, e.g. `assets/minecraft/textures/block/stone.png`. This changes that block **server-wide**. |
| `overrides/...` | Copied into the pack verbatim, with the highest priority |

- Textures must be PNG and square. Animated textures need a `.png.mcmeta`.
- The Hunger-variant food sprites are generated (they hide the hunger bar) and cannot be replaced; the rebuild tells you if you try.

## Construction block texture
- Construction blocks are retextured **infested stone**. Drop your texture at `resourcepack/assets/tactical/textures/block/construction_block.png`, then run `/game pack rebuild`.
- Until then a hazard-stripe placeholder is used.
- Breaking or exploding infested stone never spawns silverfish on this server.

## Custom blocks (`plugins/Tactical/blocks/*.yml`)
```yaml
schema-version: 1
blocks:
  concrete_panel:
    name: "Concrete panel"
    state: "minecraft:stone_brick_slab[type=double]"       # double slab = new full block
    texture: block/concrete_panel.png                      # resourcepack/assets/tactical/textures/block/concrete_panel.png
  floor_tile_n:
    name: "Floor tile (north)"
    state: "minecraft:cyan_glazed_terracotta[facing=north]" # one block per facing, no rotation
    model: block/floor_tile_n.json                         # or a Blockbench model
  cyan_retexture:
    state: "minecraft:cyan_glazed_terracotta"              # no facing: new texture, keeps vanilla rotation
    texture: block/cyan_pattern.png
```
- **Double slabs:** only the `type=double` state changes. Single slabs and the real full block keep their vanilla look. Every 26.3 slab works except `petrified_oak_slab`, whose vanilla models are irregular.
- **Glazed terracotta:** up to 4 blocks per colour (one per facing), so 64 in total. Facings you don't use keep their vanilla look and rotation. A colour can't mix per-facing blocks with a rotating replacement.
- **Sounds come from the vanilla block.** Pick a slab whose material matches your texture, because footsteps matter.
- **Rebuild checks:**
  - duplicate states, unknown blocks for this server version and missing textures or models;
  - JSON syntax;
  - model, texture and parent references inside the `tactical` namespace;
  - image sizes.

### Placing them
- `/game blocks list` lists every custom block. `/game blocks give <id> [amount]` gives an item that **always places that exact state** (double slab in one click; the configured facing regardless of where you look).
- The in-game palette menu comes with the GUI framework in Phase 4.
- In the map editor you can also place the states by hand: two slabs stacked, or terracotta facing the right way.
