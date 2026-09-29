package com.flashguard.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkPrometheusCaptureTest {
    @TempDir Path directory;
    private static final String VALID = """
        application_started_time_seconds{main_application_class="test.App"} 1.2
        jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 2.097152E7
        process_uptime_seconds 30.5
        """;

    @Test void rejects503EvenWithValidMetricsAndPreservesResponse() throws Exception {
        try (Stub stub = new Stub(503, VALID)) {
            Path target = directory.resolve("failed.txt");
            assertThatThrownBy(() -> RealHttpBenchmarkTest.PrometheusCapture.capture(stub.url(), target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 503");
            assertThat(Files.readString(target)).isEqualTo(VALID);
            assertThat(Files.readString(directory.resolve("failed.txt.http-status.txt"))).isEqualTo("503");
        }
    }

    @Test void rejects200ErrorPageAndPreservesBody() throws Exception {
        String body = "<html>Metrics unavailable</html>";
        try (Stub stub = new Stub(200, body)) {
            Path target = directory.resolve("invalid.txt");
            assertThatThrownBy(() -> RealHttpBenchmarkTest.PrometheusCapture.capture(stub.url(), target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("application_started_time_seconds");
            assertThat(Files.readString(target)).isEqualTo(body);
        }
    }

    @Test void rejectsMissingOrNonfiniteJvmSample() throws Exception {
        for (String body : new String[] {VALID.replace("jvm_memory_used_bytes", "unrelated_bytes"),
            VALID.replace("2.097152E7", "NaN")}) {
            try (Stub stub = new Stub(200, body)) {
                assertThatThrownBy(() -> RealHttpBenchmarkTest.PrometheusCapture.capture(stub.url(), directory.resolve("missing.txt")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("jvm_memory_used_bytes");
            }
        }
    }

    @Test void accepts200WithCoreApplicationJvmAndProcessSamples() throws Exception {
        try (Stub stub = new Stub(200, VALID)) {
            Path target = directory.resolve("valid.txt");
            RealHttpBenchmarkTest.PrometheusCapture.capture(stub.url(), target);
            assertThat(Files.readString(target)).isEqualTo(VALID);
            assertThat(Files.readString(directory.resolve("valid.txt.http-status.txt"))).isEqualTo("200");
        }
    }

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        Stub(int status, String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/actuator/prometheus", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
