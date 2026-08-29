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
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ChatPanel extends VBox {

    private final SherlockBackendClient client;
    private String currentCaseId = "";

    private final VBox messageContainer = new VBox(12);
    private final ScrollPane scrollPane = new ScrollPane();
    private final TextField inputField = new TextField();
    private final Button sendButton = new Button("➤");
    private final ProgressIndicator typingIndicator = new ProgressIndicator();

    public ChatPanel(SherlockBackendClient client) {
        this.client = client;

        getStyleClass().add("glass-panel");
        setPadding(new Insets(16));
        setSpacing(12);

        // Header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        
        Label icon = new Label("🤖");
        Label title = new Label("Sherlock Assistant");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(4);
        status.setAlignment(Pos.CENTER);
        Circle dot = new Circle(3, Color.web("#10b981"));
        Label statusLbl = new Label("Ready");
        statusLbl.setStyle("-fx-text-fill: #10b981; -fx-font-size: 12px; -fx-font-weight: bold;");
        status.getChildren().addAll(dot, statusLbl);
        status.setStyle("-fx-background-color: rgba(16, 185, 129, 0.1); -fx-padding: 4px 10px; -fx-background-radius: 12px;");

        header.getChildren().addAll(icon, title, spacer, status);

        // Config Row Removed

        // Quick suggestions
        FlowPane suggestions = buildQuickSuggestions();

        // Message List
        messageContainer.setPadding(new Insets(4, 8, 4, 8));
        messageContainer.setFillWidth(true);

        scrollPane.setContent(messageContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.getStyleClass().add("edge-to-edge");

        messageContainer.heightProperty().addListener((obs, oldVal, newVal) -> scrollPane.setVvalue(1.0));

        // Input Area
        typingIndicator.setMaxSize(16, 16);
        typingIndicator.setVisible(false);

        inputField.setPromptText("Ask about the evidence...");
        inputField.getStyleClass().add("text-field");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) sendMessage();
        });

        sendButton.getStyleClass().add("primary-button");
        sendButton.setStyle("-fx-padding: 8px 16px; -fx-font-size: 14px;");
        sendButton.setOnAction(e -> sendMessage());

        HBox inputRow = new HBox(8, inputField, sendButton);
        inputRow.setAlignment(Pos.CENTER);

        getChildren().addAll(header, suggestions, scrollPane, inputRow);

        addSherlockMessage("Greetings. I am Sherlock, your investigation intelligence assistant. How may I assist your investigation?");
    }

    public void setCaseId(String caseId) {
        this.currentCaseId = caseId;
        messageContainer.getChildren().clear();
        addSherlockMessage("Case **" + caseId + "** loaded. Knowledge graph and evidence inventory are synchronized.");
    }



    private FlowPane buildQuickSuggestions() {
        FlowPane pane = new FlowPane(6, 6);
        pane.getChildren().add(createSuggestionPill("Key timeline events?"));
        pane.getChildren().add(createSuggestionPill("Who was near the victim?"));
        pane.getChildren().add(createSuggestionPill("Summarize motives."));
        return pane;
    }

    private Button createSuggestionPill(String query) {
        Button btn = new Button(query);
        btn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-size: 13px; -fx-background-radius: 12px; -fx-padding: 6px 12px; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 12px;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #e2e8f0; -fx-text-fill: #0f172a; -fx-font-size: 13px; -fx-background-radius: 12px; -fx-padding: 6px 12px; -fx-cursor: hand; -fx-border-color: #cbd5e1; -fx-border-radius: 12px;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-size: 13px; -fx-background-radius: 12px; -fx-padding: 6px 12px; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 12px;"));
        btn.setOnAction(e -> {
            inputField.setText(query);
            sendMessage();
        });
        return btn;
    }

    private void sendMessage() {
        String query = inputField.getText() != null ? inputField.getText().trim() : "";
        if (query.isBlank()) return;

        addUserMessage(query);
        inputField.clear();

        sendButton.setDisable(true);
        typingIndicator.setVisible(true);

        if (currentCaseId == null || currentCaseId.isBlank()) {
            addSherlockMessage("Please select or create a case first.");
            sendButton.setDisable(false);
            typingIndicator.setVisible(false);
            return;
        }

        String selectedModel = null;
        String selectedProvider = null;
        client.sendChatMessageAsync(currentCaseId, query, selectedModel, selectedProvider)
                .thenAccept(resp -> Platform.runLater(() -> {
                    sendButton.setDisable(false);
                    typingIndicator.setVisible(false);
                    if (resp != null && resp.getAnswer() != null) {
                        addSherlockMessage(resp.getAnswer());
                    } else {
                        addSherlockMessage("No specific evidence found.");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        sendButton.setDisable(false);
                        typingIndicator.setVisible(false);
                        addSherlockMessage("Query Failed: " + ex.getMessage());
                    });
                    return null;
                });
    }

    public void addUserMessage(String text) {
        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("chat-bubble-user");
        bubble.setMaxWidth(260);

        Label userLbl = new Label("You");
        userLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: rgba(255,255,255,0.8);");

        Label content = new Label(text);
        content.setWrapText(true);
        content.setStyle("-fx-font-size: 14px; -fx-text-fill: #ffffff; -fx-line-spacing: 4px;");

        Label time = new Label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
        time.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.6); -fx-alignment: center-right;");
        time.setMaxWidth(Double.MAX_VALUE);

        bubble.getChildren().addAll(userLbl, content, time);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(4, 0, 4, 0));
        messageContainer.getChildren().add(row);
    }

    public void addSherlockMessage(String text) {
        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("chat-bubble-sherlock");
        bubble.setMaxWidth(280);

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("✨");
        Label sherlockLbl = new Label("Sherlock");
        sherlockLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;");
        header.getChildren().addAll(icon, sherlockLbl);

        Label content = new Label(text);
        content.setWrapText(true);
        content.setStyle("-fx-font-size: 14px; -fx-text-fill: #334155; -fx-line-spacing: 4px;");

        Label time = new Label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
        time.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");

        bubble.getChildren().addAll(header, content, time);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        messageContainer.getChildren().add(row);
    }
}
