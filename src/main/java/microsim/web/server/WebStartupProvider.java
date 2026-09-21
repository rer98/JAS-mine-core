/* (C) Copyright 2026, by Ross Richardson
 * Optional model-owned startup choices and preparation, independent of model type.
 * @author ross richardson
 */
package microsim.web.server;

import java.util.Map;
import java.util.function.Consumer;

public interface WebStartupProvider {
    /** JSON-compatible title/description, checkbox choices and optional fields, uploads and table definitions. */
    Map<String, Object> describe();
    /** Read-only review. Must change if inputs relevant to confirmation change. */
    Map<String, Object> review(Map<String, Boolean> choices) throws Exception;
    /** Runs only after confirmation, under the server's lifecycle write lock. */
    void prepare(Map<String, Boolean> choices, Consumer<String> progress) throws Exception;
    /** Extended startup requests; old checkbox-only providers remain supported. */
    default Map<String, Object> reviewRequest(Map<String, Object> request) throws Exception {
        return review(booleanChoices(request));
    }
    default void prepareRequest(Map<String, Object> request, Consumer<String> progress) throws Exception {
        prepare(booleanChoices(request), progress);
    }
    private static Map<String, Boolean> booleanChoices(Map<String, Object> request) {
        var choices = new java.util.LinkedHashMap<String, Boolean>();
        request.forEach((key, value) -> {
            if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Expected boolean startup choices");
            choices.put(key, flag);
        });
        return Map.copyOf(choices);
    }
    /** Only candidate files may change here, never active model inputs. */
    default void upload(String path, java.io.InputStream body) throws Exception {
        throw new IllegalArgumentException("Startup uploads are not supported by this model");
    }
    default void discardUploads() throws Exception {
        throw new IllegalArgumentException("Startup uploads are not supported by this model");
    }
    /** Read-only check of readiness and the inputs covered by confirmation. */
    void validateBuild() throws Exception;
}
