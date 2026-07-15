package org.example.view;

import org.example.engine.GameSnapshot;
import org.example.view.imglib.Img;

/**
 * View layer: turns a read-only GameSnapshot into pixels. This is the
 * abstraction the architecture review's items 1, 2, 7 and 8 point at - a
 * renderer should need nothing but a GameSnapshot to draw one frame, never a
 * live Board, EnginePort, or GameController reference.
 *
 * Implementations must not mutate anything - rendering a snapshot twice must
 * produce the same result and must never change game state (see
 * ImgRenderer and the corresponding "rendering does not mutate game state"
 * test).
 */
public interface Renderer {
    Img render(GameSnapshot snapshot);
}
