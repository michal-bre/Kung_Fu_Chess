package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * GitHub Repository: https://github.com/user/Kung_Fo_Chess
 * 
 * Kung Fu Chess - A dynamic chess game with simultaneous piece movement.
 * 
 * This program reads chess board configurations and commands from standard input,
 * processes the commands, and outputs the resulting board state.
 * 
 * Input Format:
 *   Board:
 *   [chess board in text format]
 *   Commands:
 *   [command lines]
 * 
 * Supported Commands:
 *   - print board: Display the current board state
 *   - click X Y: Process a click at pixel coordinates (X, Y)
 *   - jump X Y: Process a jump at pixel coordinates (X, Y)
 *   - wait MS: Advance game time by MS milliseconds
 */
public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        List<String> boardLines = new ArrayList<>();
        List<String> commandLines = new ArrayList<>();
        boolean readingBoard = false;
        boolean readingCommands = false;

        // Read all inputs line by line
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.equalsIgnoreCase("Board:")) {
                readingBoard = true;
                readingCommands = false;
                continue;
            }

            if (line.equalsIgnoreCase("Commands:")) {
                readingBoard = false;
                readingCommands = true;
                continue;
            }

            // Collect board configuration lines
            if (readingBoard) {
                boardLines.add(line);
            }
            // Collect command lines to execute later
            else if (readingCommands) {
                commandLines.add(line);
            }
        }
        scanner.close();

        // Process and validate the board inside a try-catch block
        try {
            // 1. Parse and create the initial board
            Board board = BoardParser.parse(boardLines);

            // 2. Initialize the game controller with the parsed board
            GameController gameController = new GameController(board);

            // 3. Process and execute each command sequentially
            for (String commandLine : commandLines) {
                executeCommand(commandLine, gameController);
            }

        } catch (IllegalArgumentException e) {
            // Prints the exact VPL validation error message
            System.out.println(e.getMessage());
        }
    }

    // Helper method to parse and route commands to the GameController
    private static void executeCommand(String commandLine, GameController gameController) {
        String lowerCommand = commandLine.toLowerCase();

        if (lowerCommand.equals("print board")) {
            gameController.printBoard();
        }
        else if (lowerCommand.startsWith("click ")) {
            // Split by spaces, expected format: click <x> <y>
            String[] tokens = commandLine.split("\\s+");
            if (tokens.length == 3) {
                try {
                    int x = Integer.parseInt(tokens[1]);
                    int y = Integer.parseInt(tokens[2]);
                    gameController.handleClick(x, y);
                } catch (NumberFormatException e) {
                    // Soft ignore or log if parsing fails (VPL input is usually well-formed)
                }
            }
        }
        else if (lowerCommand.startsWith("jump ")) {
            String[] tokens = commandLine.split("\\s+");
            if (tokens.length == 3) {
                try {
                    int x = Integer.parseInt(tokens[1]);
                    int y = Integer.parseInt(tokens[2]);
                    gameController.handleJump(x, y);
                } catch (NumberFormatException e) {
                    // Soft ignore
                }
            }
        }
        else if (lowerCommand.startsWith("wait ")) {
            // Split by spaces, expected format: wait <ms>
            String[] tokens = commandLine.split("\\s+");
            if (tokens.length == 2) {
                try {
                    long ms = Long.parseLong(tokens[1]);
                    gameController.advanceTime(ms);
                } catch (NumberFormatException e) {
                    // Soft ignore if parsing fails
                }
            }
        }
    }
}