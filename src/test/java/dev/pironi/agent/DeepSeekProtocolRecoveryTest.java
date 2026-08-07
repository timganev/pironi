package dev.pironi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.pironi.model.OpenAiCompatibleClient;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.trace.NoOpTraceWriter;
import dev.pironi.verification.NoOpVerificationGate;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekProtocolRecoveryTest {
    @Test
    void recoversFromSchemaRejectionAndTruncatedJsonWithoutPrematureOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requests = new AtomicInteger();
        List<String> formats = new CopyOnWriteArrayList<>();
        List<String> prompts = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String format = request.path("response_format").path("type").asText();
            formats.add(format);
            prompts.add(request.path("messages").toString());
            int number = requests.incrementAndGet();
            int status;
            String body;
            if (number == 1) {
                status = 200;
                body = response("{\\\"thought\\\":\\\"cut\\\",\\\"toolCalls\\\":[],\\\"finalAnswer\\\":\\\"visible too early\\\"");
            } else {
                status = 200;
                body = response("{\\\"thought\\\":\\\"fixed\\\",\\\"toolCalls\\\":[],\\\"finalAnswer\\\":\\\"recovered\\\"}");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var model = OpenAiCompatibleClient.deepSeek(
                    base, "deepseek-v4-flash", "key", Duration.ofSeconds(5), 512
            );
            StringBuilder visible = new StringBuilder();
            AgentLoop loop = new AgentLoop(
                    model, new DecisionParser(mapper), mapper, new ToolRegistry(List.of()),
                    (tool, arguments) -> ApprovalDecision.ALLOW, new NoOpTraceWriter(),
                    new AgentContext("", "", ""), new NoOpStatusReporter(),
                    new NoOpVerificationGate(), 4, 2, visible::append, AgentMemory.none()
            );

            AgentResult result = loop.run("find note");

            assertTrue(result.success());
            assertEquals("recovered", result.output());
            assertEquals("recovered" + System.lineSeparator(), visible.toString());
            assertEquals(2, requests.get());
            assertEquals(List.of("json_object", "json_object"), formats);
            assertTrue(prompts.get(1).contains("valid json object"));
        } finally {
            server.stop(0);
        }
    }

    private static String response(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                + content + "\"}}]}";
    }
}
