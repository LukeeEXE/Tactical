# Custom items

Custom items live in `plugins/Tactical/items/*.yml` (any number of files, each with an `items:` map; ids must be
unique across files). `/game reload items` reloads them; `/game item list` lists them and
`/game item give <id> [amount]` gives one to yourself (permission `tactical.admin.items`).

## Shots (`type: shot`)
- **Right-click:** applies the shot's effects to yourself.
- **Left-click (punch) a player:** a *positive* shot on a **teammate**, or a *negative* shot on an **opponent**,
  applies the effects to them and deals no damage. Any other punch is just a normal punch.
- One shot is used per application. All shots share one cooldown (`cooldown-ticks`, default 20 = 1 s), shown as the
  cooldown sweep on every shot in the hotbar.
- Only during a live round, by and on alive players of the same match.
- The target gets a ring of particles in the shot's `color`, and (with `trail: true`) particles around them while
  the effect lasts.
- A shot never mixes positive and negative effects (the file is rejected if it does).

```yaml
items:
  speed_shot:
    type: shot
    material: potion            # potion bottles are tinted with 'color'; any item works
    name: "<white>Shot of Speed"
    description:                # MiniMessage, green by default; <duration> and <level> are filled in
      - "Increase your movement speed by <white>40%</white>"
      - "for <white><duration></white>."
    color: "#3fd6e0"
    ring-radius: 0.8
    trail: true
    cooldown-ticks: 20
    effects:
      - { type: potion, effect: speed, level: 2, duration-ticks: 36 }
```

## Effects
Every effect has a `type`. Available now:

| Type | Keys |
|---|---|
| `potion` | `effect` (any vanilla effect id), `level` (1 = I), `duration-ticks`, `polarity` (`positive`/`negative`; defaults to the effect's own: speed positive, slowness negative; neutral effects must say) |
| `cleanse` | `polarity` (default positive). Instantly removes every vanilla and custom effect (and fire / freezing) and breaks any cobweb the target is standing in (map cobwebs come back at the next map reset) |
| `knockback` | `distance` (blocks on flat ground, default 7.5), `lift` (upward speed, default 0.42), `polarity` (default negative). Launches the target backwards, away from where they are looking |
| `flash` | `duration-ticks` (default 60), `polarity` (default negative). The target's screen turns fully white while they can still move; the last second fades out |

Flash is drawn in the world, not on the HUD, so F1 does not remove it: two huge white text-display backgrounds ride
on the target, are visible only to them, always face their camera and render on top of all blocks (in first and third
person). Particles and the first-person hand can still show on top.

Mining fatigue (`type: potion, effect: mining_fatigue`) is changed while a player has it from any source: mining is
disabled completely and the vanilla attack-speed penalty is cancelled out.

Examples: `items/utility.yml` (`cleanse_shot`, `flash_shot`, `knockback_shot`).

New effect types (custom effects) are added in code with `ItemEffects.register("<type>", parser)`; items then use
them in YAML like any other effect. Each effect declares its polarity, which decides who a shot can target.

## Selling custom items
In a kit's shop (`kits/<id>.yml`, see KITS.md): `give: { item: speed_shot }` (instead of `material:`). Purchases are tracked by item id, so
used shots are gone at round end and unused ones are kept like any other purchase.

## Item lore
Every match item gets the same tooltip style: name, a category tag (`UTILITY`, `WEAPON`, `ARMOR`, `ENCHANT`, `AMMO`)
in Pinch on a bordered box, the description, and usage lines ("Right-Click to Use", "Left-Click to Apply to Other
Player"). Weapons and armor from the loadout and the shop get their tag automatically (vanilla attribute lines are
hidden). Arrows stay plain so picked-up arrows still stack.
