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
    private final DetailsPanel detailsPanel = new DetailsPanel();
    private final ChatPanel chatPanel;
    private Button investigateBtn;

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
        icon.setStyle("-fx-font-size: 28px;");
        Label title = new Label("Sherlock");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
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
        Button navSearch = createNavButton("🔍", "Search");
        Button navDocs = createNavButton("📄", "Documents");
        Button navSaved = createNavButton("🔖", "Saved Views");
        Button navData = createNavButton("🗄", "Data Sources");
        Button navSettings = createNavButton("⚙️", "Settings");
        navSettings.setOnAction(e -> showSettingsDialog());

        navMenu.getChildren().addAll(navHome, navGraph, navSearch, navDocs, navSaved, navData, navSettings);

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
        onlineLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #0f172a;");
        onlineStatus.getChildren().addAll(dot, onlineLbl);

        Label version = new Label("v1.0.0");
        version.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
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
        userName.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        Label userRole = new Label("Investigator");
        userRole.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
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

        TextField searchBar = new TextField();
        searchBar.setPromptText("Ask anything or search the graph...");
        searchBar.getStyleClass().add("text-field");
        searchBar.setPrefWidth(300);

        ToggleGroup group = new ToggleGroup();
        ToggleButton graphBtn = new ToggleButton("🕸 Graph View");
        ToggleButton timelineBtn = new ToggleButton("⏳ Timeline View");
        graphBtn.setToggleGroup(group);
        timelineBtn.setToggleGroup(group);
        graphBtn.setSelected(true);

        Runnable updateStyles = () -> {
            String baseStyle = "-fx-font-weight: 600; -fx-font-size: 14px; -fx-padding: 8px 16px; ";
            String selColors = "-fx-background-color: #ffffff; -fx-text-fill: #0f172a; -fx-border-color: #e2e8f0; ";
            String unselColors = "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-border-color: transparent; ";

            graphBtn.setStyle(baseStyle + (graphBtn.isSelected() ? selColors : unselColors)
                    + "-fx-background-radius: 6px;");
            timelineBtn.setStyle(baseStyle + (timelineBtn.isSelected() ? selColors : unselColors)
                    + "-fx-background-radius: 6px;");
        };

        graphBtn.selectedProperty().addListener((obs, old, isSel) -> {
            updateStyles.run();
            if (isSel) {
                graphCanvas.setVisible(true);
                graphCanvas.setManaged(true);
                timelinePanel.setVisible(false);
                timelinePanel.setManaged(false);
            }
        });

        timelineBtn.selectedProperty().addListener((obs, old, isSel) -> {
            updateStyles.run();
            if (isSel) {
                timelinePanel.setVisible(true);
                timelinePanel.setManaged(true);
                graphCanvas.setVisible(false);
                graphCanvas.setManaged(false);
            }
        });

        group.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == null)
                old.setSelected(true);
        });

        updateStyles.run();

        HBox toggleWrapper = new HBox(4);
        toggleWrapper.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 8px; -fx-padding: 4px;");
        toggleWrapper.getChildren().addAll(graphBtn, timelineBtn);

        Region hSpacer = new Region();
        HBox.setHgrow(hSpacer, Priority.ALWAYS);

        Button refreshBtn = new Button("↻");
        refreshBtn.getStyleClass().add("tool-button");
        refreshBtn.setOnAction(e -> refreshGraphData());

        investigateBtn = new Button("🚀 Start Extraction");
        investigateBtn.getStyleClass().add("primary-button");
        investigateBtn.setStyle("-fx-padding: 8px 16px; -fx-font-size: 14px;");
        investigateBtn.setOnAction(e -> startInvestigation());

        header.getChildren().addAll(searchBar, toggleWrapper, hSpacer, investigateBtn, refreshIndicator, caseBadge,
                nodeCountBadge, edgeCountBadge, refreshBtn);

        // Stack for Graph/Timeline
        StackPane viewStack = new StackPane();
        VBox.setVgrow(viewStack, Priority.ALWAYS);

        timelinePanel.setVisible(false);
        timelinePanel.setManaged(false);
        viewStack.getChildren().addAll(graphCanvas, timelinePanel);

        // Inspector
        detailsPanel.setMinHeight(180);
        detailsPanel.setPrefHeight(240);
        detailsPanel.setMaxHeight(320);

        center.getChildren().addAll(header, viewStack, detailsPanel);
        return center;
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

        // Use default alert dialog stylings for brevity, rely on global CSS
        client.startTimelineProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                refreshGraphData();
            });
        });
    }

    private void startInvestigation() {
        if (currentCase == null || currentCase.getCaseId() == null)
            return;
        String caseId = currentCase.getCaseId();

        client.startProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                refreshGraphData();
            });
        });
    }

    private void showSettingsDialog() {
        if (currentCase == null)
            return;

        Dialog<LlmConfigDto> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Update Case Settings");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialogPane.setStyle("-fx-background-color: #ffffff;");

        // Quick dummy layout for settings to apply light theme
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField baseUrl = new TextField();
        PasswordField apiKey = new PasswordField();

        if (currentCase.getLlmConfig() != null) {
            if (currentCase.getLlmConfig().getBaseUrl() != null)
                baseUrl.setText(currentCase.getLlmConfig().getBaseUrl());
            if (currentCase.getLlmConfig().getApiKey() != null)
                apiKey.setText(currentCase.getLlmConfig().getApiKey());
        }

        grid.add(new Label("Base URL:"), 0, 0);
        grid.add(baseUrl, 1, 0);
        grid.add(new Label("API Key:"), 0, 1);
        grid.add(apiKey, 1, 1);

        dialogPane.setContent(grid);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                LlmConfigDto config = currentCase.getLlmConfig() != null ? currentCase.getLlmConfig()
                        : new LlmConfigDto();
                config.setBaseUrl(baseUrl.getText());
                config.setApiKey(apiKey.getText());
                return config;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(config -> {
            client.updateLlmConfigAsync(currentCase.getCaseId(), config);
        });
    }
}
