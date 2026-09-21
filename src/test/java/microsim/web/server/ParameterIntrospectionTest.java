package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import microsim.annotation.GUIparameter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ParameterIntrospection.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ParameterIntrospection
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class ParameterIntrospectionTest {
    enum Mode { LOW, HIGH }

    static class ParameterFixture {
        @GUIparameter(description = "integer parameter")
        public int intValue = 1;

        @GUIparameter(description = "double parameter")
        public double doubleValue = 1.5;

        @GUIparameter(description = "boolean parameter")
        public boolean boolValue = false;

        @GUIparameter(description = "string parameter")
        public String stringValue = "initial";

        @GUIparameter(description = "mode parameter")
        public Mode mode = Mode.LOW;

        @GUIparameter(description = "fixed parameter", runtimeModifiable = false)
        public int fixedValue = 2;

        public int internalValue = 3;
        public java.util.List<String> unsupported;
    }

    static class CollectorFixture {
        @GUIparameter(description = "export flag")
        public boolean exportToCSV = false;
    }

    static class BuilderFixture {
        @GUIparameter(description = "setup rate")
        public double setupRate = 0.5;
    }

    @Test
    public void convertValueCoercesSupportedParameterTypes() throws Exception {
        assertEquals(3, convert("intValue", 3));
        assertEquals(3, convert("intValue", 3.0));
        assertEquals(2.5, (Double) convert("doubleValue", 2.5), 0.000001);
        assertEquals(Boolean.TRUE, convert("boolValue", true));
        assertEquals("123", convert("stringValue", 123));
        assertEquals(Mode.HIGH, convert("mode", "HIGH"));
    }


    @Test
    public void convertValueRejectsFractionalValuesForIntegerTypes() throws Exception {
        try {
            convert("intValue", 123.5);
            fail("Expected fractional integer value to be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not valid") || e.getMessage().contains("fractional"));
        }
    }

    @Test
    public void convertValueRejectsUnsupportedParameterTypes() throws Exception {
        try {
            convert("unsupported", Arrays.asList("x"));
            fail("Expected unsupported parameter type to be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("unsupported") || e.getMessage().contains("not valid"));
        }
    }

    @Test
    public void extractParametersIncludesAnnotatedFieldsAndEnumOptions() {
        ParameterFixture fixture = new ParameterFixture();
        List<Map<String, Object>> params = new ArrayList<>();

        ParameterIntrospection.extractParameters(fixture, params);

        Map<String, Object> mode = params.stream()
            .filter(p -> p.get("name").equals("mode"))
            .findFirst()
            .orElseThrow();
        assertEquals("Mode", mode.get("type"));
        assertEquals("LOW", mode.get("value"));
        assertEquals("mode parameter", mode.get("description"));
        assertTrue((Boolean) mode.get("runtimeModifiable"));
        assertEquals(List.of("LOW", "HIGH"), mode.get("options"));

        Map<String, Object> fixed = params.stream()
            .filter(p -> p.get("name").equals("fixedValue"))
            .findFirst()
            .orElseThrow();
        assertFalse((Boolean) fixed.get("runtimeModifiable"));
        assertFalse(params.stream().anyMatch(p -> p.get("name").equals("internalValue")));
        assertFalse(params.stream().anyMatch(p -> p.get("name").equals("unsupported")));
    }

    @Test
    public void applyParametersSkipsUnknownFieldsAndLogsBadValues() {
        ParameterFixture fixture = new ParameterFixture();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("intValue", 7);
        params.put("fixedValue", 8);
        params.put("internalValue", 9);
        params.put("missing", 1);
        params.put("boolValue", "not-a-bool");

        ParameterIntrospection.applyParameters(ParameterFixture.class, fixture, params, warnings::add);

        assertEquals(7, fixture.intValue);
        assertEquals(8, fixture.fixedValue);
        assertEquals(3, fixture.internalValue);
        assertFalse(fixture.boolValue);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("boolValue"));
    }

    @Test
    public void validateAndApplyParametersIsAllOrNothing() throws Exception {
        ParameterFixture fixture = new ParameterFixture();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("intValue", 7);
        params.put("mode", "NOPE");

        try {
            ParameterIntrospection.validateAndApplyParameters(ParameterFixture.class, fixture, params);
            fail("Expected invalid enum value to be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("mode"));
        }
        assertEquals(1, fixture.intValue);
        assertEquals(Mode.LOW, fixture.mode);

        params.put("mode", "HIGH");
        ParameterIntrospection.validateAndApplyParameters(ParameterFixture.class, fixture, params);
        assertEquals(7, fixture.intValue);
        assertEquals(Mode.HIGH, fixture.mode);
    }

    @Test
    public void validateAndApplyMatchingParametersIsAllOrNothingAcrossTargets() throws Exception {
        BuilderFixture builder = new BuilderFixture();
        ParameterFixture model = new ParameterFixture();
        CollectorFixture collector = new CollectorFixture();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("setupRate", 0.75);
        params.put("intValue", 7);
        params.put("exportToCSV", true);
        params.put("definitelyMissing", 123);

        try {
            ParameterIntrospection.validateAndApplyMatchingParameters(params,
                new ParameterIntrospection.ParameterTarget(BuilderFixture.class, builder),
                new ParameterIntrospection.ParameterTarget(ParameterFixture.class, model),
                new ParameterIntrospection.ParameterTarget(CollectorFixture.class, collector));
            fail("Expected unknown parameter to abort the whole update");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("definitelyMissing"));
        }
        assertEquals(0.5, builder.setupRate, 0.000001);
        assertEquals(1, model.intValue);
        assertEquals(2, model.fixedValue);
        assertFalse(collector.exportToCSV);

        params.remove("definitelyMissing");
        params.put("fixedValue", 8);
        ParameterIntrospection.validateAndApplyMatchingParameters(params,
            new ParameterIntrospection.ParameterTarget(BuilderFixture.class, builder),
            new ParameterIntrospection.ParameterTarget(ParameterFixture.class, model),
            new ParameterIntrospection.ParameterTarget(CollectorFixture.class, collector));
        assertEquals(0.75, builder.setupRate, 0.000001);
        assertEquals(7, model.intValue);
        assertEquals(8, model.fixedValue);
        assertTrue(collector.exportToCSV);
    }


    @Test
    public void runtimeUpdateRejectsNonModifiableParameterWithoutPartialMutation() throws Exception {
        ParameterFixture fixture = new ParameterFixture();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("intValue", 7);
        params.put("fixedValue", 9);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ParameterIntrospection.validateAndApplyParameters(
                        ParameterFixture.class, fixture, params));

        assertTrue(error.getMessage().contains("fixedValue"));
        assertTrue(error.getMessage().contains("runtime"));
        assertEquals(1, fixture.intValue);
        assertEquals(2, fixture.fixedValue);
    }

    @Test
    public void runtimeUpdateRejectsUnannotatedField() {
        ParameterFixture fixture = new ParameterFixture();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ParameterIntrospection.validateAndApplyParameters(
                        ParameterFixture.class, fixture, Map.of("internalValue", 9)));

        assertTrue(error.getMessage().contains("internalValue"));
        assertTrue(error.getMessage().contains("@GUIparameter"));
        assertEquals(3, fixture.internalValue);
    }

    private Object convert(String fieldName, Object value) throws Exception {
        Field f = ParameterFixture.class.getDeclaredField(fieldName);
        return ParameterIntrospection.convertValue(f, value);
    }
}
