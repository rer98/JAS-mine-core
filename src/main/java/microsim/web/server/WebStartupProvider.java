/* (C) Copyright 2026, by Ross Richardson
 * Optional model-owned startup choices and preparation, independent of model type.
 * @author ross richardson
 */
package microsim.web.server;

import java.util.Map;
import java.util.function.Consumer;

public interface WebStartupProvider {
    /** JSON-compatible title, description, boolean choices and read-only information. */
    Map<String, Object> describe();
    /** Read-only review. Must change if inputs relevant to confirmation change. */
    Map<String, Object> review(Map<String, Boolean> choices) throws Exception;
    /** Runs only after confirmation, under the server's lifecycle write lock. */
    void prepare(Map<String, Boolean> choices, Consumer<String> progress) throws Exception;
    /** Read-only check of readiness and the inputs covered by confirmation. */
    void validateBuild() throws Exception;
}
