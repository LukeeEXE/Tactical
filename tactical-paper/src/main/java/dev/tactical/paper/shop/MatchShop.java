package dev.tactical.paper.shop;

import dev.tactical.engine.economy.EconomyChange;
import dev.tactical.engine.economy.MatchEconomy;
import dev.tactical.engine.record.EventTypes;
import dev.tactical.engine.record.RecordingSink;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.match.Participant;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Economy and purchases of ONE match. The purchase LEDGER is authoritative (never the items): items are re-created
 * from it every round, so a purchase cannot be duplicated by moving items around, and expiry/death loss are exact.
 * <ul>
 *   <li>Bought in round N: usable in rounds N..N+retention while unused; removed at the start of the round after.</li>
 *   <li>Dying loses every purchase; surviving keeps what is still in the inventory (used-up items disappear).</li>
 *   <li>Halftime clears purchases if configured.</li>
 * </ul>
 */
public final class MatchShop {

    /** One purchase. {@code amount} is what the player still has (reconciled at round end). */
    public record Purchase(UUID instance, String entryId, int round, int amount) {
    }

    public enum BuyResult { OK, NOT_BUY_PHASE, DEAD, DISABLED, UNKNOWN, TOO_POOR, MAX_CARRY, NOTHING_TO_ENCHANT, ALREADY_ENCHANTED }

    private final GameMatch match;
    private final MatchEconomy economy;
    private final ShopDefinition shop;
    private final NamespacedKey instanceKey;
    private final Map<UUID, List<Purchase>> ledger = new HashMap<>();

    MatchShop(GameMatch match, MatchEconomy economy, ShopDefinition shop, NamespacedKey instanceKey) {
        this.match = match;
        this.economy = economy;
        this.shop = shop;
        this.instanceKey = instanceKey;
    }

    public GameMatch match() {
        return match;
    }

    public MatchEconomy economy() {
        return economy;
    }

    public ShopDefinition shop() {
        return shop;
    }

    public List<Purchase> purchases(UUID player) {
        return List.copyOf(ledger.getOrDefault(player, List.of()));
    }

    public int carried(UUID player, String entryId) {
        int n = 0;
        for (Purchase p : ledger.getOrDefault(player, List.of())) if (p.entryId().equals(entryId)) n++;
        return n;
    }

    // ------------------------------------------------------------------ buying

