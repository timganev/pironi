package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyCommandTest {
    @Test
    void looksAreNotApprovals() {
        assertTrue(ReadOnlyCommand.isReadOnly("grep -n 'ANF-4467' ACTIVE.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("sed -n '/^## x/,/^## /p' ACTIVE.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("awk '/^## /{print}' ACTIVE.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("ls -la src | head -20"));
        assertTrue(ReadOnlyCommand.isReadOnly("git log --oneline -5"));
        assertTrue(ReadOnlyCommand.isReadOnly("find . -name '*.md' | wc -l"));
        assertTrue(ReadOnlyCommand.isReadOnly("cat notes.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("/usr/bin/grep -c foo bar.txt"));
    }

    @Test
    void aRedirectionIsAWrite() {
        assertFalse(ReadOnlyCommand.isReadOnly("grep foo bar.txt > out.txt"));
        assertFalse(ReadOnlyCommand.isReadOnly("echo hi >> log.txt"));
        assertFalse(ReadOnlyCommand.isReadOnly("awk '{print > \"out\"}' in.txt"));
        assertFalse(ReadOnlyCommand.isReadOnly("cat a.txt | tee b.txt"));
    }

    @Test
    void aReaderThatCanWriteIsNotAReader() {
        // sed edits in place, find deletes and runs whatever it is told to.
        assertFalse(ReadOnlyCommand.isReadOnly("sed -i '' 's/a/b/' notes.md"));
        assertFalse(ReadOnlyCommand.isReadOnly("sed -i.bak 's/a/b/' notes.md"));
        assertFalse(ReadOnlyCommand.isReadOnly("find . -name '*.tmp' -delete"));
        assertFalse(ReadOnlyCommand.isReadOnly("find . -exec rm {} ;"));
        assertFalse(ReadOnlyCommand.isReadOnly("git commit -m x"));
        assertFalse(ReadOnlyCommand.isReadOnly("git push"));
    }

    @Test
    void anythingThatHidesAnotherProgramIsAWrite() {
        assertFalse(ReadOnlyCommand.isReadOnly("cat $(which rm)"));
        assertFalse(ReadOnlyCommand.isReadOnly("echo `rm -rf x`"));
        assertFalse(ReadOnlyCommand.isReadOnly("find . -name '*.md' | xargs rm"));
        assertFalse(ReadOnlyCommand.isReadOnly("rm -rf build"));
        assertFalse(ReadOnlyCommand.isReadOnly("mvn clean verify"));
        assertFalse(ReadOnlyCommand.isReadOnly("grep foo a.txt && rm a.txt"));
        assertFalse(ReadOnlyCommand.isReadOnly(""));
        assertFalse(ReadOnlyCommand.isReadOnly(null));
    }
}
