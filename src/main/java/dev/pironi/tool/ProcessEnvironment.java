package dev.pironi.tool;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

public final class ProcessEnvironment {
    private ProcessEnvironment() {}

    public static void useCurrentJavaRuntime(Map<String, String> environment) {
        String javaHome = System.getProperty("java.home");
        putCaseInsensitive(environment, "JAVA_HOME", javaHome);
        String javaBin = Path.of(javaHome, "bin").toString();
        String pathKey = matchingKey(environment, "PATH");
        String path = pathKey == null ? "" : environment.getOrDefault(pathKey, "");
        environment.put(pathKey == null ? "PATH" : pathKey,
                path.isBlank() ? javaBin : javaBin + File.pathSeparator + path);
    }

    private static void putCaseInsensitive(Map<String, String> environment, String name, String value) {
        String existingKey = matchingKey(environment, name);
        environment.put(existingKey == null ? name : existingKey, value);
    }

    private static String matchingKey(Map<String, String> environment, String name) {
        return environment.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
