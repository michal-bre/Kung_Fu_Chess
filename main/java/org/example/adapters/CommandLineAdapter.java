package org.example.adapters;

import java.util.Scanner;
import java.util.ArrayList;
import java.util.List;

/**
 * Input Adapter: Handles command parsing from the console.
 * Separates parsing logic from application logic.
 *
 * Clean Architecture: Adapter layer (outermost).
 */
public class CommandLineAdapter {
    private final Scanner scanner;

    public CommandLineAdapter(Scanner scanner) {
        this.scanner = scanner;
    }

    public InputData readInput() {
        List<String> boardLines = new ArrayList<>();
        List<String> commandLines = new ArrayList<>();
        boolean readingBoard = false;
        boolean readingCommands = false;

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

            if (readingBoard) {
                boardLines.add(line);
            } else if (readingCommands) {
                commandLines.add(line);
            }
        }
        scanner.close();

        return new InputData(boardLines, commandLines);
    }

    public static class InputData {
        public final List<String> boardLines;
        public final List<String> commandLines;

        public InputData(List<String> boardLines, List<String> commandLines) {
            this.boardLines = boardLines;
            this.commandLines = commandLines;
        }
    }

    public static class ParsedCommand {
        public final CommandType type;
        public final int[] params;

        public ParsedCommand(CommandType type, int[] params) {
            this.type = type;
            this.params = params;
        }
    }

    public static ParsedCommand parseCommand(String commandLine) {
        CommandType commandType = CommandType.fromString(commandLine);

        if (commandType == null) {
            return null;
        }

        switch (commandType) {
            case PRINT_BOARD:
                return new ParsedCommand(commandType, new int[]{});
            case CLICK:
                return parseCoordinateCommand(commandLine, commandType);
            case JUMP:
                return parseCoordinateCommand(commandLine, commandType);
            case WAIT:
                return parseTimeCommand(commandLine, commandType);
            default:
                return null;
        }
    }

    private static ParsedCommand parseCoordinateCommand(String commandLine, CommandType type) {
        String[] tokens = commandLine.split("\\s+");
        if (tokens.length == 3) {
            try {
                int x = Integer.parseInt(tokens[1]);
                int y = Integer.parseInt(tokens[2]);
                return new ParsedCommand(type, new int[]{x, y});
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static ParsedCommand parseTimeCommand(String commandLine, CommandType type) {
        String[] tokens = commandLine.split("\\s+");
        if (tokens.length == 2) {
            try {
                int ms = Integer.parseInt(tokens[1]);
                return new ParsedCommand(type, new int[]{ms});
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
