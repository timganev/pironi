package dev.pironi.tool;

import dev.pironi.safety.AccessGrants;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, Tool> tools;
    private final AccessGrants grants;
    private volatile boolean readOnly;

    public ToolRegistry(Collection<? extends Tool> tools) {
        this(tools, new AccessGrants());
    }

    /**
     * Tools denied at startup stay in the map but are marked disabled in {@code grants}, so the
     * user can re-enable them mid-session instead of restarting.
     */
    public ToolRegistry(Collection<? extends Tool> tools, AccessGrants grants) {
        this.grants = grants == null ? new AccessGrants() : grants;
        Map<String, Tool> indexed = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool previous = indexed.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool: " + tool.name());
            }
        }
        this.tools = Map.copyOf(indexed);
    }

    public Optional<Tool> find(String name) {
        Tool tool = tools.get(name);
        return tool == null || (readOnly && tool.mutating()) || grants.isToolDisabled(name)
                ? Optional.empty() : Optional.of(tool);
    }

    public Collection<Tool> all() {
        return tools.values().stream()
                .filter(tool -> !(readOnly && tool.mutating()))
                .filter(tool -> !grants.isToolDisabled(tool.name()))
                .toList();
    }

    /** Every tool built into this session, including ones policy currently blocks. */
    public Collection<Tool> allImplemented() {
        return tools.values();
    }

    /** @return true when the name belongs to a real tool that policy is currently blocking */
    public boolean isBlocked(String name) {
        return tools.containsKey(name) && grants.isToolDisabled(name);
    }

    public AccessGrants grants() {
        return grants;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}
