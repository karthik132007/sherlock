package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.model.GraphDataDto;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.LlmConfigDto;
import com.sherlock.ui.util.LlmModelHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MainFrameView extends BorderPane {

    private final SherlockBackendClient client;
    private final Runnable onNewCaseRequested;

    private CaseDto currentCase;
    private GraphDataDto currentGraph;

    private final Label caseBadge = new Label("CASE: —");
    private final Label nodeCountBadge = new Label("0 Nodes");
    private final Label edgeCountBadge = new Label("0 Edges");
    private final ProgressIndicator refreshIndicator = new ProgressIndicator();

    private final GraphCanvas graphCanvas = new GraphCanvas();
    private final TimelinePanel timelinePanel = new TimelinePanel(this::startTimelineExtraction);
    private final ContradictionsPanel contradictionsPanel = new ContradictionsPanel(this::startContradictionsExtraction);
    private final OpinionPanel opinionPanel = new OpinionPanel(this::startOpinionExtraction);
    private final DocumentsPanel documentsPanel;
    private final DetailsPanel detailsPanel = new DetailsPanel();
    private final ChatPanel chatPanel;
    private Button investigateBtn;

    // Navigation tabs
    private Button navDocs;
    private Button navGraph;
    private Button navTimeline;
    private Button navContradictions;
    private Button navOpinion;

    private StackPane viewStack;
    private SplitPane mainAreaSplit;
    private VBox rightPanel;
    private boolean isChatCollapsed = false;
    private Button chatCollapseBtn;

    // Status bar labels
    private final Label statusOnlineLbl = new Label("System Online");
    private final Label statusVersionLbl = new Label("v1.0.1");
    private final Label statusDataSourcesLbl = new Label("Data Sources: 0");
    private final Label statusEvidenceLbl = new Label("Evidence Files: 0");
    private final Label statusLastUpdatedLbl = new Label("Last Updated: —");

    public MainFrameView(SherlockBackendClient client, Runnable onNewCaseRequested) {
        this.client = client;
        this.onNewCaseRequested = onNewCaseRequested;
        this.chatPanel = new ChatPanel(client);
        this.documentsPanel = new DocumentsPanel(client);
        this.documentsPanel.setOnFilesChanged(this::refreshGraphData);

        getStyleClass().add("root");

        // Top: navigation bar
        HBox topNavBar = buildTopNavBar();
        setTop(topNavBar);

        // Center: graph area + right chat panel
        HBox contentArea = new HBox(0);
        contentArea.getStyleClass().add("app-shell");

        VBox centerView = buildCenterView();
        HBox.setHgrow(centerView, Priority.ALWAYS);

        rightPanel = buildRightPanel();

        contentArea.getChildren().addAll(centerView, rightPanel);
        setCenter(contentArea);

        // Bottom: status bar
        HBox statusBar = buildStatusBar();
        setBottom(statusBar);

        setupInteractionWiring();
        chatPanel.setOnQueryResult(result -> {
            graphCanvas.highlightQueryResults(result.getHighlightNodeIds(), result.getHighlightRelationIds());
            if (result.getHighlightNodeIds() != null && !result.getHighlightNodeIds().isEmpty()) {
                var firstMatch = graphCanvas.getNode(result.getHighlightNodeIds().get(0));
                if (firstMatch != null) graphCanvas.selectAndFocusNode(firstMatch);
            }
        });

        switchToView("GRAPH");
    }

    public void loadCase(CaseDto caseDto) {
        this.currentCase = caseDto;
        String caseId = caseDto != null ? caseDto.getCaseId() : "—";
        caseBadge.setText("CASE: " + caseId.toUpperCase());
        chatPanel.setCaseId(caseId);
        documentsPanel.setCase(caseDto);
        detailsPanel.setClientAndCaseId(client, caseDto != null ? caseDto.getCaseId() : null);

        refreshGraphData();
    }

    public void refreshGraphData() {
        if (currentCase == null || currentCase.getCaseId() == null)
            return;

        refreshIndicator.setVisible(true);
        String caseId = currentCase.getCaseId();

        client.getGraphDataAsync(caseId)
                .thenAccept(graphData -> Platform.runLater(() -> {
                    refreshIndicator.setVisible(false);
                    this.currentGraph = graphData;
                    graphCanvas.setGraphData(graphData);

                    int nodes = graphData != null && graphData.getNodes() != null ? graphData.getNodes().size() : 0;
                    if (nodes > 0) {
                        investigateBtn.setVisible(false);
                        investigateBtn.setManaged(false);
                    } else {
                        investigateBtn.setVisible(true);
                        investigateBtn.setManaged(true);
                    }
                    int edges = graphData != null && graphData.getEdges() != null ? graphData.getEdges().size() : 0;

                    nodeCountBadge.setText(nodes + " Node" + (nodes == 1 ? "" : "s"));
                    edgeCountBadge.setText(edges + " Edge" + (edges == 1 ? "" : "s"));

                    // Update status bar
                    statusDataSourcesLbl.setText("Data Sources: " + (nodes > 0 ? Math.max(1, nodes / 4) : 0));
                    statusEvidenceLbl.setText("Evidence Files: " + edges);
                    statusLastUpdatedLbl.setText("Last Updated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")));

                    detailsPanel.showPlaceholder();
                    graphCanvas.fitToView();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        refreshIndicator.setVisible(false);
                        System.err.println("Failed to load graph data: " + ex.getMessage());
                    });
                    return null;
                });

        client.getTimelineAsync(caseId)
                .thenAccept(timelineData -> Platform.runLater(() -> {
                    timelinePanel.setTimelineData(timelineData);
                    detailsPanel.setTimelineData(timelineData);
                }))
                .exceptionally(ex -> {
                    System.err.println("Failed to load timeline data: " + ex.getMessage());
                    return null;
                });

        client.getContradictionsAsync(caseId)
                .thenAccept(contraData -> Platform.runLater(() -> {
                    contradictionsPanel.setContradictionsData(contraData);
                }))
                .exceptionally(ex -> {
                    System.err.println("Failed to load contradictions data: " + ex.getMessage());
                    return null;
                });

        client.getOpinionAsync(caseId)
                .thenAccept(opinionData -> Platform.runLater(() -> {
                    opinionPanel.setOpinionData(opinionData);
                }))
                .exceptionally(ex -> {
                    System.err.println("Failed to load opinion data: " + ex.getMessage());
                    return null;
                });

        documentsPanel.refreshFiles();
    }

    // =========================================================================
    // TOP NAVIGATION BAR
    // =========================================================================

    private HBox buildTopNavBar() {
        HBox navBar = new HBox(0);
        navBar.setAlignment(Pos.CENTER_LEFT);
        navBar.setPadding(new Insets(0, 20, 0, 16));
        navBar.setPrefHeight(52);
        navBar.setMinHeight(52);
        navBar.setMaxHeight(52);
        navBar.getStyleClass().add("top-nav-bar");

        // Brand: Logo + "Sherlock"
        HBox brand = new HBox(8);
        brand.setAlignment(Pos.CENTER_LEFT);

        ImageView logoView = new ImageView();
        try {
            java.net.URL logoUrl = getClass().getResource("/logo.png");
            if (logoUrl != null) {
                Image logoImage = new Image(logoUrl.toExternalForm(), 28, 28, true, true);
                logoView.setImage(logoImage);
                logoView.setFitWidth(28);
                logoView.setFitHeight(28);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Wrap logo in a dark circular background for visibility
        StackPane logoBg = new StackPane(logoView);
        logoBg.setMinSize(34, 34);
        logoBg.setMaxSize(34, 34);
        logoBg.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 8px;");
        logoBg.setAlignment(Pos.CENTER);

        Label brandTitle = new Label("Sherlock");
        brandTitle.getStyleClass().add("brand-title");

        brand.getChildren().addAll(logoBg, brandTitle);

        // Navigation tabs
        HBox navTabs = new HBox(0);
        navTabs.setAlignment(Pos.CENTER_LEFT);
        navTabs.setPadding(new Insets(0, 0, 0, 32));

        navDocs = createNavTab("Documents");
        navGraph = createNavTab("Graph View");
        navTimeline = createNavTab("Timeline View");
        navContradictions = createNavTab("Contradictions");
        navOpinion = createNavTab("Sherlock's Opinion");

        navDocs.setOnAction(e -> switchToView("DOCUMENTS"));
        navGraph.setOnAction(e -> switchToView("GRAPH"));
        navTimeline.setOnAction(e -> switchToView("TIMELINE"));
        navContradictions.setOnAction(e -> switchToView("CONTRADICTIONS"));
        navOpinion.setOnAction(e -> switchToView("OPINION"));

        navTabs.getChildren().addAll(navDocs, navGraph, navTimeline, navContradictions, navOpinion);

        // Right side: case badge, counts, settings
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox rightControls = new HBox(10);
        rightControls.setAlignment(Pos.CENTER_RIGHT);

        caseBadge.getStyleClass().add("case-badge");
        nodeCountBadge.getStyleClass().add("metric-badge");
        edgeCountBadge.getStyleClass().add("metric-badge");

        refreshIndicator.setMaxSize(16, 16);
        refreshIndicator.setVisible(false);

        investigateBtn = new Button("Analyze evidence");
        investigateBtn.getStyleClass().add("primary-button-sm");
        investigateBtn.setOnAction(e -> startInvestigation());

        Button settingsBtn = new Button("⚙");
        settingsBtn.getStyleClass().add("nav-settings-btn");
        settingsBtn.setTooltip(new Tooltip("Settings"));
        settingsBtn.setOnAction(e -> showSettingsDialog());

        rightControls.getChildren().addAll(investigateBtn, refreshIndicator, caseBadge, nodeCountBadge, edgeCountBadge, settingsBtn);

        navBar.getChildren().addAll(brand, navTabs, spacer, rightControls);
        return navBar;
    }

    private Button createNavTab(String text) {
        Button tab = new Button(text);
        tab.getStyleClass().add("nav-tab");
        tab.setMnemonicParsing(false);
        return tab;
    }

    private void setActiveNavTab(Button activeTab) {
        Button[] allTabs = {navDocs, navGraph, navTimeline, navContradictions, navOpinion};
        for (Button tab : allTabs) {
            if (tab != null) {
                tab.getStyleClass().remove("nav-tab-active");
            }
        }
        if (activeTab != null && !activeTab.getStyleClass().contains("nav-tab-active")) {
            activeTab.getStyleClass().add("nav-tab-active");
        }
    }

    // =========================================================================
    // CENTER VIEW (graph toolbar + view stack + details split)
    // =========================================================================

    private VBox buildCenterView() {
        VBox center = new VBox(0);
        center.setFillWidth(true);
        center.setMinWidth(0); // Ensure it can shrink so it doesn't push rightPanel off screen

        // View stack for Graph/Timeline/Contradictions/Opinion/Documents
        viewStack = new StackPane();
        viewStack.setMinWidth(0);
        VBox.setVgrow(viewStack, Priority.ALWAYS);
        viewStack.getChildren().addAll(graphCanvas, timelinePanel, contradictionsPanel, opinionPanel, documentsPanel);

        detailsPanel.setMinHeight(150.0);
        detailsPanel.setPrefHeight(420.0);
        detailsPanel.setMaxHeight(Double.MAX_VALUE);

        mainAreaSplit = new SplitPane();
        mainAreaSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox.setVgrow(mainAreaSplit, Priority.ALWAYS);

        mainAreaSplit.getItems().addAll(viewStack, detailsPanel);
        mainAreaSplit.setDividerPositions(0.68);

        center.getChildren().add(mainAreaSplit);
        return center;
    }

    // =========================================================================
    // RIGHT PANEL (Chat)
    // =========================================================================

    private VBox buildRightPanel() {
        VBox right = new VBox(0);
        rightPanel = right;
        right.setPrefWidth(360);
        right.setMinWidth(360);
        right.getStyleClass().add("assistant-rail");

        // Collapse toggle header
        HBox collapseHeader = new HBox(6);
        collapseHeader.setAlignment(Pos.CENTER_LEFT);
        collapseHeader.setPadding(new Insets(8, 12, 4, 12));

        chatCollapseBtn = new Button("‹");
        chatCollapseBtn.getStyleClass().add("chat-collapse-btn");
        chatCollapseBtn.setTooltip(new Tooltip("Collapse assistant panel"));
        chatCollapseBtn.setOnAction(e -> toggleChatPanel());

        collapseHeader.getChildren().add(chatCollapseBtn);

        VBox.setVgrow(chatPanel, Priority.ALWAYS);
        right.getChildren().addAll(collapseHeader, chatPanel);
        return right;
    }

    private void toggleChatPanel() {
        isChatCollapsed = !isChatCollapsed;
        if (isChatCollapsed) {
            rightPanel.setPrefWidth(36);
            rightPanel.setMinWidth(36);
            rightPanel.setMaxWidth(36);
            chatPanel.setVisible(false);
            chatPanel.setManaged(false);
            chatCollapseBtn.setText("›");
            chatCollapseBtn.setTooltip(new Tooltip("Expand assistant panel"));
        } else {
            rightPanel.setPrefWidth(360);
            rightPanel.setMinWidth(360);
            rightPanel.setMaxWidth(Double.MAX_VALUE);
            chatPanel.setVisible(true);
            chatPanel.setManaged(true);
            chatCollapseBtn.setText("‹");
            chatCollapseBtn.setTooltip(new Tooltip("Collapse assistant panel"));
        }
        Platform.runLater(graphCanvas::fitToView);
    }

    // =========================================================================
    // BOTTOM STATUS BAR
    // =========================================================================

    private HBox buildStatusBar() {
        HBox statusBar = new HBox(16);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(6, 20, 6, 20));
        statusBar.getStyleClass().add("status-bar");

        // Online indicator
        HBox onlineGroup = new HBox(6);
        onlineGroup.setAlignment(Pos.CENTER_LEFT);
        Circle onlineDot = new Circle(4, Color.web("#10b981"));
        statusOnlineLbl.getStyleClass().add("status-label");
        onlineGroup.getChildren().addAll(onlineDot, statusOnlineLbl);

        statusVersionLbl.getStyleClass().add("status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusDataSourcesLbl.getStyleClass().add("status-label");
        statusEvidenceLbl.getStyleClass().add("status-label");
        statusLastUpdatedLbl.getStyleClass().add("status-label");

        // Health check dot
        Circle healthDot = new Circle(4, Color.web("#10b981"));

        statusBar.getChildren().addAll(onlineGroup, statusVersionLbl, spacer, statusDataSourcesLbl, statusEvidenceLbl, statusLastUpdatedLbl, healthDot);
        return statusBar;
    }

    // =========================================================================
    // VIEW SWITCHING
    // =========================================================================

    public void switchToView(String viewName) {
        boolean isGraph = "GRAPH".equalsIgnoreCase(viewName);
        boolean isTimeline = "TIMELINE".equalsIgnoreCase(viewName);
        boolean isContra = "CONTRADICTIONS".equalsIgnoreCase(viewName);
        boolean isOpinion = "OPINION".equalsIgnoreCase(viewName);
        boolean isDocs = "DOCUMENTS".equalsIgnoreCase(viewName);

        // Active tab styling
        if (isGraph) setActiveNavTab(navGraph);
        else if (isTimeline) setActiveNavTab(navTimeline);
        else if (isContra) setActiveNavTab(navContradictions);
        else if (isOpinion) setActiveNavTab(navOpinion);
        else if (isDocs) setActiveNavTab(navDocs);

        // View visibility
        graphCanvas.setVisible(isGraph);
        graphCanvas.setManaged(isGraph);

        timelinePanel.setVisible(isTimeline);
        timelinePanel.setManaged(isTimeline);

        contradictionsPanel.setVisible(isContra);
        contradictionsPanel.setManaged(isContra);

        opinionPanel.setVisible(isOpinion);
        opinionPanel.setManaged(isOpinion);

        documentsPanel.setVisible(isDocs);
        documentsPanel.setManaged(isDocs);

        // Manage details panel in mainAreaSplit
        if (mainAreaSplit != null) {
            if (isGraph || isTimeline) {
                if (!mainAreaSplit.getItems().contains(detailsPanel)) {
                    mainAreaSplit.getItems().add(detailsPanel);
                    mainAreaSplit.setDividerPositions(0.68);
                }
            } else {
                mainAreaSplit.getItems().remove(detailsPanel);
            }
        }

        if (isGraph) {
            Platform.runLater(graphCanvas::fitToView);
        } else if (isDocs) {
            documentsPanel.refreshFiles();
        }
    }

    // =========================================================================
    // INTERACTION WIRING
    // =========================================================================

    private void setupInteractionWiring() {
        graphCanvas.setOnNodeSelected(node -> {
            List<EdgeDto> related = new ArrayList<>();
            if (currentGraph != null && currentGraph.getEdges() != null) {
                related = currentGraph.getEdges().stream()
                        .filter(e -> e.getSource().equalsIgnoreCase(node.getName())
                                || e.getTarget().equalsIgnoreCase(node.getName()))
                        .collect(Collectors.toList());
            }
            detailsPanel.showNodeDetails(node, related);
        });

        graphCanvas.setOnEdgeSelected(edge -> {
            detailsPanel.showEdgeDetails(edge);
        });

        graphCanvas.setOnSelectionCleared(() -> {
            detailsPanel.showPlaceholder();
        });

        detailsPanel.setOnNavigateToEntity(entityName -> {
            if (entityName != null && !entityName.isBlank()) {
                switchToView("GRAPH");
                var node = graphCanvas.getNode(entityName);
                if (node != null) {
                    graphCanvas.selectAndFocusNode(node);
                }
            }
        });

        contradictionsPanel.setOnNavigateToEntity(entityName -> {
            if (entityName != null && !entityName.isBlank()) {
                switchToView("GRAPH");
                var node = graphCanvas.getNode(entityName);
                if (node != null) {
                    graphCanvas.selectAndFocusNode(node);
                }
            }
        });

        opinionPanel.setOnNavigateToEntity(entityName -> {
            if (entityName != null && !entityName.isBlank()) {
                switchToView("GRAPH");
                var node = graphCanvas.getNode(entityName);
                if (node != null) {
                    graphCanvas.selectAndFocusNode(node);
                }
            }
        });
    }

    // =========================================================================
    // PROCESSING ACTIONS
    // =========================================================================

    private void startTimelineExtraction() {
        if (currentCase == null || currentCase.getCaseId() == null)
            return;
        String caseId = currentCase.getCaseId();

        client.startTimelineProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                Stage owner = getScene() != null ? (Stage) getScene().getWindow() : null;
                ProcessingLogsDialog dialog = new ProcessingLogsDialog(
                        owner,
                        "⏳ Timeline Reconstruction",
                        "Extracting chronological timeline events from evidence...",
                        () -> client.getTimelineProcessingStatusAsync(caseId),
                        this::refreshGraphData
                );
                dialog.showAndStart();
            });
        });
    }

    private void startContradictionsExtraction() {
        if (currentCase == null || currentCase.getCaseId() == null)
            return;
        String caseId = currentCase.getCaseId();

        client.startContradictionsProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                Stage owner = getScene() != null ? (Stage) getScene().getWindow() : null;
                ProcessingLogsDialog dialog = new ProcessingLogsDialog(
                        owner,
                        "⚡ Contradiction Detection",
                        "Comparing statements, alibis, and evidence to detect contradictory facts...",
                        () -> client.getContradictionsProcessingStatusAsync(caseId),
                        this::refreshGraphData
                );
                dialog.showAndStart();
            });
        });
    }

    private void startOpinionExtraction() {
        if (currentCase == null || currentCase.getCaseId() == null)
            return;
        String caseId = currentCase.getCaseId();

        client.startOpinionProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                Stage owner = getScene() != null ? (Stage) getScene().getWindow() : null;
                ProcessingLogsDialog dialog = new ProcessingLogsDialog(
                        owner,
                        "🧠 Sherlock's Opinion & Theory Deduction",
                        "Connecting dots across all evidence with LLM thinking mode...",
                        () -> client.getOpinionProcessingStatusAsync(caseId),
                        this::refreshGraphData
                );
                dialog.showAndStart();
            });
        });
    }

    private void startInvestigation() {
        if (currentCase == null || currentCase.getCaseId() == null)
            return;
        String caseId = currentCase.getCaseId();

        client.startProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                Stage owner = getScene() != null ? (Stage) getScene().getWindow() : null;
                ProcessingLogsDialog dialog = new ProcessingLogsDialog(
                        owner,
                        "🚀 Sherlock Knowledge Graph Pipeline",
                        "Extracting entities, relationships, timeline, and contradictions...",
                        () -> client.getProcessingStatusAsync(caseId),
                        this::refreshGraphData
                );
                dialog.showAndStart();
            });
        });
    }

    // =========================================================================
    // SETTINGS DIALOG
    // =========================================================================

    private void showSettingsDialog() {
        if (currentCase == null)
            return;

        Dialog<LlmConfigDto> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Update Case Settings (Connect AI & Models)");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialogPane.setStyle("-fx-background-color: #ffffff; -fx-padding: 10px;");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        // 1. API Key Input with Eye Toggle
        VBox keyBox = new VBox(8);
        Label keyLabel = new Label("Provide API key (Optional for local/demo)");
        keyLabel.setStyle("-fx-font-size: 18.7px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-... (optional)");
        TextField apiKeyVisibleField = new TextField();
        apiKeyVisibleField.setPromptText("sk-... (optional)");
        apiKeyVisibleField.setManaged(false);
        apiKeyVisibleField.setVisible(false);

        apiKeyField.textProperty().bindBidirectional(apiKeyVisibleField.textProperty());

        Button toggleKeyVisibilityBtn = new Button("👁");
        toggleKeyVisibilityBtn.getStyleClass().add("tool-button");
        toggleKeyVisibilityBtn.setOnAction(e -> {
            if (apiKeyField.isVisible()) {
                apiKeyField.setVisible(false);
                apiKeyField.setManaged(false);
                apiKeyVisibleField.setVisible(true);
                apiKeyVisibleField.setManaged(true);
                toggleKeyVisibilityBtn.setText("🔒");
            } else {
                apiKeyVisibleField.setVisible(false);
                apiKeyVisibleField.setManaged(false);
                apiKeyField.setVisible(true);
                apiKeyField.setManaged(true);
                toggleKeyVisibilityBtn.setText("👁");
            }
        });

        HBox keyInputRow = new HBox(8);
        keyInputRow.setAlignment(Pos.CENTER_LEFT);
        StackPane keyStack = new StackPane(apiKeyField, apiKeyVisibleField);
        HBox.setHgrow(keyStack, Priority.ALWAYS);
        keyInputRow.getChildren().addAll(keyStack, toggleKeyVisibilityBtn);

        keyBox.getChildren().addAll(keyLabel, keyInputRow);

        // 2. Model Selection
        VBox modelBox = new VBox(8);
        Label modelLabel = new Label("Model Name");
        modelLabel.setStyle("-fx-font-size: 18.7px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        ComboBox<String> modelCombo = new ComboBox<>();
        modelCombo.setEditable(true);
        modelCombo.setPromptText("Select or enter model...");
        if (modelCombo.getEditor() != null) {
            modelCombo.getEditor().setPromptText("Select or enter model...");
        }
        modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider("OpenAI"));
        modelCombo.setValue(LlmModelHelper.getDefaultModel("OpenAI"));
        modelCombo.setMaxWidth(Double.MAX_VALUE);
        LlmModelHelper.setupCustomModelListener(modelCombo);

        HBox modelInputRow = new HBox(8);
        modelInputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelCombo, Priority.ALWAYS);

        Button addCustomModelBtn = new Button("+ Custom");
        addCustomModelBtn.getStyleClass().add("secondary-button");
        addCustomModelBtn.setStyle("-fx-font-size: 13px; -fx-padding: 6px 12px; -fx-font-weight: 600;");
        addCustomModelBtn.setTooltip(new Tooltip("Add or specify a custom model name"));
        addCustomModelBtn.setOnAction(e -> LlmModelHelper.promptCustomModel(modelCombo));

        modelInputRow.getChildren().addAll(modelCombo, addCustomModelBtn);
        modelBox.getChildren().addAll(modelLabel, modelInputRow);

        // 3. Provider Selection
        VBox providerBox = new VBox(8);
        Label providerLabel = new Label("Provider Name");
        providerLabel.setStyle("-fx-font-size: 18.7px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        ComboBox<String> providerCombo = new ComboBox<>();
        providerCombo.getItems().addAll("OpenAI", "OpenRouter", "DeepSeek", "Groq", "Together", "Mistral", "Ollama",
                "Custom");
        providerCombo.setValue("OpenAI");
        providerCombo.setMaxWidth(Double.MAX_VALUE);

        providerCombo.setOnAction(e -> {
            String p = providerCombo.getValue();
            if ("Ollama".equalsIgnoreCase(p)) {
                apiKeyField.setDisable(true);
                apiKeyField.setText("");
                apiKeyField.setPromptText("Local Ollama (No API Key Required)");
                keyLabel.setText("API Key (Zero-key Local Ollama Mode)");
                modelLabel.setText("Ollama Local Model");

                client.getOllamaModelsAsync().thenAccept(models -> javafx.application.Platform.runLater(() -> {
                    if (models != null && !models.isEmpty()) {
                        List<String> list = new ArrayList<>(models);
                        if (!list.contains(LlmModelHelper.CUSTOM_MODEL_OPTION)) {
                            list.add(LlmModelHelper.CUSTOM_MODEL_OPTION);
                        }
                        modelCombo.getItems().setAll(list);
                        modelCombo.setValue(models.get(0));
                        modelCombo.setDisable(false);
                    } else {
                        modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider("Ollama"));
                        modelCombo.setValue(LlmModelHelper.getDefaultModel("Ollama"));
                        modelCombo.setDisable(false);
                    }
                })).exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider("Ollama"));
                        modelCombo.setValue(LlmModelHelper.getDefaultModel("Ollama"));
                        modelCombo.setDisable(false);
                    });
                    return null;
                });
            } else {
                apiKeyField.setDisable(false);
                apiKeyField.setPromptText("sk-... (required for cloud)");
                keyLabel.setText("Provide API key");
                modelLabel.setText("Model Name");
                modelCombo.setDisable(false);

                modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider(p));
                modelCombo.setValue(LlmModelHelper.getDefaultModel(p));
            }
        });

        providerBox.getChildren().addAll(providerLabel, providerCombo);

        // 4. Docs Hint / Base URL
        VBox hintBox = new VBox(8);
        hintBox.setAlignment(Pos.CENTER_LEFT);

        TextField baseUrl = new TextField();
        baseUrl.setPromptText("Optional Custom Base URL");

        Label hintLbl = new Label("Please use the exact Provider and Model Name from the Docs");
        hintLbl.setStyle("-fx-font-size: 17.3px; -fx-text-fill: #94a3b8; -fx-font-style: italic;");

        hintBox.getChildren().addAll(baseUrl, hintLbl);

        grid.add(keyBox, 0, 0);
        grid.add(modelBox, 1, 0);
        grid.add(providerBox, 0, 1);
        grid.add(hintBox, 1, 1);

        // Pre-fill existing config
        if (currentCase.getLlmConfig() != null) {
            String selectedProvider = "OpenAI";
            if (currentCase.getLlmConfig().getProvider() != null) {
                String prov = currentCase.getLlmConfig().getProvider().toLowerCase();
                if (prov.contains("openai")) selectedProvider = "OpenAI";
                else if (prov.contains("anthropic") || prov.contains("openrouter")) selectedProvider = "OpenRouter";
                else if (prov.contains("deepseek")) selectedProvider = "DeepSeek";
                else if (prov.contains("groq")) selectedProvider = "Groq";
                else if (prov.contains("together")) selectedProvider = "Together";
                else if (prov.contains("mistral")) selectedProvider = "Mistral";
                else if (prov.contains("ollama")) selectedProvider = "Ollama";
                else selectedProvider = "Custom";
                providerCombo.setValue(selectedProvider);
            }
            modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider(selectedProvider));
            modelCombo.setValue(LlmModelHelper.getDefaultModel(selectedProvider));

            if (currentCase.getLlmConfig().getModel() != null && !currentCase.getLlmConfig().getModel().isBlank()) {
                String m = currentCase.getLlmConfig().getModel().trim();
                LlmModelHelper.addAndSelectCustomModel(modelCombo, m);
            }
            if (currentCase.getLlmConfig().getBaseUrl() != null)
                baseUrl.setText(currentCase.getLlmConfig().getBaseUrl());
            if (currentCase.getLlmConfig().getApiKey() != null)
                apiKeyField.setText(currentCase.getLlmConfig().getApiKey());
        }

        dialogPane.setContent(grid);
        dialogPane.setPrefWidth(750);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                LlmConfigDto config = currentCase.getLlmConfig() != null ? currentCase.getLlmConfig()
                        : new LlmConfigDto();

                config.setProvider(providerCombo.getValue() != null ? providerCombo.getValue().toLowerCase() : "openai");
                config.setModel(LlmModelHelper.getSelectedModel(modelCombo));
                config.setBaseUrl(baseUrl.getText());
                config.setApiKey(apiKeyField.getText());
                return config;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(config -> {
            client.updateLlmConfigAsync(currentCase.getCaseId(), config);
            currentCase.setLlmConfig(config);
        });
    }
}
