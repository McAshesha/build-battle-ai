package ru.ashesha.buildBattleAI.game;

/**
 * Represents the lifecycle state of a game session within an arena.
 * <p>
 * State transitions:
 * <pre>
 * WAITING → COUNTDOWN     when minPlayers reached
 * COUNTDOWN → WAITING     when players drop below minPlayers
 * COUNTDOWN → PLAYING     when countdown reaches 0
 * PLAYING → ENDING        when game time expires or all players leave
 * ENDING → WAITING        after results display (10 seconds)
 * </pre>
 */
public enum ArenaState {

    /** Lobby accepting joins, no timers active. */
    WAITING,

    /** Minimum players reached, countdown ticking. */
    COUNTDOWN,

    /** Active game in progress — players building, ML judging. */
    PLAYING,

    /** Results display at spectator location before reset. */
    ENDING
}
