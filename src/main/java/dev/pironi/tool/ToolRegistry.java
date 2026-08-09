package dev.pironi.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, Tool> tools;
    private volatile boolean readOnly;

    public ToolRegistry(Collection<? extends Tool> tools) {
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
        return tool == null || (readOnly && tool.mutating())
                ? Optional.empty() : Optional.of(tool);
    }

    public Collection<Tool> all() {
        return readOnly
                ? tools.values().stream().filter(tool -> !tool.mutating()).toList()
                : tools.values();
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}
