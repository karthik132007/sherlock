import re

with open('app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java', 'r') as f:
    content = f.read()

settings_dialog = """
    private void showSettingsDialog() {
        if (currentCase == null) return;
        
        Dialog<com.sherlock.ui.model.LlmConfigDto> dialog = new Dialog<>();
        dialog.setTitle("Case Settings");
        dialog.setHeaderText("Update LLM Settings for Case: " + currentCase.getCaseId());

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialogPane.setStyle("-fx-background-color: #121824; -fx-text-fill: white;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField provider = new TextField();
        provider.setPromptText("openai, ollama, deepseek, etc.");
        TextField model = new TextField();
        model.setPromptText("Model name (e.g. gpt-4o-mini)");
        TextField baseUrl = new TextField();
        baseUrl.setPromptText("API Base URL (optional)");
        PasswordField apiKey = new PasswordField();
        apiKey.setPromptText("API Key (optional)");
        
        // Pre-fill existing config if available
        if (currentCase.getLlmConfig() != null) {
            if (currentCase.getLlmConfig().getProvider() != null) provider.setText(currentCase.getLlmConfig().getProvider());
            if (currentCase.getLlmConfig().getModel() != null) model.setText(currentCase.getLlmConfig().getModel());
            if (currentCase.getLlmConfig().getBaseUrl() != null) baseUrl.setText(currentCase.getLlmConfig().getBaseUrl());
            if (currentCase.getLlmConfig().getApiKey() != null) apiKey.setText(currentCase.getLlmConfig().getApiKey());
        }

        String labelStyle = "-fx-text-fill: white;";
        String fieldStyle = "-fx-control-inner-background: #1e293b; -fx-text-fill: white;";
        provider.setStyle(fieldStyle);
        model.setStyle(fieldStyle);
        baseUrl.setStyle(fieldStyle);
        apiKey.setStyle(fieldStyle);

        Label provLbl = new Label("Provider:"); provLbl.setStyle(labelStyle);
        Label modLbl = new Label("Model:"); modLbl.setStyle(labelStyle);
        Label urlLbl = new Label("Base URL:"); urlLbl.setStyle(labelStyle);
        Label keyLbl = new Label("API Key:"); keyLbl.setStyle(labelStyle);

        grid.add(provLbl, 0, 0);
        grid.add(provider, 1, 0);
        grid.add(modLbl, 0, 1);
        grid.add(model, 1, 1);
        grid.add(urlLbl, 0, 2);
        grid.add(baseUrl, 1, 2);
        grid.add(keyLbl, 0, 3);
        grid.add(apiKey, 1, 3);

        dialogPane.setContent(grid);
        Platform.runLater(provider::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                com.sherlock.ui.model.LlmConfigDto config = new com.sherlock.ui.model.LlmConfigDto();
                config.setProvider(provider.getText().trim());
                config.setModel(model.getText().trim());
                config.setBaseUrl(baseUrl.getText().trim());
                config.setApiKey(apiKey.getText().trim());
                return config;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(config -> {
            client.updateLlmConfigAsync(currentCase.getCaseId(), config).thenAccept(updatedCase -> {
                Platform.runLater(() -> {
                    this.currentCase = updatedCase;
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "Settings updated successfully.");
                    alert.show();
                });
            }).exceptionally(ex -> {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to update settings: " + ex.getMessage());
                    alert.show();
                });
                return null;
            });
        });
    }
}
"""

content = re.sub(r'}\s*$', settings_dialog, content)

with open('app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java', 'w') as f:
    f.write(content)
