package dev.tactical.engine.match;

import dev.tactical.engine.id.MatchId;
import dev.tactical.engine.kit.KitSelection;
import dev.tactical.engine.kit.KitSelectionPhase;
import dev.tactical.engine.kit.KitSelectionResult;
import dev.tactical.engine.mode.ModeRules;
import dev.tactical.engine.round.BombStatus;
import dev.tactical.engine.round.RoundEndReason;
import dev.tactical.engine.round.RoundOutcome;
import dev.tactical.engine.round.RoundOutcomeResolver;
import dev.tactical.engine.round.RoundSnapshot;
import dev.tactical.engine.state.MatchState;
import dev.tactical.engine.state.MatchStateMachine;
import dev.tactical.engine.team.Side;
import dev.tactical.engine.team.TeamId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * The authoritative, platform-independent flow of one match: states, timers, rounds, scores, sides,
 * kit selections and pause. It has no knowledge of Bukkit; the Paper layer feeds it inputs
 * (eliminations, bomb events, disconnects, GUI choices) and reacts to the {@link MatchFlowEvent}s it emits.
 *
 * <p>Tick order (see architecture §3.1): controllers → timers → round resolution. Not thread-safe:
 * all calls must come from the server main thread.
 */
public final class MatchFlow {

    /** After everyone has locked (and skip agreed, where required), the remaining time is cut to this. */
    public static final int LOCKED_GRACE_TICKS = 3 * ModeRules.TICKS_PER_SECOND;

    private final MatchId id;
    private final ModeRules rules;
    private final Map<UUID, Participant> participants;
    private final List<String> kitPool;
    private final RandomGenerator random;
    private final Consumer<MatchFlowEvent> sink;
    private final List<MatchTickable> tickables = new ArrayList<>();

    private final MatchStateMachine machine = new MatchStateMachine();
    private final EnumMap<TeamId, Side> sides = new EnumMap<>(TeamId.class);
    private final EnumMap<TeamId, Integer> score = new EnumMap<>(TeamId.class);
    private final List<TeamId> roundWinners = new ArrayList<>();

    private long tick;
    private long pausedTicks;
    private int stateTicksRemaining;
    private int stateDurationTicks;
    private int round;
    private boolean suddenDeathRound;
    private BombStatus bomb = BombStatus.NOT_PLANTED;
    private String bombSite;
    private boolean roundTimerExpired;

    private KitSelection kitSelection;
    private final Set<UUID> skipVotes = new HashSet<>();

    // pause overlay
    private boolean paused;
    private int resumeCountdown = -1;
    private UUID resumeBy;
    private long pauseStartedTick;

    private TeamId winner;
    private MatchEndReason endReason;

    public MatchFlow(MatchId id, ModeRules rules, Collection<Participant> participants, List<String> kitPool,
                     RandomGenerator random, Consumer<MatchFlowEvent> sink) {
        this.id = Objects.requireNonNull(id);
        this.rules = Objects.requireNonNull(rules);
        this.random = Objects.requireNonNull(random);
        this.sink = Objects.requireNonNull(sink);
        if (kitPool.isEmpty()) {
            throw new IllegalArgumentException("A match needs at least one kit");
        }
        this.kitPool = List.copyOf(kitPool);
        Map<UUID, Participant> map = new LinkedHashMap<>();
        for (Participant p : participants) {
            if (map.put(p.id(), p) != null) {
                throw new IllegalArgumentException("Duplicate participant " + p.id());
            }
        }
        for (TeamId t : TeamId.values()) {
            long n = map.values().stream().filter(p -> p.team() == t).count();
            if (n > rules.teamSize()) {
                throw new IllegalArgumentException("Team " + t + " has " + n + " players, max " + rules.teamSize());
            }
        }
        this.participants = Collections.unmodifiableMap(map);
        for (TeamId t : TeamId.values()) {
            sides.put(t, t.startingSide());
            score.put(t, 0);
        }
    }

    // ------------------------------------------------------------------ lifecycle inputs

    public void addTickable(MatchTickable tickable) {
        tickables.add(Objects.requireNonNull(tickable));
    }

