# Resource pack and custom blocks

The plugin builds and hosts one resource pack and sends it to every player on join and after each rebuild.
Commands need `tactical.admin.pack`.

```
/game pack rebuild     # rebuild after adding files; shows every problem found
/game pack status      # sha1, size, download URL, error count
/game pack send        # resend to yourself
```

## Hosting (`config.yml → resource-pack`)
- The pack is served over HTTP on `port` (default **25601**). This port must be reachable by players.
- Set `public-url` to the address players use, e.g. `http://play.example.com:25601`.
- If left empty, the server's `server-ip` (or 127.0.0.1) is used. That only works when testing on the same machine.
- If your host can't open a port, an external URL setting can be added later.
- The pack is deterministic: if nothing changed, the SHA-1 stays the same and players don't re-download.

## Where your files go (`plugins/Tactical/resourcepack/`)
| Path | Purpose |
|---|---|
| `assets/tactical/textures/block/*.png` | Textures for custom blocks |
| `assets/tactical/models/block/*.json` | Optional Blockbench models for custom blocks |
| `assets/minecraft/...` | Vanilla overrides with normal pack paths, e.g. `assets/minecraft/textures/block/stone.png`. This changes that block **server-wide**. |
| `overrides/...` | Copied into the pack verbatim, with the highest priority |

- Textures must be PNG and square. Animated textures need a `.png.mcmeta`.
- The Hunger-variant food sprites are generated (they hide the hunger bar) and cannot be replaced; the rebuild tells you if you try.

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
