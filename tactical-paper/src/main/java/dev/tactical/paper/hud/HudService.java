package dev.tactical.paper.hud;

import dev.tactical.engine.pack.HudGlyphs;
import dev.tactical.paper.config.ConfigService;
import dev.tactical.paper.core.Service;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Owns every player's action bar. Draws the money widget where the (hidden) hunger bar sits, using the
 * {@code tactical:hud_money} glyphs and the vanilla font shifted down with {@code tactical:offset_down_<y>}, and a
 * centred status line (plant/defuse progress, shop messages) from {@link ActionBars#status}.
 *
 * <p>Layout trick: the money widget has a total advance of 0 (it moves right, draws, then moves back), so the
 * client's centring of the action bar is unaffected and the status text stays centred.
 */
public final class HudService implements Service {

    private record Status(Component text, long until) {
    }

    private final Plugin plugin;
    private final ConfigService config;
    private final HudText text;
    private ToIntFunction<Player> money = p -> -1;
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
        status.put(p.getUniqueId(), new Status(c, ticks + durationTicks));
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
        Component statusText = st != null && st.until() >= ticks ? st.text() : null;
        if (balance < 0 && statusText == null) {
            if (lastSent.remove(p.getUniqueId()) != null) p.sendActionBar(Component.empty());
            return;
        }
        Component out = Component.empty();
        if (balance >= 0) out = out.append(moneyWidget(balance));
        if (statusText != null) out = out.append(statusText);
        Component previous = lastSent.get(p.getUniqueId());
        long at = lastSentAt.getOrDefault(p.getUniqueId(), 0L);
        // Resend unchanged content every 2 s: the client fades the action bar out after ~3 s.
        if (!force && out.equals(previous) && ticks - at < 40) return;
        p.sendActionBar(out);
        lastSent.put(p.getUniqueId(), out);
        lastSentAt.put(p.getUniqueId(), ticks);
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
        int afterCoin = coinX + coinAdvance;
        int gap = Math.max(1, rightEdge - amountWidth - afterCoin);
        Key hudFont = Key.key("tactical", "hud_money");
        Component glyphs = Component.text(String.valueOf(HudGlyphs.MONEY_PANEL)).font(hudFont);
        Component coin = Component.text(String.valueOf(HudGlyphs.MONEY_COIN)).font(hudFont);
        Component number = Component.text(amount, NamedTextColor.WHITE).font(Key.key("tactical", "offset_down_" + h.moneyOffsetY()));
        int total = h.moneyLeft() + panelAdvance - panelAdvance + coinX + coinAdvance + gap + amountWidth;
        return Component.text()
                .append(TextOffsets.space(h.moneyLeft()))
                .append(glyphs).append(TextOffsets.space(-panelAdvance))
                .append(TextOffsets.space(coinX)).append(coin)
                .append(TextOffsets.space(gap)).append(number)
                .append(TextOffsets.space(-total))
                .build();
    }
}
