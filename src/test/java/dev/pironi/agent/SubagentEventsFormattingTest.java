package dev.pironi.agent;

import dev.pironi.tool.SubagentResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentEventsFormattingTest {

    @Test
    void spawnBannerMentionsAgentAndKeepTypingHint() {
        String line = SubagentEvents.Formatting.spawn("research", "провери цените на 5 столици");
        assertTrue(line.contains("⏳ пускам агент «research»"));
        assertTrue(line.contains("продължавай да пишеш, ще те известя"));
    }

    @Test
    void spawnBannerTruncatesLongTask() {
        String longTask = "x".repeat(200);
        String line = SubagentEvents.Formatting.spawn("a", longTask);
        assertTrue(line.length() < 200, "banner stays short even for huge task text");
        assertTrue(line.contains("…"));
    }

    @Test
    void doneBannerMentionsCompletedResult() {
        String line = SubagentEvents.Formatting.done(
                SubagentResult.completed("sub_1", "research", "Цените: Берлин 100, Париж 120"),
                5L, "Цените: Берлин 100, Париж 120");
        assertTrue(line.contains("✅ агент «research» приключи за 5s"));
        assertTrue(line.contains("виж пълния резултат при следващото ти съобщение"));
    }

    @Test
    void errorBannerMentionsFailure() {
        String line = SubagentEvents.Formatting.done(
                SubagentResult.error("sub_1", "research", "timeout"),
                0L, "timeout");
        assertTrue(line.contains("⚠️ агент «research» се провали: timeout"));
    }

    @Test
    void timeoutBannerMentionsInterrupt() {
        String line = SubagentEvents.Formatting.done(
                new SubagentResult("sub_1", "research", "timeout", "stuck",
                        java.util.List.of(), java.time.Duration.ofSeconds(120)),
                120L, "stuck");
        assertTrue(line.contains("⏱ агент «research» изтече (120s) — прекратен"));
        assertTrue(line.contains("не е приключил"));
    }
}
