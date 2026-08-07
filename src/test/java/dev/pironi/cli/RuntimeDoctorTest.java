package dev.pironi.cli;

import com.sun.net.httpserver.HttpServer;
import dev.pironi.agent.AgentContext;
import dev.pironi.agent.CapabilityReport;
import dev.pironi.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDoctorTest {
    @TempDir Path temporaryDirectory;

    @Test void reportsLocalRuntimeAndSuccessfulNetworkProbe() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            AgentContext context = new AgentContext("", "", "");
            context.updateRuntimeSession("approval: auto");
            RuntimeDoctor doctor = new RuntimeDoctor(
                    temporaryDirectory, temporaryDirectory,
                    new CapabilityReport(new ToolRegistry(List.of()), context),
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/probe")
            );

            String report = doctor.run();

            assertTrue(report.contains("Pironi doctor"));
            assertTrue(report.contains("Java: " + System.getProperty("java.version")));
            assertTrue(report.contains("write=true"));
            assertTrue(report.contains("Network probe: reachable (HTTP 204)"));
            assertTrue(report.contains("approval: auto"));
        } finally {
            server.stop(0);
        }
    }
}
