package org.example;

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
