package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ExportStreamingUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ExportStreamingUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class ExportStreamingUtilsTest {
    @Test
    public void normaliseZipModeDefaultsAndValidatesModes() {
        assertEquals("never", ExportStreamingUtils.normaliseZipMode(null));
        assertEquals("never", ExportStreamingUtils.normaliseZipMode(""));
        assertEquals("auto", ExportStreamingUtils.normaliseZipMode("AUTO"));
        assertEquals("always", ExportStreamingUtils.normaliseZipMode("always"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ExportStreamingUtils.normaliseZipMode("sometimes"));
        assertEquals("zip must be one of: auto, always, never", ex.getMessage());
    }

    @Test
    public void streamingExceptionMessageDistinguishesClientDisconnects() {
        assertEquals(
            "Export download interrupted by client disconnect/timeout: Broken pipe",
            ExportStreamingUtils.streamingExceptionMessage("Export download", new SocketException("Broken pipe"))
        );
        assertEquals(
            "Export download failed mid-stream: IOException: disk failed",
            ExportStreamingUtils.streamingExceptionMessage("Export download", new IOException("disk failed"))
        );
    }

    @Test
    public void logStreamingExceptionUsesProvidedLogger() {
        List<String> messages = new ArrayList<>();
        ExportStreamingUtils.logStreamingException("Input download", new SocketException("Connection reset"), messages::add);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("interrupted by client disconnect/timeout"));
    }
}
