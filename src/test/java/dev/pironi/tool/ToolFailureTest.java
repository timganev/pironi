package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureTest {

    @Test
    void saysWhatWentWrongAndNotOnlyWhere() {
        // Run 2026-08-23T0232: "Failed read_file - C:\...\outlook_top5.json" was the whole message,
        // because that is what getMessage() returns here. The agent read it as "something broke"
        // and rewrote a script it had just written.
        assertEquals("no such file or directory: C:\\work\\top5.json",
                ToolFailure.describe(new NoSuchFileException("C:\\work\\top5.json")));
    }

    @Test
    void separatesRefusalFromAbsence() {
        assertEquals("access denied: /etc/shadow",
                ToolFailure.describe(new AccessDeniedException("/etc/shadow")));
    }

    @Test
    void keepsTheReasonWhenTheSystemGivesOne() {
        // Windows puts the useful part here - which process holds the file - and it must survive.
        FileSystemException locked = new FileSystemException(
                "C:\\Users\\t\\mailbox.ost", null,
                "The process cannot access the file because it is being used by another process"
        );

        String described = ToolFailure.describe(locked);

        assertTrue(described.startsWith("The process cannot access the file"), described);
        assertTrue(described.endsWith("mailbox.ost"), described);
    }

    @Test
    void namesBothPathsOfAMove() {
        FileSystemException move = new FileSystemException("from.txt", "to.txt", "cross-device link");

        assertEquals("cross-device link: from.txt -> to.txt", ToolFailure.describe(move));
    }

    @Test
    void leavesAnOrdinaryMessageAlone() {
        assertEquals("path is outside the workspace",
                ToolFailure.describe(new IllegalArgumentException("path is outside the workspace")));
    }

    @Test
    void namesTheKindOfFaultWhenThereIsNoMessageAtAll() {
        assertEquals("IOException", ToolFailure.describe(new IOException()));
        assertEquals("failed for an unknown reason", ToolFailure.describe(null));
    }
}
