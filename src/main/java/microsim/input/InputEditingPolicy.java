/* (C) Copyright 2026, by Ross Richardson
 * Optional model-owned input editing rules for application file operations.
 * @author ross richardson
 */
package microsim.input;

public interface InputEditingPolicy {
    record State(boolean built, boolean buildStarted) {}
    /** Pure query. Path is canonical, relative to input/, with '/' separators.
     * Return an explanation to deny replacement, or null to allow it.
     * Application lifecycle and data-access restrictions still apply.
     */
    String inputReadOnlyReason(String path, State state);
}
