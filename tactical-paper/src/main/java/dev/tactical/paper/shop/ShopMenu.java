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

    ShopMenu(MatchShop shop, Icons icons, MatchShop.ItemFactory factory) {
        this.factory = factory;
        this.shop = shop;
        this.icons = icons;
    }

    MatchShop shop() {
        return shop;
    }

    // Slot layout (6-row chest), matching the background textures (see PackBuilder.generateGui).
    static final int MONEY_SLOT = 1, EXIT_SLOT = 7;
    static final int[] MELEE_SLOTS = {11, 12, 13, 14, 15};      // row 1, columns 2-6
    static final int[] UTILITY_SLOTS = {30, 31, 32};            // row 3, columns 3-5
    static final int[] RANGED_SLOTS = {47, 48, 49, 50, 51};     // row 5, columns 2-6
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
                .append(dev.tactical.paper.hud.TextOffsets.space(40 - 7 - 17))                        // x 40: header bar
                .append(Component.text("SHOP", HEADER_TEXT).font(net.kyori.adventure.key.Key.key("tactical", "pinch_up_14")))
                .build()
                .color(NamedTextColor.WHITE)
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.none());
    }

    @Override
    public void render(Player viewer, MenuView view) {
        int money = shop.economy().balance(viewer.getUniqueId());
        int secs = (shop.match().flow().stateTicksRemaining() + 19) / ModeRules.TICKS_PER_SECOND;
        view.set(MONEY_SLOT, named(new ItemStack(Material.GOLD_INGOT), Component.text("$" + String.format("%,d", money), NamedTextColor.GOLD),
                List.of(Component.text("Buy phase: " + secs + "s left", NamedTextColor.GRAY),
                        Component.text("Unused purchases last " + shop.economy().rules().retentionRounds() + " more rounds.", NamedTextColor.DARK_GRAY),
                        Component.text("Dying loses everything you bought.", NamedTextColor.DARK_GRAY))));
        view.set(EXIT_SLOT, named(new ItemStack(Material.OAK_DOOR), Component.text("Exit", NamedTextColor.RED), List.of()),
                c -> viewer.closeInventory());
        ShopDefinition def = shop.shopOf(viewer.getUniqueId());
        place(viewer, view, def.entries(ShopDefinition.Category.MELEE), MELEE_SLOTS, money);
        place(viewer, view, def.entries(ShopDefinition.Category.UTILITY), UTILITY_SLOTS, money);
        place(viewer, view, def.entries(ShopDefinition.Category.RANGED), RANGED_SLOTS, money);
    }

    /** Entries centred in their row's slots (3 entries in a 5-slot row use the middle three). */
    private void place(Player viewer, MenuView view, List<ShopEntry> entries, int[] slots, int money) {
        int n = Math.min(entries.size(), slots.length);
        int start = (slots.length - n) / 2;
        for (int i = 0; i < n; i++) {
            ShopEntry e = entries.get(i);
            view.set(slots[start + i], entryIcon(viewer, e, money), click -> {
                if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                    if (shop.sellBack(viewer, e.id())) viewer.playSound(viewer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.7f);
                    else deny(viewer, "Only items bought this round can be sold back");
                    return;
                }
                MatchShop.BuyResult r = shop.buy(viewer, e.id());
                if (r == MatchShop.BuyResult.OK) viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                else deny(viewer, message(r));
            });
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
        };
    }
}
