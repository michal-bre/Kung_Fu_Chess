package org.example.net.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The full wire vocabulary GameServer and GameClient speak to each other,
 * plus a tiny builder so building one of these messages doesn't mean five
 * lines of `new LinkedHashMap<>(); put; put; put;` at every call site in
 * both GameServer and GameClient.
 *
 * Every message is a flat JSON object with a "type" field (one of the TYPE_*
 * constants below) and a handful of type-specific fields, built/read via
 * {@link Json}. Kept as plain string type-tags and a Map-based payload
 * (rather than one Java class per message type) because the two ends of this
 * protocol are the ONLY two things that ever speak it - the ceremony of a
 * full class hierarchy for a project-internal 10-message protocol would cost
 * more to maintain than the type safety would be worth.
 *
 * Direction reference (see GameServer/GameClient for the actual send/receive
 * code - this class only names the shape):
 *   client -> server: LOGIN, MOVE, JUMP
 *   server -> client: COLOR_ASSIGNED, ACCOUNT_INFO, STATE, SCORE, MOVE_LOG,
 *                      GAME_STARTED, GAME_ENDED, MOVE_REJECTED,
 *                      JUMP_REJECTED, ERROR
 *
 * GAME_ENDED carries extra winnerUsername/winnerNewElo/winnerEloDelta/
 * loserUsername/loserNewElo/loserEloDelta fields (Phase 3) whenever both
 * players in that game were logged-in accounts - see
 * GameServer.onGameEnded's class doc for when those fields are, and aren't,
 * present.
 */
public final class Protocol {

    private Protocol() {
    }

    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_MOVE = "MOVE";
    public static final String TYPE_JUMP = "JUMP";

    public static final String TYPE_COLOR_ASSIGNED = "COLOR_ASSIGNED";
    public static final String TYPE_ACCOUNT_INFO = "ACCOUNT_INFO";
    public static final String TYPE_STATE = "STATE";
    public static final String TYPE_SCORE = "SCORE";
    public static final String TYPE_MOVE_LOG = "MOVE_LOG";
    public static final String TYPE_GAME_STARTED = "GAME_STARTED";
    public static final String TYPE_GAME_ENDED = "GAME_ENDED";
    public static final String TYPE_MOVE_REJECTED = "MOVE_REJECTED";
    public static final String TYPE_JUMP_REJECTED = "JUMP_REJECTED";
    public static final String TYPE_ERROR = "ERROR";

    // Phase 4: matchmaking + disconnect/auto-resign.
    public static final String TYPE_WAITING = "WAITING";
    public static final String TYPE_OPPONENT_DISCONNECTED = "OPPONENT_DISCONNECTED";
    public static final String TYPE_OPPONENT_RECONNECTED = "OPPONENT_RECONNECTED";

    /**
     * Builds a message object: {@code msg(TYPE_MOVE, "from", "e2", "to", "e4")}
     * produces {@code {"type":"MOVE","from":"e2","to":"e4"}}. Field order is
     * preserved (Json.write walks a LinkedHashMap) purely for readability of
     * captured traffic - the protocol itself doesn't care about key order.
     */
    public static Map<String, Object> msg(String type, Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("msg() requires an even number of key/value arguments");
        }
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    /** Shortcut for the common send path: build then immediately serialize. */
    public static String write(String type, Object... keyValuePairs) {
        return Json.write(msg(type, keyValuePairs));
    }

    /** Reads a String field, or null if absent/not a String - a defensive accessor for fields arriving over the network from a peer this class can't fully trust to send well-formed messages. */
    public static String getString(Map<String, Object> message, String key) {
        Object value = message.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** Reads a Number field as a double (Json's number model - see Json's class doc), or Double.NaN if absent/not a Number. */
    public static double getNumber(Map<String, Object> message, String key) {
        Object value = message.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    /** Reads a Boolean field, defaulting to false if absent/not a Boolean. */
    public static boolean getBoolean(Map<String, Object> message, String key) {
        Object value = message.get(key);
        return value instanceof Boolean && (Boolean) value;
    }
}
