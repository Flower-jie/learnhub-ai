package com.flower.learnhub;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** PDFBox is only executed in this heap-limited, disposable JVM; no Spring context is started. */
public final class PdfWorker {
    static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;
    static final int MAX_CHARS = 200_000;
    static final int MAX_PAGES = 50;
    // Versioned, length-prefixed UTF-8 protocol. Never Java object serialization.
    static final int MAGIC = 0x4c485031; // LHP1
    static final int OK = 0;
    static final int ENCRYPTED = 1;
    static final int TOO_MANY_PAGES = 2;

    private PdfWorker() {}

    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IOException("Invalid worker arguments");
            Path input = Path.of(args[0]);
            Path output = Path.of(args[1]);
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.size(input) > MAX_INPUT_BYTES
                    || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid worker files");
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            Result result;
            try {
                result = extract(input, deadline);
            } catch (InvalidPasswordException e) {
                result = new Result(ENCRYPTED, List.of());
            }
            writeResult(output, result);
        } catch (Exception e) {
            // No PDF text, file names or parser diagnostics cross the process boundary.
            System.exit(1);
        }
    }

    private record Result(int status, List<String> pages) {}

    private static Result extract(Path input, long deadline) throws IOException {
        // Loading, lazy stream decompression and font parsing all stay out of the service JVM.
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            if (document.isEncrypted()) return new Result(ENCRYPTED, List.of());
            int count = document.getNumberOfPages();
            if (count > MAX_PAGES) return new Result(TOO_MANY_PAGES, List.of());
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                private int glyphs;
                private int operations;
                @Override protected void processTextPosition(TextPosition position) {
                    if (++glyphs > MAX_CHARS || System.nanoTime() > deadline) throw new PdfLimitException();
                    super.processTextPosition(position);
                }
                @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                    if (++operations > 1_000_000 || System.nanoTime() > deadline) throw new PdfLimitException();
                    super.processOperator(operator, operands);
                }
            };
            stripper.setSortByPosition(false);
            int remaining = MAX_CHARS - Math.max(0, count - 1) * 2; // Include joined page separators.
            for (int page = 1; page <= count; page++) {
                if (System.nanoTime() > deadline) throw new PdfLimitException();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedTextWriter writer = new BoundedTextWriter(remaining, deadline);
                stripper.writeText(document, writer);
                String text = writer.toString();
                remaining -= text.length();
                pages.add(text);
            }
            return new Result(OK, pages);
        }
    }

    private static void writeResult(Path output, Result result) throws IOException {
        try (var writer = new DataOutputStream(new BufferedOutputStream(new BoundedOutputStream(
                Files.newOutputStream(output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))))) {
            writer.writeInt(MAGIC);
            writer.writeInt(result.status());
            if (result.status() != OK) return;
            writer.writeInt(result.pages().size());
            for (int i = 0; i < result.pages().size(); i++) {
                byte[] text = result.pages().get(i).getBytes(StandardCharsets.UTF_8);
                writer.writeInt(i + 1);
                writer.writeInt(text.length);
                writer.write(text);
            }
        }
    }

    private static final class PdfLimitException extends RuntimeException {}

    private static final class BoundedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private final long deadline;
        BoundedTextWriter(int limit, long deadline) { this.limit = limit; this.deadline = deadline; }
        @Override public void write(char[] chars, int offset, int length) {
            if (length > limit - text.length() || System.nanoTime() > deadline) throw new PdfLimitException();
            text.append(chars, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return text.toString(); }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream target;
        private int written;
        BoundedOutputStream(OutputStream target) { this.target = target; }
        @Override public void write(int value) throws IOException {
            checkLimit(1);
            target.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            checkLimit(length);
            target.write(bytes, offset, length);
        }
        private void checkLimit(int length) throws IOException {
            if (length > MAX_RESULT_BYTES - written) throw new IOException("Result limit exceeded");
            written += length;
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() throws IOException { target.close(); }
    }
}
