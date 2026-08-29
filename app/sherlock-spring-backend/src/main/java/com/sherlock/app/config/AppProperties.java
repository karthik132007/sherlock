package com.sherlock.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        if (baseDirectory == null || baseDirectory.isBlank() || baseDirectory.equals("../../data")) {
            baseDirectory = Paths.get(System.getProperty("user.home"), "Documents", "Sherlock").toString();
        }
        Path p = Paths.get(baseDirectory);
        
        p = ensureWritableDirectory(p);
        if (p == null) {
            // Fallback 1: user.home/Sherlock (avoids Windows Controlled Folder Access on Documents)
            p = ensureWritableDirectory(Paths.get(System.getProperty("user.home"), "Sherlock"));
        }
        if (p == null) {
            // Fallback 2: current directory / sherlock_data
            p = ensureWritableDirectory(Paths.get("sherlock_data").toAbsolutePath());
        }
        if (p == null) {
            // Ultimate fallback (might still fail later, but we tried)
            p = Paths.get(System.getProperty("user.home"), "Sherlock");
        }
        
        baseDirectory = p.toAbsolutePath().normalize().toString();
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
