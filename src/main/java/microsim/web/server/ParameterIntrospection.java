package microsim.web.server;

import microsim.annotation.GUIparameter;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


/* (C) Copyright 2026, by Ross Richardson
 *
 * GUI parameter reflection and validation helper for JAS-mine Web.
 * Extracts @GUIparameter metadata, converts JSON values to Java field types, applies build-time parameters,
 * and validates runtime updates all-or-nothing before mutation.
 *
 * @author ross richardson
 *
 */

/** Reflection helpers for discovering and applying @GUIparameter fields. */
public final class ParameterIntrospection {
    private ParameterIntrospection() {}

    public static final class ParameterTarget {
        private final Class<?> clazz;
        private final Object instance;

        public ParameterTarget(Class<?> clazz, Object instance) {
            this.clazz = clazz;
            this.instance = instance;
        }
    }

    private static final class ResolvedField {
        private final Field field;
        private final Object instance;
        private final Object value;

        private ResolvedField(Field field, Object instance, Object value) {
            this.field = field;
            this.instance = instance;
            this.value = value;
        }
    }

    public static void extractParameters(Object target, List<Map<String, Object>> paramList) {
        Field[] fields = target.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (field.isAnnotationPresent(GUIparameter.class)) {
                field.setAccessible(true);
                GUIparameter annotation = field.getAnnotation(GUIparameter.class);
                try {
                    Map<String, Object> paramInfo = new HashMap<>();
                    paramInfo.put("name", field.getName());
                    paramInfo.put("type", field.getType().getSimpleName());
                    Object value = field.get(target);
                    // JSON numbers would lose long precision in browser Number values.
                    paramInfo.put("value", value instanceof Long ? value.toString()
                            : value instanceof Enum<?> e ? e.name() : value);
                    paramInfo.put("description", annotation.description());
                    paramInfo.put("runtimeModifiable", annotation.runtimeModifiable());

                    if (field.getType().isEnum()) {
                        Object[] enumConstants = field.getType().getEnumConstants();
                        List<String> options = new ArrayList<>();
                        for (Object ec : enumConstants) {
                            options.add(((Enum<?>) ec).name());
                        }
                        paramInfo.put("options", options);
                    }
                    paramList.add(paramInfo);
                } catch (IllegalAccessException e) {
                    // Skip inaccessible fields, matching previous SimulationServer behaviour.
                }
            }
        }
    }

    /**
     * Convert a JSON-decoded value to the target field's type, throwing
     * IllegalArgumentException (with a user-readable message) if the value
     * cannot be coerced. Pure: performs no field mutation.
     */
    public static Object convertValue(Field field, Object value) {
        Class<?> type = field.getType();
        try {
            if (type.equals(Byte.class) || type.equals(byte.class)) {
                return integerValue(value).byteValueExact();
            } else if (type.equals(Short.class) || type.equals(short.class)) {
                return integerValue(value).shortValueExact();
            } else if (type.equals(Integer.class) || type.equals(int.class)) {
                return integerValue(value).intValueExact();
            } else if (type.equals(Long.class) || type.equals(long.class)) {
                return integerValue(value).longValueExact();
            } else if (type.equals(Double.class) || type.equals(double.class)) {
                double result = ((Number) value).doubleValue();
                if (!Double.isFinite(result)) throw new IllegalArgumentException("must be finite");
                if (result == 0 && new BigDecimal(value.toString()).signum() != 0)
                    throw new IllegalArgumentException("outside double range");
                return result;
            } else if (type.equals(Float.class) || type.equals(float.class)) {
                float result = ((Number) value).floatValue();
                if (!Float.isFinite(result) || (result == 0 && new BigDecimal(value.toString()).signum() != 0))
                    throw new IllegalArgumentException("outside float range");
                return result;
            } else if (type.equals(Character.class) || type.equals(char.class)) {
                if (!(value instanceof String text) || text.length() != 1)
                    throw new IllegalArgumentException("requires one UTF-16 character");
                return text.charAt(0);
            } else if (type.equals(Boolean.class) || type.equals(boolean.class)) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("requires true or false");
                return value;
            } else if (type.equals(String.class)) {
                return value == null ? null : String.valueOf(value);
            } else if (type.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), (String) value);
                return enumValue;
            } else {
                throw new IllegalArgumentException("unsupported parameter type " + type.getSimpleName());
            }
        } catch (ClassCastException | NullPointerException | ArithmeticException e) {
            throw new IllegalArgumentException("value '" + value + "' is not valid for type " + type.getSimpleName());
        } catch (IllegalArgumentException e) {
            // e.g. Enum.valueOf with an unknown constant
            throw new IllegalArgumentException("value '" + value + "' is not valid for type " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Accept exact decimal strings as well as legacy JSON numbers; never truncate. */
    private static BigDecimal integerValue(Object value) {
        if (!(value instanceof Number) && !(value instanceof String))
            throw new IllegalArgumentException("requires an integer");
        if (value instanceof String text && !text.matches("[+-]?[0-9]+"))
            throw new IllegalArgumentException("requires a decimal integer string");
        BigDecimal decimal = new BigDecimal(value.toString());
        // A browser may have rounded a large integer before JSON serialization.
        if (value instanceof Number && decimal.abs().compareTo(new BigDecimal("9007199254740991")) > 0)
            throw new IllegalArgumentException("send large integers as decimal strings");
        return decimal;
    }

    /**
     * Apply annotated experiment-builder parameters before engine setup. Fields
     * belonging to other build targets are skipped here and validated after setup.
     */
    public static void applyParameters(Class<?> clazz, Object instance, Map<String, Object> params,
            Consumer<String> warningLogger) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            try {
                Field field = clazz.getDeclaredField(entry.getKey());
                if (!field.isAnnotationPresent(GUIparameter.class)) continue;
                field.setAccessible(true);
                field.set(instance, convertValue(field, entry.getValue()));
            } catch (NoSuchFieldException e) {
                // Field not in this target: legitimate when the same parameter map
                // will later be applied to the model and collector.
            } catch (Exception e) {
                if (warningLogger != null) {
                    warningLogger.accept("applyParameters: failed to set '" + entry.getKey() + "': "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Apply a live parameter update to one target. Every submitted name must be
     * annotated with {@link GUIparameter} and permit runtime modification. The
     * update is all-or-nothing.
     */
    public static void validateAndApplyParameters(Class<?> clazz, Object instance, Map<String, Object> params)
            throws IllegalAccessException {
        validateAndApplyMatchingParameters(true, params, new ParameterTarget(clazz, instance));
    }

    /**
     * Apply build-time parameters across multiple possible targets. Only fields
     * annotated with {@link GUIparameter} are configurable, but parameters marked
     * non-runtime-modifiable remain valid during this pre-run build phase.
     */
    public static void validateAndApplyMatchingParameters(Map<String, Object> params, ParameterTarget... targets)
            throws IllegalAccessException {
        validateAndApplyMatchingParameters(false, params, targets);
    }

    private static void validateAndApplyMatchingParameters(boolean runtimeOnly, Map<String, Object> params,
            ParameterTarget... targets) throws IllegalAccessException {
        List<String> errors = new ArrayList<>();
        List<ResolvedField> resolved = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            boolean foundField = false;
            boolean matchedParameter = false;
            for (ParameterTarget target : targets) {
                if (target == null || target.clazz == null) continue;
                Field field;
                try {
                    field = target.clazz.getDeclaredField(entry.getKey());
                } catch (NoSuchFieldException e) {
                    continue;
                }
                foundField = true;
                GUIparameter annotation = field.getAnnotation(GUIparameter.class);
                if (annotation == null) continue;

                matchedParameter = true;
                if (runtimeOnly && !annotation.runtimeModifiable()) {
                    errors.add("parameter '" + entry.getKey() + "' cannot be modified at runtime");
                    continue;
                }

                field.setAccessible(true);
                try {
                    resolved.add(new ResolvedField(field, target.instance, convertValue(field, entry.getValue())));
                } catch (IllegalArgumentException e) {
                    errors.add("'" + entry.getKey() + "': " + e.getMessage());
                }
            }
            if (!matchedParameter) {
                if (foundField) {
                    errors.add("parameter '" + entry.getKey() + "' is not annotated with @GUIparameter");
                } else {
                    errors.add("unknown parameter '" + entry.getKey() + "'");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        for (ResolvedField r : resolved) {
            r.field.set(r.instance, r.value);
        }
    }

}
