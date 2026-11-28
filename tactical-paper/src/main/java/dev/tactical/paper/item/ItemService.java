package dev.tactical.paper.item;

import dev.tactical.engine.match.Participant;
import dev.tactical.engine.state.MatchState;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom items from {@code items/*.yml}: creates them (identified by {@code tactical:item_id} in their persistent
 * data) and runs their behaviour. Shots:
 * <ul>
 *   <li>right-click: the effects are applied to yourself;</li>
 *   <li>left-click (punch) a player: a positive shot on a teammate / a negative shot on an opponent applies the
 *       effects to them and deals no damage; any other punch is just a normal punch;</li>
 *   <li>one shot is used up per application; every shot shares one cooldown (default 1 s);</li>
 *   <li>the target gets a ring of particles in the shot's colour (no trail).</li>
 * </ul>
 * Only during a live round, by and on alive participants of the same match.
 */
public final class ItemService implements Service, Listener {

    public static final Key SHOT_COOLDOWN_GROUP = Key.key("tactical", "shot");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final ConfigService config;
    private final MatchService matches;
    private final NamespacedKey idKey;

    public ItemService(Plugin plugin, ConfigService config, MatchService matches) {
        this.plugin = plugin;
        this.config = config;
        this.matches = matches;
        this.idKey = new NamespacedKey(plugin, "item_id");
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        customEffects = new CustomEffects(plugin);
        Bukkit.getPluginManager().registerEvents(customEffects, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (customEffects != null) {
            HandlerList.unregisterAll(customEffects);
            customEffects.shutdown();
        }
    }

    /** Flash, cleanse and the mining fatigue change (see {@link CustomEffects}). */
    private CustomEffects customEffects;

    /** {@code /game item list} and {@code /game item give <id> [amount]} (permission tactical.admin.items). */
    public void attach(com.mojang.brigadier.builder.LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> root) {
        var cmd = io.papermc.paper.command.brigadier.Commands.literal("item")
                .requires(s -> s.getSender().hasPermission("tactical.admin.items"));
        cmd.then(io.papermc.paper.command.brigadier.Commands.literal("list").executes(ctx -> {
            var sender = ctx.getSource().getSender();
            if (config.items().isEmpty()) sender.sendMessage(Component.text("No custom items (items/*.yml).", net.kyori.adventure.text.format.NamedTextColor.RED));
            for (ItemDefinition d : config.items().values()) {
                sender.sendMessage(Component.text(d.id() + " (" + d.type() + ", " + d.polarity().name().toLowerCase(Locale.ROOT)
                        + ", " + d.effects().size() + " effect(s))", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            }
            return 1;
        }));
        var id = io.papermc.paper.command.brigadier.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests((c, b) -> {
                    config.items().keySet().forEach(b::suggest);
                    return b.buildFuture();
                });
        com.mojang.brigadier.Command<io.papermc.paper.command.brigadier.CommandSourceStack> give = ctx -> {
            if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                ctx.getSource().getSender().sendMessage(Component.text("Only players can do this.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return 0;
            }
            String itemId = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
            int amount = 1;
            try {
                amount = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount");
            } catch (IllegalArgumentException ignored) {
                // no amount given
            }
            Optional<ItemStack> item = create(itemId, amount);
            if (item.isEmpty()) {
                p.sendMessage(Component.text("Unknown item '" + itemId + "'. /game item list", net.kyori.adventure.text.format.NamedTextColor.RED));
                return 0;
            }
            p.getInventory().addItem(item.get());
            p.sendMessage(Component.text("Given " + amount + "x " + itemId + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN));
            return 1;
        };
        cmd.then(io.papermc.paper.command.brigadier.Commands.literal("give").then(id.executes(give)
                .then(io.papermc.paper.command.brigadier.Commands.argument("amount",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64)).executes(give))));
        root.then(cmd);
    }

    // ------------------------------------------------------------------ base kits

    /**
     * Clears nothing; gives the player's kit base kit (or the config's placeholder loadout for kits without one).
     * Custom items come from items/*.yml; vanilla items are prepared (unbreakable, lore). Armor slots are worn.
     */
    public void equipBaseKit(Player p, String kitId) {
        dev.tactical.paper.config.KitDefinition kit = kitId == null ? null : config.kits().get(kitId);
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        if (kit == null || kit.baseKit().isEmpty()) {
            for (String spec : config.general().placeholderLoadout()) {
                ItemStack item = dev.tactical.paper.arena.MatchArena.parseItem(spec);
                if (item != null) inv.addItem(dev.tactical.paper.shop.MatchShop.prepare(item));
            }
            return;
        }
        for (dev.tactical.paper.config.KitDefinition.KitItem ki : kit.baseKit()) {
            ItemStack item = stack(ki);
            if (item == null) continue;
            switch (ki.slot()) {
                case "helmet" -> inv.setHelmet(item);
                case "chestplate" -> inv.setChestplate(item);
                case "leggings" -> inv.setLeggings(item);
                case "boots" -> inv.setBoots(item);
                case "offhand" -> inv.setItemInOffHand(item);
                case "auto" -> {
                    EquipmentSlot armor = item.getType().getEquipmentSlot();
                    if (armor.isArmor() && (inv.getItem(armor) == null || inv.getItem(armor).getType().isAir())) inv.setItem(armor, item);
                    else inv.addItem(item);
                }
                default -> inv.setItem(Integer.parseInt(ki.slot()), item);
            }
        }
    }

    /** How many of an inventory item key ({@code tactical:<id>} or a material key) a kit's base kit gives. */
    public int baseCount(String kitId, String key) {
        dev.tactical.paper.config.KitDefinition kit = kitId == null ? null : config.kits().get(kitId);
        int n = 0;
        if (kit == null || kit.baseKit().isEmpty()) {
            for (String spec : config.general().placeholderLoadout()) {
                ItemStack i = dev.tactical.paper.arena.MatchArena.parseItem(spec);
                if (i != null && i.getType().getKey().asString().equals(key)) n += i.getAmount();
            }
            return n;
        }
        for (var ki : kit.baseKit()) {
            String k = ki.item() != null ? "tactical:" + ki.item() : ki.material();
            if (k.equals(key)) n += ki.amount();
        }
        return n;
    }

    private ItemStack stack(dev.tactical.paper.config.KitDefinition.KitItem ki) {
        if (ki.item() != null) return create(ki.item(), ki.amount()).orElse(null);
        org.bukkit.Material m = org.bukkit.Material.matchMaterial(ki.material());
        return m == null ? null : dev.tactical.paper.shop.MatchShop.prepare(new ItemStack(m, ki.amount()));
    }

    public Optional<ItemDefinition> definition(String id) {
        return Optional.ofNullable(config.items().get(id));
    }

    /** The custom item id of a stack, if it is one of ours. */
    public Optional<String> idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    public Optional<ItemDefinition> definitionOf(ItemStack item) {
        return idOf(item).flatMap(this::definition);
    }

    /** A fresh stack of a custom item, or empty if the id is unknown. */
    public Optional<ItemStack> create(String id, int amount) {
        return definition(id).map(d -> create(d, amount));
    }

    public ItemStack create(ItemDefinition d, int amount) {
        ItemStack item = new ItemStack(d.material(), Math.max(1, amount));
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, d.id());
            meta.displayName(MM.deserialize(d.name()).decoration(TextDecoration.ITALIC, false));
        });
        item.unsetData(DataComponentTypes.CONSUMABLE); // a potion-looking shot must not be drunk
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(Math.max(0.05f, d.cooldownTicks() / 20f))
                .cooldownGroup(SHOT_COOLDOWN_GROUP).build());
        if (d.material() == org.bukkit.Material.POTION || d.material() == org.bukkit.Material.SPLASH_POTION
                || d.material() == org.bukkit.Material.LINGERING_POTION) {
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents()
                    .customColor(Color.fromRGB(d.color().value())).build());
        }
        if (d.model() != null) item.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(d.model()));
        item.lore(ItemLore.lines(d.category(), description(d), List.of(ItemLore.Usage.RIGHT_CLICK_USE, ItemLore.Usage.LEFT_CLICK_APPLY)));
        ItemLore.hideVanillaLines(item);
        return item;
    }

    /** Description lines: MiniMessage in the description colour, with {@code <duration>} and {@code <level>}. */
    private static List<Component> description(ItemDefinition d) {
        ItemEffect first = d.effects().get(0);
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("duration", seconds(d.durationTicks())),
                Placeholder.unparsed("level", String.valueOf(first.level())));
        return d.description().stream()
                .map(line -> MM.deserialize(line, placeholders).colorIfAbsent(ItemLore.DESCRIPTION))
                .toList();
    }

    static String seconds(int ticks) {
        double s = ticks / 20.0;
        return (s == Math.floor(s) ? String.valueOf((long) s) : String.format(Locale.ROOT, "%.1f", s)) + "s";
    }

    // ------------------------------------------------------------------ shots

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        ItemDefinition d = definitionOf(item).orElse(null);
        if (d == null || !d.type().equals("shot")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        GameMatch m = liveMatchOf(p).orElse(null);
        if (m == null || p.hasCooldown(item)) return;
        use(p, p, d, item);
    }

    /** Left-click: runs after friendly-fire cancellation, so it also sees (cancelled) hits on teammates. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPunch(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player source) || !(e.getEntity() instanceof Player target)) return;
        ItemStack item = source.getInventory().getItemInMainHand();
        ItemDefinition d = definitionOf(item).orElse(null);
        if (d == null || !d.type().equals("shot")) return;
        GameMatch m = liveMatchOf(source).orElse(null);
        if (m == null || !alive(m, target) || source.hasCooldown(item)) return;
        boolean sameTeam = m.member(source.getUniqueId()).map(MatchMember::team).orElse(null)
                == m.member(target.getUniqueId()).map(MatchMember::team).orElse(null);
        boolean valid = d.polarity() == ItemEffect.Polarity.POSITIVE ? sameTeam : !sameTeam;
        if (!valid) return; // just a regular punch (or a blocked hit on a teammate)
        e.setCancelled(true);
        use(source, target, d, item);
    }

    private void use(Player source, Player target, ItemDefinition d, ItemStack item) {
        for (ItemEffect effect : d.effects()) effect.apply(target, source);
        item.setAmount(item.getAmount() - 1);
        if (d.cooldownTicks() > 0) source.setCooldown(SHOT_COOLDOWN_GROUP, d.cooldownTicks());
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(d.color().value()), 1.0f);
        ring(target, dust, d.ringRadius());
        // Only the ring: no trail while the effect lasts.
    }

    /** A ring of particles around the player, at waist height. */
    private static void ring(Player target, Particle.DustOptions dust, double radius) {
        Location c = target.getLocation().add(0, 1.0, 0);
        int points = (int) Math.max(16, Math.round(radius * 24));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            target.getWorld().spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * radius, c.getY(), c.getZ() + Math.sin(a) * radius,
                    1, 0, 0, 0, 0, dust, true);
        }
    }

    /** A few particles around the player every 4 ticks while the effect lasts (stops early when they leave or die). */
    private void trail(Player target, Particle.DustOptions dust, int ticks) {
        BukkitTask[] task = new BukkitTask[1];
        int[] left = {ticks};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            left[0] -= 4;
            GameMatch m = matches.matchOf(target.getUniqueId()).orElse(null);
            if (left[0] <= 0 || !target.isOnline() || m == null || !alive(m, target)) {
                task[0].cancel();
                return;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 2; i++) {
                double a = r.nextDouble(2 * Math.PI);
                Location l = target.getLocation().add(Math.cos(a) * 0.45, r.nextDouble(0.1, 1.8), Math.sin(a) * 0.45);
                target.getWorld().spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dust);
            }
        }, 4L, 4L);
    }

    /** The player's match if a round is being played and they are alive in it. */
    private Optional<GameMatch> liveMatchOf(Player p) {
        GameMatch m = matches.matchOf(p.getUniqueId()).orElse(null);
        if (m == null || m.flow().isPaused()) return Optional.empty();
        if (m.state() != MatchState.ROUND_LIVE && m.state() != MatchState.BOMB_PLANTED) return Optional.empty();
        return alive(m, p) ? Optional.of(m) : Optional.empty();
    }

    private static boolean alive(GameMatch m, Player p) {
        return m.isMember(p.getUniqueId()) && m.flow().participant(p.getUniqueId()).map(Participant::active).orElse(false);
    }
}
