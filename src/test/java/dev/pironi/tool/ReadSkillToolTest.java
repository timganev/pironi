package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.session.SkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadSkillToolTest {
    @TempDir
    Path home;

    @Test
    void readsASkillTheChosenOnePointsAt() throws Exception {
        SkillStore store = new SkillStore(home);
        store.save("email-triage", "---\ndescription: Triage\n---\nFollow the story, not a word.");
        ReadSkillTool tool = new ReadSkillTool(store);

        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("name", "email-triage"));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("Follow the story"), result.output());
    }

    @Test
    void listsWhatIsThereWhenAskedForNothingInParticular() throws Exception {
        SkillStore store = new SkillStore(home);
        store.save("one", "---\ndescription: The first thing\n---\nBody");
        store.save("two", "---\ndescription: The second thing\n---\nBody");
        ReadSkillTool tool = new ReadSkillTool(store);

        ToolResult result = tool.execute(new ObjectMapper().createObjectNode());

        assertTrue(result.output().contains("one: The first thing"), result.output());
        assertTrue(result.output().contains("two: The second thing"), result.output());
    }

    @Test
    void namesTheAlternativesRatherThanSayingNotFound() throws Exception {
        // "No such skill" reads as "there are none", and the next move is to stop looking.
        SkillStore store = new SkillStore(home);
        store.save("email-triage", "---\ndescription: Triage\n---\nBody");
        ReadSkillTool tool = new ReadSkillTool(store);

        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("name", "mail-triage"));

        assertFalse(result.success());
        assertTrue(result.output().contains("email-triage"), result.output());
    }

    @Test
    void isAReadAndSaysSo() throws Exception {
        ReadSkillTool tool = new ReadSkillTool(new SkillStore(home));

        assertFalse(tool.mutating());
        assertTrue(tool.description().contains("follow a reference"), tool.description());
    }
}
