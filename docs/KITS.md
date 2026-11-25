# Kits: base kit and shop

Each kit is one file, `plugins/Tactical/kits/<id>.yml`, holding:
- **base-kit**: the items every player of that kit gets at the start of EVERY round (not purchases; never lost or
  sold). `slot`: 0-8 hotbar, 9-35 inventory, `helmet`/`chestplate`/`leggings`/`boots`/`offhand`, or `auto`. Each
  item is either `material:` (vanilla, made unbreakable with standard lore) or `item:` (custom item from items/*.yml).
- **shop**: the kit's own shop, the same on every map (map shops no longer exist; a map's `shop:` key is ignored).
  Three categories with fixed places in the shop screen: `melee` (up to 5 entries, row 1), `utility` (up to 3, row 3),
  `ranged` (up to 5, row 5). Fewer entries are centred in their row.

```yaml
schema-version: 2
id: medic
name: "Medic"
description: "Keeps the team alive."
enabled: true
icon: { material: golden_apple }
base-kit:
  - { slot: 0, material: wooden_sword }
  - { slot: 1, material: wooden_pickaxe }
  - { slot: 2, item: heal_shot }
shop:
  melee:
    iron_sword: { name: "Iron Sword", price: 750, max: 1, give: { material: iron_sword } }
    sharpness: { name: "Sharpness I", price: 800, max: 1, enchant: { enchantment: sharpness, level: 1, applies-to: [iron_sword] } }
  utility:
    heal_shot: { name: "Shot of Healing", price: 300, max: 1, give: { item: heal_shot } }
  ranged:
    bow: { name: "Bow", price: 500, max: 1, give: { material: bow } }
```
`/game reload kits` applies changes. Purchases and money reset at halftime (`economy/economy.yml → halftime`), when
players pick their second-half kit; the shop always follows the player's current kit.

## Shop screen
The shop is drawn with three background textures and an icon (in the resource pack under
`assets/tactical/textures/gui/`: `shop_header.png`, `shop_body.png`, `shop_footer.png`, `shop_icon.png`; put your
own PNG at the same path under `resourcepack/` to replace one, same size). The header shows the icon and "SHOP" in
Pinch. Row 0 holds the money (gold ingot, hover for the balance) and the exit (door; Escape also closes). The
"Inventory" label of chest screens is blanked by the pack in every language, since it would be drawn over the
background.

### Arranging the hotbar in the shop
While the shop is open, the visible inventory row (the slots in the footer texture) holds your hotbar: drag and
click items within that row to reorder them. When the shop closes (door, Escape, or the buy phase ending), that row
becomes your hotbar in the new order. Items bought while the shop is open appear in that row too. Only plain moves
inside the row are allowed (no shift-click, number keys, double-click or dropping).
The order is remembered for the rest of the half: it is saved when the shop closes and at the end of every round you
survive (so changes made during the round count too), and applied at the start of each round after the base kit and
kept purchases are given. A slot whose item is gone (used up, or lost by dying) stays empty and keeps remembering that
item: buying it again puts it straight back into that slot (in the shop row while the shop is open, and at round
start). A slot forgets only when the item shows up in another hotbar slot or another item takes its place. Other new
items fill the slots that were empty, then the inventory. The bomb is never part of a layout. Layouts reset at halftime and before sudden death (new kits).

