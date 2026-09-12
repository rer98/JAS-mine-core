package microsim.web.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Runtime parameter update log writer for JAS-mine Web.
 * Appends changed parameter values to GUIparameters.csv using the current simulation time
 * so browser and AI tools can inspect parameter history for a run.
 *
 * @author ross richardson
 *
 */

/** Appends runtime GUI parameter updates to GUIparameters.csv. */
public final class ParameterUpdateLog {
    private ParameterUpdateLog() {}

    public static void append(File outputFolder, double simTime, Map<String, Object> params) throws Exception {
        File csvFile = new File(outputFolder, "GUIparameters.csv");
        boolean writeHeader = !csvFile.exists();
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(csvFile, true), StandardCharsets.UTF_8))) {
            if (writeHeader) {
                pw.println("time,parameter,value");
            }
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String val = entry.getValue() == null ? "" : TabularExportUtils.escapeCsv(String.valueOf(entry.getValue()));
                pw.println(simTime + "," + TabularExportUtils.escapeCsv(entry.getKey()) + "," + val);
            }
        }
    }
}
