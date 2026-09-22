/* (C) Copyright 2026, by Ross Richardson
 * Bounded archive preflight before accepting Excel files for web sessions.
 * @author ross richardson
 */
package microsim.web.server;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public final class WorkbookBudget {
    public static final long MAX_DECODED_BYTES = 64L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private WorkbookBudget() {}

    public static void validate(Path path, String filename) throws IOException {
        if (!InputFileUtils.isExcelFile(filename)) return;
        if (Files.size(path) > MAX_FILE_BYTES) throw new UploadLimits.LimitException(413, "Workbook exceeds 16 MiB file limit");
        // Detect the format from bytes: a ZIP renamed to .xls still needs expansion checks.
        byte[] signature;
        try (var input = Files.newInputStream(path)) { signature = input.readNBytes(8); }
        if (java.util.Arrays.equals(signature, new byte[] {(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1})) return;
        if (signature.length < 4 || signature[0] != 'P' || signature[1] != 'K' || signature[2] != 3 || signature[3] != 4)
            throw new IOException("Unsupported Excel file format");
        long[] total = {0};
        long[] cells = {0};
        int entries = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[65536];
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > 1024) throw new IOException("Workbook has too many archive entries");
                var limited = new FilterInputStream(zip) {
                    @Override public void close() {}
                    @Override public int read() throws IOException { int b=in.read(); if(b>=0) count(1); return b; }
                    @Override public int read(byte[] b,int off,int len) throws IOException { int n=in.read(b,off,len); if(n>0)count(n); return n; }
                    private void count(int n) throws IOException {
                        total[0] += n;
                        if(total[0]>MAX_DECODED_BYTES) throw new UploadLimits.LimitException(413,"Workbook exceeds 64 MiB decoded limit");
                    }
                };
                if (entry.getName().endsWith(".xml")) {
                    try {
                        var factory = javax.xml.parsers.SAXParserFactory.newInstance();
                        factory.setNamespaceAware(true);
                        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
                        factory.newSAXParser().parse(limited, new org.xml.sax.helpers.DefaultHandler() {
                            private int text;
                            @Override public void startElement(String uri,String local,String name,org.xml.sax.Attributes attrs) throws org.xml.sax.SAXException {
                                text=0;
                                if ((local.equals("c") || local.equals("si")) && ++cells[0]>1_000_000)
                                    throw new org.xml.sax.SAXException("Workbook exceeds one million cells/shared strings");
                            }
                            @Override public void characters(char[] chars,int start,int length) throws org.xml.sax.SAXException {
                                text+=length;
                                if(text>65536) throw new org.xml.sax.SAXException("Workbook text exceeds 64 KiB");
                            }
                        });
                    } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
                        throw new IOException("Workbook XML is invalid or exceeds structural limits",e);
                    }
                }
                while (limited.read(buffer) != -1) { /* Count all remaining entry bytes. */ }
            }
        }
        if (entries == 0) throw new IOException("Invalid XLSX archive");
    }
}
