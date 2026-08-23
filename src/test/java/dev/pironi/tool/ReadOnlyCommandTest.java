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
        assertTrue(ReadOnlyCommand.isReadOnly("find . -name '*.md' | wc -l", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("cat notes.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("/usr/bin/grep -c foo bar.txt"));
    }

    @Test
    void cmdExeHasItsOwnReaders() {
        // Without these the classifier called every native Windows command a write, so the
        // wider reach and the absent prompt were macOS and Linux features only.
        assertTrue(ReadOnlyCommand.isReadOnly("dir /b src", "Windows 11"));
        assertTrue(ReadOnlyCommand.isReadOnly("type notes.txt", "Windows 11"));
        assertTrue(ReadOnlyCommand.isReadOnly("findstr /s TODO *.java", "Windows 11"));
        assertTrue(ReadOnlyCommand.isReadOnly("tasklist /FO CSV", "Windows 11"));
        assertTrue(ReadOnlyCommand.isReadOnly("where java", "Windows 11"));
        // find on Windows is a text search, not the Unix walker.
        assertTrue(ReadOnlyCommand.isReadOnly("find \"TODO\" notes.txt", "Windows 11"));
        assertTrue(ReadOnlyCommand.isReadOnly("C:\\tools\\findstr.exe x f.txt", "Windows 11"));

        assertFalse(ReadOnlyCommand.isReadOnly("del notes.txt", "Windows 11"));
        assertFalse(ReadOnlyCommand.isReadOnly("copy a b", "Windows 11"));
        assertFalse(ReadOnlyCommand.isReadOnly("dir /b > out.txt", "Windows 11"));
        assertFalse(ReadOnlyCommand.isReadOnly("cd C:\\secrets", "Windows 11"));
    }

    @Test
    void theUnixWalkerGuardIsUnixOnly() {
        assertFalse(ReadOnlyCommand.isReadOnly("find . -delete", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("find . -name '*.md'", "Linux"));
    }

    @Test
    void discardingOutputIsNotWriting() {
        // The exact commands a live session was asked to approve four times for one edit.
        assertTrue(ReadOnlyCommand.isReadOnly("find . -maxdepth 3 -name ACTIVE.md 2>/dev/null", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("grep -n 'Tim-4' ACTIVE.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("sed -n '/^## .*Tim-3/,/^## [^#]/p' ACTIVE.md"));
        assertTrue(ReadOnlyCommand.isReadOnly("ls -la 2>/dev/null"));
        assertTrue(ReadOnlyCommand.isReadOnly("dir /b 2>NUL", "Windows 11"));

        // A discard is the only redirect that keeps a reader a reader.
        assertFalse(ReadOnlyCommand.isReadOnly("grep x f.txt 2>/dev/null > out.txt"));
        assertFalse(ReadOnlyCommand.isReadOnly("cat f.txt > /dev/null/../out"));
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
        // find on Windows is a text search with no -delete, so this rule names its platform
        // instead of inheriting the runner's - it passed on Linux and failed on Windows.
        assertFalse(ReadOnlyCommand.isReadOnly("find . -name '*.tmp' -delete", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("find . -exec rm {} ;", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("git commit -m x"));
        assertFalse(ReadOnlyCommand.isReadOnly("git push"));
    }

    /**
     * A wrong answer here is not one prompt: {@code RunCommandTool.mutating} asks this, so false
     * means the call is not a mutation at all, and ConsoleApprovalPolicy lets a non-mutation
     * through in every approval mode - ask included. Each of these returned true.
     */
    @Test
    void aSecondCommandCannotRideOnTheFirstOne() {
        // A newline separates commands exactly as ";" does. bash runs both halves; the classifier
        // saw one call to a reader with unusual arguments.
        assertFalse(ReadOnlyCommand.isReadOnly("cat notes.txt\nrm -f notes.txt", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("grep -rn TODO src\nrm -rf build", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("ls\r\nrm x", "Linux"));
        // Still one command, still a read.
        assertTrue(ReadOnlyCommand.isReadOnly("grep -rn TODO src", "Linux"));
    }

    @Test
    void aTextToolWithAShellInsideItIsAWrite() {
        // The script is one quoted argument, so no shell metacharacter appears anywhere for the
        // escape check to find - the shell never sees them either. awk and sed run these
        // themselves.
        assertFalse(ReadOnlyCommand.isReadOnly("awk 'BEGIN{system(\"touch pwned\")}'", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("sed '1e touch pwned' file.txt", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("awk '{print $2}' data.txt", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("sed -n '1,20p' file.txt", "Linux"));
    }

    @Test
    void theGitSubcommandsThatListAreNotTheOnesThatWrite() {
        // branch, tag and remote were read-only whatever came after them.
        assertFalse(ReadOnlyCommand.isReadOnly("git branch -D main", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("git tag -d v0.9.4", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("git remote add evil https://example.invalid", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("git remote set-url origin https://example.invalid", "Linux"));

        assertTrue(ReadOnlyCommand.isReadOnly("git branch", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("git branch --list", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("git tag -l", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("git remote -v", "Linux"));
        assertTrue(ReadOnlyCommand.isReadOnly("git log --oneline -5", "Linux"));
    }

    @Test
    void anythingThatHidesAnotherProgramIsAWrite() {
        assertFalse(ReadOnlyCommand.isReadOnly("cat $(which rm)"));
        assertFalse(ReadOnlyCommand.isReadOnly("echo `rm -rf x`"));
        assertFalse(ReadOnlyCommand.isReadOnly("find . -name '*.md' | xargs rm", "Linux"));
        assertFalse(ReadOnlyCommand.isReadOnly("rm -rf build"));
        assertFalse(ReadOnlyCommand.isReadOnly("mvn clean verify"));
        assertFalse(ReadOnlyCommand.isReadOnly("grep foo a.txt && rm a.txt"));
        assertFalse(ReadOnlyCommand.isReadOnly(""));
        assertFalse(ReadOnlyCommand.isReadOnly(null));
    }
}
