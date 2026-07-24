package dev.pironi.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void processEnvironmentTakesPrecedenceOverDotenv() throws Exception {
        Path dotenv = Files.writeString(
                temporaryDirectory.resolve(".env"),
                "DEEPSEEK_API_KEY=file-key\n"
        );

        assertEquals(
                "environment-key",
                ApiKeyResolver.resolve(
                        Map.of("DEEPSEEK_API_KEY", "environment-key"),
                        "DEEPSEEK_API_KEY",
                        dotenv
                )
        );
    }

    @Test
    void acceptsExportAndQuotedDotenvValue() throws Exception {
        Path dotenv = Files.writeString(
                temporaryDirectory.resolve(".env"),
                "export DEEPSEEK_API_KEY=\"quoted-key\"\n"
        );

        assertEquals(
                "quoted-key",
                ApiKeyResolver.resolve(Map.of(), "DEEPSEEK_API_KEY", dotenv)
        );
    }

    @Test
    void doesNotUseAnotherVariable() throws Exception {
        Path dotenv = Files.writeString(
                temporaryDirectory.resolve(".env"),
                "OPENAI_API_KEY=wrong-key\n"
        );

        assertEquals(
                null,
                ApiKeyResolver.resolve(Map.of(), "DEEPSEEK_API_KEY", dotenv)
        );
    }
}
