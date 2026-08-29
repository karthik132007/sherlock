package com.sherlock.app.model;

import jakarta.validation.constraints.NotBlank;

public class CaseRequest {

    @NotBlank(message = "Case name is required")
    private String caseName;

    public String getCaseName() {
        return caseName;
    }

    public void setCaseName(String caseName) {
        this.caseName = caseName;
    }
}
