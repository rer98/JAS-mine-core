/* (C) Copyright 2026, by Ross Richardson
 *
 * Verifies scalar parameter conversion, exact long transport and atomic validation.
 *
 * @author ross richardson
 */
package microsim.web.server;

import microsim.annotation.GUIparameter;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ParameterScalarTypesTest {
    enum Mode { FIRST; public String toString() { return "Friendly label"; } }
    static class Scalars {
        @GUIparameter byte b; @GUIparameter Byte boxedB;
        @GUIparameter short s; @GUIparameter Short boxedS;
        @GUIparameter int i; @GUIparameter Integer boxedI;
        @GUIparameter long l = Long.MAX_VALUE; @GUIparameter Long boxedL = Long.MIN_VALUE;
        @GUIparameter float f; @GUIparameter Float boxedF;
        @GUIparameter double d; @GUIparameter Double boxedD;
        @GUIparameter boolean flag; @GUIparameter Boolean boxedFlag;
        @GUIparameter char c; @GUIparameter Character boxedC;
        @GUIparameter String text = "hello";
        @GUIparameter Mode mode = Mode.FIRST;
    }
    private Object convert(String field, Object value) throws Exception {
        return ParameterIntrospection.convertValue(Scalars.class.getDeclaredField(field), value);
    }
    @Test void primitivesAndWrappersAcceptValidValues() throws Exception {
        for (String prefix : List.of("", "boxed")) {
            String[] names = prefix.isEmpty() ? new String[]{"b","s","i","l","f","d","flag","c"}
                : new String[]{"boxedB","boxedS","boxedI","boxedL","boxedF","boxedD","boxedFlag","boxedC"};
            Object[] input = {127, 32767, 2147483647, "9223372036854775807", 0.15, 0.15, true, " "};
            Object[] expected = {(byte)127, (short)32767, Integer.MAX_VALUE, Long.MAX_VALUE, 0.15f, 0.15d, true, ' '};
            for (int i=0; i<names.length; i++) assertEquals(expected[i], convert(names[i], input[i]), names[i]);
        }
        assertEquals(Long.MIN_VALUE, convert("l", "-9223372036854775808"));
        assertEquals("123", convert("text", 123));
        assertEquals(Mode.FIRST, convert("mode", "FIRST"));
        assertEquals(Double.MIN_VALUE, convert("d", Double.MIN_VALUE));
        assertEquals(Float.MIN_VALUE, convert("f", (double)Float.MIN_VALUE));
    }
    @Test void rejectsOverflowFractionsAndInvalidTypes() {
        Map<String, List<Object>> invalid = Map.of(
            "b", List.of(-129, 128, 1.5), "s", List.of(-32769, 32768, 1.5),
            "i", List.of(-2147483649L, 2147483648L, 1.5),
            "l", List.of("9223372036854775808", "-9223372036854775809", "1.5", 9007199254740992d, Long.MAX_VALUE, true),
            "f", List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, Double.MIN_VALUE, new BigDecimal("1e-400")),
            "d", List.of(Double.NaN, Double.NEGATIVE_INFINITY, new BigDecimal("1e400"), new BigDecimal("1e-400")),
            "c", List.of("", "ab", "😀", 65), "flag", List.of("false", 0),
            "mode", List.of("Friendly label", "UNKNOWN"));
        invalid.forEach((field, values) -> values.forEach(value ->
            assertThrows(IllegalArgumentException.class, () -> convert(field, value), field + ": " + value)));
    }
    @Test void nullIsRejectedForScalarEditorsExceptString() throws Exception {
        for (String field : List.of("b", "boxedB", "s", "boxedS", "i", "boxedI", "l", "boxedL", "f", "boxedF", "d", "boxedD", "flag", "boxedFlag", "c", "boxedC", "mode"))
            assertThrows(IllegalArgumentException.class, () -> convert(field, null), field);
        assertNull(convert("text", null));
    }
    @Test void metadataPreservesLongsAndEnumIdentity() {
        List<Map<String,Object>> rows = new ArrayList<>();
        ParameterIntrospection.extractParameters(new Scalars(), rows);
        Map<String, Map<String,Object>> byName = new HashMap<>();
        rows.forEach(row -> byName.put((String)row.get("name"), row));
        assertEquals("9223372036854775807", byName.get("l").get("value"));
        assertEquals("-9223372036854775808", byName.get("boxedL").get("value"));
        assertEquals("FIRST", byName.get("mode").get("value"));
        assertEquals(List.of("FIRST"), byName.get("mode").get("options"));
    }
    @Test void jsonResponsesAndRequestsKeepExactLongDigits() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String,Object>> rows = new ArrayList<>();
        ParameterIntrospection.extractParameters(new Scalars(), rows);
        String json = mapper.writeValueAsString(ParameterResponseUtils.parameterLists(rows, List.of()));
        assertTrue(json.contains("\"9223372036854775807\""));
        assertTrue(json.contains("\"-9223372036854775808\""));
        Map<?,?> request = mapper.readValue("{\"l\":\"9007199254740993\"}", Map.class);
        assertEquals(9007199254740993L, convert("l", request.get("l")));
        Map<?,?> unsafe = mapper.readValue("{\"l\":9007199254740993}", Map.class);
        assertThrows(IllegalArgumentException.class, () -> convert("l", unsafe.get("l")));
    }
    @Test void invalidUpdateDoesNotPartiallyMutateFields() {
        Scalars target = new Scalars();
        Map<String,Object> changes = new LinkedHashMap<>();
        changes.put("l", "9007199254740993");
        changes.put("b", 128);
        assertThrows(IllegalArgumentException.class, () -> ParameterIntrospection.validateAndApplyParameters(Scalars.class, target, changes));
        assertEquals(Long.MAX_VALUE, target.l);
        assertEquals(0, target.b);
    }
    @Test void buildAndRuntimeUpdatesPreserveLongs() throws Exception {
        Scalars target = new Scalars();
        ParameterIntrospection.validateAndApplyMatchingParameters(Map.of("l", "9007199254740993"),
            new ParameterIntrospection.ParameterTarget(Scalars.class, target));
        assertEquals(9007199254740993L, target.l);
        ParameterIntrospection.validateAndApplyParameters(Scalars.class, target, Map.of("l", "-9223372036854775808"));
        assertEquals(Long.MIN_VALUE, target.l);
    }
}
