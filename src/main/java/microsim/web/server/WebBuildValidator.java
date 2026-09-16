package microsim.web.server;

import java.util.Map;

/** Optional model-specific validation before the web server mutates build state. */
public interface WebBuildValidator {
    /** Reject incompatible configuration with IllegalArgumentException. Must not mutate the request. */
    void validateWebBuildParameters(Map<String, Object> parameters);
}
