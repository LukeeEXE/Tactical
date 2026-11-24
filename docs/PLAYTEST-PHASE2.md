# Phase 2 playtest checklist (needs a Minecraft 26.3 client)

These parts can't be tested without a real client. Please check them and tell me what you see.

## Setup
1. Install the plugin on a Paper 26.3 server.
2. Set `resource-pack.public-url` to an address your client can reach, and open port 25601.
3. Build a small map (see MAPS.md), validate it, publish it, enable it.
4. To start a test match, use either:
   - `/game match test quickplay <map> 4` alone, with 4 virtual players per team; or
   - `/game match test test <map> 0 You,Friend` with real players.

## Things to check
| # | Check | Expected |
|---|---|---|
| 1 | Resource pack prompt on join | The pack downloads. The **hunger bar is invisible** while you're in adventure or survival mode. |
| 2 | Custom blocks | The double slab and glazed terracotta show your textures. Normal slabs and terracotta look vanilla. Untouched terracotta facings have their normal rotation. |
| 3 | Sprint for a whole match | You never lose sprint, food never drops, and health slowly regenerates (half a heart every 4 s). |
| 4 | Round start | You spawn at your side's spawn behind the barrier. The barrier disappears when the buy phase ends and is back next round. |
| 5 | Bomb (attacker) | One attacker gets the TNT "Bomb" in slot 9. Hold right-click **inside a site**: a "Planting" bar fills in 5 s. Releasing or leaving the site cancels it. |
| 6 | Planted bomb | A small bomb model sits on the ground. You can walk through it. A red particle ring shows the defuse radius (3.5) and it beeps faster near the end. |
| 7 | Defuse (defender) | Sneak and **don't move** inside the ring for 10 s. Moving, un-sneaking or leaving the ring interrupts. After 5 s the checkpoint holds, so a teammate continues from 5 s. |
| 8 | Bomb drop | The carrier dies or presses Q, and the bomb lies on the ground glowing red. Walking over it as an attacker picks it up. |
| 9 | Death | There's no death screen. You become a spectator viewing an alive teammate. You can't spectate enemies, and you can't use the spectator teleport menu. |
| 10 | **Spectator input (important)** | While dead, try left-click, right-click, scroll wheel and sneak. With `debug.log-spectator-input: true`, the console logs which ones reached the server. **Send me those console lines**: they decide the final controls. Sneak should already switch to the next teammate. |
| 11 | Boundaries | Walking or falling outside the bounds eliminates you. |
| 12 | Pause | `/game pause` freezes the timers and bomb. Player movement is not frozen yet (Phase 6). |
| 13 | Disconnect | Leaving mid-round counts as dead. Rejoining within 3 minutes puts you back as a spectator, and you respawn next round. |

## Added: kit selection and shop
| # | Check | Expected |
|---|---|---|
| 14 | Match start | The kit menu opens on its own. Your team's row and the enemy row show live picks. Left-click selects, clicking again (or right-click) locks. Teammates can't lock the same kit. Closing the menu is fine: the nether star in slot 5 reopens it. |
| 15 | Halftime / sudden death | The menu opens again with a skip button. In sudden death only your two half kits are selectable. |
| 16 | Buy phase | An emerald in slot 9 opens the shop. Money shows on the action bar. Buying, right-click selling back, and red messages when you can't afford something or are at the limit. |
| 17 | Retention | Bought items come back next round if you survived and didn't use them, and disappear after 2 extra rounds. Dying loses them. |
| 18 | Exploits | Try shift-clicking, number keys, dragging and double-clicking in the menus. No item should ever leave a menu. |
