package com.flower.learnhub;

import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only subprocess, with small, deterministic failure modes. */
public final class PdfWorkerTestStub {
    private PdfWorkerTestStub() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path output = Path.of(args[2]);
        if (mode.equals("hang")) {
            // Deliberately exceed the test parent's deadline; it must forcibly reap us.
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("nonzero")) {
            System.out.println("private-document-text");
            System.err.println("private-parser-diagnostic");
            System.exit(7);
        }
        if (mode.equals("oom-exit")) System.exit(3); // JVM ExitOnOutOfMemoryError exit, without allocating memory.
        if (mode.equals("missing")) {
            Files.delete(output);
            return;
        }
        if (mode.equals("oversized")) {
            try (RandomAccessFile file = new RandomAccessFile(output.toFile(), "rw")) {
                file.setLength(PdfWorker.MAX_RESULT_BYTES + 1L);
            }
            Thread.sleep(60_000); // Parent must notice oversized output before its ordinary deadline.
            return;
        }
        if (mode.equals("noisy")) {
            byte[] noise = "private-document-text".repeat(200).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 1024; i++) {
                System.out.write(noise);
                System.err.write(noise);
            }
        }
        try (var writer = new DataOutputStream(Files.newOutputStream(output))) {
            writer.writeInt(mode.equals("bad-magic") ? 0 : PdfWorker.MAGIC);
            writer.writeInt(mode.equals("bad-status") ? 999 : mode.equals("error-payload") ? PdfWorker.ENCRYPTED : PdfWorker.OK);
            writer.writeInt(mode.equals("too-many-pages") ? 51 : mode.equals("negative-count") ? -1
                    : mode.equals("total-chars") ? 2 : 3);
            writer.writeInt(mode.equals("bad-page") ? 0 : 1);
            if (mode.equals("huge-length") || mode.equals("negative-length")) {
                writer.writeInt(mode.equals("huge-length") ? Integer.MAX_VALUE : -1);
                return;
            }
            if (mode.equals("truncated")) {
                writer.writeInt(100);
                writer.writeByte(65);
                return;
            }
            if (mode.equals("invalid-utf8")) {
                writer.writeInt(2);
                writer.write(new byte[]{(byte) 0xc3, 0x28});
                return;
            }
            if (mode.equals("total-chars")) {
                text(writer, "a".repeat(100_000));
                writer.writeInt(2);
                text(writer, "b".repeat(100_001));
                return;
            }
            text(writer, "first page");
            writer.writeInt(mode.equals("duplicate-page") ? 1 : 2);
            text(writer, "");
            writer.writeInt(3);
            text(writer, "知识检索\uD840\uDC00");
            if (mode.equals("trailing")) writer.writeByte(1);
        }
    }

    private static void text(DataOutputStream writer, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writer.writeInt(bytes.length);
        writer.write(bytes);
    }
}
