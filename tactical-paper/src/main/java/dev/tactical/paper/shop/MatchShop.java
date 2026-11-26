package dev.tactical.paper.shop;

import dev.tactical.engine.economy.EconomyChange;
import dev.tactical.engine.economy.MatchEconomy;
import dev.tactical.engine.match.Participant;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.record.RecordingSink;
import dev.tactical.engine.state.MatchState;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Economy and purchases of ONE match. The purchase LEDGER is authoritative, never the items: every round the items
 * are re-created from it, so moving items around can never duplicate a purchase, and expiry/death loss are exact.
 *
 * <p>Purchased items carry no per-purchase tag so they STACK with each other and with the loadout (e.g. arrows).
 * What a player still owns is derived at round end by counting that item (custom item id, else material) in the
 * inventory and subtracting what the loadout gave (used items are consumed oldest-purchase-first).
 * <ul>
 *   <li>Bought in round N: usable in rounds N..N+retention while unused; dropped at the start of the round after.</li>
 *   <li>Dying loses every purchase, including enchantments (the enchanted item is gone, so it can be bought again).</li>
 *   <li>Halftime clears purchases if configured.</li>
 * </ul>
 */
public final class MatchShop {

    /** One purchase. {@code amount} is what the player still has of it (reconciled at round end). */
    public record Purchase(String entryId, int round, int amount) {
    }

    public enum BuyResult { OK, NOT_BUY_PHASE, DEAD, DISABLED, UNKNOWN, TOO_POOR, MAX_CARRY, NOTHING_TO_ENCHANT, ALREADY_ENCHANTED, INVENTORY_FULL, ALREADY_OWNED }

    private final GameMatch match;
    private final MatchEconomy economy;
    /** Each player's shop is their current kit's shop. */
    private final java.util.function.Function<UUID, ShopDefinition> shopOf;
    private final java.util.function.ToIntBiFunction<UUID, String> baseCount;
    private final ItemFactory factory;
    private final Map<UUID, List<Purchase>> ledger = new HashMap<>();

    /** Creates the stack an ITEM entry gives (vanilla or custom), or null if it cannot be made. */
    @FunctionalInterface
    public interface ItemFactory {
        ItemStack create(ShopEntry entry, int amount);
    }

    /** Identifies an inventory stack: {@code tactical:<id>} for custom items, else the material key. */
    @FunctionalInterface
    public interface ItemKeys {
        String keyOf(ItemStack item);
    }

    private final ItemKeys keys;

    /** @param baseCount how many of an item key a player's base kit gives (not purchases) */
    MatchShop(GameMatch match, MatchEconomy economy, java.util.function.Function<UUID, ShopDefinition> shopOf,
              java.util.function.ToIntBiFunction<UUID, String> baseCount,
              ItemFactory factory, ItemKeys keys) {
        this.match = match;
        this.economy = economy;
        this.shopOf = shopOf;
        this.baseCount = baseCount;
        this.factory = factory;
        this.keys = keys;
    }

    public GameMatch match() {
        return match;
    }

    public MatchEconomy economy() {
        return economy;
    }

    /** The shop of this player's current kit. */
    public ShopDefinition shopOf(UUID player) {
        return shopOf.apply(player);
    }

    private ShopEntry entry(UUID player, String entryId) {
        return entryId == null ? null : shopOf.apply(player).entries().get(entryId);
    }

