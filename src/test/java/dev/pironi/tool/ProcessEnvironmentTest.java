package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEnvironmentTest {
    @Test
    void preservesWindowsStylePathKeyAndPrependsCurrentJava() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("Path", "C:\\Windows\\System32;C:\\Windows");

        ProcessEnvironment.useCurrentJavaRuntime(environment);

        String javaBin = Path.of(System.getProperty("java.home"), "bin").toString();
        assertEquals(javaBin + File.pathSeparator + "C:\\Windows\\System32;C:\\Windows",
                environment.get("Path"));
        assertFalse(environment.containsKey("PATH"));
        assertTrue(environment.containsKey("JAVA_HOME"));
    }

    @Test
    void usesExistingCaseForJavaHomeToo() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("java_home", "old");

        ProcessEnvironment.useCurrentJavaRuntime(environment);

        assertEquals(System.getProperty("java.home"), environment.get("java_home"));
        assertFalse(environment.containsKey("JAVA_HOME"));
    }
}
