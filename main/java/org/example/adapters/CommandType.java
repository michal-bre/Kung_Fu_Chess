package org.example.adapters;

/**
 * Adapters layer: vocabulary of CLI commands understood by CommandLineAdapter.
 * This is I/O/format vocabulary, not a domain concept, so it lives outside
 * the four core layers.
 */
public enum CommandType {
    PRINT_BOARD("print board"),
    CLICK("click"),
    JUMP("jump"),
    WAIT("wait");

    private final String command;

    CommandType(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    public static CommandType fromString(String input) {
        String lowerInput = input.toLowerCase().trim();

        for (CommandType cmd : CommandType.values()) {
            if (lowerInput.equals(cmd.command)) {
                return cmd;
            }
            if (lowerInput.startsWith(cmd.command + " ")) {
                return cmd;
            }
        }
        return null;
    }
}
