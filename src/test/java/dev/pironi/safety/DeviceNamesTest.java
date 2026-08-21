package dev.pironi.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeviceNamesTest {
    private static final String WINDOWS = "Windows 11";
    private static final String LINUX = "Linux";

    @Test
    void bareDeviceNamesAreRecognised() {
        assertEquals("NUL", DeviceNames.reservedName("NUL"));
        assertEquals("con", DeviceNames.reservedName("con"));
        assertEquals("COM1", DeviceNames.reservedName("COM1"));
        assertEquals("LPT9", DeviceNames.reservedName("LPT9"));
    }

    @Test
    void anExtensionDoesNotMakeADeviceNameAFilename() {
        // NUL.txt reaches the same device as NUL, which is what makes the write look like it
        // worked while producing an entry no Win32 path API can open.
        assertEquals("NUL", DeviceNames.reservedName("NUL.txt"));
        assertEquals("aux", DeviceNames.reservedName("aux.tar.gz"));
    }

    @Test
    void trailingSpacesAndDotsAreStrippedBeforeTheNameIsJudged() {
        assertEquals("NUL", DeviceNames.reservedName("NUL. "));
        assertEquals("con", DeviceNames.reservedName("con."));
    }

    @Test
    void ordinaryNamesThatMerelyStartTheSameWayAreLeftAlone() {
        assertNull(DeviceNames.reservedName("console.txt"));
        assertNull(DeviceNames.reservedName("nullable"));
        assertNull(DeviceNames.reservedName("com10"));
        assertNull(DeviceNames.reservedName("notes.txt"));
    }

    @Test
    void aDeviceNameAnywhereInThePathIsFound() {
        assertEquals("NUL", DeviceNames.reservedSegment(Path.of("docs/NUL.txt"), WINDOWS));
        assertEquals("con", DeviceNames.reservedSegment(Path.of("con/notes.txt"), WINDOWS));
        assertNull(DeviceNames.reservedSegment(Path.of("docs/notes.txt"), WINDOWS));
    }

    @Test
    void theRuleAppliesOnWindowsOnly() {
        // Elsewhere NUL.txt is an ordinary filename and refusing it would be wrong.
        assertNull(DeviceNames.reservedSegment(Path.of("docs/NUL.txt"), LINUX));
    }
}
