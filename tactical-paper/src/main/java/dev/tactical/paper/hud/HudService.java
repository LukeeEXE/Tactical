package dev.tactical.paper.hud;

import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns every player's action bar. Three independent parts, each with a net advance of 0 so none of them can push
 * another around (the client centres the action bar by its total width, which therefore stays 0):
 * <ul>
 *   <li>money widget on the hunger-bar row (right of centre);</li>
 *   <li>a two-line hint box (e.g. "Right-click to open the shop") centred above the hotbar;</li>
 *   <li>short status messages (plant/defuse progress, shop errors) which replace the hint's second line, or stand
 *       alone when there is no hint.</li>
 * </ul>
 * All parts are drawn without text shadow.
 */
public final class HudService implements Service {

    /** Two centred lines on a dark box. Plain strings are measured, so keep them ASCII. */
    public record Hint(String title, TextColor titleColor, String detail, TextColor detailColor) {
    }

    private record Status(Component text, String plain, long until) {
    }

    private static final int HINT_PADDING = 6;
    private static final int LINE_TWO_OFFSET = 10;

    private final Plugin plugin;
    private final ConfigService config;
    private final HudText text;
    private ToIntFunction<Player> money = p -> -1;
    private final List<Function<Player, Hint>> hints = new CopyOnWriteArrayList<>();
    private final Map<UUID, Status> status = new HashMap<>();
    private final Map<UUID, Component> lastSent = new HashMap<>();
    private final Map<UUID, Long> lastSentAt = new HashMap<>();
    private BukkitTask task;
    private long ticks;

    public HudService(Plugin plugin, ConfigService config, HudText text) {
        this.plugin = plugin;
        this.config = config;
        this.text = text;
    }

    /** -1 = no money to show (not in a match). */
    public void setMoneyProvider(ToIntFunction<Player> provider) {
        this.money = provider;
    }

    /** Registers a hint source; the first provider returning a hint for a player wins. */
    public void addHintProvider(Function<Player, Hint> provider) {
        hints.add(provider);
    }

    public HudText text() {
        return text;
    }

    @Override
    public void enable() {
        ActionBars.install(this::setStatus);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        ActionBars.install((p, t, ticks) -> p.sendActionBar(t));
    }

    private void setStatus(Player p, Component c, int durationTicks) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        status.put(p.getUniqueId(), new Status(c, plain, ticks + durationTicks));
        render(p, true);
    }

    private void tick() {
        ticks += 2;
        for (Player p : Bukkit.getOnlinePlayers()) render(p, false);
        status.entrySet().removeIf(e -> e.getValue().until() < ticks || Bukkit.getPlayer(e.getKey()) == null);
        lastSent.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        lastSentAt.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void render(Player p, boolean force) {
        int balance = config.hud().moneyEnabled() ? money.applyAsInt(p) : -1;
        Status st = status.get(p.getUniqueId());
        if (st != null && st.until() < ticks) st = null;
        Hint hint = null;
        for (Function<Player, Hint> h : hints) {
            hint = h.apply(p);
            if (hint != null) break;
        }
        if (balance < 0 && st == null && hint == null) {
            if (lastSent.remove(p.getUniqueId()) != null) p.sendActionBar(Component.empty());
            return;
        }
        Component out = Component.empty();
        if (balance >= 0) out = out.append(moneyWidget(balance));
        if (hint != null) {
            out = out.append(hintBox(hint, st));
        } else if (st != null) {
            out = out.append(HudShapes.centred(st.text().shadowColor(ShadowColor.none()), HudText.vanillaWidth(st.plain())));
        }
        Component previous = lastSent.get(p.getUniqueId());
        long at = lastSentAt.getOrDefault(p.getUniqueId(), 0L);
        // Resend unchanged content every 2 s: the client fades the action bar out after ~3 s.
        if (!force && out.equals(previous) && ticks - at < 40) return;
        p.sendActionBar(out);
        lastSent.put(p.getUniqueId(), out);
        lastSentAt.put(p.getUniqueId(), ticks);
    }

    /** Two centred lines on a dark box; a status message, if any, replaces the second line. */
    private Component hintBox(Hint hint, Status st) {
        Component line1 = Component.text(hint.title(), hint.titleColor());
        int w1 = HudText.vanillaWidth(hint.title());
        Component line2;
        int w2;
        if (st != null) {
            line2 = st.text();
            w2 = HudText.vanillaWidth(st.plain());
        } else {
            line2 = Component.text(hint.detail(), hint.detailColor());
            w2 = HudText.vanillaWidth(hint.detail());
        }
        line2 = line2.font(Key.key("tactical", "offset_down_" + LINE_TWO_OFFSET));
        int boxWidth = Math.max(config.hud().actionHintWidth(), Math.max(w1, w2) + 2 * HINT_PADDING); // fixed: never resizes with its text
        return Component.text()
                .append(HudShapes.centred(HudShapes.rect(HudGlyphs.ACTION_PANEL, boxWidth, "hud_actionbar", NamedTextColor.BLACK), boxWidth))
                .append(HudShapes.centred(line1, w1))
                .append(HudShapes.centred(line2, w2))
                .build()
                .shadowColor(ShadowColor.none());
    }

    /** Zero-advance money widget: panel + coin + right-aligned amount on the hunger-bar row. */
    public Component moneyWidget(int balance) {
        HudConfig h = config.hud();
        String amount = String.format("%,d", balance);
        int amountWidth = HudText.vanillaWidth(amount);
        int panelAdvance = HudGlyphs.MONEY_PANEL_WIDTH + 1;
        int coinX = 2;
        int coinAdvance = 9;
        int rightEdge = HudGlyphs.MONEY_PANEL_WIDTH - 3;
        int gap = Math.max(1, rightEdge - amountWidth - (coinX + coinAdvance));
        Key hudFont = Key.key("tactical", "hud_money");
        Component panel = Component.text(String.valueOf(HudGlyphs.MONEY_PANEL)).font(hudFont).color(NamedTextColor.WHITE);
        Component coin = Component.text(String.valueOf(HudGlyphs.MONEY_COIN)).font(hudFont).color(NamedTextColor.WHITE);
        Component number = Component.text(amount, NamedTextColor.WHITE).font(Key.key("tactical", "offset_down_" + h.moneyOffsetY()));
        int total = h.moneyLeft() + coinX + coinAdvance + gap + amountWidth;
        return Component.text()
                .append(TextOffsets.space(h.moneyLeft()))
                .append(panel).append(TextOffsets.space(-panelAdvance))
                .append(TextOffsets.space(coinX)).append(coin)
                .append(TextOffsets.space(gap)).append(number)
                .append(TextOffsets.space(-total))
                .build()
                .shadowColor(ShadowColor.none());
    }
}