    /** CREATED → LOADING. Called once the host starts preparing the map instance. */
    public void beginLoading() {
        transition(MatchState.LOADING, 0);
    }

    /** LOADING → KIT_SELECT. Called once the map instance is ready and players are in it. */
    public void loaded() {
        openKitSelection(MatchState.KIT_SELECT, KitSelectionPhase.MATCH_START, rules.kitSelectTicks(),
                KitSelection.open(KitSelectionPhase.MATCH_START, teamMap(), kitPool));
    }

    /** Aborts from any non-terminal state. Idempotent. */
    public void abort(MatchEndReason reason, String detail) {
        MatchState s = state();
        if (s == MatchState.ABORTED || s == MatchState.CLEANUP || s == MatchState.CLOSED) {
            return;
        }
        if (s == MatchState.POSTGAME || s == MatchState.MATCH_END) {
            // Already finished normally; skip straight to cleanup rather than rewriting the result.
            transition(MatchState.CLEANUP, 0);
            return;
        }
        winner = null;
        endReason = reason;
        paused = false;
        resumeCountdown = -1;
        transition(MatchState.ABORTED, 0);
        emit(new MatchFlowEvent.MatchEnded(tick, null, reason, scoreOf(TeamId.A), scoreOf(TeamId.B), detail));
    }

    /** CLEANUP → CLOSED. Called by the host once all world/entity/task resources have been released. */
    public void markClosed() {
        transition(MatchState.CLOSED, 0);
    }

    // ------------------------------------------------------------------ the tick

    public void tick() {
        MatchState s = state();
        if (s == MatchState.CLOSED || s == MatchState.CREATED || s == MatchState.LOADING || s == MatchState.CLEANUP) {
            return;
        }
        if (s == MatchState.ABORTED) {
            transition(MatchState.CLEANUP, 0);
            return;
        }
        if (paused) {
            pausedTicks++;
            if (resumeCountdown > 0 && --resumeCountdown == 0) {
                finishResume();
            }
            return;
        }
        tick++;

        for (MatchTickable t : List.copyOf(tickables)) {
            t.tick(this);
            if (state() != s) {
                return; // a controller ended/aborted the round or match this tick
            }
        }

        if (s.isRoundActive()) {
            tickRound();
            return;
        }
        if (stateTicksRemaining > 0) {
            stateTicksRemaining--;
        }
        if (stateTicksRemaining == 0) {
            onStateTimerExpired(s);
        }
    }

    private void tickRound() {
        if (stateTicksRemaining > 0) {
            stateTicksRemaining--;
        }
        if (stateTicksRemaining == 0) {
            if (state() == MatchState.BOMB_PLANTED && bomb == BombStatus.PLANTED) {
                bomb = BombStatus.DETONATED;
                emit(new MatchFlowEvent.BombDetonated(tick, round));
            } else if (state() == MatchState.ROUND_LIVE) {
                roundTimerExpired = true;
            }
        }
        RoundSnapshot snapshot = new RoundSnapshot(aliveCount(Side.ATTACKERS), aliveCount(Side.DEFENDERS), bomb,
                roundTimerExpired);
        RoundOutcomeResolver.resolve(snapshot).ifPresent(this::endRound);
    }

    private void onStateTimerExpired(MatchState s) {
        switch (s) {
            case KIT_SELECT, HALFTIME, SUDDEN_DEATH_KIT_SELECT -> closeKitSelectionAndStartRound();
            case BUY_PHASE -> {
                transition(MatchState.ROUND_LIVE, rules.roundTicks());
                emit(new MatchFlowEvent.RoundLive(tick, round));
            }
            case ROUND_END -> afterRoundEnd();
            case MATCH_END -> transition(MatchState.POSTGAME, rules.postgameTicks());
            case POSTGAME -> transition(MatchState.CLEANUP, 0);
            default -> { /* no timer-driven exit */ }
        }
    }

    // ------------------------------------------------------------------ rounds