    /** Number of purchases of this entry the player still holds (for the max-carry limit). */
    public int carried(UUID player, String entryId) {
        int n = 0;
        for (Purchase p : ledger.getOrDefault(player, List.of())) if (p.entryId().equals(entryId) && p.amount() > 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ buying

    public BuyResult canBuy(Player p, ShopEntry e) {
        if (match.state() != MatchState.BUY_PHASE || match.flow().isPaused()) return BuyResult.NOT_BUY_PHASE;
        if (!match.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false)) return BuyResult.DEAD;
        if (!e.enabled()) return BuyResult.DISABLED;
        if (e.kind() == ShopEntry.Kind.ABSORPTION) {
            if (absorptionLevel(p) >= e.level() / 2 - 1) return BuyResult.ALREADY_OWNED; // same or more hearts already
        } else if (e.kind() == ShopEntry.Kind.ENCHANT) {
            // Limited by the ITEM, not by past purchases: a new item can always be enchanted again.
            ItemStack target = enchantTarget(p.getInventory(), e, true);
            if (target == null) {
                return enchantTarget(p.getInventory(), e, false) == null ? BuyResult.NOTHING_TO_ENCHANT : BuyResult.ALREADY_ENCHANTED;
            }
        } else if (carried(p.getUniqueId(), e.id()) >= e.maxCarry()) {
            return BuyResult.MAX_CARRY;
        }
        if (economy.balance(p.getUniqueId()) < e.price()) return BuyResult.TOO_POOR;
        return BuyResult.OK;
    }

    public BuyResult buy(Player p, String entryId) {
        ShopEntry e = entry(p.getUniqueId(), entryId);
        if (e == null) return fail(p, entryId, BuyResult.UNKNOWN);
        BuyResult r = canBuy(p, e);
        if (r != BuyResult.OK) return fail(p, entryId, r);
        List<EconomyChange> changes = new ArrayList<>();
        if (!economy.trySpend(p.getUniqueId(), e.price(), changes, "purchase:" + e.id())) return fail(p, entryId, BuyResult.TOO_POOR);
        Purchase purchase = new Purchase(e.id(), match.flow().round(), e.amount());
        if (!apply(p, e, purchase.amount())) {
            economy.credit(p.getUniqueId(), e.price(), "refund:" + e.id()); // all-or-nothing
            return fail(p, entryId, e.kind() == ShopEntry.Kind.ENCHANT ? BuyResult.NOTHING_TO_ENCHANT : BuyResult.INVENTORY_FULL);
        }
        ledger.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(purchase);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("entry", e.id());
        d.put("price", e.price());
        d.put("balance", economy.balance(p.getUniqueId()));
        match.recorder().record(EventTypes.PURCHASE, List.of(p.getUniqueId()), team(p.getUniqueId()), d);
        recordChanges(changes);
        return BuyResult.OK;
    }

    /** Sells back a purchase made THIS round that is still fully owned. */
    public boolean sellBack(Player p, String entryId) {
        if (!economy.rules().sellBackSameRound() || match.state() != MatchState.BUY_PHASE || match.flow().isPaused()) return false;
        ShopEntry e = entry(p.getUniqueId(), entryId);
        List<Purchase> list = ledger.get(p.getUniqueId());
        if (e == null || list == null) return false;
        for (int i = list.size() - 1; i >= 0; i--) {
            Purchase pu = list.get(i);
            if (!pu.entryId().equals(entryId) || pu.round() != match.flow().round()) continue;
            if (e.kind() == ShopEntry.Kind.ABSORPTION) {
                if (absorptionLevel(p) != e.level() / 2 - 1) continue;
                p.removePotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION);
            } else if (e.kind() == ShopEntry.Kind.ITEM) {
                String k = e.itemKey();
                if (k == null || count(p.getInventory(), k) - baseCount.applyAsInt(p.getUniqueId(), k) < pu.amount()) continue;
                remove(p.getInventory(), k, pu.amount());
            } else {
                removeEnchant(p, e);
            }
            list.remove(i);
            EconomyChange c = economy.credit(p.getUniqueId(), e.price(), "sell_back:" + e.id());
            match.recorder().record(EventTypes.PURCHASE, List.of(p.getUniqueId()), team(p.getUniqueId()),
                    Map.of("entry", e.id(), "sold_back", true, "refund", c.delta()));
            recordChanges(List.of(c));
            return true;
        }
        return false;
    }

    private BuyResult fail(Player p, String entryId, BuyResult r) {
        match.recorder().record(EventTypes.PURCHASE_FAILED, List.of(p.getUniqueId()), team(p.getUniqueId()),
                Map.of("entry", entryId, "reason", r.name()), "FAILED", RecordingSink.Priority.NORMAL);
        return r;
    }

    // ------------------------------------------------------------------ round lifecycle

    /** Round start (after the arena gave the loadout): drop expired purchases, re-deliver the rest. */
    void onRoundStart(Player p) {
        List<Purchase> list = ledger.get(p.getUniqueId());
        if (list == null) return;
        int round = match.flow().round();
        int keep = economy.rules().retentionRounds();
        List<Purchase> kept = new ArrayList<>();
        List<Purchase> enchants = new ArrayList<>();
        for (Purchase pu : list) {
            ShopEntry e = entry(p.getUniqueId(), pu.entryId());
            if (e == null || pu.amount() <= 0) continue;
            if (round - pu.round() > keep) {
                match.recorder().record(EventTypes.ITEM_EXPIRED, List.of(p.getUniqueId()), team(p.getUniqueId()),
                        Map.of("entry", pu.entryId(), "bought_round", pu.round()));
                continue;
            }
            if (e.kind() == ShopEntry.Kind.ABSORPTION) continue; // hearts last one round
            if (e.kind() == ShopEntry.Kind.ENCHANT) enchants.add(pu);
            else if (apply(p, e, pu.amount())) kept.add(pu);
        }
        // Enchantments after items, so they land on the re-delivered armor/weapons.
        for (Purchase pu : enchants) {
            if (apply(p, entry(p.getUniqueId(), pu.entryId()), 1)) kept.add(pu);
        }
        ledger.put(p.getUniqueId(), kept);
    }

