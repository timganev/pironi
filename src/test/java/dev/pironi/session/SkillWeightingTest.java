package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trigger is a promise about what a skill is for. A description is prose written for a person,
 * and any of its words can turn up in an unrelated sentence. They used to count the same, and it
 * showed both ways: "give me an outlook report for the week" named its subject outright, matched
 * on one word, and applied nothing - while "git status" reached a skill whose description happens
 * to mention status reports. Two of ten real questions reached the right skill.
 */
class SkillWeightingTest {
    @TempDir Path home;

    private void skill(String name, String description, String triggers) throws Exception {
        Path dir = Files.createDirectories(home.resolve("skills").resolve(name));
        StringBuilder md = new StringBuilder("# " + name + "\n\ndescription: " + description + "\n");
        if (triggers != null) md.append("triggers: ").append(triggers).append('\n');
        Files.writeString(dir.resolve("SKILL.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private SkillStore.SkillDecision decide(String task) throws Exception {
        return new SkillStore(home).decide(task);
    }

    @Test
    void oneWordOfPromiseIsEnough() throws Exception {
        skill("outlook-map", "Where mail and calendar data lives.", "outlook, mailbox, calendar");

        SkillStore.SkillDecision decision = decide("give me an outlook report for the week");

        assertEquals("chosen", decision.reason(), decision.scores().toString());
        assertEquals("outlook-map", decision.chosen().orElseThrow().name());
    }

    @Test
    void oneWordOfProseIsNot() throws Exception {
        // "status" is in the description because a human had to be told what this does. It is not
        // a claim that every question containing the word belongs here.
        skill("team-lead", "Windows workflows for Planner, CSV and status reports.", null);

        SkillStore.SkillDecision decision = decide("git status and then commit");

        assertEquals("below-threshold", decision.reason(), decision.scores().toString());
        assertTrue(decision.chosen().isEmpty());
    }

    @Test
    void twoWordsOfProseStillReachTheThreshold() throws Exception {
        // Prose is weaker, not worthless: enough of it still says the subject is this one.
        skill("team-lead", "Windows workflows for Planner, CSV and status reports.", null);

        assertEquals("chosen", decide("write the planner status report").reason());
    }

    @Test
    void aSkillNameCountsAsAPromiseToo() throws Exception {
        // The directory name is chosen as deliberately as any trigger.
        skill("weather-forecast", "Nothing here says what it is about.", null);

        assertEquals("chosen", decide("what is the weather tomorrow").reason());
    }

    @Test
    void theWrongSkillIsStillNotChosen() throws Exception {
        skill("outlook-map", "Where mail and calendar data lives.", "outlook, mailbox, calendar");
        skill("team-lead", "Windows workflows for Planner, CSV and status reports.", null);

        for (String unrelated : new String[]{
                "fix this java test", "write a sorting function", "install a maven dependency"}) {
            assertEquals("no-match", decide(unrelated).reason(), unrelated);
        }
    }

    @Test
    void twoSkillsPromisingTheSameWordAreATie() throws Exception {
        // Ambiguity is two skills each making the same claim, and picking one of them silently
        // would be a guess dressed as a decision.
        skill("first-calendar", "One calendar tool.", "calendar, meetings");
        skill("second-calendar", "Another calendar tool.", "calendar, meetings");

        assertEquals("tie", decide("show me the calendar meetings").reason());
    }
}
