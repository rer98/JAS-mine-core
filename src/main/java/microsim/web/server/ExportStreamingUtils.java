package microsim.web.server;

import io.javalin.http.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Single-file streaming helper for JAS-mine Web downloads.
 * Normalises ZIP mode requests, streams ordinary files, wraps individual files in ZIP archives when requested,
 * and reports mid-stream exceptions consistently.
 *
 * @author ross richardson
 *
 */

/** Streaming helpers for single-file export/input downloads. */
public final class ExportStreamingUtils {
    private ExportStreamingUtils() {}

    public static String normaliseZipMode(String zipMode) {
        String mode = (zipMode == null || zipMode.isEmpty()) ? "never" : zipMode.toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode) && !"always".equals(mode) && !"never".equals(mode)) {
            throw new IllegalArgumentException("zip must be one of: auto, always, never");
        }
        return mode;
    }

    public static void streamSingleFile(Context ctx, File file, String downloadName, String zipMode, long zipThresholdBytes) throws IOException {
        String mode = normaliseZipMode(zipMode);
        boolean zip = ExportFileUtils.shouldZipSingleFile(file, mode, zipThresholdBytes);
        if (zip) {
            ctx.contentType("application/zip")
               .header("Content-Disposition", "attachment; filename=\"" + downloadName + ".zip\"");
            try (ZipOutputStream zos = new ZipOutputStream(ctx.outputStream())) {
                zos.putNextEntry(new ZipEntry(downloadName));
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        } else {
            ctx.contentType("application/octet-stream")
               .header("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
            Files.copy(file.toPath(), ctx.outputStream());
            ctx.outputStream().flush();
        }
    }

    public static String streamingExceptionMessage(String what, Exception e) {
        if (ExportFileUtils.isExpectedClientDisconnect(e)) {
            return what + " interrupted by client disconnect/timeout: " + e.getMessage();
        }
        return what + " failed mid-stream: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    public static void logStreamingException(String what, Exception e, Consumer<String> logMessage) {
        logMessage.accept(streamingExceptionMessage(what, e));
        if (!ExportFileUtils.isExpectedClientDisconnect(e)) {
            e.printStackTrace();
        }
    }
}
