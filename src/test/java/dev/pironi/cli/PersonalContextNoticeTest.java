package dev.pironi.cli;

import dev.pironi.agent.AgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalContextNoticeTest {
    @TempDir Path home;

    private String noticeFor(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args, java.util.Map.of("DEEPSEEK_API_KEY", "x"));
        // Empty context is what the loader returns when it skipped the personal files.
        AgentContext context = new AgentContext("", "", "");
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            PironiMain.warnAboutSkippedPersonalContext(options, context, true);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private String[] base(String... extra) {
        String[] head = {"--provider", "deepseek", "--model", "deepseek-v4-flash",
                "--pironi-home", home.toString(), "--workspace", home.toString()};
        String[] all = new String[head.length + extra.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(extra, 0, all, head.length, extra.length);
        return all;
    }

    @Test void tellsTheUserWhenAPersonaWasWrittenButNotLoaded() throws Exception {
        Files.writeString(home.resolve("SOUL.md"), "# Persona\nReply in Bulgarian.");
        String output = noticeFor(base());
        assertTrue(output.contains("SOUL.md/USER.md were not loaded"), output);
        assertTrue(output.contains("--personal-context allow"), output);
        assertTrue(output.contains("deepseek"), "must name where the contents would go: " + output);
    }

    @Test void staysQuietWhenThereIsNoPersonaToLoad() throws Exception {
        assertFalse(noticeFor(base()).contains("SOUL.md"), "nothing to warn about");
    }

    @Test void staysQuietWhenTheUserAlreadyAllowedPersonalContext() throws Exception {
        Files.writeString(home.resolve("SOUL.md"), "# Persona");
        String output = noticeFor(base("--personal-context", "allow"));
        assertFalse(output.contains("SOUL.md"), "the files are loaded, so there is nothing to say");
    }

    @Test void staysQuietWhenTheUserDeniedPersonalContextOnPurpose() throws Exception {
        Files.writeString(home.resolve("SOUL.md"), "# Persona");
        String output = noticeFor(base("--personal-context", "deny"));
        assertFalse(output.contains("SOUL.md"), "an explicit deny is a decision, not a surprise");
    }
}
