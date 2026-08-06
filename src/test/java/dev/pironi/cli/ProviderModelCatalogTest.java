package dev.pironi.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderModelCatalogTest {
    @Test
    void loadsInstalledOllamaModelsFromTagsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"qwen3.6:35b-a3b\"},{\"name\":\"gemma4:e4b\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CliOptions options = CliOptions.parse(
                    new String[]{
                            "--base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                            "--model", "qwen3.6:35b-a3b"
                    },
                    Map.of()
            );
            ProviderModelCatalog catalog = new ProviderModelCatalog(
                    HttpClient.newHttpClient(), new ObjectMapper()
            );

            assertEquals(
                    List.of("qwen3.6:35b-a3b", "gemma4:e4b"),
                    catalog.models(options)
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loadsDeepSeekModelsWithoutHardcodedAllowlist() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"deepseek-future-model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CliOptions options = CliOptions.parse(
                    new String[]{
                            "--provider", "deepseek",
                            "--base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                            "--model", "deepseek-v4-flash"
                    },
                    Map.of("DEEPSEEK_API_KEY", "secret")
            );
            ProviderModelCatalog catalog = new ProviderModelCatalog(
                    HttpClient.newHttpClient(), new ObjectMapper()
            );

            assertEquals(
                    List.of("deepseek-v4-flash", "deepseek-future-model"),
                    catalog.models(options)
            );
        } finally {
            server.stop(0);
        }
    }
}
