package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessControlToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void terminationAlwaysRequiresExplicitApproval() {
        ProcessControlTool tool = new ProcessControlTool(new FakeBackend());
        assertTrue(tool.requiresExplicitApproval(args(9001, "worker", "terminate")));
        assertTrue(tool.requiresExplicitApproval(args(9001, "worker", "force-kill")));
    }

    @Test void terminatesExactlyMatchingSinglePid() {
        FakeBackend backend = new FakeBackend();
        backend.processes.put(9001L, new ProcessControlTool.ProcessIdentity("worker", user()));
        ProcessControlTool tool = new ProcessControlTool(backend);
        assertTrue(tool.validate(args(9001, "worker", "terminate")).success());
        assertTrue(tool.execute(args(9001, "worker", "terminate")).success());
        assertTrue(backend.terminated);
        assertFalse(backend.forced);
    }

    @Test void forceKillNeverHappensImplicitlyAfterTerminateFailure() {
        FakeBackend backend = new FakeBackend();
        backend.processes.put(9002L, new ProcessControlTool.ProcessIdentity("hung-worker", user()));
        backend.stops = false;
        ToolResult result = new ProcessControlTool(backend)
                .execute(args(9002, "hung-worker", "terminate"));
        assertFalse(result.success());
        assertTrue(backend.terminated);
        assertFalse(backend.forced);
    }

    @Test void rejectsPidReuseNameMismatchBeforeMutation() {
        FakeBackend backend = new FakeBackend();
        backend.processes.put(9003L, new ProcessControlTool.ProcessIdentity("replacement", user()));
        ToolResult result = new ProcessControlTool(backend)
                .execute(args(9003, "original", "force-kill"));
        assertFalse(result.success());
        assertTrue(result.output().contains("PID identity changed"));
        assertFalse(backend.forced);
    }

    @Test void rejectsCriticalForeignMissingAndMalformedTargets() {
        FakeBackend backend = new FakeBackend();
        backend.processes.put(9004L, new ProcessControlTool.ProcessIdentity("systemd", user()));
        backend.processes.put(9005L, new ProcessControlTool.ProcessIdentity("worker", "someone-else"));
        ProcessControlTool tool = new ProcessControlTool(backend);
        assertFalse(tool.validate(args(9004, "systemd", "terminate")).success());
        assertFalse(tool.validate(args(9005, "worker", "terminate")).success());
        assertFalse(tool.validate(args(9999, "gone", "terminate")).success());
        assertFalse(tool.validate(args(9006, "../worker", "terminate")).success());
        assertFalse(tool.validate(args(9006, "..\\worker", "terminate")).success());
        assertFalse(tool.validate(args(9006, "worker", "kill-tree")).success());
    }

    @Test void acceptsDomainQualifiedCurrentUserButProtectsPironiItself() {
        FakeBackend backend = new FakeBackend();
        backend.processes.put(9010L, new ProcessControlTool.ProcessIdentity(
                "worker.exe", "WORKSTATION\\" + user()));
        ProcessControlTool tool = new ProcessControlTool(backend);
        assertTrue(tool.validate(args(9010, "worker.exe", "terminate")).success());
        assertFalse(tool.validate(args(ProcessHandle.current().pid(), "java", "terminate")).success());
    }

    private ObjectNode args(long pid, String expectedName, String action) {
        return mapper.createObjectNode().put("pid", pid).put("expectedName", expectedName)
                .put("action", action);
    }

    private static String user() { return System.getProperty("user.name", ""); }

    private static final class FakeBackend implements ProcessControlTool.Backend {
        final Map<Long, ProcessControlTool.ProcessIdentity> processes = new HashMap<>();
        boolean terminated;
        boolean forced;
        boolean stops = true;
        public Optional<ProcessControlTool.ProcessIdentity> identity(long pid) {
            return Optional.ofNullable(processes.get(pid));
        }
        public boolean terminate(long pid) { terminated = true; return true; }
        public boolean force(long pid) { forced = true; return true; }
        public boolean awaitStopped(long pid, Duration timeout) { return stops; }
    }
}