    public BuyResult canBuy(Player p, ShopEntry e) {
        if (match.state() != MatchState.BUY_PHASE || match.flow().isPaused()) return BuyResult.NOT_BUY_PHASE;
        if (!match.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false)) return BuyResult.DEAD;
        if (!e.enabled()) return BuyResult.DISABLED;
        if (carried(p.getUniqueId(), e.id()) >= e.maxCarry()) return BuyResult.MAX_CARRY;
        if (economy.balance(p.getUniqueId()) < e.price()) return BuyResult.TOO_POOR;
        if (e.kind() == ShopEntry.Kind.ENCHANT) {
            ItemStack target = enchantTarget(p.getInventory(), e);
            if (target == null) return BuyResult.NOTHING_TO_ENCHANT;
            Enchantment ench = enchantment(e);
            if (ench != null && target.getEnchantmentLevel(ench) >= e.level()) return BuyResult.ALREADY_ENCHANTED;
        }
        return BuyResult.OK;
    }

    public BuyResult buy(Player p, String entryId) {
        ShopEntry e = shop.entries().get(entryId);
        if (e == null) return fail(p, entryId, BuyResult.UNKNOWN);
        BuyResult r = canBuy(p, e);
        if (r != BuyResult.OK) return fail(p, entryId, r);
        List<EconomyChange> changes = new ArrayList<>();
        if (!economy.trySpend(p.getUniqueId(), e.price(), changes, "purchase:" + e.id())) return fail(p, entryId, BuyResult.TOO_POOR);
        Purchase purchase = new Purchase(UUID.randomUUID(), e.id(), match.flow().round(), e.amount());
        if (!apply(p, e, purchase)) {
            // Could not deliver (e.g. inventory problem): refund so the purchase is all-or-nothing.
            economy.credit(p.getUniqueId(), e.price(), "refund:" + e.id());
            return fail(p, entryId, BuyResult.NOTHING_TO_ENCHANT);
        }
        ledger.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(purchase);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("entry", e.id());
        d.put("price", e.price());
        d.put("instance", purchase.instance());
        d.put("balance", economy.balance(p.getUniqueId()));
        match.recorder().record(EventTypes.PURCHASE, List.of(p.getUniqueId()), team(p.getUniqueId()), d);
        recordChanges(changes);
        return BuyResult.OK;
    }

    /** Sells back a purchase made THIS round that is still fully in the inventory. */
    public boolean sellBack(Player p, String entryId) {
        if (!economy.rules().sellBackSameRound() || match.state() != MatchState.BUY_PHASE || match.flow().isPaused()) return false;
        List<Purchase> list = ledger.getOrDefault(p.getUniqueId(), new ArrayList<>());
        ShopEntry e = shop.entries().get(entryId);
        if (e == null) return false;
        for (int i = list.size() - 1; i >= 0; i--) {
            Purchase pu = list.get(i);
            if (!pu.entryId().equals(entryId) || pu.round() != match.flow().round()) continue;
            if (e.kind() == ShopEntry.Kind.ITEM && countInstance(p.getInventory(), pu.instance()) < pu.amount()) continue;
            if (e.kind() == ShopEntry.Kind.ITEM) removeInstance(p.getInventory(), pu.instance());
            else removeEnchant(p, e);
            list.remove(i);
            EconomyChange c = economy.credit(p.getUniqueId(), e.price(), "sell_back:" + e.id());
            match.recorder().record(EventTypes.PURCHASE, List.of(p.getUniqueId()), team(p.getUniqueId()),
                    Map.of("entry", e.id(), "sold_back", true, "refund", c.delta(), "instance", pu.instance()));
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

    /** Round start (after the arena reset the inventory): drop expired purchases, re-deliver the rest. */
    void onRoundStart(Player p) {
        List<Purchase> list = ledger.get(p.getUniqueId());
        if (list == null) return;
        int round = match.flow().round();
        int keep = economy.rules().retentionRounds();
        List<Purchase> kept = new ArrayList<>();
        for (Purchase pu : list) {
            if (round - pu.round() > keep) {
                match.recorder().record(EventTypes.ITEM_EXPIRED, List.of(p.getUniqueId()), team(p.getUniqueId()),
                        Map.of("entry", pu.entryId(), "instance", pu.instance(), "bought_round", pu.round()));
                continue;
            }
            ShopEntry e = shop.entries().get(pu.entryId());
            if (e != null && apply(p, e, pu)) kept.add(pu);
        }
        ledger.put(p.getUniqueId(), kept);
    }

    /** Round end: survivors keep what is still in their inventory (used-up items are gone). */
    void reconcile(Player p) {
        List<Purchase> list = ledger.get(p.getUniqueId());
        if (list == null) return;
        List<Purchase> out = new ArrayList<>();
        for (Purchase pu : list) {
            ShopEntry e = shop.entries().get(pu.entryId());
            if (e == null) continue;
            if (e.kind() == ShopEntry.Kind.ENCHANT) {
                out.add(pu);
                continue;
            }
            int have = countInstance(p.getInventory(), pu.instance());
            if (have > 0) out.add(new Purchase(pu.instance(), pu.entryId(), pu.round(), Math.min(have, pu.amount())));
        }
        ledger.put(p.getUniqueId(), out);
    }

    /** Death: every purchase is lost (also removed from the inventory). */
    void onDeath(UUID player, Player online) {
        List<Purchase> lost = ledger.remove(player);
        if (online != null && lost != null) {
            for (Purchase pu : lost) removeInstance(online.getInventory(), pu.instance());
        }
    }

    void clearAll() {
        ledger.clear();
    }

    // ------------------------------------------------------------------ item helpers

    private boolean apply(Player p, ShopEntry e, Purchase pu) {
        PlayerInventory inv = p.getInventory();
        if (e.kind() == ShopEntry.Kind.ENCHANT) {
            ItemStack target = enchantTarget(inv, e);
            Enchantment ench = enchantment(e);
            if (target == null || ench == null) return false;
            target.addUnsafeEnchantment(ench, e.level());
            return true;
        }
        Material m = Material.matchMaterial(e.material());
        if (m == null) return false;
        ItemStack item = new ItemStack(m, Math.max(1, pu.amount()));
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, pu.instance().toString());
            meta.lore(List.of(Component.text("Purchased (round " + pu.round() + ")", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        });
        EquipmentSlot armorSlot = m.getEquipmentSlot();
        if (armorSlot.isArmor()) {
            ItemStack current = inv.getItem(armorSlot);
            if (current == null || current.getType().isAir() || !isPurchase(current)) {
                if (current != null && !current.getType().isAir()) inv.addItem(current);
                inv.setItem(armorSlot, item);
                return true;
            }
        }
        return inv.addItem(item).isEmpty();
    }

    private ItemStack enchantTarget(PlayerInventory inv, ShopEntry e) {
        for (ItemStack i : inv.getContents()) {
            if (i != null && e.appliesTo().contains(i.getType().getKey().asString())) return i;
        }
        return null;
    }

    private void removeEnchant(Player p, ShopEntry e) {
        ItemStack t = enchantTarget(p.getInventory(), e);
        Enchantment ench = enchantment(e);
        if (t != null && ench != null) t.removeEnchantment(ench);
    }

    static Enchantment enchantment(ShopEntry e) {
        NamespacedKey k = e.enchantment() == null ? null : NamespacedKey.fromString(e.enchantment());
        return k == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k);
    }

    private boolean isPurchase(ItemStack i) {
        return i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(instanceKey, PersistentDataType.STRING);
    }

    private int countInstance(PlayerInventory inv, UUID instance) {
        int n = 0;
        String id = instance.toString();
        for (ItemStack i : inv.getContents()) {
            if (i != null && i.hasItemMeta() && id.equals(i.getItemMeta().getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING))) {
                n += i.getAmount();
            }
        }
        return n;
    }

    private void removeInstance(PlayerInventory inv, UUID instance) {
        String id = instance.toString();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (c[i] != null && c[i].hasItemMeta() && id.equals(c[i].getItemMeta().getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING))) {
                inv.setItem(i, null);
            }
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
