package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSpeedToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void calculatesBitsPerSecondWithoutPuttingPayloadInOutput() {
        NetworkSpeedTool tool = new NetworkSpeedTool(bytes ->
                new NetworkSpeedTool.ProbeResult(200, bytes, 20_000_000, 1_000_000_000));

        ToolResult result = tool.execute(mapper.createObjectNode().put("megabytes", 10));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("downloadMbps=80.00"));
        assertTrue(result.output().contains("latencyMs=20.0"));
        assertTrue(result.output().contains("bytes=10000000"));
    }

    @Test void rejectsOversizedProbeAndHttpFailure() {
        NetworkSpeedTool tool = new NetworkSpeedTool(bytes ->
                new NetworkSpeedTool.ProbeResult(503, 0, 1, 1));
        assertFalse(tool.execute(mapper.createObjectNode().put("megabytes", 26)).success());
        assertFalse(tool.execute(mapper.createObjectNode().put("megabytes", 2)).success());
    }
}
