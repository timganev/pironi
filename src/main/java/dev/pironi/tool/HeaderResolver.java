package dev.pironi.tool;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves placeholder header values (e.g. {@code "Bearer PIRONI_API_KEY"}) into the real
 * secrets that Pironi already holds (the configured provider API key), and enforces which
 * hosts may receive an {@code Authorization} header.
 *
 * <p>The model never sees nor types the real key: it writes a placeholder token, and this
 * resolver substitutes the value from {@link #placeholders}. The resolved value is added only
 * to the outgoing HTTP request — never to the {@code ToolResult} — so secrets cannot leak into
 * the trace or the conversation. {@code Authorization} is additionally gated by an explicit
 * allowlist of trusted API hosts to stop the model from shipping a token to an arbitrary site.
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
     * Resolves {@code rawValue} (which may contain a placeholder token such as
     * {@code PIRONI_API_KEY}) into the actual header value, or returns empty when the header
     * is not permitted for {@code host}.
     *
     * @param host        the lowercase request host (e.g. {@code api.deepseek.com})
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
            // Secrets only travel to explicitly trusted API hosts.
            if (authorization && !authorizationHosts.contains(host.toLowerCase(Locale.ROOT))) {
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

        // No placeholder token was found. Authorization headers must always come from
        // placeholders so the model can never hardcode a real credential into the request.
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
