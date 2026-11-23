package dev.tactical.engine.team;

public enum Side {
    ATTACKERS, DEFENDERS;

    public Side opposite() {
        return this == ATTACKERS ? DEFENDERS : ATTACKERS;
    }
}
