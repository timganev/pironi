package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppControlToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void statusLaunchAndGracefulCloseUseOnlyAllowlistedApp() {
        FakeBackend backend = new FakeBackend();
        AppControlTool tool = new AppControlTool(backend);

        assertTrue(tool.execute(args("firefox", "status")).output().contains("not running"));
        assertTrue(tool.execute(args("firefox", "new-window")).success());
        assertTrue(backend.newWindow);
        backend.running = 2;
        assertTrue(tool.execute(args("firefox", "close")).success());
        assertTrue(backend.closeCalled);
    }

    @Test void rejectsUnknownAppActionAndInjection() {
        AppControlTool tool = new AppControlTool(new FakeBackend());
        assertFalse(tool.execute(args("firefox; rm -rf /", "close")).success());
        assertFalse(tool.execute(args("firefox", "force-close")).success());
        assertFalse(tool.execute(args("calculator", "launch")).success());
    }

    @Test void gracefulFailureNeverEscalatesToForce() {
        FakeBackend backend = new FakeBackend();
        backend.running = 1;
        backend.remaining = 1;
        ToolResult result = new AppControlTool(backend).execute(args("firefox", "close"));
        assertFalse(result.success());
        assertTrue(result.output().contains("force-close was not attempted"));
    }

    @Test void supportsCommonDesktopAndSystemApplicationsWithoutFreeFormNames() {
        AppControlTool tool = new AppControlTool(new FakeBackend());
        assertTrue(tool.execute(args("slack", "status")).success());
        assertTrue(tool.execute(args("image-viewer", "close")).success());
        assertTrue(tool.execute(args("settings", "launch")).success());
        assertFalse(tool.execute(args("SystemSettings.exe", "close")).success());
    }

    @ParameterizedTest
    @ValueSource(strings = {"google chrome", "google-chrome", "microsoft edge", "code",
            "visual studio code", "photos", "photo viewer", "imageviewer",
            "system settings", "windows settings"})
    void normalizesOnlyExplicitSafeAliases(String alias) {
        assertTrue(new AppControlTool(new FakeBackend()).execute(args(alias, "status")).success());
    }

    @ParameterizedTest
    @ValueSource(strings = {"slack", "obsidian", "image-viewer", "settings"})
    void rejectsUnsupportedNewWindowInsteadOfClaimingItOpened(String application) {
        ToolResult result = new AppControlTool(new FakeBackend())
                .execute(args(application, "new-window"));
        assertFalse(result.success());
        assertTrue(result.output().contains("new-window is not supported"));
    }

    @Test void launchResultDoesNotClaimThatAWindowWasObserved() {
        ToolResult result = new AppControlTool(new FakeBackend()).execute(args("settings", "launch"));
        assertTrue(result.success());
        assertTrue(result.output().contains("window not verified"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode args(String app, String action) {
        return mapper.createObjectNode().put("application", app).put("action", action);
    }

    private static final class FakeBackend implements AppControlTool.Backend {
        int running;
        int remaining;
        boolean newWindow;
        boolean closeCalled;
        public int running(AppControlTool.App app) { return running; }
        public void launch(AppControlTool.App app, boolean newWindow) { this.newWindow = newWindow; }
        public void close(AppControlTool.App app) { closeCalled = true; running = remaining; }
        public int awaitStopped(AppControlTool.App app, Duration timeout) { return remaining; }
    }
}