    private void startRound() {
        round++;
        bomb = BombStatus.NOT_PLANTED;
        bombSite = null;
        roundTimerExpired = false;
        for (Participant p : participants.values()) {
            p.setAlive(p.connected());
        }
        transition(MatchState.BUY_PHASE, rules.buyPhaseTicks());
        emit(new MatchFlowEvent.RoundStarted(tick, round, suddenDeathRound, Map.copyOf(sides)));
    }

    private void endRound(RoundOutcome outcome) {
        TeamId winnerTeam = teamOn(outcome.winner());
        score.merge(winnerTeam, 1, Integer::sum);
        roundWinners.add(winnerTeam);
        transition(MatchState.ROUND_END, rules.roundEndTicks());
        emit(new MatchFlowEvent.RoundEnded(tick, round, outcome.winner(), winnerTeam, outcome.reason(),
                scoreOf(TeamId.A), scoreOf(TeamId.B)));
    }

    private void afterRoundEnd() {
        int a = scoreOf(TeamId.A);
        int b = scoreOf(TeamId.B);
        if (suddenDeathRound || a >= rules.roundsToWin() || b >= rules.roundsToWin()) {
            finishMatch(a > b ? TeamId.A : TeamId.B);
            return;
        }
        if (a == rules.suddenDeathAt() && b == rules.suddenDeathAt()) {
            startSuddenDeathSelection();
            return;
        }
        if (round == rules.halftimeAfterRound()) {
            startHalftime();
            return;
        }
        startRound();
    }

    /**
     * {@code team} gives up (its players voted to forfeit): the other team wins now, whatever the phase. Returns false
     * once the match is already over or has not started.
     */
    public boolean forfeit(TeamId team) {
        MatchState s = state();
        boolean playing = switch (s) {
            case KIT_SELECT, BUY_PHASE, ROUND_LIVE, BOMB_PLANTED, ROUND_END, HALFTIME, SUDDEN_DEATH_KIT_SELECT -> true;
            default -> false;
        };
        if (!playing) return false;
        winner = team.other();
        endReason = MatchEndReason.FORFEIT;
        paused = false;
        resumeCountdown = -1;
        if (kitSelection != null) {
            KitSelection sel = kitSelection;
            kitSelection = null;
            emit(new MatchFlowEvent.KitSelectionClosed(tick, sel.phase(), Map.copyOf(sel.locks()))); // menus close
        }
        transition(MatchState.MATCH_END, 1);
        emit(new MatchFlowEvent.MatchEnded(tick, winner, endReason, scoreOf(TeamId.A), scoreOf(TeamId.B), "forfeit by " + team));
        return true;
    }

    private void finishMatch(TeamId winningTeam) {
        winner = winningTeam;
        endReason = MatchEndReason.COMPLETED;
        transition(MatchState.MATCH_END, 1);
        emit(new MatchFlowEvent.MatchEnded(tick, winner, endReason, scoreOf(TeamId.A), scoreOf(TeamId.B), ""));
    }

    private void startHalftime() {
        for (TeamId t : TeamId.values()) {
            sides.put(t, sides.get(t).opposite());
        }
        emit(new MatchFlowEvent.HalftimeStarted(tick, round, Map.copyOf(sides)));
        openKitSelection(MatchState.HALFTIME, KitSelectionPhase.SECOND_HALF, rules.halftimeTicks(),
                KitSelection.open(KitSelectionPhase.SECOND_HALF, teamMap(), kitPool));
    }

    private void startSuddenDeathSelection() {
        boolean swap = switch (rules.suddenDeathSides()) {
            case KEEP -> false;
            case SWAP -> true;
            case RANDOM -> random.nextBoolean();
        };
        if (swap) {
            for (TeamId t : TeamId.values()) {
                sides.put(t, sides.get(t).opposite());
            }
        }
        suddenDeathRound = true;
        emit(new MatchFlowEvent.SuddenDeathStarted(tick, Map.copyOf(sides)));
        Map<UUID, String> first = new LinkedHashMap<>();
        Map<UUID, String> second = new LinkedHashMap<>();
        for (Participant p : participants.values()) {
            first.put(p.id(), p.kitFirstHalf() != null ? p.kitFirstHalf() : kitPool.getFirst());
            second.put(p.id(), p.kitSecondHalf() != null ? p.kitSecondHalf() : first.get(p.id()));
        }
        openKitSelection(MatchState.SUDDEN_DEATH_KIT_SELECT, KitSelectionPhase.SUDDEN_DEATH,
                rules.suddenDeathKitSelectTicks(), KitSelection.suddenDeath(teamMap(), first, second));
    }

