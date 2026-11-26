package dev.tactical.paper.shop;

import dev.tactical.engine.mode.ModeRules;
import dev.tactical.paper.gui.Icons;
import dev.tactical.paper.gui.Menu;
import dev.tactical.paper.gui.MenuView;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Buy-phase shop of the player's CURRENT KIT: melee (row 1), utility (row 3) and ranged (row 5) on a custom
 * background; money (gold ingot) and exit (door) in row 0. Left-click buys, right-click sells back something bought
 * this round. Entry icons are the real items with their lore plus price lines.
 */
final class ShopMenu extends Menu {

    private final MatchShop shop;
    private final Icons icons;

    private final MatchShop.ItemFactory factory;

    private final dev.tactical.paper.gui.MenuService menus;

    ShopMenu(MatchShop shop, Icons icons, MatchShop.ItemFactory factory, dev.tactical.paper.gui.MenuService menus) {
        this.menus = menus;
        this.factory = factory;
        this.shop = shop;
        this.icons = icons;
    }

    MatchShop shop() {
        return shop;
    }

    // Slot layout (6-row chest), matching the background textures (see PackBuilder.generateGui).
    /** Player-inventory slots under the confirm button (row 3, columns 3-5; the button is drawn from the middle one). */
    static final int CONFIRM_LEFT = 30, CONFIRM_SLOT = 31, CONFIRM_RIGHT = 32;
    static final int[] MELEE_SLOTS = {10, 11, 12, 13, 14};      // row 1, columns 1-5
    static final int[] UTILITY_SLOTS = {29, 30, 31};            // row 3, columns 2-4
    static final int[] RANGED_SLOTS = {46, 47, 48, 49, 50};     // row 5, columns 1-5
    static final int[] ABSORPTION_SLOTS = {16, 25, 34};         // column 7, rows 1-3 (top = fewest hearts)
    static final int[] ARROW_SLOTS = {52};                      // row 5, column 7
    /** Slot of the money item (row 0, column 8: item x 152). It draws "$1,234" up into the header bar, right-aligned. */
    static final int MONEY_SLOT = 8;
    private static final int MONEY_SLOT_X = 152, MONEY_RIGHT = 155; // text ends (incl. Pinch's trailing 1 px) at x 155
    private static final net.kyori.adventure.text.format.TextColor HEADER_TEXT = net.kyori.adventure.text.format.TextColor.color(0xE4E8FA);

