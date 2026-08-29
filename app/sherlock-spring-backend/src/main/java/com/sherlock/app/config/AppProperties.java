package com.sherlock.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sherlock")
public class AppProperties {
    private String baseDirectory = System.getProperty("user.home") + "/Documents/Sherlock";
    private String pythonCommand = "python3";
    private String pythonScriptPath = "";
    private String processArgument = "--case-id";

    public String getBaseDirectory() { return baseDirectory; }
    public void setBaseDirectory(String baseDirectory) { this.baseDirectory = baseDirectory; }
    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
    public String getPythonScriptPath() { return pythonScriptPath; }
    public void setPythonScriptPath(String pythonScriptPath) { this.pythonScriptPath = pythonScriptPath; }
    public String getProcessArgument() { return processArgument; }
    public void setProcessArgument(String processArgument) { this.processArgument = processArgument; }
}
