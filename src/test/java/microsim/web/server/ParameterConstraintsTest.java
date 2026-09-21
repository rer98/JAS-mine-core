/* (C) Copyright 2026, by Ross Richardson
 * Tests shared constraints, exact integer limits and mutation-free type validation.
 * @author ross richardson
 */
package microsim.web.server;
import microsim.parameter.ParameterConstraints;
import microsim.annotation.GUIparameter;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParameterConstraintsTest {
    static class Model {
        @GUIparameter public int endYear = 2026;
        @GUIparameter public long seed = 606;
        public int internal = 1;
    }
    @Test void exactLongBoundsAndIntegerValidation() {
        var rule = ParameterConstraints.Rule.integerRange(9007199254740993L, Long.MAX_VALUE, "outside range");
        assertTrue(rule.accepts("9007199254740993"));
        assertFalse(rule.accepts("9007199254740992"));
        assertTrue(rule.accepts(Long.toString(Long.MAX_VALUE)));
        assertFalse(rule.accepts("9223372036854775808"));
        assertFalse(rule.accepts("9007199254740993.5"));
        assertEquals("9223372036854775807", rule.metadata().get("maximum"));
    }
    @Test void constraintsAndTypesDoNotMutateAndMetadataUsesSameRule() {
        Model model = new Model();
        var values = new LinkedHashMap<String,Object>(Map.of("endYear", 2027));
        var rule = ParameterConstraints.Rule.integerRange(2019, 2026, "invalid year");
        var rules = Map.of("endYear", rule);
        assertEquals(Map.of("endYear", "invalid year"), ParameterConstraints.violations(rules, values));
        assertEquals(2026, model.endYear);
        assertEquals(Map.of("endYear", 2027), values);
        var metadata = new ArrayList<Map<String,Object>>();
        ParameterIntrospection.extractParameters(model, metadata);
        ParameterIntrospection.addConstraints(metadata, rules);
        assertEquals(rule.metadata(), metadata.get(0).get("constraints"));
        assertFalse(ParameterIntrospection.validateBuildTypes(Map.of("endYear", 2020.5), Model.class).isEmpty());
        assertFalse(ParameterIntrospection.validateBuildTypes(Map.of("internal", 2), Model.class).isEmpty());
        assertFalse(ParameterIntrospection.validateBuildTypes(Map.of("unknown", 2), Model.class).isEmpty());
        assertTrue(ParameterIntrospection.validateBuildTypes(Map.of("seed", "9223372036854775807"), Model.class).isEmpty());
    }
}
