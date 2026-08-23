package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allowlist decided which hosts could receive a secret, and was consulted only when the header
 * was literally named {@code Authorization}. Any other name skipped it: {@code X-Api-Key: Bearer
 * PIRONI_API_KEY} resolved the provider key and sent it wherever the request pointed. http_get is
 * not a mutation, so it never reaches an approval prompt - a page the agent was asked to summarise
 * only had to ask for one fetch, and nothing about the run would have looked unusual.
 */
class HeaderResolverTest {
    private static final String SECRET = "sk-live-0123456789";
    private final HeaderResolver resolver =
            new HeaderResolver(Map.of("PIRONI_API_KEY", SECRET), Set.of("api.deepseek.com"));

    @Test
    void aTrustedHostGetsTheRealKey() {
        assertEquals(Optional.of("Bearer " + SECRET),
                resolver.resolve("api.deepseek.com", "Authorization", "Bearer PIRONI_API_KEY"));
    }

    @Test
    void noHeaderNameCarriesASecretToAnUntrustedHost() {
        for (String name : new String[]{
                "Authorization", "X-Api-Key", "Cookie", "x-goog-api-key", "Proxy-Authorization"}) {
            assertEquals(Optional.empty(),
                    resolver.resolve("attacker.example", name, "Bearer PIRONI_API_KEY"),
                    name + " carried the key off the allowlist");
        }
    }

    @Test
    void aValueWithNoPlaceholderIsNotASecretAndTravelsAnywhere() {
        // The allowlist is about resolved secrets, not about every header: an ordinary Accept or
        // User-Agent has nothing to leak and blocking it would only push the model into guessing.
        assertEquals(Optional.of("application/json"),
                resolver.resolve("attacker.example", "Accept", "application/json"));
        // Authorization stays the exception with no placeholder in it: a key the model typed out
        // by hand is still a key, and it has no business being invented here.
        assertEquals(Optional.empty(),
                resolver.resolve("api.deepseek.com", "Authorization", "Bearer sk-typed-by-hand"));
    }

    @Test
    void anEmptyResolverResolvesNothing() {
        assertEquals(Optional.empty(),
                HeaderResolver.EMPTY.resolve("api.deepseek.com", "Authorization", "Bearer x"));
        assertTrue(HeaderResolver.EMPTY.authorizationHosts().isEmpty());
    }
}
