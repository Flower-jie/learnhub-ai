package com.flower.learnhub;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

@Timeout(30)
class PdfIsolationTest {
    @TempDir Path temporaryRoot;
    private final List<Process> processes = new ArrayList<>();

    private MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "../../private-user-name.pdf", "application/pdf", bytes);
    }

    private TextProcessor realWorker() {
        return new TextProcessor(Duration.ofSeconds(15), temporaryRoot, builder -> {
            assertThat(builder.command().getFirst()).isEqualTo(Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString());
            assertThat(builder.command()).contains("-Xmx256m", "-XX:+ExitOnOutOfMemoryError", "-XX:-HeapDumpOnOutOfMemoryError",
                    "-cp", "com.flower.learnhub.PdfWorker");
            assertThat(builder.command()).doesNotContain("org.springframework.boot.loader.launch.PropertiesLauncher");
            assertThat(String.join(" ", builder.command())).doesNotContain("private-user-name");
            assertThat(builder.redirectOutput()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
            assertThat(builder.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
            assertThat(builder.environment().keySet()).allMatch(key -> List.of("SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP")
                    .contains(key.toUpperCase(java.util.Locale.ROOT)));
            Process process = builder.start();
            processes.add(process);
            return process;
        });
    }

    private TextProcessor stub(String mode, Duration timeout) {
        return new TextProcessor(timeout, temporaryRoot, builder -> startStub(builder, mode));
    }

    private Process startStub(ProcessBuilder builder, String mode) throws IOException {
        List<String> command = new ArrayList<>(builder.command());
        int main = command.indexOf("com.flower.learnhub.PdfWorker");
        command.set(main, PdfWorkerTestStub.class.getName());
        command.add(main + 1, mode);
        builder.command(command);
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    private void assertClean() throws IOException {
        assertThat(processes).allMatch(process -> !process.isAlive());
        try (var files = Files.list(temporaryRoot)) { assertThat(files.toList()).isEmpty(); }
    }

    private void assertGenericFailure(TextProcessor processor) {
        assertThatThrownBy(() -> processor.extract(upload(new byte[]{1})))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo("INVALID_DOCUMENT");
                    assertThat(error.getMessage()).contains("PDF", "拆分").doesNotContain("private", "Exception", temporaryRoot.toString());
                });
    }

    @Test void ordinaryFiftyPagePdfExtractsInClasspathWorkerAndCleansFiles() throws Exception {
        var extracted = realWorker().extract(upload(TestDocuments.pdf(50, true, false)));
        assertThat(extracted.name()).isEqualTo("private-user-name.pdf");
        assertThat(extracted.chunks()).hasSize(50);
        for (int i = 0; i < 50; i++) {
            assertThat(extracted.chunks().get(i).page()).isEqualTo(i + 1);
            assertThat(extracted.chunks().get(i).text()).isEqualTo("Learning knowledge retrieval page " + (i + 1));
        }
        assertClean();
    }

    @Test void smallHighlyCompressedOverCharacterPdfCannotDamageParent() throws Exception {
        byte[] compressed = TestDocuments.overCharacterLimitPdf();
        assertThat(compressed.length).isLessThan(10_000);
        assertThatThrownBy(() -> realWorker().extract(upload(compressed)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422))
                .hasMessageContaining("拆分");
        assertClean();
        assertThat(realWorker().extract(upload(TestDocuments.pdf(1, true, false))).text()).contains("page 1");
        assertClean();
    }

    @Test void streamingInputLimitDoesNotTrustMultipartReportedSize() throws Exception {
        var lying = new MockMultipartFile("file", "huge.pdf", "application/pdf", new byte[TextProcessor.MAX_BYTES + 1]) {
            @Override public long getSize() { return 1; }
        };
        var processor = new TextProcessor(Duration.ofSeconds(15), temporaryRoot, builder -> {
            throw new AssertionError("Oversized input must not launch a worker");
        });
        assertThatThrownBy(() -> processor.extract(lying)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(413));
        assertClean();
        assertThat(stub("valid", Duration.ofSeconds(5)).extract(upload(new byte[]{1})).text()).contains("first page");
        assertClean();
    }

    @Test void declaredOversizedPdfIsRejectedBeforeWorkerStarts() throws Exception {
        var processor = new TextProcessor(Duration.ofSeconds(15), temporaryRoot, builder -> {
            throw new AssertionError("Oversized input must not launch a worker");
        });
        assertThatThrownBy(() -> processor.extract(upload(new byte[TextProcessor.MAX_BYTES + 1])))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(413));
        assertClean();
    }

    @Test void timeoutCoversWholeChildAndForciblyReapsItBeforeCleanup() throws Exception {
        long start = System.nanoTime();
        assertGenericFailure(stub("hang", Duration.ofMillis(600)));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(5));
        assertThat(processes).hasSize(1);
        assertClean();
        assertThat(stub("valid", Duration.ofSeconds(5)).extract(upload(new byte[]{1})).chunks()).hasSize(2);
        assertClean();
    }

    @ParameterizedTest @ValueSource(strings = {"nonzero", "oom-exit", "missing"})
    void failedWorkerReturnsGenericErrorWithoutPrivateText(String mode) throws Exception {
        assertGenericFailure(stub(mode, Duration.ofSeconds(5)));
        assertClean();
    }

    @Test void excessResultFileKillsStillRunningWorkerBeforeDeadline() throws Exception {
        long start = System.nanoTime();
        assertGenericFailure(stub("oversized", Duration.ofSeconds(15)));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertClean();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-magic", "bad-status", "error-payload", "too-many-pages", "negative-count", "bad-page",
            "duplicate-page", "huge-length", "negative-length", "truncated", "invalid-utf8", "total-chars", "trailing"})
    void invalidWorkerProtocolIsBoundedAndRejected(String mode) throws Exception {
        assertGenericFailure(stub(mode, Duration.ofSeconds(5)));
        assertClean();
    }

    @ParameterizedTest @ValueSource(strings = {"valid", "noisy"})
    void unicodePageNumbersAndEmptyPagesSurviveAndOutputPipesCannotBlock(String mode) throws Exception {
        var extracted = stub(mode, Duration.ofSeconds(5)).extract(upload(new byte[]{1}));
        assertThat(extracted.chunks()).extracting(TextProcessor.TextChunk::page).containsExactly(1, 3);
        assertThat(extracted.chunks()).extracting(TextProcessor.TextChunk::text).containsExactly("first page", "知识检索\uD840\uDC00");
        assertClean();
    }

    @Test void semaphoreIsSharedAcrossProcessorsAndDoesNotBlockTextImports() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        var first = new TextProcessor(Duration.ofSeconds(2), temporaryRoot, builder -> {
            Process process = startStub(builder, "hang");
            started.countDown();
            return process;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> assertGenericFailure(first));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> new TextProcessor().extract(upload(new byte[]{1})))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status()).isEqualTo(429);
                        assertThat(error.code()).isEqualTo("PDF_BUSY");
                        assertThat(error.getMessage()).contains("稍后");
                    });
            assertThat(new TextProcessor().extract(new MockMultipartFile("file", "notes.txt", "text/plain",
                    "still responsive".getBytes(StandardCharsets.UTF_8))).text()).isEqualTo("still responsive");
            active.get(5, TimeUnit.SECONDS);
        }
        assertClean();
        assertThat(stub("valid", Duration.ofSeconds(5)).extract(upload(new byte[]{1})).chunks()).hasSize(2);
        assertClean();
    }

    @Test void interruptionReapsWorkerRestoresInterruptAndReleasesPermit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var processor = new TextProcessor(Duration.ofSeconds(15), temporaryRoot, builder -> {
            Process process = startStub(builder, "hang");
            started.countDown();
            return process;
        });
        Thread request = Thread.ofPlatform().start(() -> {
            try {
                assertGenericFailure(processor);
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) { failure.set(e); }
        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            request.interrupt();
            request.join(5000);
            assertThat(request.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(interrupted).isTrue();
        } finally {
            request.interrupt();
            request.join(5000);
        }
        assertClean();
        assertThat(stub("valid", Duration.ofSeconds(5)).extract(upload(new byte[]{1})).chunks()).hasSize(2);
        assertClean();
    }

    @Test void failedLaunchDeletesInputAndReleasesPermit() throws Exception {
        var processor = new TextProcessor(Duration.ofSeconds(15), temporaryRoot, builder -> {
            throw new IOException("private-launch-diagnostic");
        });
        assertGenericFailure(processor);
        assertClean();
        assertThat(stub("valid", Duration.ofSeconds(5)).extract(upload(new byte[]{1})).chunks()).hasSize(2);
        assertClean();
    }

    @Test void workerEnvironmentDropsCredentialsAndJvmInjectionWithoutChangingParent() {
        ProcessBuilder builder = new ProcessBuilder("unused").directory(temporaryRoot.toFile());
        builder.environment().clear();
        for (String name : List.of("APP_ACCESS_TOKEN", "EMBEDDING_API_KEY", "CHAT_API_KEY", "OPENAI_API_KEY", "AWS_SECRET_ACCESS_KEY",
                "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "LOADER_MAIN", "SPRING_APPLICATION_JSON", "UNKNOWN_SECRET")) {
            builder.environment().put(name, "test-only-sensitive-value");
        }
        builder.environment().put("SystemRoot", "test-windows-root");
        builder.environment().put("PATH", "test-path");
        TextProcessor.sanitizeWorkerEnvironment(builder);
        assertThat(builder.environment()).containsOnlyKeys("SystemRoot", "PATH", "TEMP", "TMP");
        assertThat(builder.environment().get("TEMP")).isEqualTo(temporaryRoot.toString());
        assertThat(builder.environment().get("TMP")).isEqualTo(temporaryRoot.toString());
        assertThat(new ProcessBuilder("unused").environment().keySet()).containsExactlyInAnyOrderElementsOf(System.getenv().keySet());
    }
}
