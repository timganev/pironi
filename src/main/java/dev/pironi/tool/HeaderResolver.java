package dev.pironi.tool;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns placeholder headers such as {@code "Bearer PIRONI_API_KEY"} into the real key, and decides
 * which hosts may receive a resolved secret at all - under any header name, not only
 * {@code Authorization}. The resolved value goes only into the outgoing request, never into the
 * {@code ToolResult}.
 */
public final class HeaderResolver {
    /** Soft cap on any header value so a placeholder substitution cannot blow the request. */
    private static final int MAX_HEADER_VALUE = 512;

    /** No-op resolver: never resolves Authorization headers, passes plain values through. */
    public static final HeaderResolver EMPTY = new HeaderResolver(Map.of(), Set.of());

    private final Map<String, String> placeholders;
    private final Set<String> authorizationHosts;

    public HeaderResolver(Map<String, String> placeholders, Set<String> authorizationHosts) {
        this.placeholders = Map.copyOf(new LinkedHashMap<>(placeholders));
        this.authorizationHosts = Set.copyOf(authorizationHosts);
    }

    /**
     * Resolves a value that may hold a placeholder, or returns empty when the header is not
     * permitted for {@code host}.
     *
     * @param host        the lowercase request host, e.g. {@code api.deepseek.com}
     * @param headerName  header name, matched case-insensitively
     * @param rawValue    the value the model supplied
     */
    public Optional<String> resolve(String host, String headerName, String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        boolean authorization = "authorization".equalsIgnoreCase(headerName);

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (!trimmed.contains(entry.getKey())) {
                continue;
            }
            // The allowlist used to be checked only when the header was named Authorization, so
            // "X-Api-Key: Bearer PIRONI_API_KEY" resolved the real key for any host at all - and
            // http_get is not a mutation, so it never reaches an approval prompt. A page the agent
            // was summarising only had to ask for one fetch. What decides is the value carrying a
            // secret, never the name someone put in front of it.
            if (!authorizationHosts.contains(host.toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            String resolved = entry.getValue();
            if (resolved == null || resolved.isBlank()) {
                return Optional.empty();
            }
            String substituted = trimmed.replace(entry.getKey(), resolved);
            if (substituted.length() > MAX_HEADER_VALUE) {
                return Optional.empty();
            }
            return Optional.of(substituted);
        }

        // No placeholder token was found.
        if (authorization) {
            return Optional.empty();
        }
        // Non-sensitive headers may carry plain values, with a sanity cap.
        if (trimmed.length() > MAX_HEADER_VALUE) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /** The set of header names (lowercase) a given host may carry. A value of {@code *} means any non-Authorization header. */
    public Set<String> authorizationHosts() {
        return authorizationHosts;
    }
}
