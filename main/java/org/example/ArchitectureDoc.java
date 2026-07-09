package org.example;

/**
 * CLEAN ARCHITECTURE - PACKAGE-BY-LAYER REFACTORING
 *
 * This document describes the four-layer Clean Architecture applied to
 * Kung Fu Chess, plus the adapters/composition-root layer that sits outside
 * it.
 *
 * ============================================================================
 * THE FOUR LAYERS
 * ============================================================================
 *
 * 1. MODEL (org.example.model)
 *    - Pure domain entities: Board, Piece, Position.
 *    - ZERO dependencies on rules, engine, controller, or adapters.
 *    - No System.out, no Scanner, no knowledge of timing or ports.
 *    - Board no longer implements a port interface and no longer has a
 *      print() method - rendering is strictly an adapters-layer concern.
 *
 * 2. RULES (org.example.rules)
 *    - Logic/validation services: MoveValidationService, PawnPromotionService,
 *      AirCaptureService.
 *    - Depends ONLY on model, plus two ports it owns itself:
 *        - ActiveMoveQuery: the one engine capability rules needs (whether a
 *          square is claimed by an in-flight move). Implemented by
 *          MovementEngine. This is Dependency Inversion: rules (inner layer)
 *          defines the abstraction, engine (outer layer) implements it, so
 *          the compile-time dependency still points inward.
 *        - MoveValidationPort: what the controller layer is allowed to see
 *          of MoveValidationService.
 *    - PawnPromotionService.handlePawnPromotion(Piece, Position) and
 *      AirCaptureService.isCapturedByJump(...) take plain model values, never
 *      an engine.ActiveMove - that keeps rules from ever importing engine
 *      types directly.
 *
 * 3. ENGINE (org.example.engine)
 *    - Real-time state management and game dynamics: MovementEngine,
 *      ActiveMove, EnginePort.
 *    - Manages the passage of time (gameTimeMillis) and the list of moves
 *      in flight, and orchestrates the resulting state changes (mutating
 *      Board, deciding when to call into rules services).
 *    - Depends on model (Board) directly, and on rules (AirCaptureService,
 *      PawnPromotionService) directly - both are legitimate inward
 *      dependencies from engine's point of view.
 *    - Implements EnginePort (its own port, used by controller) and
 *      rules.ActiveMoveQuery (rules' port, see above).
 *
 * 4. CONTROLLER (org.example.controller)
 *    - Application entry point / coordinator: GameController,
 *      InteractionHandler.
 *    - Depends on engine and rules ONLY through their ports (EnginePort,
 *      MoveValidationPort), never on MovementEngine or MoveValidationService
 *      concretely.
 *    - Depends on model (Board) directly, since Board is a pure entity
 *      visible to every layer.
 *    - Has ZERO dependency on the adapters/UI layer: it does not import
 *      BoardPresenter, CommandLineAdapter, or System.out anywhere.
 *    - Uses ONLY constructor injection. Nothing in this layer calls `new`
 *      on another layer's class - every collaborator is supplied by the
 *      composition root.
 *
 * ============================================================================
 * OUTSIDE THE FOUR LAYERS
 * ============================================================================
 *
 * ADAPTERS (org.example.adapters)
 *   - BoardPresenter: the ONLY place System.out appears for board display.
 *   - CommandLineAdapter: reads/parses commands from stdin.
 *   - BoardParser: turns raw input text into a Board (a model->adapter
 *     boundary concern, since it's about an external text format).
 *   - CommandType: vocabulary of recognized CLI commands.
 *
 * COMPOSITION ROOT (org.example.Main)
 *   - The only class allowed to know about all five packages at once.
 *   - Constructs the full object graph by hand (MovementEngine ->
 *     MoveValidationService -> InteractionHandler -> GameController) and
 *     injects each dependency via constructor parameters.
 *   - Calls BoardPresenter directly for "print board" - GameController no
 *     longer exposes a printBoard() method, since printing is I/O and
 *     GameController must stay ignorant of the adapters layer.
 *
 * ============================================================================
 * DEPENDENCY DIRECTION
 * ============================================================================
 *
 *   Main (composition root)
 *     -> controller (GameController, InteractionHandler)
 *        -> engine.EnginePort, rules.MoveValidationPort   [abstractions]
 *        -> model.Board                                   [pure entity]
 *     -> engine (MovementEngine)
 *        -> rules (AirCaptureService, PawnPromotionService) [concrete, inward]
 *        -> model.Board                                     [concrete, inward]
 *        -> implements engine.EnginePort, rules.ActiveMoveQuery
 *     -> rules (MoveValidationService)
 *        -> model (Board, Piece, Position)                  [concrete, inward]
 *        -> rules.ActiveMoveQuery                            [its own port]
 *     -> model: no outward dependencies at all.
 *
 * Every arrow either points inward (toward model) or is inverted through a
 * port owned by the inner layer (rules.ActiveMoveQuery). No layer reaches
 * "up" to a layer that isn't allowed to see it, and no I/O logic exists
 * anywhere in model, rules, engine, or controller.
 *
 * ============================================================================
 * WHY THIS MATTERS FOR TESTING
 * ============================================================================
 *
 * - rules.MoveValidationService can be unit tested with a one-line fake
 *   ActiveMoveQuery, with no MovementEngine, no Board mutation machinery, no
 *   controller at all.
 * - rules.AirCaptureService and PawnPromotionService are pure functions over
 *   model values - no setup required beyond a Board/Piece/Position.
 * - engine.MovementEngine can be tested with a real Board and no controller.
 * - controller.GameController / InteractionHandler can be tested against
 *   hand-written fake EnginePort / MoveValidationPort implementations,
 *   without a real MovementEngine or MoveValidationService.
 */
public class ArchitectureDoc {
    // This class documents the architecture. No runtime code.
}
