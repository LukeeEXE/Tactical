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
import java.util.Map;

/**
 * Buy-phase shop. Each category gets one row (header icon + up to 8 entries). Left-click buys, right-click sells
 * back something bought this round. Entry icons are the item itself (placeholder) carrying
 * {@code custom_model_data "tactical:shop/<entry>"} so they can be retextured.
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

    @Override
    public Component title(Player viewer) {
        return Component.text("Shop");
    }

    @Override
    public void render(Player viewer, MenuView view) {
        int money = shop.economy().balance(viewer.getUniqueId());
        int secs = (shop.match().flow().stateTicksRemaining() + 19) / ModeRules.TICKS_PER_SECOND;
        for (int i = 0; i < 9; i++) view.set(i, icons.icon("ui.filler", Component.empty(), List.of()));
        view.set(4, icons.icon("ui.money", Component.text("$" + String.format("%,d", money), NamedTextColor.GOLD),
                List.of(Component.text("Buy phase: " + secs + "s left", NamedTextColor.GRAY),
                        Component.text("Unused purchases last " + shop.economy().rules().retentionRounds() + " more rounds.", NamedTextColor.DARK_GRAY),
                        Component.text("Dying loses everything you bought.", NamedTextColor.DARK_GRAY))));
        int row = 1;
        for (Map.Entry<String, String> cat : shop.shop().categories().entrySet()) {
            if (row > 4) break;
            List<ShopEntry> entries = new ArrayList<>();
            for (ShopEntry e : shop.shop().entries().values()) if (e.category().equals(cat.getKey()) && e.enabled()) entries.add(e);
            if (entries.isEmpty()) continue;
            view.set(row * 9, icons.icon("shop.category", Component.text(cat.getValue(), NamedTextColor.AQUA), List.of()));
            int col = 1;
            for (ShopEntry e : entries) {
                if (col > 8) break;
                view.set(row * 9 + col++, entryIcon(viewer, e, money), click -> {
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
            row++;
        }
        for (int i = 45; i < 54; i++) view.set(i, icons.icon("ui.filler", Component.empty(), List.of()));
        view.set(49, icons.icon("ui.close", Component.text("Close", NamedTextColor.RED), List.of()), c -> viewer.closeInventory());
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
