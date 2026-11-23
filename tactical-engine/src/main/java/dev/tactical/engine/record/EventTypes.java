package dev.tactical.engine.record;

/**
 * Every recording event type (schema v1). The full field list per type is in docs/recording-schema.md.
 * Strings rather than an enum so add-ons can define their own types without changing this class.
 */
public final class EventTypes {
    private EventTypes() {
    }

    // match lifecycle
    public static final String MATCH_HEADER = "MATCH_HEADER";
    public static final String MATCH_STATE = "MATCH_STATE";
    public static final String MATCH_END = "MATCH_END";
    public static final String MATCH_FOOTER = "MATCH_FOOTER";
    public static final String ROUND_START = "ROUND_START";
    public static final String ROUND_LIVE = "ROUND_LIVE";
    public static final String ROUND_END = "ROUND_END";
    public static final String HALFTIME = "HALFTIME";
    public static final String SUDDEN_DEATH = "SUDDEN_DEATH";
    public static final String TIMER_CHANGE = "TIMER_CHANGE";
    // players
    public static final String PLAYER_JOIN = "PLAYER_JOIN";
    public static final String PLAYER_DISCONNECT = "PLAYER_DISCONNECT";
    public static final String PLAYER_RECONNECT = "PLAYER_RECONNECT";
    public static final String PLAYER_LEAVE = "PLAYER_LEAVE";
    public static final String KIT_SELECTION_OPEN = "KIT_SELECTION_OPEN";
    public static final String KIT_HOVER = "KIT_HOVER";
    public static final String KIT_LOCK = "KIT_LOCK";
    public static final String KIT_SELECTION_CLOSE = "KIT_SELECTION_CLOSE";
    public static final String SKIP_VOTE = "SKIP_VOTE";
    public static final String SNAPSHOT_POSITIONS = "SNAPSHOT_POSITIONS";
    public static final String SNAPSHOT_FULL = "SNAPSHOT_FULL";
    // combat
    public static final String ATTACK = "ATTACK";
    public static final String DAMAGE = "DAMAGE";
    public static final String PLAYER_KILL = "PLAYER_KILL";
    public static final String ELIMINATION = "ELIMINATION";
    public static final String ASSIST = "ASSIST";
    public static final String PROJECTILE_LAUNCH = "PROJECTILE_LAUNCH";
    public static final String PROJECTILE_PATH = "PROJECTILE_PATH";
    public static final String PROJECTILE_HIT = "PROJECTILE_HIT";
    public static final String UTILITY_USE = "UTILITY_USE";
    public static final String UTILITY_EFFECT = "UTILITY_EFFECT";
    // bomb
    public static final String BOMB_ASSIGN = "BOMB_ASSIGN";
    public static final String BOMB_DROP = "BOMB_DROP";
    public static final String BOMB_PICKUP = "BOMB_PICKUP";
    public static final String PLANT_START = "PLANT_START";
    public static final String PLANT_CANCEL = "PLANT_CANCEL";
    public static final String BOMB_PLANTED = "BOMB_PLANTED";
    public static final String BOMB_PLANT_LOCATION = "BOMB_PLANT_LOCATION";
    public static final String DEFUSE_START = "DEFUSE_START";
    public static final String DEFUSE_PROGRESS = "DEFUSE_PROGRESS";
    public static final String DEFUSE_CHECKPOINT = "DEFUSE_CHECKPOINT";
    public static final String DEFUSE_CANCEL = "DEFUSE_CANCEL";
    public static final String BOMB_DEFUSED = "BOMB_DEFUSED";
    public static final String BOMB_EXPLODED = "BOMB_EXPLODED";
    // world / economy / items
    public static final String BLOCK_CHANGE = "BLOCK_CHANGE";
    public static final String PURCHASE = "PURCHASE";
    public static final String PURCHASE_FAILED = "PURCHASE_FAILED";
    public static final String ECONOMY_CHANGE = "ECONOMY_CHANGE";
    public static final String ECONOMY_COMPENSATION = "ECONOMY_COMPENSATION";
    public static final String INVENTORY_CHANGE = "INVENTORY_CHANGE";
    public static final String ITEM_EXPIRED = "ITEM_EXPIRED";
    // system
    public static final String AFK_ELIMINATION = "AFK_ELIMINATION";
    public static final String PAUSE = "PAUSE";
    public static final String RESUME_COUNTDOWN = "RESUME_COUNTDOWN";
    public static final String RESUME = "RESUME";
    public static final String ADMIN_ACTION = "ADMIN_ACTION";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String ERROR = "ERROR";
    public static final String WARNING = "WARNING";
}
