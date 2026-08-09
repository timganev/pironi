package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsCreateToolTest {
    @TempDir Path root;

    @Test void writesStableEscapedCrLfCalendar() throws Exception {
        ToolResult result = new IcsCreateTool(new Workspace(root)).execute(new ObjectMapper().readTree("""
                {"path":"out/milestones.ics","events":[{"uid":"phoenix-1@test","startUtc":"20260810T090000Z","endUtc":"20260810T093000Z","summary":"Review, phase 1","description":"Risk; owner\\\\team"}]}
                """));
        assertTrue(result.success(), result.output());
        String output = Files.readString(root.resolve("out/milestones.ics"));
        assertTrue(output.contains("SUMMARY:Review\\, phase 1\r\n"));
        assertTrue(output.contains("DESCRIPTION:Risk\\; owner\\\\team\r\n"));
        assertEquals(1, output.split("BEGIN:VEVENT", -1).length - 1);
    }
}