    /** Round end, survivors only: keep what is still in the inventory; used items are consumed oldest-first. */
    void reconcile(Player p) {
        List<Purchase> list = ledger.get(p.getUniqueId());
        if (list == null) return;
        Map<String, Integer> remaining = new HashMap<>();
        List<Purchase> out = new ArrayList<>(list);
        for (int i = out.size() - 1; i >= 0; i--) { // newest first keep their amounts
            Purchase pu = out.get(i);
            ShopEntry e = entry(p.getUniqueId(), pu.entryId());
            if (e == null) {
                out.remove(i);
                continue;
            }
            if (e.kind() == ShopEntry.Kind.ABSORPTION) {
                out.remove(i); // used for this round only
                continue;
            }
            if (e.kind() == ShopEntry.Kind.ENCHANT) {
                if (enchantTarget(p.getInventory(), e, false) == null) out.remove(i); // the enchanted item is gone
                continue;
            }
            String key = e.itemKey();
            int left = remaining.computeIfAbsent(key, k -> Math.max(0, count(p.getInventory(), k) - baseCount.applyAsInt(p.getUniqueId(), k)));
            int have = Math.min(left, pu.amount());
            remaining.put(key, left - have);
            out.set(i, new Purchase(pu.entryId(), pu.round(), have));
        }
        out.removeIf(pu -> pu.amount() <= 0);
        ledger.put(p.getUniqueId(), out);
    }

    /** Death: every purchase is lost. (The spectator's inventory is reset at the next round start.) */
    void onDeath(UUID player) {
        ledger.remove(player);
    }

    void clearAll() {
        ledger.clear();
    }

    // ------------------------------------------------------------------ item helpers

