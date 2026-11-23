-- Tactical schema v1 (Phase 1). Later phases add tables in V2__, V3__ ... never edit an applied migration.
CREATE TABLE players (
    uuid        TEXT PRIMARY KEY,
    last_name   TEXT    NOT NULL,
    first_seen  INTEGER NOT NULL,
    last_seen   INTEGER NOT NULL,
    xp          INTEGER NOT NULL DEFAULT 0,
    level       INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_players_name ON players (last_name COLLATE NOCASE);

CREATE TABLE player_names (
    uuid     TEXT    NOT NULL,
    name     TEXT    NOT NULL,
    seen_at  INTEGER NOT NULL,
    PRIMARY KEY (uuid, name)
);
CREATE INDEX idx_player_names_name ON player_names (name COLLATE NOCASE);

CREATE TABLE match_seq (
    day  TEXT PRIMARY KEY,
    seq  INTEGER NOT NULL
);

CREATE TABLE matches (
    match_id     TEXT PRIMARY KEY,
    mode         TEXT    NOT NULL,
    map_id       TEXT,
    map_version  INTEGER,
    started_at   INTEGER NOT NULL,
    ended_at     INTEGER,
    score_a      INTEGER NOT NULL DEFAULT 0,
    score_b      INTEGER NOT NULL DEFAULT 0,
    winner       TEXT,
    end_reason   TEXT,
    is_test      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE match_players (
    match_id          TEXT    NOT NULL REFERENCES matches (match_id),
    uuid              TEXT    NOT NULL,
    name              TEXT    NOT NULL,
    original_team     TEXT    NOT NULL,
    virtual           INTEGER NOT NULL DEFAULT 0,
    kit_first_half    TEXT,
    kit_second_half   TEXT,
    kit_sudden_death  TEXT,
    rewards_granted   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (match_id, uuid)
);
CREATE INDEX idx_match_players_uuid ON match_players (uuid);

CREATE TABLE roles (
    uuid        TEXT PRIMARY KEY,
    role        TEXT    NOT NULL,
    granted_by  TEXT,
    granted_at  INTEGER NOT NULL
);

CREATE TABLE audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_uuid  TEXT,
    actor_name  TEXT    NOT NULL,
    action      TEXT    NOT NULL,
    target      TEXT,
    details     TEXT,
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_audit_created ON audit_log (created_at);
