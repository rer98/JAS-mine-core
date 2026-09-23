package microsim.web.server;

/* (C) Copyright 2026, by Ross Richardson
 * Safe, explicit diagnostic budget failures without user-data disclosure.
 * @author ross richardson
 */
public final class DiagnosticLimitException extends java.io.IOException {
    public DiagnosticLimitException(String message) { super(message); }
}
