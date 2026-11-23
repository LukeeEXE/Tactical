package dev.tactical.engine.mode;

/** How many connected members of a team must vote for that team to "agree" to skip a break. */
public enum SkipVoteRule {
    MAJORITY, ALL, ANY;

    public boolean satisfied(int votes, int connected) {
        if (connected <= 0) {
            return true; // an empty/disconnected team never blocks the other team
        }
        return switch (this) {
            case ANY -> votes >= 1;
            case ALL -> votes >= connected;
            case MAJORITY -> votes * 2 > connected;
        };
    }
}
