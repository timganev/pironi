package dev.pironi.safety;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.tool.Tool;

@FunctionalInterface
public interface ApprovalPolicy {
    ApprovalDecision decide(Tool tool, JsonNode arguments);
}