    // ------------------------------------------------------------------ kit selection

    private void openKitSelection(MatchState state, KitSelectionPhase phase, int duration, KitSelection selection) {
        kitSelection = selection;
        skipVotes.clear();
        transition(state, duration);
        emit(new MatchFlowEvent.KitSelectionOpened(tick, phase, duration));
        for (Map.Entry<UUID, String> e : selection.locks().entrySet()) {
            emit(new MatchFlowEvent.KitLocked(tick, e.getKey(), participants.get(e.getKey()).team(), e.getValue(), true));
        }
        checkSelectionComplete();
    }

    private void closeKitSelectionAndStartRound() {
        KitSelection sel = kitSelection;
        Map<UUID, String> auto = sel.autoFill();
        for (Map.Entry<UUID, String> e : auto.entrySet()) {
            emit(new MatchFlowEvent.KitLocked(tick, e.getKey(), participants.get(e.getKey()).team(), e.getValue(), true));
        }
        sel.close();
        for (Map.Entry<UUID, String> e : sel.locks().entrySet()) {
            Participant p = participants.get(e.getKey());
            switch (sel.phase()) {
                case MATCH_START -> p.setKitFirstHalf(e.getValue());
                case SECOND_HALF -> p.setKitSecondHalf(e.getValue());
                case SUDDEN_DEATH -> p.setKitSuddenDeath(e.getValue());
            }
        }
        emit(new MatchFlowEvent.KitSelectionClosed(tick, sel.phase(), Map.copyOf(sel.locks())));
        startRound();
    }

    public KitSelectionResult hoverKit(UUID player, String kit) {
        if (kitSelection == null || !state().isKitSelection() || paused) return KitSelectionResult.SELECTION_CLOSED;
        KitSelectionResult r = kitSelection.select(player, kit);
        if (r == KitSelectionResult.OK) {
            emit(new MatchFlowEvent.KitHovered(tick, player, participants.get(player).team(), kit));
        }
        return r;
    }

    public KitSelectionResult lockKit(UUID player, String kit) {
        if (kitSelection == null || !state().isKitSelection() || paused) return KitSelectionResult.SELECTION_CLOSED;
        KitSelectionResult r = kitSelection.lock(player, kit);
        if (r == KitSelectionResult.OK) {
            emit(new MatchFlowEvent.KitLocked(tick, player, participants.get(player).team(), kit, false));
            checkSelectionComplete();
        }
        return r;
    }

    /** Vote to skip the rest of halftime / sudden-death kit selection. Returns false if not allowed now. */
    public boolean voteSkip(UUID player) {
        MatchState s = state();
        Participant p = participants.get(player);
        if (p == null || paused || (s != MatchState.HALFTIME && s != MatchState.SUDDEN_DEATH_KIT_SELECT)) {
            return false;
        }
        if (!skipVotes.add(player)) {
            return false;
        }
        emit(new MatchFlowEvent.SkipVoted(tick, player, p.team(), teamAgreesToSkip(p.team())));
        checkSelectionComplete();
        return true;
    }

    public boolean hasVotedSkip(UUID player) {
        return skipVotes.contains(player);
    }

    /** Whether the current phase offers a skip vote (every kit selection: match start, halftime, sudden death). */
    public boolean skipVoteAvailable() {
        return state() == MatchState.KIT_SELECT || state() == MatchState.HALFTIME || state() == MatchState.SUDDEN_DEATH_KIT_SELECT;
    }

    public boolean teamAgreesToSkip(TeamId team) {
        int votes = 0;
        int connected = 0;
        for (Participant p : participants.values()) {
            if (p.team() != team || !p.connected()) continue;
            connected++;
            if (skipVotes.contains(p.id())) votes++;
        }
        return rules.skipVoteRule().satisfied(votes, connected);
    }

