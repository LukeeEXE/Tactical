package dev.tactical.paper.shop;

import java.util.List;

/**
 * One thing for sale.
 * <ul>
 *   <li>{@code ITEM}: gives {@code amount} of {@code material} (armor is equipped directly), or of the custom item
 *       {@code customItem} from items/*.yml.</li>
 *   <li>{@code ENCHANT}: adds {@code enchantment} at {@code level} to the first held item whose type is in
 *       {@code appliesTo}.</li>
 *   <li>{@code ABSORPTION}: {@code level} absorption hearts (2, 4 or 6) right away, for this round.</li>
 * </ul>
 */
public record ShopEntry(String id, String category, String name, Kind kind, int price, int maxCarry, boolean enabled,
                        String material, int amount, String enchantment, int level, List<String> appliesTo,
                        String customItem, String iconModel) {

    /** What identifies this entry's items in an inventory: {@code tactical:<id>} or the material key. */
    public String itemKey() {
        return customItem != null ? "tactical:" + customItem : material;
    }

    public enum Kind { ITEM, ENCHANT, ABSORPTION }
}
