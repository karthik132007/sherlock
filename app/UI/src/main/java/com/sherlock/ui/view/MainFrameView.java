package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.model.GraphDataDto;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.LlmConfigDto;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

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
    private final DetailsPanel detailsPanel = new DetailsPanel();
    private final ChatPanel chatPanel;
    private Button investigateBtn;
    private StackPane centerContainer;
    private ToggleButton graphBtn;
    private ToggleButton timelineBtn;
    private ToggleButton contradictionsBtn;

    public MainFrameView(SherlockBackendClient client, Runnable onNewCaseRequested) {
        this.client = client;
        this.onNewCaseRequested = onNewCaseRequested;
        this.chatPanel = new ChatPanel(client);

        getStyleClass().add("root");

        HBox mainLayout = new HBox();
        mainLayout.setStyle("-fx-background-color: transparent;");
        mainLayout.getChildren().addAll(buildSidebar(), buildCenterView(), buildRightPanel());
        
        MediaView backgroundVideoView = null;
        try {
            java.net.URL videoUrl = getClass().getResource("/BG_video.mp4");
            if (videoUrl != null) {
                Media media = new Media(videoUrl.toExternalForm());
                MediaPlayer mediaPlayer = new MediaPlayer(media);
                mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                mediaPlayer.setMute(true);
                mediaPlayer.play();
                
                backgroundVideoView = new MediaView(mediaPlayer);
                backgroundVideoView.setPreserveRatio(false);
                backgroundVideoView.setOpacity(0.25);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        StackPane rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: #f8fafc;");
        if (backgroundVideoView != null) {
            rootStack.getChildren().add(backgroundVideoView);
            backgroundVideoView.fitWidthProperty().bind(rootStack.widthProperty());
            backgroundVideoView.fitHeightProperty().bind(rootStack.heightProperty());
        }
        rootStack.getChildren().add(mainLayout);
        
        setCenter(rootStack);

        setupInteractionWiring();
        chatPanel.setOnQueryResult(result -> {
            graphCanvas.highlightQueryResults(result.getHighlightNodeIds(), result.getHighlightRelationIds());
            if (result.getHighlightNodeIds() != null && !result.getHighlightNodeIds().isEmpty()) {
                var firstMatch = graphCanvas.getNode(result.getHighlightNodeIds().get(0));
                if (firstMatch != null) graphCanvas.selectAndFocusNode(firstMatch);
            }
        });
    }

    public void loadCase(CaseDto caseDto) {
        this.currentCase = caseDto;
        String caseId = caseDto != null ? caseDto.getCaseId() : "—";
        caseBadge.setText("CASE: " + caseId.toUpperCase());
        chatPanel.setCaseId(caseId);

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
                    detailsPanel.showPlaceholder();
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
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(16);
        sidebar.setPrefWidth(260);
        sidebar.setMinWidth(260);
        sidebar.setPadding(new Insets(20));
        sidebar.setStyle("-fx-background-color: rgba(248, 250, 252, 0.7); -fx-border-color: #e2e8f0; -fx-border-width: 0 1px 0 0;");

        // Brand
        HBox brand = new HBox(8);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(0, 0, 16, 0));
        Label icon = new Label("✨");
        icon.setStyle("-fx-font-size: 33.6px;");
        Label title = new Label("Sherlock");
        title.setStyle("-fx-font-size: 28.8px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        brand.getChildren().addAll(icon, title);

        // Navigation
        VBox navMenu = new VBox(4);

        Button navHome = createNavButton("🏠", "Home");
        navHome.getStyleClass().add("nav-item-active");
        navHome.setOnAction(e -> {
            if (onNewCaseRequested != null)
                onNewCaseRequested.run();
        });

        Button navGraph = createNavButton("🕸", "Knowledge Graph");
        navGraph.setOnAction(e -> {
            if (graphBtn != null) graphBtn.setSelected(true);
        });

        Button navTimeline = createNavButton("⏳", "Timeline");
        navTimeline.setOnAction(e -> {
            if (timelineBtn != null) timelineBtn.setSelected(true);
        });

        Button navContradictions = createNavButton("⚡", "Contradictions");
        navContradictions.setOnAction(e -> {
            if (contradictionsBtn != null) contradictionsBtn.setSelected(true);
        });

        Button navDocs = createNavButton("📄", "Documents");
        Button navSaved = createNavButton("🔖", "Saved Views");
        Button navSettings = createNavButton("⚙️", "Settings");
        navSettings.setOnAction(e -> showSettingsDialog());

        navMenu.getChildren().addAll(navHome, navGraph, navTimeline, navContradictions, navDocs, navSettings);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // System Online Status
        VBox statusBox = new VBox(4);
        statusBox.setPadding(new Insets(12));
        statusBox.setStyle(
                "-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-background-radius: 8px; -fx-border-radius: 8px;");
        HBox onlineStatus = new HBox(6);
        onlineStatus.setAlignment(Pos.CENTER_LEFT);
        Circle dot = new Circle(4, Color.web("#10b981"));
        Label onlineLbl = new Label("System Online");
        onlineLbl.setStyle("-fx-font-size: 15.6px; -fx-font-weight: 600; -fx-text-fill: #0f172a;");
        onlineStatus.getChildren().addAll(dot, onlineLbl);

        Label version = new Label("v1.0.0");
        version.setStyle("-fx-font-size: 13.2px; -fx-text-fill: #94a3b8;");
        statusBox.getChildren().addAll(onlineStatus, version);

        // User Profile Mockup
        HBox userBox = new HBox(10);
        userBox.setAlignment(Pos.CENTER_LEFT);
        userBox.setPadding(new Insets(10, 0, 0, 0));

        Label avatar = new Label("S");
        avatar.setAlignment(Pos.CENTER);
        avatar.setMinSize(32, 32);
        avatar.setStyle(
                "-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 16px;");

        VBox userInfo = new VBox(2);
        Label userName = new Label("Sherlock Admin");
        userName.setStyle("-fx-font-size: 16.8px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        Label userRole = new Label("Investigator");
        userRole.setStyle("-fx-font-size: 14.4px; -fx-text-fill: #64748b;");
        userInfo.getChildren().addAll(userName, userRole);

        userBox.getChildren().addAll(avatar, userInfo);

        sidebar.getChildren().addAll(brand, navMenu, spacer, statusBox, userBox);
        return sidebar;
    }

    private Button createNavButton(String iconText, String text) {
        Button btn = new Button(iconText + "   " + text);
        btn.getStyleClass().add("nav-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        return btn;
    }

    private VBox buildCenterView() {
        VBox center = new VBox(0);
        HBox.setHgrow(center, Priority.ALWAYS);
        center.setFillWidth(true);

        // Header
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.getStyleClass().add("glass-toolbar");
        header.setStyle("-fx-background-color: rgba(255, 255, 255, 0.6);");

        

        ToggleGroup group = new ToggleGroup();
        graphBtn = new ToggleButton("🕸 Graph View");
        timelineBtn = new ToggleButton("⏳ Timeline View");
        contradictionsBtn = new ToggleButton("⚡ Contradictions");
        graphBtn.setToggleGroup(group);
        timelineBtn.setToggleGroup(group);
        contradictionsBtn.setToggleGroup(group);
        graphBtn.setSelected(true);

        Runnable updateStyles = () -> {
            String baseStyle = "-fx-font-weight: 600; -fx-font-size: 13.5px; -fx-padding: 6px 12px; ";
            String selColors = "-fx-background-color: #ffffff; -fx-text-fill: #0f172a; -fx-border-color: #e2e8f0; ";
            String unselColors = "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-border-color: transparent; ";

            graphBtn.setStyle(baseStyle + (graphBtn.isSelected() ? selColors : unselColors)
                    + "-fx-background-radius: 6px;");
            timelineBtn.setStyle(baseStyle + (timelineBtn.isSelected() ? selColors : unselColors)
                    + "-fx-background-radius: 6px;");
            contradictionsBtn.setStyle(baseStyle + (contradictionsBtn.isSelected() ? selColors : unselColors)
                    + "-fx-background-radius: 6px;");
        };

        graphBtn.selectedProperty().addListener((obs, old, isSel) -> {
            updateStyles.run();
            if (isSel) {
                graphCanvas.setVisible(true);
                graphCanvas.setManaged(true);
                timelinePanel.setVisible(false);
                timelinePanel.setManaged(false);
                contradictionsPanel.setVisible(false);
                contradictionsPanel.setManaged(false);
                detailsPanel.setVisible(true);
                detailsPanel.setManaged(true);
            }
        });

        timelineBtn.selectedProperty().addListener((obs, old, isSel) -> {
            updateStyles.run();
            if (isSel) {
                timelinePanel.setVisible(true);
                timelinePanel.setManaged(true);
                graphCanvas.setVisible(false);
                graphCanvas.setManaged(false);
                contradictionsPanel.setVisible(false);
                contradictionsPanel.setManaged(false);
                detailsPanel.setVisible(true);
                detailsPanel.setManaged(true);
            }
        });

        contradictionsBtn.selectedProperty().addListener((obs, old, isSel) -> {
            updateStyles.run();
            if (isSel) {
                contradictionsPanel.setVisible(true);
                contradictionsPanel.setManaged(true);
                graphCanvas.setVisible(false);
                graphCanvas.setManaged(false);
                timelinePanel.setVisible(false);
                timelinePanel.setManaged(false);
                detailsPanel.setVisible(false);
                detailsPanel.setManaged(false);
            }
        });

        group.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == null)
                old.setSelected(true);
        });

        updateStyles.run();

        HBox toggleWrapper = new HBox(4);
        toggleWrapper.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 8px; -fx-padding: 4px;");
        toggleWrapper.getChildren().addAll(graphBtn, timelineBtn, contradictionsBtn);

        Region hSpacer = new Region();
        HBox.setHgrow(hSpacer, Priority.ALWAYS);

        Button refreshBtn = new Button("↻");
        refreshBtn.getStyleClass().add("tool-button");
        refreshBtn.setOnAction(e -> refreshGraphData());

        investigateBtn = new Button("🚀 Start Extraction");
        investigateBtn.getStyleClass().add("primary-button");
        investigateBtn.setStyle("-fx-padding: 6px 12px; -fx-font-size: 14px;");
        investigateBtn.setOnAction(e -> startInvestigation());

        header.getChildren().addAll(toggleWrapper, hSpacer, investigateBtn, refreshIndicator, caseBadge,
                nodeCountBadge, edgeCountBadge, refreshBtn);

        // Stack for Graph/Timeline/Contradictions
        StackPane viewStack = new StackPane();
        VBox.setVgrow(viewStack, Priority.ALWAYS);

        timelinePanel.setVisible(false);
        timelinePanel.setManaged(false);
        contradictionsPanel.setVisible(false);
        contradictionsPanel.setManaged(false);
        viewStack.getChildren().addAll(graphCanvas, timelinePanel, contradictionsPanel);
        
        viewStack.setOnMouseClicked(e -> {
            detailsPanel.setPrefHeight(345.0);
        });

        detailsPanel.setMinHeight(270.0);
        detailsPanel.setPrefHeight(345.0);

        SplitPane mainAreaSplit = new SplitPane();
        mainAreaSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox.setVgrow(mainAreaSplit, Priority.ALWAYS);
        
        mainAreaSplit.getItems().addAll(viewStack, detailsPanel);
        // Set initial divider position (e.g. 70% graph, 30% details)
        mainAreaSplit.setDividerPositions(0.7);
        
        detailsPanel.setMinHeight(150.0);
        detailsPanel.setPrefHeight(300.0);
        detailsPanel.setMaxHeight(600.0);

        center.getChildren().addAll(header, mainAreaSplit);
        return center;
    }
    
    private VBox buildHomeView() {
        VBox home = new VBox(20);
        home.setAlignment(Pos.CENTER);
        Label lbl = new Label("Sherlock Home");
        lbl.setStyle("-fx-font-size: 34.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        
        
        home.getChildren().addAll(lbl);
        return home;
    }
    
    private VBox buildSavedViews() {
        VBox saved = new VBox(20);
        saved.setPadding(new Insets(40));
        saved.setAlignment(Pos.TOP_LEFT);
        
        Label title = new Label("Saved Checkpoints");
        title.setStyle("-fx-font-size: 30.7px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        ListView<String> savedList = new ListView<>();
        savedList.getItems().addAll("Checkpoint 1 - Initial Graph", "Checkpoint 2 - Suspect Connections");
        VBox.setVgrow(savedList, Priority.ALWAYS);
        
        saved.getChildren().addAll(title, savedList);
        return saved;
    }
    
    private VBox buildRightPanel() {
        VBox right = new VBox();
        right.setPrefWidth(380);
        right.setMinWidth(380);
        right.setStyle("-fx-background-color: rgba(248, 250, 252, 0.7); -fx-border-color: #e2e8f0; -fx-border-width: 0 0 0 1px;");

        VBox.setVgrow(chatPanel, Priority.ALWAYS);
        right.getChildren().add(chatPanel);
        return right;
    }

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
                if (graphBtn != null) graphBtn.setSelected(true);
                var node = graphCanvas.getNode(entityName);
                if (node != null) {
                    graphCanvas.selectAndFocusNode(node);
                }
            }
        });

        contradictionsPanel.setOnNavigateToEntity(entityName -> {
            if (entityName != null && !entityName.isBlank()) {
                if (graphBtn != null) graphBtn.setSelected(true);
                var node = graphCanvas.getNode(entityName);
                if (node != null) {
                    graphCanvas.selectAndFocusNode(node);
                }
            }
        });
    }

    // (Timeline processing, Investigation progress dialogs, and settings dialogs
    // remain mostly structurally the same but their styles have been removed to let
    // CSS take over, or adjusted slightly for light theme)

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
        modelCombo.setEditable(false);
        modelCombo.getItems().addAll(
                "gpt-4o-mini",
                "gpt-4o",
                "deepseek-chat",
                "llama-3.3-70b-versatile",
                "mistral-large-latest",
                "ollama/llama3");
        modelCombo.setValue("gpt-4o-mini");
        modelCombo.setMaxWidth(Double.MAX_VALUE);

        modelBox.getChildren().addAll(modelLabel, modelCombo);

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
                        modelCombo.getItems().setAll(models);
                        modelCombo.setValue(models.get(0));
                        modelCombo.setDisable(false);
                    } else {
                        modelCombo.getItems().clear();
                        modelCombo.setPromptText("No Ollama models found");
                        modelCombo.setValue(null);
                        modelCombo.setDisable(true);
                    }
                }));
            } else {
                apiKeyField.setDisable(false);
                apiKeyField.setPromptText("sk-... (required for cloud)");
                keyLabel.setText("Provide API key");
                modelLabel.setText("Model Name");
                modelCombo.setDisable(false);

                if ("DeepSeek".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("deepseek-chat", "deepseek-coder");
                    modelCombo.setValue("deepseek-chat");
                } else if ("Groq".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("llama-3.3-70b-versatile", "mixtral-8x7b-32768");
                    modelCombo.setValue("llama-3.3-70b-versatile");
                } else if ("OpenRouter".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet");
                    modelCombo.setValue("openai/gpt-4o-mini");
                } else if ("Mistral".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("mistral-large-latest");
                    modelCombo.setValue("mistral-large-latest");
                } else {
                    modelCombo.getItems().setAll("gpt-4o-mini", "gpt-4o");
                    modelCombo.setValue("gpt-4o-mini");
                }
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
            if (currentCase.getLlmConfig().getProvider() != null) {
                String prov = currentCase.getLlmConfig().getProvider().toLowerCase();
                if (prov.contains("openai")) providerCombo.setValue("OpenAI");
                else if (prov.contains("anthropic") || prov.contains("openrouter")) providerCombo.setValue("OpenRouter");
                else if (prov.contains("deepseek")) providerCombo.setValue("DeepSeek");
                else if (prov.contains("groq")) providerCombo.setValue("Groq");
                else if (prov.contains("mistral")) providerCombo.setValue("Mistral");
                else if (prov.contains("ollama")) providerCombo.setValue("Ollama");
                else providerCombo.setValue("Custom");
            }
            if (currentCase.getLlmConfig().getModel() != null && !currentCase.getLlmConfig().getModel().isBlank()) {
                // Ensure model is in the list, if not add it
                if (!modelCombo.getItems().contains(currentCase.getLlmConfig().getModel())) {
                    modelCombo.getItems().add(currentCase.getLlmConfig().getModel());
                }
                modelCombo.setValue(currentCase.getLlmConfig().getModel());
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
                config.setModel(modelCombo.getValue());
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
