package dev.pironi.cli;

import dev.pironi.agent.CapabilityReport;
import dev.pironi.tool.PlatformShell;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class RuntimeDoctor {
    private static final URI DEFAULT_PROBE = URI.create(
            "https://api.open-meteo.com/v1/forecast?latitude=42.7&longitude=23.3&forecast_days=1"
    );
    private final Path workspace;
    private final Path pironiHome;
    private final CapabilityReport capabilities;
    private final HttpClient client;
    private final URI networkProbe;
    private org.jline.terminal.Terminal terminal;

    /** Set so /doctor can explain why the status row is or is not pinned. */
    void useTerminal(org.jline.terminal.Terminal activeTerminal) {
        this.terminal = activeTerminal;
    }

    RuntimeDoctor(Path workspace, Path pironiHome, CapabilityReport capabilities) {
        this(workspace, pironiHome, capabilities, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build(), DEFAULT_PROBE);
    }

    RuntimeDoctor(Path workspace, Path pironiHome, CapabilityReport capabilities,
            HttpClient client, URI networkProbe) {
        this.workspace = workspace;
        this.pironiHome = pironiHome;
        this.capabilities = capabilities;
        this.client = client;
        this.networkProbe = networkProbe;
    }

    String run() {
        return """
                Pironi doctor
                Java: %s (%s)
                Platform: %s %s
                Workspace: %s [%s]
                Pironi home: %s [%s]
                Shell: %s
                Version: %s
                Terminal: %s
                Status row: %s
                Network probe: %s

                %s
                """.formatted(
                System.getProperty("java.version"), System.getProperty("java.vendor"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                workspace, access(workspace), pironiHome, access(pironiHome),
                PlatformShell.name(), BuildVersion.current(), terminalDescription(),
                dev.pironi.status.TerminalStatusReporter.describeStatusSupport(terminal).reason(),
                networkStatus(), capabilities.render()
        ).strip();
    }

    private String terminalDescription() {
        if (terminal == null) return "none (non-interactive)";
        var size = terminal.getSize();
        return terminal.getType() + " " + size.getColumns() + "x" + size.getRows()
                + " (" + terminal.getClass().getSimpleName() + ")";
    }

    private String networkStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder(networkProbe)
                    .timeout(Duration.ofSeconds(8)).header("User-Agent", "Pironi/0.1")
                    .GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 400 ? "reachable (HTTP " + status + ")"
                    : "failed (HTTP " + status + ")";
        } catch (Exception e) {
            return "failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    private static String access(Path path) {
        return "read=" + Files.isReadable(path) + ", write=" + Files.isWritable(path);
    }
}
