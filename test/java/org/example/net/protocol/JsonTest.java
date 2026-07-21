package org.example.net.protocol;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Direct coverage for the hand-rolled JSON codec (see Json's class doc for
 * why it's hand-rolled at all) - the rest of Phase 2's tests exercise it
 * only indirectly through GameServer's real wire traffic
 * (GameServerIntegrationTest), so this locks in the codec's own edge cases
 * (escaping, nesting, negative/decimal numbers, round-tripping) directly and
 * fast, without needing a live socket.
 */
public class JsonTest {

    @Test
    public void writesAndParsesAFlatObject() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "MOVE");
        obj.put("from", "e2");
        obj.put("to", "e4");

        String written = Json.write(obj);
        Map<String, Object> parsed = Json.parseObject(written);

        assertEquals("MOVE", parsed.get("type"));
        assertEquals("e2", parsed.get("from"));
        assertEquals("e4", parsed.get("to"));
    }

    @Test
    public void roundTripsIntegersWithoutATrailingDecimalPoint() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("score", 5);
        String written = Json.write(obj);

        assertEquals("{\"score\":5}", written);
        Map<String, Object> parsed = Json.parseObject(written);
        assertEquals(5.0, ((Number) parsed.get("score")).doubleValue(), 0.0001);
    }

    @Test
    public void roundTripsNegativeAndDecimalNumbers() {
        Object parsed = Json.parse("[-1, 3.5, -2.25]");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) parsed;
        assertEquals(-1.0, ((Number) list.get(0)).doubleValue(), 0.0001);
        assertEquals(3.5, ((Number) list.get(1)).doubleValue(), 0.0001);
        assertEquals(-2.25, ((Number) list.get(2)).doubleValue(), 0.0001);
    }

    @Test
    public void escapesAndUnescapesSpecialCharactersInStrings() {
        String tricky = "line1\nline2\ttabbed \"quoted\" back\\slash";
        String written = Json.write(tricky);
        Object parsed = Json.parse(written);
        assertEquals(tricky, parsed);
    }

    @Test
    public void parsesNestedObjectsAndArrays() {
        String json = "{\"type\":\"STATE\",\"pieces\":[{\"id\":\"p1\",\"x\":100,\"y\":200},{\"id\":\"p2\",\"x\":50.5,\"y\":0}],\"gameOver\":false,\"winner\":null}";
        Map<String, Object> parsed = Json.parseObject(json);

        assertEquals("STATE", parsed.get("type"));
        assertEquals(Boolean.FALSE, parsed.get("gameOver"));
        assertNull(parsed.get("winner"));

        @SuppressWarnings("unchecked")
        List<Object> pieces = (List<Object>) parsed.get("pieces");
        assertEquals(2, pieces.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> firstPiece = (Map<String, Object>) pieces.get(0);
        assertEquals("p1", firstPiece.get("id"));
        assertEquals(100.0, ((Number) firstPiece.get("x")).doubleValue(), 0.0001);
    }

    @Test
    public void protocolMsgBuilderRoundTripsThroughWriteAndParse() {
        String written = Protocol.write(Protocol.TYPE_MOVE, "from", "e2", "to", "e4");
        Map<String, Object> parsed = Json.parseObject(written);

        assertEquals(Protocol.TYPE_MOVE, parsed.get("type"));
        assertEquals("e2", Protocol.getString(parsed, "from"));
        assertEquals("e4", Protocol.getString(parsed, "to"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parsingMalformedJsonThrows() {
        Json.parse("{\"type\": ");
    }
}
