package com.factchecker.pdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Converts non-PDF document formats (currently Word .doc/.docx) to PDF using a local, headless
 * LibreOffice install - fully local, no external API, matching the rest of this project. Runs
 * once per upload, before extraction: everything downstream (chunking, embedding, the pdf.js
 * viewer, evidence highlighting) only ever sees a real PDF file, so none of it needs to know or
 * care what format the user originally uploaded.
 */
@Service
public class DocumentConversionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentConversionService.class);
    private static final long CONVERSION_TIMEOUT_SECONDS = 120;

    public boolean needsConversion(String storagePath) {
        return !storagePath.toLowerCase().endsWith(".pdf");
    }

    /** Converts the file at sourcePath into a PDF in the same directory, returning the new path.
     *  The original file is left in place - the caller decides whether to delete it. */
    public Path convertToPdf(Path sourcePath) throws IOException, InterruptedException {
        Path outDir = sourcePath.getParent();

        ProcessBuilder builder = new ProcessBuilder(
                "soffice", "--headless", "--norestore", "--convert-to", "pdf",
                "--outdir", outDir.toString(), sourcePath.toString()
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (var reader = process.inputReader()) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b);
        }

        boolean finished = process.waitFor(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Document conversion timed out after " + CONVERSION_TIMEOUT_SECONDS + "s.");
        }
        if (process.exitValue() != 0) {
            log.error("LibreOffice conversion failed for {}: {}", sourcePath, output);
            throw new IOException("Failed to convert the document to PDF.");
        }

        Path converted = outDir.resolve(withoutExtension(sourcePath.getFileName().toString()) + ".pdf");
        if (!Files.exists(converted)) {
            throw new IOException("Conversion reported success but no PDF was produced.");
        }
        return converted;
    }

    private String withoutExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
