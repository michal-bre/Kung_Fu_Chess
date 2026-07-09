package org.example;

import org.example.adapters.BoardParser;
import org.example.adapters.BoardPresenter;
import org.example.adapters.CommandLineAdapter;
import org.example.adapters.CommandLineAdapter.InputData;
import org.example.adapters.CommandLineAdapter.ParsedCommand;
import org.example.controller.GameController;
import org.example.controller.InteractionHandler;
import org.example.engine.MovementEngine;
import org.example.model.Board;
import org.example.rules.MoveValidationService;

import java.util.Scanner;

/**
 * Entry Point / Composition Root: bootstraps the application and wires every
 * dependency by hand via constructor injection.
 *
 * Clean Architecture: this is the outermost layer. It is the ONLY place that
 * is allowed to know about every layer at once - model, rules, engine,
 * controller, and adapters - because its entire job is to construct the
 * object graph and connect I/O to it.
 *
 * Responsibilities:
 * - Read input (via CommandLineAdapter)
 * - Parse board (via BoardParser)
 * - Construct concrete rules/engine implementations and inject them into the
 *   controller layer via constructors (no layer creates its own
 *   collaborators internally anymore)
 * - Print the board directly via BoardPresenter (GameController has no
 *   knowledge of the adapters/UI layer)
 */
public class Main {
    public static void main(String[] args) {
        try {
            // Input adaptation layer
            Scanner scanner = new Scanner(System.in);
            CommandLineAdapter inputAdapter = new CommandLineAdapter(scanner);
            InputData inputData = inputAdapter.readInput();

            // Adapters layer: parse raw text into the model
            Board board = BoardParser.parse(inputData.boardLines);

            // Composition root wiring: build every layer explicitly and inject
            // it into the next. Nothing below this point is instantiated by
            // the classes that consume it.
            MovementEngine movementEngine = new MovementEngine(board);
            MoveValidationService moveValidationService = new MoveValidationService(board, movementEngine);
            InteractionHandler interactionHandler = new InteractionHandler(board, movementEngine, moveValidationService);
            GameController gameController = new GameController(movementEngine, interactionHandler);
            BoardPresenter boardPresenter = new BoardPresenter(board);

            // Execute commands
            for (String commandLine : inputData.commandLines) {
                ParsedCommand parsedCmd = CommandLineAdapter.parseCommand(commandLine);
                if (parsedCmd != null) {
                    executeCommand(parsedCmd, gameController, boardPresenter);
                }
            }

        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }

    private static void executeCommand(CommandLineAdapter.ParsedCommand parsedCmd, GameController gameController, BoardPresenter boardPresenter) {
        switch (parsedCmd.type) {
            case PRINT_BOARD:
                boardPresenter.printBoard();
                break;
            case CLICK:
                if (parsedCmd.params.length == 2) {
                    gameController.handleClick(parsedCmd.params[0], parsedCmd.params[1]);
                }
                break;
            case JUMP:
                if (parsedCmd.params.length == 2) {
                    gameController.handleJump(parsedCmd.params[0], parsedCmd.params[1]);
                }
                break;
            case WAIT:
                if (parsedCmd.params.length == 1) {
                    gameController.advanceTime(parsedCmd.params[0]);
                }
                break;
        }
    }
}
