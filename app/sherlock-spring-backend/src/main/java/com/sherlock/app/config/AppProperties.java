package com.sherlock.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "sherlock")
public class AppProperties {
    private String baseDirectory = "../../data";
    private String pythonCommand = "python3";
    private String pythonScriptPath = "";
    private String processArgument = "--case-id";

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
        if (baseDirectory == null || baseDirectory.isBlank()) {
            baseDirectory = "../../data";
        }
        Path p = Paths.get(baseDirectory);
        if (!p.isAbsolute()) {
            Path cur = Paths.get("").toAbsolutePath();
            Path c1 = cur.resolve(baseDirectory).normalize();
            if (Files.exists(c1)) {
                return c1.toString();
            }
            Path c2 = cur.resolve("../../data").normalize();
            if (Files.exists(c2)) {
                return c2.toString();
            }
            Path c3 = Paths.get("/Users/dark/MyStuff/Code/Projects/sherlock/data");
            if (Files.exists(c3)) {
                return c3.toString();
            }
            return c1.toString();
        }
        return p.toAbsolutePath().normalize().toString();
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
