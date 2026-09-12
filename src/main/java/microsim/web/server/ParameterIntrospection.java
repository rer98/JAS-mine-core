package microsim.web.server;

import microsim.annotation.GUIparameter;

import java.lang.reflect.Field;
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
                    paramInfo.put("value", field.get(target));
                    paramInfo.put("description", annotation.description());
                    paramInfo.put("runtimeModifiable", annotation.runtimeModifiable());

                    if (field.getType().isEnum()) {
                        Object[] enumConstants = field.getType().getEnumConstants();
                        List<String> options = new ArrayList<>();
                        for (Object ec : enumConstants) {
                            options.add(ec.toString());
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
            if (type.equals(Integer.class) || type.equals(int.class)) {
                Number n = (Number) value;
                if (n.doubleValue() % 1 != 0) throw new IllegalArgumentException("fractional value is not valid for type " + type.getSimpleName());
                return n.intValue();
            } else if (type.equals(Double.class) || type.equals(double.class)) {
                return ((Number) value).doubleValue();
            } else if (type.equals(Float.class) || type.equals(float.class)) {
                return ((Number) value).floatValue();
            } else if (type.equals(Long.class) || type.equals(long.class)) {
                Number n = (Number) value;
                if (n.doubleValue() % 1 != 0) throw new IllegalArgumentException("fractional value is not valid for type " + type.getSimpleName());
                return n.longValue();
            } else if (type.equals(Boolean.class) || type.equals(boolean.class)) {
                return (Boolean) value;
            } else if (type.equals(String.class)) {
                return value == null ? null : String.valueOf(value);
            } else if (type.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), (String) value);
                return enumValue;
            } else {
                throw new IllegalArgumentException("unsupported parameter type " + type.getSimpleName());
            }
        } catch (ClassCastException | NullPointerException e) {
            throw new IllegalArgumentException("value '" + value + "' is not valid for type " + type.getSimpleName());
        } catch (IllegalArgumentException e) {
            // e.g. Enum.valueOf with an unknown constant
            throw new IllegalArgumentException("value '" + value + "' is not valid for type " + type.getSimpleName());
        }
    }

    public static void applyParameters(Class<?> clazz, Object instance, Map<String, Object> params, Consumer<String> warningLogger) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            try {
                Field field = clazz.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(instance, convertValue(field, entry.getValue()));
            } catch (NoSuchFieldException e) {
                // Field not in this target, skip — legitimate when applying the same params
                // map to both the model and the collector.
            } catch (Exception e) {
                if (warningLogger != null) {
                    warningLogger.accept("applyParameters: failed to set '" + entry.getKey() + "': "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * All-or-nothing parameter update against a single target. Validates every
     * submitted parameter first (unknown names and un-coercible values are
     * collected as errors); only if validation fully passes are any values
     * written. Throws IllegalArgumentException listing all problems otherwise,
     * leaving the target unmodified.
     */
    public static void validateAndApplyParameters(Class<?> clazz, Object instance, Map<String, Object> params)
            throws IllegalAccessException {
        validateAndApplyMatchingParameters(params, new ParameterTarget(clazz, instance));
    }

    /**
     * All-or-nothing update across multiple possible targets. A submitted name is
     * valid if it exists on at least one target; if it exists on more than one,
     * every matching field is validated and then updated. Unknown names or any
     * failed coercion abort the whole update before any field is changed.
     */
    public static void validateAndApplyMatchingParameters(Map<String, Object> params, ParameterTarget... targets)
            throws IllegalAccessException {
        List<String> errors = new ArrayList<>();
        List<ResolvedField> resolved = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            boolean matched = false;
            for (ParameterTarget target : targets) {
                if (target == null || target.clazz == null) continue;
                Field field;
                try {
                    field = target.clazz.getDeclaredField(entry.getKey());
                } catch (NoSuchFieldException e) {
                    continue;
                }
                matched = true;
                field.setAccessible(true);
                try {
                    resolved.add(new ResolvedField(field, target.instance, convertValue(field, entry.getValue())));
                } catch (IllegalArgumentException e) {
                    errors.add("'" + entry.getKey() + "': " + e.getMessage());
                }
            }
            if (!matched) {
                errors.add("unknown parameter '" + entry.getKey() + "'");
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