    private void checkSelectionComplete() {
        if (kitSelection == null || !kitSelection.allLocked()) return;
        boolean needsSkip = skipVoteAvailable(); // all locked AND both teams agree to skip
        if (needsSkip && !(teamAgreesToSkip(TeamId.A) && teamAgreesToSkip(TeamId.B))) return;
        if (stateTicksRemaining > LOCKED_GRACE_TICKS) {
            setStateTimer(LOCKED_GRACE_TICKS, "all-locked");
        }
    }

    // ------------------------------------------------------------------ gameplay inputs

    /** Eliminates an alive participant during a live round. Returns false if the call had no effect. */
    public boolean eliminate(UUID victim, UUID killer, EliminationCause cause) {
        Participant p = participants.get(victim);
        if (p == null || !p.alive() || !state().isRoundActive() || paused) {
            return false;
        }
        p.setAlive(false);
        emit(new MatchFlowEvent.PlayerEliminated(tick, round, victim, killer, cause));
        return true;
    }

    /** Reports a completed plant. Validated: live round, not paused, planter is an alive attacker. */
    public boolean bombPlanted(UUID planter, String site) {
        Participant p = participants.get(planter);
        if (state() != MatchState.ROUND_LIVE || paused || bomb != BombStatus.NOT_PLANTED
                || p == null || !p.active() || sideOf(p.team()) != Side.ATTACKERS) {
            return false;
        }
        bomb = BombStatus.PLANTED;
        bombSite = site;
        transition(MatchState.BOMB_PLANTED, rules.bombTicks());
        emit(new MatchFlowEvent.BombPlanted(tick, round, planter, site));
        return true;
    }

    /** Reports a completed defuse. Validated: bomb planted, not paused, defuser is an alive defender. */
    public boolean bombDefused(UUID defuser) {
        Participant p = participants.get(defuser);
        if (state() != MatchState.BOMB_PLANTED || paused || bomb != BombStatus.PLANTED
                || p == null || !p.active() || sideOf(p.team()) != Side.DEFENDERS) {
            return false;
        }
        bomb = BombStatus.DEFUSED;
        emit(new MatchFlowEvent.BombDefused(tick, round, defuser));
        return true;
    }

    /** Administrative round end with a chosen winner. */
    public boolean forceEndRound(Side winnerSide) {
        if (!state().isRoundActive() && state() != MatchState.BUY_PHASE) {
            return false;
        }
        endRound(new RoundOutcome(winnerSide, RoundEndReason.FORCED));
        return true;
    }

    public void playerDisconnected(UUID player) {
        Participant p = participants.get(player);
        if (p == null || !p.connected()) return;
        boolean wasAlive = p.alive();
        p.setConnected(false);
        emit(new MatchFlowEvent.PlayerDisconnected(tick, player, wasAlive));
        if (wasAlive && state().isRoundActive()) {
            p.setAlive(false);
            emit(new MatchFlowEvent.PlayerEliminated(tick, round, player, null, EliminationCause.DISCONNECT));
        } else if (wasAlive && state() == MatchState.BUY_PHASE) {
            p.setAlive(false);
        }
        checkSelectionComplete();
    }

    /** Reconnected players stay dead until the next round starts. */
    public void playerReconnected(UUID player) {
        Participant p = participants.get(player);
        if (p == null || p.connected() || isFinished()) return;
        p.setConnected(true);
        emit(new MatchFlowEvent.PlayerReconnected(tick, player));
    }

    // ------------------------------------------------------------------ pause

    /** Pauses the match. Returns false if already paused or the state cannot be paused. */
    public boolean pause(UUID by, String reason) {
        MatchState s = state();
        if (paused || isFinished() || s == MatchState.CREATED || s == MatchState.LOADING) {
            return false;
        }
        paused = true;
        resumeCountdown = -1;
        pauseStartedTick = pausedTicks;
        emit(new MatchFlowEvent.Paused(tick, by, reason == null ? "" : reason));
        return true;
    }

