package org.example.controller;

import org.example.engine.EnginePort;

/**
 * Controller layer: application entry point / coordinator.
 *
 * Pure constructor injection - GameController never instantiates any of its
 * own collaborators. Every dependency (engine, interaction handler, and,
 * transitively, the rules service used by the interaction handler) is built
 * by the composition root (Main.java, or a test factory) and handed in here.
 *
 * GameController depends only on the EnginePort abstraction, never on the
 * concrete MovementEngine, and it has NO dependency on the adapters/UI layer:
 * it does not know BoardPresenter or System.out exist. Printing the board is
 * the composition root's job (it already holds a reference to the Board and
 * can hand it to a BoardPresenter directly).
 */
public class GameController {
    private final EnginePort engine;
    private final InteractionHandler interactionHandler;

    public GameController(EnginePort engine, InteractionHandler interactionHandler) {
        this.engine = engine;
        this.interactionHandler = interactionHandler;
    }

    public void handleClick(int x, int y) {
        interactionHandler.handleClick(x, y);
    }

    public void handleJump(int x, int y) {
        interactionHandler.handleJump(x, y);
    }

    public void advanceTime(long millis) {
        engine.advanceTime(millis);
    }
}
