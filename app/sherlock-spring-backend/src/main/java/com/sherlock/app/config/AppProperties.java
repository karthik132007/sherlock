package com.sherlock.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "sherlock")
public class AppProperties {
    private String baseDirectory;
    private String pythonCommand = "python3";
    private String pythonScriptPath = "";
    private String processArgument = "--project";

    // Neo4j Configuration
    private Neo4jConfig neo4j = new Neo4jConfig();

    public static class Neo4jConfig {
        private String uri = "bolt://localhost:7687";
        private String username = "neo4j";
        private String password = "password";
        private String database = "neo4j";
        private boolean enabled = true;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public String getBaseDirectory() {
        if (baseDirectory != null && !baseDirectory.isBlank() && !baseDirectory.equals("../../data")) {
            Path p = Paths.get(baseDirectory);
            p = ensureWritableDirectory(p);
            if (p != null) {
                return p.toAbsolutePath().normalize().toString();
            }
        }

        // 1. Check OneDrive Documents directory (for Windows systems syncing Documents with OneDrive)
        List<Path> candidates = new ArrayList<>();
        String oneDrive = System.getenv("OneDrive");
        if (oneDrive != null && !oneDrive.isBlank()) {
            candidates.add(Paths.get(oneDrive, "Documents", "Sherlock"));
        }
        String oneDriveConsumer = System.getenv("OneDriveConsumer");
        if (oneDriveConsumer != null && !oneDriveConsumer.isBlank()) {
            candidates.add(Paths.get(oneDriveConsumer, "Documents", "Sherlock"));
        }
        String oneDriveCommercial = System.getenv("OneDriveCommercial");
        if (oneDriveCommercial != null && !oneDriveCommercial.isBlank()) {
            candidates.add(Paths.get(oneDriveCommercial, "Documents", "Sherlock"));
        }
        candidates.add(Paths.get(System.getProperty("user.home"), "OneDrive", "Documents", "Sherlock"));

        // 2. Standard user Documents directory
        candidates.add(Paths.get(System.getProperty("user.home"), "Documents", "Sherlock"));

        // 3. Fallback: user home / Sherlock
        candidates.add(Paths.get(System.getProperty("user.home"), "Sherlock"));

        // 4. Fallback: current working directory / sherlock_data
        candidates.add(Paths.get("sherlock_data").toAbsolutePath());

        for (Path candidate : candidates) {
            Path parent = candidate.getParent();
            if (parent != null && Files.exists(parent)) {
                Path p = ensureWritableDirectory(candidate);
                if (p != null) {
                    baseDirectory = p.toAbsolutePath().normalize().toString();
                    return baseDirectory;
                }
            }
        }

        // Ultimate fallback
        Path fallback = ensureWritableDirectory(Paths.get(System.getProperty("user.home"), "Documents", "Sherlock"));
        if (fallback == null) {
            fallback = Paths.get(System.getProperty("user.home"), "Sherlock");
        }
        baseDirectory = fallback.toAbsolutePath().normalize().toString();
        return baseDirectory;
    }
    
    private Path ensureWritableDirectory(Path dir) {
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            // Check if we can actually write to it (e.g. by creating a temp file or just checking isWritable)
            if (Files.isWritable(dir)) {
                return dir;
            }
        } catch (Exception e) {
            // Access denied or IO error
        }
        return null;
    }

    public void setBaseDirectory(String baseDirectory) { this.baseDirectory = baseDirectory; }
    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
    public String getPythonScriptPath() { return pythonScriptPath; }
    public void setPythonScriptPath(String pythonScriptPath) { this.pythonScriptPath = pythonScriptPath; }
    public String getProcessArgument() { return processArgument; }
    public void setProcessArgument(String processArgument) { this.processArgument = processArgument; }
    public Neo4jConfig getNeo4j() { return neo4j; }
    public void setNeo4j(Neo4jConfig neo4j) { this.neo4j = neo4j; }
}
