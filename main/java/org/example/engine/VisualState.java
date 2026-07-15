package org.example.engine;

/**
 * Engine layer: the vocabulary of visual states a piece can be in, as far as
 * the game's real-time state machine is concerned - idle, traveling along a
 * normal move, airborne on a jump, or resting (split into the two durations
 * EnginePort already distinguishes: a short rest after a jump, a longer one
 * after a move).
 *
 * This enum is owned by the engine layer on purpose, mirroring the reasoning
 * in ArchitectureDoc for why rules.ActiveMoveQuery is owned by rules rather
 * than engine: the ENGINE is what actually knows which of these states a
 * piece is in (see DefaultGameEngine's snapshot-building code) - the view
 * layer only needs to be handed the answer, never derive it. PieceSprites
 * (view layer) has its own, separately-named State enum for asset-folder
 * lookup purposes; the two are intentionally kept distinct so the view
 * layer's asset organization is free to change without the engine layer
 * needing to know or care.
 */
public enum VisualState {
    IDLE,
    MOVE,
    JUMP,
    SHORT_REST,
    LONG_REST
}
