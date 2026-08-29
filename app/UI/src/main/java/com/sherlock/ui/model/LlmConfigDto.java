package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmConfigDto {

    @JsonProperty("provider")
    private String provider = "openai";

    @JsonProperty("model")
    private String model = "gpt-4o-mini";

    @JsonProperty("api_key")
    private String apiKey = "";

    @JsonProperty("base_url")
    private String baseUrl = "";

    @JsonProperty("context_window")
    private Integer contextWindow = 128000;

    @JsonProperty("temperature")
    private Double temperature = 0.1;

    public LlmConfigDto() {}

    public LlmConfigDto(String provider, String model, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
}
