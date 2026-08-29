package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ChatPanel extends VBox {

    private final SherlockBackendClient client;
    private String currentCaseId = "";

    private final ComboBox<String> providerSelector = new ComboBox<>();
    private final ComboBox<String> modelSelector = new ComboBox<>();
    private final VBox messageContainer = new VBox(12);
    private final ScrollPane scrollPane = new ScrollPane();
    private final TextField inputField = new TextField();
    private final Button sendButton = new Button("➤");
    private final ProgressIndicator typingIndicator = new ProgressIndicator();

    public ChatPanel(SherlockBackendClient client) {
        this.client = client;

        getStyleClass().add("dark-panel");
        setPadding(new Insets(16));
        setSpacing(10);
        setPrefWidth(360);
        setMinWidth(320);

        // Header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Chat Window");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label status = new Label("Sherlock AI");
        status.setStyle(
                "-fx-background-color: rgba(16, 185, 129, 0.2); -fx-text-fill: #34d399; -fx-font-size: 10px; -fx-padding: 2px 8px; -fx-background-radius: 10px;");

        header.getChildren().addAll(title, spacer, status);

        // Provider Selector Bar
        HBox providerRow = new HBox(6);
        providerRow.setAlignment(Pos.CENTER_LEFT);
        Label providerIcon = new Label("🔌 Provider:");
        providerIcon.setStyle("-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: #94a3b8;");

        providerSelector.setStyle(
                "-fx-font-size: 10.5px; -fx-background-color: #1e293b; -fx-text-fill: #a78bfa; -fx-background-radius: 6px;");
        providerSelector.setMaxWidth(120);
        providerSelector.getItems().addAll("Ollama", "OpenAI", "DeepSeek", "OpenRouter", "Groq", "Mistral", "Custom");
        providerSelector.setValue("Ollama");
        providerSelector.setOnAction(e -> applyProviderDefaults(providerSelector.getValue()));

        providerRow.getChildren().addAll(providerIcon, providerSelector);

        // Model Selector Bar
        HBox modelRow = new HBox(6);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        Label modelIcon = new Label("🤖 Model:");
        modelIcon.setStyle("-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: #94a3b8;");

        modelSelector.setEditable(true);
        modelSelector.setStyle(
                "-fx-font-size: 10.5px; -fx-background-color: #1e293b; -fx-text-fill: #38bdf8; -fx-background-radius: 6px;");
        modelSelector.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modelSelector, Priority.ALWAYS);

        // Populate models for the default provider (Ollama)
        applyProviderDefaults(providerSelector.getValue());

        modelRow.getChildren().addAll(modelIcon, modelSelector);

        // Quick suggestions
        FlowPane suggestions = buildQuickSuggestions();

        // Message List
        messageContainer.setPadding(new Insets(8));
        messageContainer.setFillWidth(true);

        scrollPane.setContent(messageContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        // Auto scroll to bottom
        messageContainer.heightProperty().addListener((obs, oldVal, newVal) -> scrollPane.setVvalue(1.0));

        // Input Area
        typingIndicator.setMaxSize(18, 18);
        typingIndicator.setVisible(false);

        inputField.setPromptText("Type your message...");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                sendMessage();
            }
        });

        sendButton.getStyleClass().add("primary-button");
        sendButton.setStyle("-fx-padding: 8px 14px; -fx-font-size: 13px;");
        sendButton.setOnAction(e -> sendMessage());

        HBox inputRow = new HBox(8, inputField, sendButton);
        inputRow.setAlignment(Pos.CENTER);

        getChildren().addAll(header, providerRow, modelRow, suggestions, scrollPane, inputRow);

        // Initial greeting
        addSherlockMessage(
                "Greetings. I am Sherlock, your investigation intelligence assistant. Ask me questions about persons of interest, call logs, witness statements, or chronological occurrences.");
    }

    public void setCaseId(String caseId) {
        this.currentCaseId = caseId;
        messageContainer.getChildren().clear();
        addSherlockMessage("Case **" + caseId
                + "** loaded. Knowledge graph and evidence inventory are synchronized. How may I assist your investigation?");
    }

    /**
     * Populates the model selector based on the chosen provider. When Ollama is
     * selected, the available local models are fetched from the backend (the
     * equivalent of `ollama list`); other providers get their conventional
     * default model lists.
     */
    private void applyProviderDefaults(String provider) {
        if (provider == null || provider.isBlank()) {
            return;
        }

        if ("Ollama".equalsIgnoreCase(provider)) {
            modelSelector.getItems().clear();
            modelSelector.setValue(null);
            modelSelector.setPromptText("Loading Ollama models...");
            client.getOllamaModelsAsync().thenAccept(models -> Platform.runLater(() -> {
                if (models != null && !models.isEmpty()) {
                    modelSelector.getItems().setAll(models);
                    modelSelector.setValue(models.get(0));
                    modelSelector.setDisable(false);
                    modelSelector.setPromptText(null);
                } else {
                    modelSelector.getItems().clear();
                    modelSelector.setValue(null);
                    modelSelector.setDisable(true);
                    modelSelector.setPromptText("No Ollama models found (is Ollama running?)");
                }
            }));
            return;
        }

        modelSelector.setDisable(false);
        modelSelector.setPromptText(null);
        if ("DeepSeek".equalsIgnoreCase(provider)) {
            modelSelector.getItems().setAll("deepseek-chat", "deepseek-coder");
            modelSelector.setValue("deepseek-chat");
        } else if ("Groq".equalsIgnoreCase(provider)) {
            modelSelector.getItems().setAll("llama-3.3-70b-versatile", "mixtral-8x7b-32768");
            modelSelector.setValue("llama-3.3-70b-versatile");
        } else if ("OpenRouter".equalsIgnoreCase(provider)) {
            modelSelector.getItems().setAll("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "meta-llama/llama-3.3-70b-instruct");
            modelSelector.setValue("openai/gpt-4o-mini");
        } else if ("Mistral".equalsIgnoreCase(provider)) {
            modelSelector.getItems().setAll("mistral-large-latest", "mistral-small-latest");
            modelSelector.setValue("mistral-large-latest");
        } else {
            // OpenAI / Custom
            modelSelector.getItems().setAll("gpt-4o-mini", "gpt-4o", "gpt-4-turbo");
            modelSelector.setValue("gpt-4o-mini");
        }
    }

    private FlowPane buildQuickSuggestions() {
        FlowPane pane = new FlowPane(6, 6);
        pane.getChildren().add(createSuggestionPill("Show calls related to Arjun"));
        pane.getChildren().add(createSuggestionPill("What are the key timeline events?"));
        pane.getChildren().add(createSuggestionPill("Who was near Rose?"));
        return pane;
    }

    private Button createSuggestionPill(String query) {
        Button btn = new Button(query);
        btn.setStyle(
                "-fx-background-color: #1e293b; -fx-text-fill: #94a3b8; -fx-font-size: 10px; -fx-background-radius: 10px; -fx-padding: 3px 8px; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: #2563eb; -fx-text-fill: #ffffff; -fx-font-size: 10px; -fx-background-radius: 10px; -fx-padding: 3px 8px; -fx-cursor: hand;"));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: #1e293b; -fx-text-fill: #94a3b8; -fx-font-size: 10px; -fx-background-radius: 10px; -fx-padding: 3px 8px; -fx-cursor: hand;"));
        btn.setOnAction(e -> {
            inputField.setText(query);
            sendMessage();
        });
        return btn;
    }

    private void sendMessage() {
        String query = inputField.getText() != null ? inputField.getText().trim() : "";
        if (query.isBlank())
            return;

        addUserMessage(query);
        inputField.clear();

        sendButton.setDisable(true);
        typingIndicator.setVisible(true);

        if (currentCaseId == null || currentCaseId.isBlank()) {
            addSherlockMessage("Please select or create a case first to query the knowledge graph.");
            sendButton.setDisable(false);
            typingIndicator.setVisible(false);
            return;
        }

        String selectedModel = modelSelector.getValue();
        String selectedProvider = providerSelector.getValue();
        client.sendChatMessageAsync(currentCaseId, query, selectedModel, selectedProvider)
                .thenAccept(resp -> Platform.runLater(() -> {
                    sendButton.setDisable(false);
                    typingIndicator.setVisible(false);
                    if (resp != null && resp.getAnswer() != null) {
                        addSherlockMessage(resp.getAnswer());
                    } else {
                        addSherlockMessage("No specific evidence found matching your query.");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        sendButton.setDisable(false);
                        typingIndicator.setVisible(false);
                        addSherlockMessage("Investigation Query Failed: " + ex.getMessage());
                    });
                    return null;
                });
    }

    public void addUserMessage(String text) {
        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("chat-bubble-user");
        bubble.setMaxWidth(280);

        Label userLbl = new Label("You");
        userLbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #bfdbfe;");

        Label content = new Label(text);
        content.setWrapText(true);
        content.setStyle("-fx-font-size: 12px; -fx-text-fill: #ffffff;");

        Label time = new Label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
        time.getStyleClass().add("chat-timestamp");
        time.setStyle("-fx-text-fill: #bfdbfe;");

        bubble.getChildren().addAll(userLbl, content, time);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        messageContainer.getChildren().add(row);
    }

    public void addSherlockMessage(String text) {
        VBox bubble = new VBox(6);
        bubble.getStyleClass().add("chat-bubble-sherlock");
        bubble.setMaxWidth(300);

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("🕵️");
        icon.setStyle("-fx-font-size: 12px;");
        Label sherlockLbl = new Label("Sherlock");
        sherlockLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #60a5fa;");
        header.getChildren().addAll(icon, sherlockLbl);

        Label content = new Label(text);
        content.setWrapText(true);
        content.setStyle("-fx-font-size: 12px; -fx-text-fill: #e2e8f0; -fx-line-spacing: 2px;");

        Label time = new Label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
        time.getStyleClass().add("chat-timestamp");

        bubble.getChildren().addAll(header, content, time);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_LEFT);
        messageContainer.getChildren().add(row);
    }
}
