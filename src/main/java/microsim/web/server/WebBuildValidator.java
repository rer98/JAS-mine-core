package microsim.web.server;

import java.util.Map;

/* (C) Copyright 2026, by Ross Richardson
 *
 * Optional model-specific validation before the web server mutates build state.
 *
 * @author ross richardson
 *
 */
public interface WebBuildValidator {
    /** Reject incompatible configuration with IllegalArgumentException. Must not mutate the request. */
    void validateWebBuildParameters(Map<String, Object> parameters);
}
