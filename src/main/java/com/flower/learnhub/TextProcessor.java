package com.flower.learnhub;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class TextProcessor {
    public static final int MAX_BYTES = PdfWorker.MAX_INPUT_BYTES;
    public static final int MAX_CHARS = PdfWorker.MAX_CHARS;
    public static final int MAX_CHUNKS = 400;
    public static final int CHUNK_SIZE = 800;
    public static final int OVERLAP = 100;
    private static final Semaphore PDF_WORKERS = new Semaphore(1);
    private static final String PDF_FAILURE = "PDF 过大、内容过于复杂或无法解析，请拆分后重试；暂不支持 OCR";
    private static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private final Duration pdfTimeout;
    private final Path temporaryRoot;
    private final WorkerStarter workerStarter;

    public TextProcessor() { this(Duration.ofSeconds(15), null, ProcessBuilder::start); }

    // Package-private seam for bounded subprocess tests, not application configuration.
    TextProcessor(Duration pdfTimeout, Path temporaryRoot, WorkerStarter workerStarter) {
        this.pdfTimeout = pdfTimeout;
        this.temporaryRoot = temporaryRoot;
        this.workerStarter = workerStarter;
    }

    @FunctionalInterface
    interface WorkerStarter { Process start(ProcessBuilder builder) throws IOException; }

    public record PageText(Integer page, String text) {}
    public record TextChunk(Integer page, int ordinal, String text) {}
    public record Extracted(String name, String text, List<TextChunk> chunks) {}

    public Extracted extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("文件为空，请上传含有文本的文件");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) throw invalid("文件名不能为空");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
            throw invalid("文件名无效或超过 255 字符");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean pdf = lower.endsWith(".pdf");
        if (!pdf && !lower.endsWith(".txt") && !lower.endsWith(".md")) {
            throw new ApiException(415, "UNSUPPORTED_FILE", "仅支持 UTF-8 TXT、Markdown 和可提取文本的 PDF");
        }
        try (var input = file.getInputStream()) {
            List<PageText> pages;
            if (pdf) {
                pages = pdfPages(input);
            } else {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw tooLarge();
                pages = List.of(new PageText(null, decode(bytes)));
            }
            List<TextChunk> chunks = split(pages);
            if (chunks.isEmpty()) throw invalid(pdf ? "PDF 无可提取文本，暂不支持 OCR；请上传文本型 PDF" : "文件没有有效文本");
            String text = String.join("\n\n", pages.stream().map(PageText::text).toList());
            return new Extracted(name, text, chunks);
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid(pdf ? PDF_FAILURE : "文件无法读取，请检查文件格式");
        }
    }

    private String decode(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.startsWith("\uFEFF")) value = value.substring(1);
            if (value.indexOf('\0') >= 0) throw invalid("文件必须是 UTF-8 文本，不能包含二进制内容");
            return value;
        } catch (CharacterCodingException e) {
            throw invalid("文本编码无效，请将文件转换为 UTF-8 后重新导入");
        }
    }

    private List<PageText> pdfPages(InputStream source) throws IOException {
        if (!PDF_WORKERS.tryAcquire()) {
            throw new ApiException(429, "PDF_BUSY", "PDF 正在处理中，请稍后重试");
        }
        Path directory = null;
        Process process = null;
        try {
            directory = temporaryRoot == null ? Files.createTempDirectory("learnhub-pdf-")
                    : Files.createTempDirectory(temporaryRoot, "learnhub-pdf-");
            Path input = directory.resolve("input.pdf");
            Path output = Files.createFile(directory.resolve("result.bin"));
            try (var target = Files.newOutputStream(input, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = source.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) throw tooLarge();
                    target.write(buffer, 0, read);
                }
            }
            // Include JVM startup, PDF loading/decompression, extraction and result writing.
            long deadline = System.nanoTime() + pdfTimeout.toNanos();
            ProcessBuilder builder = workerBuilder(directory, input, output);
            process = workerStarter.start(builder);
            process.getOutputStream().close();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || (Files.exists(output) && Files.size(output) > PdfWorker.MAX_RESULT_BYTES)) {
                    throw invalid(PDF_FAILURE);
                }
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break;
            }
            if (System.nanoTime() > deadline || process.exitValue() != 0) throw invalid(PDF_FAILURE);
            return readPdfResult(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw invalid(PDF_FAILURE);
        } finally {
            try {
                stopAndWait(process);
                if (directory != null) {
                    try (var paths = Files.walk(directory)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    }
                }
            } finally {
                PDF_WORKERS.release();
            }
        }
    }

    private static void stopAndWait(Process process) {
        if (process == null) return;
        boolean interrupted = false;
        if (process.isAlive()) process.destroyForcibly();
        while (true) {
            try { process.waitFor(); break; }
            catch (InterruptedException e) { interrupted = true; process.destroyForcibly(); }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static ProcessBuilder workerBuilder(Path directory, Path input, Path output) throws IOException {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx256m", "-XX:+ExitOnOutOfMemoryError", "-XX:-HeapDumpOnOutOfMemoryError",
                "-XX:ErrorFile=" + directory.resolve("jvm-error.log"),
                "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory, "-Dpdfbox.fontcache=" + directory));
        ClassLoader loader = TextProcessor.class.getClassLoader();
        boolean bootArchive = false;
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current.getClass().getName().equals("org.springframework.boot.loader.launch.LaunchedClassLoader")) {
                bootArchive = true;
                break;
            }
        }
        if (bootArchive) {
            try {
                Class<?> launcher = Class.forName(PROPERTIES_LAUNCHER, false, loader);
                Path archive = Path.of(launcher.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!Files.isRegularFile(archive)) throw new IOException("Missing executable archive");
                command.addAll(List.of("-Dloader.main=com.flower.learnhub.PdfWorker", "-cp", archive.toString(), PROPERTIES_LAUNCHER));
            } catch (ReflectiveOperationException | java.net.URISyntaxException e) {
                throw new IOException("PDF worker launcher unavailable", e);
            }
        } else {
            // Resolve relative entries before switching the child to its private working directory.
            String classPath = String.join(java.io.File.pathSeparator,
                    java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator, -1))
                            .map(entry -> Path.of(entry).toAbsolutePath().toString()).toList());
            command.addAll(List.of("-cp", classPath, "com.flower.learnhub.PdfWorker"));
        }
        command.addAll(List.of(input.toString(), output.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        sanitizeWorkerEnvironment(builder);
        return builder;
    }

    static void sanitizeWorkerEnvironment(ProcessBuilder builder) {
        // Also excludes JVM/Boot option injection (JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, LOADER_*, etc.).
        Set<String> allowed = Set.of("SYSTEMROOT", "WINDIR", "PATH");
        builder.environment().keySet().removeIf(key -> !allowed.contains(key.toUpperCase(Locale.ROOT)));
        builder.environment().put("TEMP", builder.directory().getAbsolutePath());
        builder.environment().put("TMP", builder.directory().getAbsolutePath());
    }

    private static List<PageText> readPdfResult(Path output) throws IOException {
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) > PdfWorker.MAX_RESULT_BYTES) {
            throw invalid(PDF_FAILURE);
        }
        byte[] bytes;
        try (var source = Files.newInputStream(output)) {
            bytes = source.readNBytes(PdfWorker.MAX_RESULT_BYTES + 1);
        }
        if (bytes.length > PdfWorker.MAX_RESULT_BYTES) throw invalid(PDF_FAILURE);
        try (var result = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (result.readInt() != PdfWorker.MAGIC) throw invalid(PDF_FAILURE);
            int status = result.readInt();
            if (status != PdfWorker.OK) {
                if (result.available() != 0) throw invalid(PDF_FAILURE);
                if (status == PdfWorker.ENCRYPTED) throw invalid("不支持加密 PDF，请先移除密码；暂不支持 OCR");
                if (status == PdfWorker.TOO_MANY_PAGES) throw invalid("PDF 最多支持 50 页");
                throw invalid(PDF_FAILURE);
            }
            int count = result.readInt();
            if (count < 0 || count > PdfWorker.MAX_PAGES) throw invalid(PDF_FAILURE);
            List<PageText> pages = new ArrayList<>();
            int total = Math.max(0, count - 1) * 2;
            for (int page = 1; page <= count; page++) {
                if (result.readInt() != page) throw invalid(PDF_FAILURE);
                int length = result.readInt();
                if (length < 0 || length > 3 * (MAX_CHARS - total) || length > result.available()) throw invalid(PDF_FAILURE);
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result.readNBytes(length))).toString();
                total += text.length();
                if (total > MAX_CHARS) throw invalid(PDF_FAILURE);
                pages.add(new PageText(page, text));
            }
            if (result.available() != 0) throw invalid(PDF_FAILURE);
            return pages;
        }
    }

    public List<TextChunk> split(List<PageText> pages) {
        int total = 0;
        List<TextChunk> chunks = new ArrayList<>();
        for (PageText page : pages) {
            total += page.text().length();
            if (total > MAX_CHARS) throw invalid("每份文档最多支持 200000 字符，请拆分后导入");
            String text = page.text().replace("\r\n", "\n").replace('\r', '\n').strip();
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                String chunk = text.substring(start, end);
                if (!chunk.isBlank()) chunks.add(new TextChunk(page.page(), chunks.size() + 1, chunk));
                if (chunks.size() > MAX_CHUNKS) throw invalid("文档分块超过 400，请拆分后导入");
                if (end == text.length()) break;
                start = end - OVERLAP;
                if (Character.isLowSurrogate(text.charAt(start))) start++;
            }
        }
        return List.copyOf(chunks);
    }

    private static ApiException invalid(String message) { return new ApiException(422, "INVALID_DOCUMENT", message); }
    private static ApiException tooLarge() { return new ApiException(413, "FILE_TOO_LARGE", "文件不能超过 10MB"); }
}