    /**
     * Resumes after {@code countdownTicks} (0 = immediately). The match stays frozen during the countdown.
     * Returns false if not paused or a resume is already counting down.
     */
    public boolean resume(UUID by, int countdownTicks) {
        if (!paused || resumeCountdown > 0) {
            return false;
        }
        resumeBy = by;
        if (countdownTicks <= 0) {
            finishResume();
        } else {
            resumeCountdown = countdownTicks;
            emit(new MatchFlowEvent.ResumeCountdown(tick, by, countdownTicks));
        }
        return true;
    }

    private void finishResume() {
        paused = false;
        resumeCountdown = -1;
        emit(new MatchFlowEvent.Resumed(tick, resumeBy, pausedTicks - pauseStartedTick));
        resumeBy = null;
    }

    // ------------------------------------------------------------------ helpers

    private void transition(MatchState to, int duration) {
        MatchState from = machine.transition(to);
        stateTicksRemaining = duration;
        stateDurationTicks = duration;
        emit(new MatchFlowEvent.StateChanged(tick, from, to, duration));
    }

    private void setStateTimer(int ticks, String reason) {
        int old = stateTicksRemaining;
        stateTicksRemaining = ticks;
        emit(new MatchFlowEvent.TimerChanged(tick, state(), old, ticks, reason));
    }

    /** Administrative timer override (e.g. extend halftime). */
    public boolean setTimer(int ticks, String reason) {
        if (ticks < 1 || isFinished() || state() == MatchState.CREATED || state() == MatchState.LOADING) {
            return false;
        }
        setStateTimer(ticks, reason);
        return true;
    }

    private void emit(MatchFlowEvent event) {
        sink.accept(event);
    }

    private Map<UUID, TeamId> teamMap() {
        Map<UUID, TeamId> m = new LinkedHashMap<>();
        participants.values().forEach(p -> m.put(p.id(), p.team()));
        return m;
    }

    private int aliveCount(Side side) {
        int n = 0;
        for (Participant p : participants.values()) {
            if (p.active() && sideOf(p.team()) == side) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ queries

    public MatchId id() {
        return id;
    }

    public ModeRules rules() {
        return rules;
    }

    public MatchState state() {
        return machine.current();
    }

    public boolean isPaused() {
        return paused;
    }

    public int resumeCountdown() {
        return Math.max(resumeCountdown, 0);
    }

    public boolean isFinished() {
        MatchState s = state();
        return s == MatchState.MATCH_END || s == MatchState.POSTGAME || s == MatchState.CLEANUP
                || s == MatchState.CLOSED || s == MatchState.ABORTED;
    }

    /** Un-paused ticks elapsed since the match started (the match clock). */
    public long currentTick() {
        return tick;
    }

    public long pausedTicks() {
        return pausedTicks;
    }

    public int round() {
        return round;
    }

    public boolean isSuddenDeathRound() {
        return suddenDeathRound;
    }

    public int stateTicksRemaining() {
        return stateTicksRemaining;
    }

    public int stateDurationTicks() {
        return stateDurationTicks;
    }

    public BombStatus bomb() {
        return bomb;
    }

    public Optional<String> bombSite() {
        return Optional.ofNullable(bombSite);
    }

    public int scoreOf(TeamId team) {
        return score.get(team);
    }

    public Side sideOf(TeamId team) {
        return sides.get(team);
    }

    public TeamId teamOn(Side side) {
        return sides.get(TeamId.A) == side ? TeamId.A : TeamId.B;
    }

    public Map<TeamId, Side> sides() {
        return Map.copyOf(sides);
    }

    public List<TeamId> roundWinners() {
        return List.copyOf(roundWinners);
    }

    public Collection<Participant> participants() {
        return participants.values();
    }

    public Optional<Participant> participant(UUID id) {
        return Optional.ofNullable(participants.get(id));
    }

    public Optional<KitSelection> kitSelection() {
        return state().isKitSelection() ? Optional.ofNullable(kitSelection) : Optional.empty();
    }

    public List<String> kitPool() {
        return kitPool;
    }

    public Optional<TeamId> winner() {
        return Optional.ofNullable(winner);
    }

    public Optional<MatchEndReason> endReason() {
        return Optional.ofNullable(endReason);
    }
}