    /** Amplifier of the player's absorption (0 = Absorption I = 2 hearts), -1 if none. */
    static int absorptionLevel(Player p) {
        var effect = p.getPotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION);
        return effect == null ? -1 : effect.getAmplifier();
    }

    private boolean apply(Player p, ShopEntry e, int amount) {
        PlayerInventory inv = p.getInventory();
        if (e.kind() == ShopEntry.Kind.ABSORPTION) { // Absorption I/II/III = 2/4/6 hearts, until used up or the round ends
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION);
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, e.level() / 2 - 1, false, false, true));
            return true;
        }
        if (e.kind() == ShopEntry.Kind.ENCHANT) {
            ItemStack target = enchantTarget(inv, e, true);
            Enchantment ench = enchantment(e);
            if (target == null || ench == null) return false;
            target.addUnsafeEnchantment(ench, e.level());
            return true;
        }
        ItemStack item = factory.create(e, Math.max(1, amount));
        if (item == null) return false;
        EquipmentSlot armorSlot = item.getType().getEquipmentSlot();
        if (armorSlot.isArmor()) {
            ItemStack current = inv.getItem(armorSlot);
            if (current == null || current.getType().isAir()) {
                inv.setItem(armorSlot, item);
                return true;
            }
        }
        return inv.addItem(item).isEmpty();
    }

    /** Match items never break and show no durability bar; weapons and armor get the standard lore. */
    public static ItemStack prepare(ItemStack item) {
        if (item.getType().getMaxDurability() > 0) item.setData(DataComponentTypes.UNBREAKABLE);
        return dev.tactical.paper.item.ItemLore.decorate(item);
    }

    /** First item the enchantment applies to; with {@code needsUpgrade} only items below the purchased level. */
    private ItemStack enchantTarget(PlayerInventory inv, ShopEntry e, boolean needsUpgrade) {
        Enchantment ench = enchantment(e);
        for (ItemStack i : inv.getContents()) {
            if (i == null || !e.appliesTo().contains(i.getType().getKey().asString())) continue;
            if (!needsUpgrade || ench == null || i.getEnchantmentLevel(ench) < e.level()) return i;
        }
        return null;
    }

    private void removeEnchant(Player p, ShopEntry e) {
        Enchantment ench = enchantment(e);
        if (ench == null) return;
        for (ItemStack i : p.getInventory().getContents()) {
            if (i != null && e.appliesTo().contains(i.getType().getKey().asString()) && i.getEnchantmentLevel(ench) >= e.level()) {
                i.removeEnchantment(ench);
                return;
            }
        }
    }

    static Enchantment enchantment(ShopEntry e) {
        NamespacedKey k = e.enchantment() == null ? null : NamespacedKey.fromString(e.enchantment());
        return k == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k);
    }

    int count(PlayerInventory inv, String key) {
        int n = 0;
        for (ItemStack i : inv.getContents()) if (i != null && !i.getType().isAir() && key.equals(keys.keyOf(i))) n += i.getAmount();
        return n;
    }

    /** Removes up to {@code amount} of an item, taking from the main inventory before armor slots. */
    private void remove(PlayerInventory inv, String key, int amount) {
        ItemStack[] c = inv.getContents();
        for (int pass = 0; pass < 2 && amount > 0; pass++) {
            for (int i = 0; i < c.length && amount > 0; i++) {
                boolean armorSlot = i >= 36 && i <= 39;
                if (c[i] == null || c[i].getType().isAir() || !key.equals(keys.keyOf(c[i])) || (pass == 0) == armorSlot) continue;
                int take = Math.min(amount, c[i].getAmount());
                amount -= take;
                if (take == c[i].getAmount()) inv.setItem(i, null);
                else c[i].setAmount(c[i].getAmount() - take);
            }
        }
    }

    // ------------------------------------------------------------------ hotbar layouts

    private static final String BOMB_KEY = "tactical:bomb";

    /** Per player: what each hotbar slot held (item key, null = empty) when they last arranged / survived a round. */
    private final Map<UUID, String[]> layouts = new HashMap<>();

    /**
     * Remembers the player's hotbar order (shop closed, or round survived). An empty slot KEEPS the item it held
     * before (used up or lost), unless that item is now in another hotbar slot: so a used shot or a re-bought weapon
     * returns to its old slot.
     */
    public void rememberLayout(Player p) {
        String[] now = new String[9];
        for (int i = 0; i < 9; i++) {
            ItemStack it = p.getInventory().getItem(i);
            String k = it == null || it.getType().isAir() ? null : this.keys.keyOf(it);
            now[i] = BOMB_KEY.equals(k) ? null : k; // the bomb changes hands every round: never part of a layout
        }
        String[] before = layouts.get(p.getUniqueId());
        if (before != null) {
            List<String> present = java.util.Arrays.asList(now.clone());
            for (int i = 0; i < 9; i++) {
                if (now[i] == null && before[i] != null && !present.contains(before[i])) now[i] = before[i];
            }
        }
        layouts.put(p.getUniqueId(), now);
    }

    /** Hotbar slot the player keeps this item in (-1 = none remembered). */
    public int rememberedSlot(UUID player, String key, java.util.function.IntPredicate usable) {
        String[] layout = layouts.get(player);
        if (layout == null || key == null) return -1;
        for (int i = 0; i < 9; i++) if (key.equals(layout[i]) && usable.test(i)) return i;
        return -1;
    }

    String keyOf(ItemStack item) {
        return keys.keyOf(item);
    }

    /** New kits (halftime, sudden death): layouts start over. */
    void clearLayouts() {
        layouts.clear();
    }

    /**
     * Round start, after the base kit and kept purchases were given: every remembered slot gets its item back. A slot
     * whose item is gone (lost by dying) stays EMPTY; other items fill the slots that were empty in the layout, then
     * the main inventory, and only then the reserved slots.
     */
    void applyLayout(Player p) {
        String[] layout = layouts.get(p.getUniqueId());
        if (layout == null) return;
        PlayerInventory inv = p.getInventory();
        List<ItemStack> pool = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || BOMB_KEY.equals(keys.keyOf(it))) continue; // the bomb stays put
            pool.add(it);
            inv.setItem(i, null);
        }
        boolean[] reserved = new boolean[9];
        for (int i = 0; i < 9; i++) {
            if (layout[i] == null || inv.getItem(i) != null) continue;
            reserved[i] = true;
            for (int j = 0; j < pool.size(); j++) {
                if (layout[i].equals(keys.keyOf(pool.get(j)))) {
                    inv.setItem(i, pool.remove(j));
                    break;
                }
            }
        }
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (!reserved[i]) free.add(i);
        for (int i = 9; i < 36; i++) free.add(i);
        for (int i = 0; i < 9; i++) if (reserved[i] && inv.getItem(i) == null) free.add(i); // last resort
        for (ItemStack it : pool) {
            while (!free.isEmpty() && inv.getItem(free.get(0)) != null) free.remove(0);
            if (free.isEmpty()) {
                inv.addItem(it);
                continue;
            }
            inv.setItem(free.remove(0), it);
        }
    }

    // ------------------------------------------------------------------ recording

    void recordChanges(List<EconomyChange> changes) {
        for (EconomyChange c : changes) {
            if (c.delta() == 0) continue;
            match.recorder().record(EventTypes.ECONOMY_CHANGE, List.of(c.player()), team(c.player()),
                    Map.of("delta", c.delta(), "balance", c.balance(), "reason", c.reason()));
        }
    }

    private dev.tactical.engine.team.TeamId team(UUID id) {
        return match.member(id).map(MatchMember::team).orElse(null);
    }
}
