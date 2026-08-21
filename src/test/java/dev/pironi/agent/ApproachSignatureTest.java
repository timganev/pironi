package dev.pironi.agent;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reaching an eighteen-line method meant reading past thirteen hundred lines twice in a day. */
class ApproachSignatureTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void collapsesShellVariationsOntoTheProgramTheyRun() throws Exception {
        assertEquals("run_command:osascript", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"osascript -e 'tell app' | head -5\"}")
        )));
        assertEquals("run_command:osascript", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"# probe\\nosascript -e 'other wording'\"}")
        )));
        assertEquals("run_command:sqlite3", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"/usr/bin/sqlite3 store.db .tables\"}")
        )));
        assertEquals("read_file", ApproachSignature.approachSignature(new ToolCall(
                "read_file", objectMapper.readTree("{\"path\":\"a.txt\"}")
        )));
    }
    @Test
    void wallsOffSinglePurposeProgramsAndAimedInterpretersButNotBareOnes() {
        assertTrue(ApproachSignature.blockable("run_command:osascript"));
        assertTrue(ApproachSignature.blockable("run_command:sqlite3"));
        assertTrue(ApproachSignature.blockable("read_file"));
        // a bare interpreter is a capability, not an approach
        assertTrue(!ApproachSignature.blockable("run_command:python3"));
        assertTrue(!ApproachSignature.blockable("run_command:bash"));
        // aimed at something specific, it is an approach again
        assertTrue(ApproachSignature.blockable("run_command:python3:sqlite3"));
        assertTrue(ApproachSignature.blockable("run_command:bash:osascript"));
        // a search is as general as an interpreter: eleven fruitless finds for one thing said
        // nothing about a find for another, and blocking the program cost a run the one
        // directory it had not looked in
        assertTrue(!ApproachSignature.blockable("run_command:find"));
        assertTrue(!ApproachSignature.blockable("run_command:grep"));
        assertTrue(ApproachSignature.blockable("run_command:find:*calendar*"));
    }
    @Test
    void positioningPrefixesAreNotApproaches() throws Exception {
        // The agent writes "cd path && real_command" constantly - twelve of fifteen commands in
        // one run - and keying on the first word blocked "cd", and with it almost everything.
        assertEquals("run_command:sqlite3", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"cd \\\"/some/dir\\\" && sqlite3 db .tables\"}")
        )));
        assertEquals("run_command:gunzip", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"export X=1; gunzip -c a.gz\"}")
        )));
        // An assignment that wraps a substitution is whatever runs inside it.
        assertEquals("run_command:find:*.log", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"f=$(find . -name '*.log')\"}")
        )));
        // A line that only positions keeps the positioning word: repeating that alone is a dead
        // end of its own.
        assertEquals("run_command:cd", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"cd /some/dir\"}")
        )));
    }
    @Test
    void positioningPrefixesAreSkippedOnCmdExeToo() throws Exception {
        // cmd.exe positions with "cd /d", assigns with "set", and separates with a single "&".
        assertEquals("run_command:findstr", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree(
                        "{\"command\":\"cd /d C:\\\\Users\\\\me & findstr /s TODO *.java\"}")
        )));
        assertEquals("run_command:type", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"set X=1 && type notes.txt\"}")
        )));
        // A program named by a Windows path is still that program.
        assertEquals("run_command:sqlite3.exe", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree(
                        "{\"command\":\"C:\\\\tools\\\\sqlite3.exe db .tables\"}")
        )));
    }
    @Test
    void separatesWhatASearchIsLookingFor() throws Exception {
        assertEquals("run_command:find:*calendar*", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree(
                        "{\"command\":\"find ~/Library -name '*calendar*' | head -20\"}")
        )));
        // Same tree, different subject: these must not collapse into one blockable approach.
        assertEquals("run_command:find:*.olk15*", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"find ~/Library -name '*.olk15*'\"}")
        )));
        assertEquals("run_command:grep:Subject", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"grep -e Subject somefile.xml\"}")
        )));
    }
    @Test
    void separatesWhatAnInterpreterIsReachingFor() throws Exception {
        assertEquals("run_command:python3:sqlite3", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"python3 -c \\\"import sqlite3; q()\\\"\"}")
        )));
        assertEquals("run_command:python3:glob+gzip", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"python3 -c \\\"import gzip, glob\\\"\"}")
        )));
        assertEquals("run_command:python3:report.py", ApproachSignature.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"python3 scripts/report.py --days 2\"}")
        )));
        // single-purpose programs keep the plain signature
        assertEquals("run_command:sqlite3", ApproachSignature.approachSignature(new ToolCall(
                "run_command", objectMapper.readTree("{\"command\":\"sqlite3 store.db .tables\"}")
        )));
    }
}
