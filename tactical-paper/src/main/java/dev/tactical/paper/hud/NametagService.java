package dev.tactical.paper.hud;

import dev.tactical.engine.match.Participant;
import dev.tactical.engine.pack.PackBuilder;
import dev.tactical.paper.match.GameMatch;
import dev.tactical.paper.match.MatchMember;
import dev.tactical.paper.match.MatchService;
import dev.tactical.paper.core.Service;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Name tags for match players, replacing the vanilla ones (hidden with a scoreboard team): text displays riding on
 * the player (so they move with them), billboard centre, no shadow, hidden by walls like any other text in the world.
 * <ul>
 *   <li>name in the team colour, seen by everyone but the player;</li>
 *   <li>above it the health bar (2 hearts per segment + the number), seen by teammates and by opponents who hurt
 *       the player this round ({@link DamageMemory});</li>
 *   <li>sneaking hides both; dead players have none.</li>
 * </ul>
 */
public final class NametagService implements Service, Listener {

    private static final String TEAM = "tactical_nametags";
    private static final Key HEALTH_FONT = Key.key("tactical", PackBuilder.HEALTH_FONT);

    private final Plugin plugin;
    private final MatchService matches;
    private final DamageMemory damage;
    private final Map<UUID, Tag> tags = new HashMap<>();
    private BukkitTask task;

    private static final class Tag {
        final TextDisplay name;
        final TextDisplay health;
        Component nameText;
        Component healthText;
        boolean hidden;

        Tag(TextDisplay name, TextDisplay health) {
            this.name = name;
            this.health = health;
        }

        boolean valid() {
            return name.isValid() && health.isValid();
        }

        void remove() {
            if (name.isValid()) name.remove();
            if (health.isValid()) health.remove();
        }
    }

    public NametagService(Plugin plugin, MatchService matches, DamageMemory damage) {
        this.plugin = plugin;
        this.matches = matches;
        this.damage = damage;
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(damage, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(damage);
        for (Tag t : tags.values()) t.remove();
        tags.clear();
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(TEAM);
        if (team != null) team.unregister();
    }

    private static Team hiddenNames() {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = board.getTeam(TEAM);
        if (t == null) {
            t = board.registerNewTeam(TEAM);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return t;
    }

    private void tick() {
        Team vanillaHidden = hiddenNames();
        Set<UUID> wanted = new HashSet<>();
        for (GameMatch match : matches.matches()) {
            for (MatchMember m : match.members()) {
                Player p = Bukkit.getPlayer(m.uuid());
                if (p == null || m.virtual()) continue;
                if (!vanillaHidden.hasEntry(p.getName())) vanillaHidden.addEntry(p.getName());
                boolean alive = match.flow().participant(m.uuid()).map(Participant::active).orElse(false)
                        && p.getGameMode() != GameMode.SPECTATOR && !p.isDead();
                if (!alive) continue;
                wanted.add(p.getUniqueId());
                Tag tag = tags.get(p.getUniqueId());
                if (tag == null || !tag.valid() || !tag.name.getWorld().equals(p.getWorld())) {
                    if (tag != null) tag.remove();
                    tag = create(p);
                    tags.put(p.getUniqueId(), tag);
                }
                update(match, m, p, tag);
            }
        }
        Iterator<Map.Entry<UUID, Tag>> it = tags.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Tag> e = it.next();
            if (wanted.contains(e.getKey())) continue;
            e.getValue().remove();
            it.remove();
        }
        // players no longer in a match get their vanilla name tag back
        for (String entry : new HashSet<>(vanillaHidden.getEntries())) {
            Player p = Bukkit.getPlayerExact(entry);
            if (p == null || matches.matchOf(p.getUniqueId()).isEmpty()) vanillaHidden.removeEntry(entry);
        }
    }

    private Tag create(Player p) {
        TextDisplay name = display(p, 0.3f, Color.fromARGB(0x40000000));
        TextDisplay health = display(p, 0.58f, Color.fromARGB(0));
        return new Tag(name, health);
    }

    private TextDisplay display(Player p, float up, Color background) {
        TextDisplay d = p.getWorld().spawn(p.getLocation().add(0, 1.8, 0), TextDisplay.class, t -> {
            t.text(Component.empty());
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(background);
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setTransformation(new Transformation(new Vector3f(0, up, 0), new AxisAngle4f(), new Vector3f(1), new AxisAngle4f()));
        });
        p.addPassenger(d);
        return d;
    }

    private void update(GameMatch match, MatchMember m, Player owner, Tag tag) {
        for (TextDisplay d : new TextDisplay[]{tag.name, tag.health}) {
            if (!owner.getPassengers().contains(d)) owner.addPassenger(d); // e.g. after a teleport dismounted it
        }
        boolean hide = owner.isSneaking();
        Component name = hide ? Component.empty() : Component.text(owner.getName(), TeamStyle.color(m.team()));
        Component health = hide ? Component.empty() : healthBar(owner);
        if (!name.equals(tag.nameText)) {
            tag.name.text(name);
            tag.nameText = name;
        }
        if (!health.equals(tag.healthText)) {
            tag.health.text(health);
            tag.healthText = health;
        }
        if (hide != tag.hidden) {
            tag.name.setBackgroundColor(hide ? Color.fromARGB(0) : Color.fromARGB(0x40000000));
            tag.hidden = hide;
        }
        for (Player viewer : owner.getWorld().getPlayers()) {
            boolean self = viewer.equals(owner);
            show(viewer, tag.name, !self);
            show(viewer, tag.health, !self && damage.canSeeHealth(match, viewer.getUniqueId(), owner.getUniqueId()));
        }
    }

    private void show(Player viewer, TextDisplay d, boolean visible) {
        if (visible && !viewer.canSee(d)) viewer.showEntity(plugin, d);
        else if (!visible && viewer.canSee(d)) viewer.hideEntity(plugin, d);
    }

    /** One 13x7 segment per 2 hearts (borders overlap by 1 px) and the health number ("6.5"), from the pack font. */
    static Component healthBar(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        double max = attr == null ? 20 : attr.getValue();
        int halves = (int) Math.ceil(Math.max(0, Math.min(p.getHealth(), max))); // half hearts
        int segments = Math.max(1, (int) Math.ceil(max / 4.0));
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < segments; i++) {
            int fill = Math.max(0, Math.min(4, halves - 4 * i));
            b.append(Component.text(String.valueOf((char) (PackBuilder.HEALTH_SEGMENT + fill))).font(HEALTH_FONT));
            if (i < segments - 1) b.append(TextOffsets.space(-2)); // advance 14 -> 12: shared border
        }
        b.append(TextOffsets.space(1));
        b.append(Component.text(String.valueOf((char) (PackBuilder.HEALTH_NUMBER + Math.min(20, halves)))).font(HEALTH_FONT));
        return b.build().color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.none());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Tag t = tags.remove(e.getPlayer().getUniqueId());
        if (t != null) t.remove();
        damage.forget(e.getPlayer().getUniqueId());
    }
}
