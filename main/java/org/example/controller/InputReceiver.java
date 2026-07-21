package org.example.controller;

/**
 * Controller layer: the two actions a board's mouse input can ever produce -
 * abstracted out of GameController so BoardInputListener can be handed
 * something that isn't necessarily a local GameController.
 *
 * Before Phase 2, BoardInputListener held a concrete GameController field
 * directly, which was fine when the only thing that could ever receive a
 * click was the local, single-process game. The networked client (Phase 2)
 * needs the exact same click/jump routing - convert a pixel coordinate,
 * decide select-vs-move, forward it - but has to send the result to a
 * GameServer over a WebSocket instead of calling straight into a local
 * GameEngine. Rather than teach BoardInputListener two different code paths,
 * this interface lets it stay exactly as simple as it already was: it just
 * needs *something* that can handleClick/handleJump, and doesn't care
 * whether that something is backed by a local engine or a network socket.
 *
 * GameController already had exactly this method shape, so it now
 * implements this interface with no change to its own behavior; nothing
 * about local/hot-seat mode changes because of this.
 */
public interface InputReceiver {
    void handleClick(int x, int y);

    void handleJump(int x, int y);
}
