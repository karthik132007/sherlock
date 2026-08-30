package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.ChatMessageDto;
import com.sherlock.ui.model.ChatSessionDto;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChatPanel extends VBox {

    private final SherlockBackendClient client;
    private String currentCaseId = "";
    private String currentSessionId = null;
    private String currentSessionTitle = "New Chat";
    private final List<ChatSessionDto> cachedSessions = new ArrayList<>();

    // UI Structure
    private final Label sessionTitleLbl = new Label("New Chat");
    private final Button newChatBtn = new Button("+ New Chat");
    private final Button historyToggleBtn = new Button("📜 History");
    private final StackPane mainStack = new StackPane();

    // Conversation View
    private final VBox conversationView = new VBox(10);
    private final VBox messageContainer = new VBox(12);
    private final ScrollPane scrollPane = new ScrollPane();
    private final FlowPane suggestionsPane = new FlowPane(6, 6);
    private final TextField inputField = new TextField();
    private final Button sendButton = new Button("➤");
    private final ProgressIndicator typingIndicator = new ProgressIndicator();

    // History View
    private final VBox historyView = new VBox(12);
    private final VBox historyListContainer = new VBox(10);
    private final ScrollPane historyScrollPane = new ScrollPane();

    private Consumer<ChatMessageDto> onQueryResult;

    public ChatPanel(SherlockBackendClient client) {
        this.client = client;

        getStyleClass().add("glass-panel");
        setPadding(new Insets(14));
        setSpacing(10);

        HBox topBar = buildTopBar();
        buildConversationView();
        buildHistoryView();

        mainStack.getChildren().addAll(conversationView, historyView);
        VBox.setVgrow(mainStack, Priority.ALWAYS);

        getChildren().addAll(topBar, mainStack);

        startNewChat();
    }

    private HBox buildTopBar() {
        HBox topBar = new HBox(8);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(4, 0, 8, 0));
        topBar.setStyle("-fx-border-color: #edf1f6; -fx-border-width: 0 0 1px 0;");

        Label icon = new Label("✦");
        icon.setStyle("-fx-font-size: 16px; -fx-text-fill: #1d4ed8;");

        VBox titleBox = new VBox(2);
        Label title = new Label("Sherlock Assistant");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        HBox onlineRow = new HBox(4);
        onlineRow.setAlignment(Pos.CENTER_LEFT);
        Circle onlineDot = new Circle(3.5, Color.web("#10b981"));
        Label onlineLbl = new Label("Who is online");
        onlineLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #10b981; -fx-font-weight: 600;");
        onlineRow.getChildren().addAll(onlineDot, onlineLbl);

        titleBox.getChildren().addAll(title, onlineRow);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        newChatBtn.setText("🗨 New Chat");
        newChatBtn.setStyle("-fx-background-color: #1e40af; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 5px 12px; -fx-font-weight: 700; -fx-background-radius: 6px; -fx-cursor: hand;");
        newChatBtn.setTooltip(new Tooltip("Start a brand new investigation chat"));
        newChatBtn.setOnAction(e -> startNewChat());

        historyToggleBtn.setText("⋮");
        historyToggleBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: 16px; -fx-padding: 2px 6px; -fx-cursor: hand;");
        historyToggleBtn.setTooltip(new Tooltip("View all previous chat sessions"));
        historyToggleBtn.setOnAction(e -> {
            if (historyView.isVisible()) {
                showConversationView();
            } else {
                showHistoryView();
            }
        });

        topBar.getChildren().addAll(icon, titleBox, spacer, newChatBtn, historyToggleBtn);
        return topBar;
    }

    private void buildConversationView() {
        conversationView.setFillWidth(true);
        VBox.setVgrow(conversationView, Priority.ALWAYS);

        // Quick suggestions
        suggestionsPane.getChildren().add(createSuggestionPill("Key timeline events?"));
        suggestionsPane.getChildren().add(createSuggestionPill("Who was near the victim?"));
        suggestionsPane.getChildren().add(createSuggestionPill("Summarize motives."));

        // Message List
        messageContainer.setPadding(new Insets(4, 6, 4, 6));
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

        HBox inputRow = new HBox(8, typingIndicator, inputField, sendButton);
        inputRow.setAlignment(Pos.CENTER);

        conversationView.getChildren().addAll(suggestionsPane, scrollPane, inputRow);
    }

    private void buildHistoryView() {
        historyView.setFillWidth(true);
        historyView.setVisible(false);
        historyView.setManaged(false);
        VBox.setVgrow(historyView, Priority.ALWAYS);

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Investigation Chats");
        title.setStyle("-fx-font-size: 21px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button backBtn = new Button("✕ Close");
        backBtn.getStyleClass().add("tool-button");
        backBtn.setStyle("-fx-font-size: 16px; -fx-padding: 5px 10px;");
        backBtn.setOnAction(e -> showConversationView());

        header.getChildren().addAll(title, spacer, backBtn);

        historyListContainer.setPadding(new Insets(4, 2, 4, 2));
        historyListContainer.setFillWidth(true);

        historyScrollPane.setContent(historyListContainer);
        historyScrollPane.setFitToWidth(true);
        historyScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        historyScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(historyScrollPane, Priority.ALWAYS);
        historyScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        Button newChatFullBtn = new Button("＋ Start New Chat Session");
        newChatFullBtn.getStyleClass().add("primary-button");
        newChatFullBtn.setMaxWidth(Double.MAX_VALUE);
        newChatFullBtn.setStyle("-fx-font-size: 18px; -fx-padding: 10px; -fx-font-weight: 600;");
        newChatFullBtn.setOnAction(e -> startNewChat());

        historyView.getChildren().addAll(header, newChatFullBtn, historyScrollPane);
    }

    public void setCaseId(String caseId) {
        this.currentCaseId = caseId;
        loadSessionsAndShowHistory();
    }

    public void loadSessionsAndShowHistory() {
        if (currentCaseId == null || currentCaseId.isBlank()) return;

        client.getChatSessionsAsync(currentCaseId)
                .thenAccept(sessions -> Platform.runLater(() -> {
                    cachedSessions.clear();
                    if (sessions != null) {
                        cachedSessions.addAll(sessions);
                    }
                    updateHistoryButtonBadge();
                    renderHistoryList();

                    if (!cachedSessions.isEmpty()) {
                        // After opening the dashboard, show the chat history panel
                        showHistoryView();
                    } else {
                        startNewChat();
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        cachedSessions.clear();
                        updateHistoryButtonBadge();
                        startNewChat();
                    });
                    return null;
                });
    }

    private void updateHistoryButtonBadge() {
        int count = cachedSessions.size();
        if (count > 0) {
            historyToggleBtn.setText("📜 History (" + count + ")");
        } else {
            historyToggleBtn.setText("📜 History");
        }
    }

    private void renderHistoryList() {
        historyListContainer.getChildren().clear();

        if (cachedSessions.isEmpty()) {
            VBox emptyBox = new VBox(10);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(30, 10, 30, 10));
            Label emptyLbl = new Label("No chat sessions yet for this case.");
            emptyLbl.setStyle("-fx-font-size: 18px; -fx-text-fill: #94a3b8;");
            Label hintLbl = new Label("Start a new chat to begin asking questions.");
            hintLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #cbd5e1;");
            emptyBox.getChildren().addAll(emptyLbl, hintLbl);
            historyListContainer.getChildren().add(emptyBox);
            return;
        }

        for (ChatSessionDto session : cachedSessions) {
            VBox card = new VBox(6);
            boolean isActive = session.getSessionId() != null && session.getSessionId().equals(currentSessionId);
            if (isActive) {
                card.getStyleClass().add("chat-session-card-active");
            } else {
                card.getStyleClass().add("chat-session-card");
            }

            HBox topRow = new HBox(6);
            topRow.setAlignment(Pos.CENTER_LEFT);

            Label titleLbl = new Label(session.getTitle() != null ? session.getTitle() : "Chat");
            titleLbl.setStyle("-fx-font-size: 18px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");
            HBox.setHgrow(titleLbl, Priority.ALWAYS);

            Button deleteBtn = new Button("🗑");
            deleteBtn.getStyleClass().add("tool-button");
            deleteBtn.setStyle("-fx-font-size: 14px; -fx-padding: 3px 6px;");
            deleteBtn.setTooltip(new Tooltip("Delete this chat session"));
            deleteBtn.setOnAction(e -> {
                e.consume();
                deleteSession(session.getSessionId());
            });

            topRow.getChildren().addAll(titleLbl, deleteBtn);

            HBox metaRow = new HBox(8);
            metaRow.setAlignment(Pos.CENTER_LEFT);

            Label countBadge = new Label(session.getMessageCount() + " msg" + (session.getMessageCount() == 1 ? "" : "s"));
            countBadge.getStyleClass().add("chat-badge-pill");

            String timeStr = formatTimestamp(session.getUpdatedAt());
            Label timeLbl = new Label(timeStr);
            timeLbl.setStyle("-fx-font-size: 15px; -fx-text-fill: #94a3b8;");

            metaRow.getChildren().addAll(countBadge, timeLbl);

            card.getChildren().addAll(topRow, metaRow);

            card.setOnMouseClicked(e -> loadSession(session));

            historyListContainer.getChildren().add(card);
        }
    }

    public void startNewChat() {
        currentSessionId = null;
        currentSessionTitle = "New Chat";
        sessionTitleLbl.setText("New Chat");
        messageContainer.getChildren().clear();
        suggestionsPane.setVisible(true);
        suggestionsPane.setManaged(true);
        showConversationView();

        addSherlockMessage("Greetings. I am Sherlock, your investigation intelligence assistant. How may I assist your investigation?");
    }

    public void loadSession(ChatSessionDto session) {
        if (session == null) return;
        currentSessionId = session.getSessionId();
        currentSessionTitle = session.getTitle() != null ? session.getTitle() : "Chat";
        sessionTitleLbl.setText(currentSessionTitle);
        messageContainer.getChildren().clear();
        suggestionsPane.setVisible(false);
        suggestionsPane.setManaged(false);
        showConversationView();

        if (session.getMessages() != null && !session.getMessages().isEmpty()) {
            for (ChatMessageDto msg : session.getMessages()) {
                String content = msg.getContent() != null ? msg.getContent() : msg.getAnswer();
                if (content == null || content.isBlank()) continue;
                if ("user".equalsIgnoreCase(msg.getRole())) {
                    addUserMessage(content, msg.getTimestamp());
                } else {
                    addSherlockMessage(content, msg.getTimestamp());
                }
            }
        } else if (currentCaseId != null && !currentCaseId.isBlank() && currentSessionId != null) {
            client.getChatSessionAsync(currentCaseId, currentSessionId)
                    .thenAccept(loaded -> Platform.runLater(() -> {
                        if (loaded != null && loaded.getMessages() != null) {
                            session.setMessages(loaded.getMessages());
                            for (ChatMessageDto msg : loaded.getMessages()) {
                                String content = msg.getContent() != null ? msg.getContent() : msg.getAnswer();
                                if (content == null || content.isBlank()) continue;
                                if ("user".equalsIgnoreCase(msg.getRole())) {
                                    addUserMessage(content, msg.getTimestamp());
                                } else {
                                    addSherlockMessage(content, msg.getTimestamp());
                                }
                            }
                        }
                    }));
        }
    }

    public void showHistoryView() {
        conversationView.setVisible(false);
        conversationView.setManaged(false);
        historyView.setVisible(true);
        historyView.setManaged(true);
        historyToggleBtn.setText("💬 Active Chat");
        renderHistoryList();
    }

    public void showConversationView() {
        historyView.setVisible(false);
        historyView.setManaged(false);
        conversationView.setVisible(true);
        conversationView.setManaged(true);
        updateHistoryButtonBadge();
    }

    private void deleteSession(String sessionId) {
        if (sessionId == null || currentCaseId == null) return;
        client.deleteChatSessionAsync(currentCaseId, sessionId)
                .thenAccept(deleted -> Platform.runLater(() -> {
                    cachedSessions.removeIf(s -> sessionId.equals(s.getSessionId()));
                    updateHistoryButtonBadge();
                    if (sessionId.equals(currentSessionId)) {
                        startNewChat();
                    } else {
                        renderHistoryList();
                    }
                }));
    }

    private String formatTimestamp(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) return "";
        try {
            LocalDateTime dt = LocalDateTime.parse(isoTimestamp);
            return dt.format(DateTimeFormatter.ofPattern("MMM dd, hh:mm a"));
        } catch (Exception e) {
            return isoTimestamp;
        }
    }

    public void setOnQueryResult(Consumer<ChatMessageDto> onQueryResult) {
        this.onQueryResult = onQueryResult;
    }

    private FlowPane buildQuickSuggestions() {
        return suggestionsPane;
    }

    private Button createSuggestionPill(String query) {
        Button btn = new Button(query);
        btn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-size: 13.5px; -fx-background-radius: 12px; -fx-padding: 6px 12px; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 12px;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #e2e8f0; -fx-text-fill: #0f172a; -fx-font-size: 13.5px; -fx-background-radius: 12px; -fx-padding: 6px 12px; -fx-cursor: hand; -fx-border-color: #cbd5e1; -fx-border-radius: 12px;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-size: 13.5px; -fx-background-radius: 12px; -fx-padding: 6px 12px; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 12px;"));
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
        suggestionsPane.setVisible(false);
        suggestionsPane.setManaged(false);

        sendButton.setDisable(true);
        typingIndicator.setVisible(true);

        if (currentCaseId == null || currentCaseId.isBlank()) {
            addSherlockMessage("Please select or create a case first.");
            sendButton.setDisable(false);
            typingIndicator.setVisible(false);
            return;
        }

        client.sendChatMessageAsync(currentCaseId, query, null, null, currentSessionId)
                .thenAccept(resp -> Platform.runLater(() -> {
                    sendButton.setDisable(false);
                    typingIndicator.setVisible(false);
                    if (resp != null) {
                        if (resp.getSessionId() != null && !resp.getSessionId().isBlank()) {
                            currentSessionId = resp.getSessionId();
                        }
                        if (resp.getSessionTitle() != null && !resp.getSessionTitle().isBlank()) {
                            currentSessionTitle = resp.getSessionTitle();
                            sessionTitleLbl.setText(currentSessionTitle);
                        }
                        if (resp.getAnswer() != null) {
                            addSherlockMessage(resp.getAnswer(), resp.getTimestamp());
                            if (onQueryResult != null) onQueryResult.accept(resp);
                        } else {
                            addSherlockMessage("No specific evidence found.");
                        }

                        // Silently refresh sessions in background to keep history drawer updated
                        client.getChatSessionsAsync(currentCaseId).thenAccept(updatedSessions -> Platform.runLater(() -> {
                            if (updatedSessions != null) {
                                cachedSessions.clear();
                                cachedSessions.addAll(updatedSessions);
                                updateHistoryButtonBadge();
                            }
                        }));
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
        addUserMessage(text, LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
    }

    public void addUserMessage(String text, String timestampStr) {
        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("chat-bubble-user");
        bubble.setMaxWidth(260);

        Label userLbl = new Label("You");
        userLbl.setStyle("-fx-font-size: 14.4px; -fx-font-weight: bold; -fx-text-fill: rgba(255,255,255,0.8);");

        Label content = new Label(text);
        content.setWrapText(true);
        content.setStyle("-fx-font-size: 14px; -fx-text-fill: #ffffff; -fx-line-spacing: 4px;");

        String time = timestampStr != null && !timestampStr.isBlank() ? timestampStr : LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
        Label timeLbl = new Label(time);
        timeLbl.setStyle("-fx-font-size: 13.2px; -fx-text-fill: rgba(255,255,255,0.6); -fx-alignment: center-right;");
        timeLbl.setMaxWidth(Double.MAX_VALUE);

        bubble.getChildren().addAll(userLbl, content, timeLbl);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(4, 0, 4, 0));
        messageContainer.getChildren().add(row);
    }

    public void addSherlockMessage(String text) {
        addSherlockMessage(text, LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
    }

    public void addSherlockMessage(String text, String timestampStr) {
        VBox bubble = new VBox(4);
        bubble.getStyleClass().add("chat-bubble-sherlock");
        bubble.setMaxWidth(280);

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("✨");
        Label sherlockLbl = new Label("Sherlock");
        sherlockLbl.setStyle("-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;");
        header.getChildren().addAll(icon, sherlockLbl);

        TextFlow content = renderMarkdown(text);
        content.setMaxWidth(280);

        String time = timestampStr != null && !timestampStr.isBlank() ? timestampStr : LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
        Label timeLbl = new Label(time);
        timeLbl.setStyle("-fx-font-size: 13.2px; -fx-text-fill: #94a3b8;");

        bubble.getChildren().addAll(header, content, timeLbl);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        messageContainer.getChildren().add(row);
    }

    /** Lightweight, safe Markdown rendering for LLM answers (no HTML execution). */
    private TextFlow renderMarkdown(String markdown) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(4);
        String[] lines = (markdown == null ? "" : markdown).replace("\r", "").split("\n", -1);
        boolean codeBlock = false;
        for (String rawLine : lines) {
            String line = rawLine;
            if (line.strip().startsWith("```")) {
                codeBlock = !codeBlock;
                continue;
            }
            if (codeBlock) {
                appendStyled(flow, line + "\n", "-fx-font-family: 'monospace'; -fx-font-size: 14.4px; -fx-fill: #1e3a8a;");
                continue;
            }
            if (line.startsWith("### ") || line.startsWith("## ") || line.startsWith("# ")) {
                String heading = line.replaceFirst("^#+\\s*", "");
                appendStyled(flow, heading + "\n", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-fill: #0f172a;");
            } else if (line.strip().startsWith("- ") || line.strip().startsWith("* ") || line.strip().startsWith("• ")) {
                appendInlineMarkdown(flow, "• " + line.strip().replaceFirst("^[-*•]\\s+", "") + "\n");
            } else if (line.strip().startsWith(">")) {
                appendStyled(flow, "  " + line.strip().replaceFirst("^>\\s*", "") + "\n", "-fx-font-size: 13.5px; -fx-font-style: italic; -fx-fill: #475569;");
            } else {
                appendInlineMarkdown(flow, line + "\n");
            }
        }
        return flow;
    }

    private void appendInlineMarkdown(TextFlow flow, String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)").matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) appendStyled(flow, text.substring(cursor, matcher.start()), baseTextStyle());
            String token = matcher.group();
            if (token.startsWith("**")) {
                appendStyled(flow, token.substring(2, token.length() - 2), "-fx-font-size: 14px; -fx-font-weight: bold; -fx-fill: #0f172a;");
            } else if (token.startsWith("*")) {
                appendStyled(flow, token.substring(1, token.length() - 1), "-fx-font-size: 14px; -fx-font-style: italic; -fx-fill: #0f172a;");
            } else {
                appendStyled(flow, token.substring(1, token.length() - 1), "-fx-font-family: 'monospace'; -fx-font-size: 14.4px; -fx-fill: #1e3a8a;");
            }
            cursor = matcher.end();
        }
        if (cursor < text.length()) appendStyled(flow, text.substring(cursor), baseTextStyle());
    }

    private void appendStyled(TextFlow flow, String text, String style) {
        Text node = new Text(text);
        node.setStyle(style);
        flow.getChildren().add(node);
    }

    private String baseTextStyle() {
        return "-fx-font-size: 14px; -fx-fill: #334155;";
    }
}