    /**
     * The whole screen is drawn by the title: header (with the shop icon and "SHOP" in Pinch), the slot panel and the
     * cover over the player inventory, each a glyph of the {@code tactical:gui_shop} font placed with spaces. The
     * title starts at x 8 of the screen; glyphs are white so the default (grey) title colour does not tint them.
     */
    @Override
    public Component title(Player viewer) {
        net.kyori.adventure.key.Key font = net.kyori.adventure.key.Key.key("tactical", "gui_shop");
        java.util.function.Function<Character, Component> glyph = c -> Component.text(String.valueOf(c)).font(font);
        return Component.text()
                .append(dev.tactical.paper.hud.TextOffsets.space(-8))                                 // x 0
                .append(glyph.apply(dev.tactical.engine.pack.PackBuilder.GUI_SHOP_HEADER))            // 176 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(-177 + 6))                           // x 6
                .append(glyph.apply(dev.tactical.engine.pack.PackBuilder.GUI_SHOP_BODY))              // 164 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(-165 - 6))                           // x 0
                .append(glyph.apply(dev.tactical.engine.pack.PackBuilder.GUI_SHOP_FOOTER))            // 176 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(-177 + 7))                           // x 7: icon box
                .append(glyph.apply(dev.tactical.engine.pack.PackBuilder.GUI_SHOP_ICON))              // 16 + 1
                .append(dev.tactical.paper.hud.TextOffsets.space(37 - 7 - 17))                        // x 37: 3 px into the bar
                .append(Component.text("SHOP", HEADER_TEXT).font(net.kyori.adventure.key.Key.key("tactical", "pinch_up_14")))
                .build()
                .color(NamedTextColor.WHITE)
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.none());
    }

    @Override
    public void render(Player viewer, MenuView view) {
        int money = shop.economy().balance(viewer.getUniqueId());
        view.set(MONEY_SLOT, moneyItem(money));
        ShopDefinition def = shop.shopOf(viewer.getUniqueId());
        place(viewer, view, def.entries(ShopDefinition.Category.MELEE), MELEE_SLOTS, money);
        place(viewer, view, def.entries(ShopDefinition.Category.UTILITY), UTILITY_SLOTS, money);
        place(viewer, view, def.entries(ShopDefinition.Category.RANGED), RANGED_SLOTS, money);
        place(viewer, view, def.entries(ShopDefinition.Category.ABSORPTION), ABSORPTION_SLOTS, money);
        place(viewer, view, def.entries(ShopDefinition.Category.ARROWS), ARROW_SLOTS, money);
    }

    /** Entries centred in their row's slots (3 entries in a 5-slot row use the middle three). */
    private void place(Player viewer, MenuView view, List<ShopEntry> entries, int[] slots, int money) {
        int n = Math.min(entries.size(), slots.length);
        int start = (slots.length - n) / 2;
        for (int i = 0; i < n; i++) {
            ShopEntry e = entries.get(i);
            view.set(slots[start + i], entryIcon(viewer, e, money), click -> {
                if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                    if (shop.sellBack(viewer, e.id())) {
                        viewer.playSound(viewer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.7f);
                    } else {
                        deny(viewer, "Only items bought this round can be sold back");
                    }
                    return;
                }
                ItemStack[] hotbarBefore = new ItemStack[9];
                for (int h = 0; h < 9; h++) {
                    ItemStack it = viewer.getInventory().getItem(h);
                    hotbarBefore[h] = it == null ? null : it.clone();
                }
                MatchShop.BuyResult r = shop.buy(viewer, e.id());
                if (r == MatchShop.BuyResult.OK && swapped) pullNewItemsIntoRow(viewer, viewer.getInventory(), hotbarBefore);
                if (r == MatchShop.BuyResult.OK) {
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                } else {
                    deny(viewer, message(r));
                }
            });
        }
    }

    // ------------------------------------------------------------------ hotbar arranging

    /** Inventory row shown by the footer texture (player slots 9-17): it stands in for the hotbar while open. */
    static final int ROW_START = 9;
    private boolean swapped;

    /** Whatever was in the three slots under the confirm button while the shop is open. */
    private final ItemStack[] underButton = new ItemStack[3];

    /** The hotbar is moved into the visible row so it can be rearranged there; the confirm button is placed. */
    @Override
    public void onOpen(Player viewer) {
        org.bukkit.inventory.PlayerInventory inv = viewer.getInventory();
        swapRows(inv);
        for (int i = 0; i < 3; i++) underButton[i] = inv.getItem(CONFIRM_LEFT + i);
        // The button is drawn 3 slots wide from the middle slot; invisible items keep purchases out of the side slots.
        inv.setItem(CONFIRM_LEFT, blank());
        inv.setItem(CONFIRM_SLOT, named(model(dev.tactical.engine.pack.PackBuilder.MODEL_SHOP_CONFIRM),
                Component.text("Confirm", NamedTextColor.GREEN), List.of(Component.text("Close the shop and keep this hotbar", NamedTextColor.GRAY))));
        inv.setItem(CONFIRM_RIGHT, blank());
        swapped = true;
    }

    @Override
    public boolean onPlayerSlotClick(Player viewer, int slot, ClickType click) {
        if (slot < CONFIRM_LEFT || slot > CONFIRM_RIGHT) return false;
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        viewer.closeInventory(); // onClose applies the hotbar
        return true;
    }

    /**
     * The money as ONE item (pack model {@code tactical:money}): k-th character from the right in strings[k], its x
     * offset from this slot in floats[k], laid out with Pinch's real advances.
     */
    private static ItemStack moneyItem(int money) {
        String text = "$" + String.format("%,d", money);
        if (text.length() > dev.tactical.engine.pack.PackBuilder.MONEY_PARTS) text = text.substring(0, dev.tactical.engine.pack.PackBuilder.MONEY_PARTS);
        int[] left = new int[text.length()];
        int x = MONEY_RIGHT - dev.tactical.paper.item.ItemLore.pinchWidth(text);
        for (int i = 0; i < text.length(); i++) {
            left[i] = x;
            x += dev.tactical.paper.item.ItemLore.pinchWidth(String.valueOf(text.charAt(i)));
        }
        CustomModelData.Builder data = CustomModelData.customModelData();
        for (int k = 0; k < text.length(); k++) {
            int i = text.length() - 1 - k;
            data.addString(dev.tactical.engine.pack.PackBuilder.moneyCharName(text.charAt(i)));
            data.addFloat(left[i] - MONEY_SLOT_X);
        }
        ItemStack item = model("tactical:money");
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, data.build());
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return item;
    }

    /** An item drawn with one of the pack's item models (plain paper underneath). */
    private static ItemStack model(String modelKey) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(modelKey));
        return item;
    }

    private static ItemStack blank() {
        ItemStack item = model(dev.tactical.engine.pack.PackBuilder.MODEL_BLANK);
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return item;
    }

    @Override
    public boolean editablePlayerSlot(int slot) {
        return swapped && slot >= ROW_START && slot < ROW_START + 9;
    }

    /** Closing puts the row, in its new order, back on the hotbar (and anything held on the cursor into it). */
    @Override
    public void onClose(Player viewer) {
        if (!swapped) return;
        swapped = false;
        org.bukkit.inventory.PlayerInventory inv = viewer.getInventory();
        for (int i = 0; i < 3; i++) inv.setItem(CONFIRM_LEFT + i, underButton[i]); // button and blanks go away
        ItemStack cursor = viewer.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            viewer.setItemOnCursor(null);
            if (!putInRow(inv, cursor)) inv.addItem(cursor).values().forEach(left -> viewer.getWorld().dropItem(viewer.getLocation(), left));
        }
        swapRows(inv);
        shop.rememberLayout(viewer);
    }

    private static void swapRows(org.bukkit.inventory.PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            ItemStack hot = inv.getItem(i);
            inv.setItem(i, inv.getItem(ROW_START + i));
            inv.setItem(ROW_START + i, hot);
        }
    }

    /** Merges into / fills the visible row; false if it does not fit. */
    private static boolean putInRow(org.bukkit.inventory.PlayerInventory inv, ItemStack item) {
        int left = item.getAmount();
        for (int i = ROW_START; i < ROW_START + 9 && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.isSimilar(item) && s.getAmount() < s.getMaxStackSize()) {
                int add = Math.min(left, s.getMaxStackSize() - s.getAmount());
                s.setAmount(s.getAmount() + add);
                left -= add;
            }
        }
        for (int i = ROW_START; i < ROW_START + 9 && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) {
                ItemStack put = item.clone();
                put.setAmount(Math.min(left, item.getMaxStackSize()));
                inv.setItem(i, put);
                left -= put.getAmount();
            }
        }
        item.setAmount(left);
        return left <= 0;
    }

    /** A purchase lands where addItem puts it (often the real hotbar, hidden right now): move it into the row. */
    private void pullNewItemsIntoRow(Player viewer, org.bukkit.inventory.PlayerInventory inv, ItemStack[] hotbarBefore) {
        for (int i = 0; i < 9; i++) {
            ItemStack now = inv.getItem(i);
            if (now == null || now.getType().isAir()) continue;
            ItemStack before = hotbarBefore[i];
            int added = before == null || before.getType().isAir() ? now.getAmount()
                    : before.isSimilar(now) ? now.getAmount() - before.getAmount() : now.getAmount();
            if (added <= 0) continue;
            if (before != null && !before.getType().isAir() && !before.isSimilar(now)) continue; // not ours to move
            ItemStack moving = now.clone();
            moving.setAmount(added);
            // back into the slot this item used to have, if that slot is free
            int slot = shop.rememberedSlot(viewer.getUniqueId(), shop.keyOf(moving),
                    s -> inv.getItem(ROW_START + s) == null || inv.getItem(ROW_START + s).getType().isAir());
            if (slot >= 0) {
                inv.setItem(ROW_START + slot, moving.clone());
                moving.setAmount(0);
            } else {
                putInRow(inv, moving);
            }
            int stays = now.getAmount() - (added - moving.getAmount());
            if (stays <= 0) inv.setItem(i, null);
            else now.setAmount(stays);
        }
    }

    private static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    private ItemStack entryIcon(Player viewer, ShopEntry e, int money) {
        // The real item (with its lore) for item entries; an enchanted book with an ENCHANT tag for upgrades.
        ItemStack real = e.kind() == ShopEntry.Kind.ITEM ? factory.create(e, 1) : null;
        ItemStack item = real != null ? real : icons.icon("shop.category", Component.empty(), List.of())
                .withType(e.kind() == ShopEntry.Kind.ENCHANT ? Material.ENCHANTED_BOOK : Material.PAPER);
        if (e.kind() == ShopEntry.Kind.ABSORPTION) {
            item.lore(List.of(dev.tactical.paper.item.ItemLore.tag(dev.tactical.paper.item.ItemCategory.ABSORPTION), Component.empty(),
                    Component.text("Gain ", dev.tactical.paper.item.ItemLore.DESCRIPTION)
                            .append(Component.text(e.level() + " absorption hearts", NamedTextColor.WHITE))
                            .append(Component.text(" for this round.", dev.tactical.paper.item.ItemLore.DESCRIPTION))));
        }
        if (e.iconModel() != null) item.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(e.iconModel()));
        int carried = shop.carried(viewer.getUniqueId(), e.id());
        MatchShop.BuyResult can = shop.canBuy(viewer, e);
        List<Component> lore = new ArrayList<>();
        List<Component> existing = item.lore();
        if (existing != null && !existing.isEmpty()) {
            lore.addAll(existing);
        } else {
            lore.add(dev.tactical.paper.item.ItemLore.tag(e.kind() == ShopEntry.Kind.ENCHANT
                    ? dev.tactical.paper.item.ItemCategory.ENCHANT : dev.tactical.paper.item.ItemCategory.of(item.getType())));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Price: $" + String.format("%,d", e.price()), e.price() <= money ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(Component.text("Owned: " + carried + "/" + e.maxCarry(), NamedTextColor.GRAY));
        if (can != MatchShop.BuyResult.OK) lore.add(Component.text(message(can), NamedTextColor.RED));
        lore.add(Component.text("Left-click: buy   Right-click: sell back", NamedTextColor.DARK_GRAY));
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString("tactical:shop/" + e.id()).build());
        if (e.kind() == ShopEntry.Kind.ITEM && e.amount() > 1) item.setAmount(e.amount());
        item.editMeta(meta -> {
            meta.displayName(Component.text(e.name(), can == MatchShop.BuyResult.OK ? NamedTextColor.WHITE : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    private static void deny(Player p, String msg) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
        dev.tactical.paper.hud.ActionBars.status(p, Component.text(msg, NamedTextColor.RED));
    }

    static String message(MatchShop.BuyResult r) {
        return switch (r) {
            case OK -> "";
            case NOT_BUY_PHASE -> "You can only buy during the buy phase";
            case DEAD -> "You are dead";
            case DISABLED, UNKNOWN -> "Not for sale";
            case TOO_POOR -> "Not enough money";
            case MAX_CARRY -> "You already carry the maximum";
            case NOTHING_TO_ENCHANT -> "You have no item this enchantment applies to";
            case ALREADY_ENCHANTED -> "Already enchanted";
            case INVENTORY_FULL -> "Your inventory is full";
            case ALREADY_OWNED -> "You already have this many hearts or more";
        };
    }
}
