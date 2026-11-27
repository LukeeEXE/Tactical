# Kits: base kit and shop

Each kit is one file, `plugins/Tactical/kits/<id>.yml`, holding:
- **base-kit**: the items every player of that kit gets at the start of EVERY round (not purchases; never lost or
  sold). `slot`: 0-8 hotbar, 9-35 inventory, `helmet`/`chestplate`/`leggings`/`boots`/`offhand`, or `auto`. Each
  item is either `material:` (vanilla, made unbreakable with standard lore) or `item:` (custom item from items/*.yml).
- **shop**: the kit's own shop, the same on every map (map shops no longer exist; a map's `shop:` key is ignored).
  Categories with fixed places in the shop screen: `melee` (up to 5 entries, row 1), `utility` (up to 3, row 3),
  `ranged` (up to 5, row 5), `absorption` (up to 3, right column, top to bottom) and `arrows` (1, bottom right).
  Fewer entries are centred in their row. Entries give an item (`give:`), enchant a held item (`enchant:`) or give
  absorption hearts for the round (`absorption: { hearts: 2 | 4 | 6 }` = Absorption I/II/III; a lower or equal tier
  cannot be bought on top of a higher one). `model:` sets the icon's item model, e.g. `tactical:absorption_1`.

```yaml
schema-version: 2
id: medic
name: "Medic"
description: "Keeps the team alive."
category: support      # kit menu row: defense, damage or support (bundled kits default to their own)
icon: { material: golden_apple, model: "tactical:kit_icon_medic" }   # model = logo from the resource pack
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
Pinch, and your money ("$1,234" in Pinch, right-aligned in the header bar). The money is an item (pack model
`tactical:money` in the top-right slot) whose characters are drawn up into the header, each at its own pixel offset,
so it updates live without reopening the shop. The CONFIRM button over the footer's recess (drawn 3 slots
wide from inventory row 3, column 4, onto the recess) closes the shop and applies the hotbar; Escape does the same. The vanilla chest
background is invisible (transparent `gui/container/generic_54.png`) and the "Inventory" label of chest screens is
blanked in every language, so only the custom screen shows.

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


## Kit menu

The kit selection screen is drawn like the shop (title glyphs + slots on the frames):

- Red box (chest row 1): the red team's picks. Blue box (your inventory's top row): the blue team's picks.
- Rows 3-5: one row per `category` (Defense, Damage, Support): the category icon in column 1, up to six kits.
- Footer: vote to skip in the small square (hotbar slot 2) and the LOCK IN / LOCKED button (hotbar slots 4-6).
- Your inventory is put aside while the menu is open and given back when it closes.

## Bundled kits (placeholders)

| Kit | Category |
|---|---|
| Bruiser, Tactician | Defense |
| Initiator, Marksman, Recon | Damage |
| Medic, Specialist | Support |

All use the same placeholder base kit and shop for now. Kit files from older versions (schema 3) are replaced; the
retired kits (vanguard, sentinel, phantom, warden, striker) are removed after a backup to `backups/config/`.
