package org.example;

import org.example.adapters.BoardParser;
import org.example.model.Board;
import org.example.view.BoardView;
import org.example.view.GameWindow;

import java.util.Arrays;
import java.util.List;

/**
 * Composition root for the graphical (Swing) entry point - separate from
 * Main.java, which is the composition root for the existing text-command
 * CLI and is exercised directly by the JUnit suite.
 *
 * Keeping this in its own class means launching the GUI can never affect
 * CLI behavior or spin up a JFrame during headless test runs: nothing in
 * the engine/rules/controller/adapters layers references org.example.view
 * or this class.
 *
 * Phase 1: wire the board model to the view and display the chessboard.
 * Piece rendering and mouse-driven interaction land in later phases.
 */
public class GuiMain {

    public static void main(String[] args) {
        List<String> startingPosition = Arrays.asList(
                "bR bN bB bQ bK bB bN bR",
                "bP bP bP bP bP bP bP bP",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                ". . . . . . . .",
                "wP wP wP wP wP wP wP wP",
                "wR wN wB wQ wK wB wN wR"
        );

        Board board = BoardParser.parse(startingPosition);

        BoardView boardView = new BoardView(board);
        GameWindow window = new GameWindow("Kung Fu Chess", boardView);
        window.show();
    }
}
