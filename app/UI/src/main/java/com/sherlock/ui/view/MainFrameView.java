package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.model.GraphDataDto;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

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
    private Button settingsBtn;

    public MainFrameView(SherlockBackendClient client, Runnable onNewCaseRequested) {
        this.client = client;
        this.onNewCaseRequested = onNewCaseRequested;
        this.chatPanel = new ChatPanel(client);

        getStyleClass().add("root");
        setStyle("-fx-background-color: #0b0e14;");

        setTop(buildTopBar());
        setCenter(buildMainSplit());

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

    private HBox buildTopBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setStyle("-fx-background-color: #121824; -fx-border-color: #1e293b; -fx-border-width: 0 0 1px 0;");

        // Sherlock Logo
        HBox brand = new HBox(6);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("🕵️");
        icon.setStyle("-fx-font-size: 18px;");
        Label title = new Label("SHERLOCK");
        title.setStyle(
                "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-letter-spacing: 1.5px;");
        brand.getChildren().addAll(icon, title);

        // Case Badge
        caseBadge.setStyle(
                "-fx-background-color: #1e293b; -fx-text-fill: #38bdf8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 4px 12px; -fx-background-radius: 6px; -fx-border-color: #243048; -fx-border-radius: 6px;");

        // Stats Badges
        nodeCountBadge.setStyle(
                "-fx-background-color: rgba(59, 130, 246, 0.15); -fx-text-fill: #60a5fa; -fx-font-size: 11px; -fx-padding: 4px 10px; -fx-background-radius: 6px;");
        edgeCountBadge.setStyle(
                "-fx-background-color: rgba(16, 185, 129, 0.15); -fx-text-fill: #34d399; -fx-font-size: 11px; -fx-padding: 4px 10px; -fx-background-radius: 6px;");

        refreshIndicator.setMaxSize(16, 16);
        refreshIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Action Buttons
        investigateBtn = new Button("🚀 Start Investigation");
        investigateBtn.getStyleClass().add("primary-button");
        investigateBtn.setStyle(
                "-fx-background-color: #8b5cf6; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6px 14px;");
        investigateBtn.setOnAction(e -> startInvestigation());

        settingsBtn = new Button("⚙ Settings");
        settingsBtn.getStyleClass().add("secondary-button");
        settingsBtn.setOnAction(e -> showSettingsDialog());

        Button historyBtn = new Button("📁 Case History");
        historyBtn.getStyleClass().add("secondary-button");
        historyBtn.setOnAction(e -> showCaseHistoryDialog());

        Button refreshBtn = new Button("↺ Refresh");
        refreshBtn.getStyleClass().add("secondary-button");
        refreshBtn.setOnAction(e -> refreshGraphData());

        Button newCaseBtn = new Button("＋ New Case");
        newCaseBtn.getStyleClass().add("primary-button");
        newCaseBtn.setStyle("-fx-font-size: 12px; -fx-padding: 6px 14px;");
        newCaseBtn.setOnAction(e -> {
            if (onNewCaseRequested != null)
                onNewCaseRequested.run();
        });

        bar.getChildren().addAll(brand, caseBadge, nodeCountBadge, edgeCountBadge, refreshIndicator, spacer,
                investigateBtn, settingsBtn, historyBtn, refreshBtn, newCaseBtn);
        return bar;
    }

    private void startTimelineExtraction() {
        if (currentCase == null || currentCase.getCaseId() == null) {
            return;
        }

        String caseId = currentCase.getCaseId();

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Timeline Extraction");
        dialog.setHeaderText("Running Timeline Extraction for Case: " + caseId);

        VBox content = new VBox(10);
        content.setPrefWidth(500);
        content.setPrefHeight(300);

        Label lblExtract = new Label("🔄 Extracting Timeline Events...");
        lblExtract.setStyle("-fx-text-fill: #38bdf8; -fx-font-size: 14px; -fx-font-weight: bold;");

        VBox stages = new VBox(5, lblExtract);
        stages.setPadding(new Insets(10));
        stages.setStyle("-fx-background-color: #161e2e; -fx-background-radius: 5px;");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-control-inner-background: #0b0e14; -fx-text-fill: #a1a1aa; -fx-font-family: monospace;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        content.getChildren().addAll(stages, logArea);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(true); // Disable until done

        client.startTimelineProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                javafx.animation.Timeline[] timelineArr = new javafx.animation.Timeline[1];
                timelineArr[0] = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                            client.getTimelineProcessingStatusAsync(caseId).thenAccept(currStatus -> {
                                Platform.runLater(() -> {
                                    if (currStatus != null && currStatus.getLogs() != null) {
                                        String allLogs = String.join("\n", currStatus.getLogs());
                                        logArea.setText(allLogs);
                                        logArea.setScrollTop(Double.MAX_VALUE);
                                    }

                                    if (currStatus != null && currStatus.isCompleted()) {
                                        if (currStatus.isSuccess()) {
                                            lblExtract.setStyle("-fx-text-fill: #34d399; -fx-font-size: 14px; -fx-font-weight: bold;");
                                            lblExtract.setText("✅ Timeline Extracted");
                                            logArea.appendText("\n\nTimeline Extraction Completed!");
                                        } else {
                                            lblExtract.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 14px; -fx-font-weight: bold;");
                                            lblExtract.setText("❌ Timeline Extraction Failed");
                                            logArea.appendText("\n\nTimeline Extraction Failed: " + (currStatus.getMessage() != null ? currStatus.getMessage() : "Unknown error"));
                                        }
                                        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(false);
                                        if (timelineArr[0] != null)
                                            timelineArr[0].stop();
                                        refreshGraphData();
                                    }
                                });
                            }).exceptionally(ex -> {
                                Platform.runLater(() -> {
                                    logArea.appendText("\nFailed to poll status: " + ex.getMessage());
                                });
                                return null;
                            });
                        }));
                timelineArr[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
                timelineArr[0].play();

                dialog.setOnHidden(ev -> {
                    if (timelineArr[0] != null)
                        timelineArr[0].stop();
                });
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                lblExtract.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 14px; -fx-font-weight: bold;");
                lblExtract.setText("❌ Failed to start extraction");
                logArea.setText("Error starting timeline extraction:\n" + ex.getMessage());
                dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(false);
            });
            return null;
        });

        dialog.show();
    }

    private void startInvestigation() {
        if (currentCase == null || currentCase.getCaseId() == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Please load or create a case first.");
            alert.show();
            return;
        }

        String caseId = currentCase.getCaseId();

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Investigation Progress");
        dialog.setHeaderText("Running Sherlock AI Pipeline on Case: " + caseId);

        VBox content = new VBox(10);
        content.setPrefWidth(500);
        content.setPrefHeight(350);

        Label lblChunk = new Label("⏳ Chunking...");
        Label lblEntity = new Label("⏳ Entity Extraction...");
        Label lblRel = new Label("⏳ Relationship Extraction...");
        Label lblMap = new Label("⏳ Relation Mapping...");

        String pendingStyle = "-fx-text-fill: #94a3b8; -fx-font-size: 14px;";
        String activeStyle = "-fx-text-fill: #38bdf8; -fx-font-size: 14px; -fx-font-weight: bold;";
        String doneStyle = "-fx-text-fill: #34d399; -fx-font-size: 14px; -fx-font-weight: bold;";

        lblChunk.setStyle(pendingStyle);
        lblEntity.setStyle(pendingStyle);
        lblRel.setStyle(pendingStyle);
        lblMap.setStyle(pendingStyle);

        VBox stages = new VBox(5, lblChunk, lblEntity, lblRel, lblMap);
        stages.setPadding(new Insets(10));
        stages.setStyle("-fx-background-color: #161e2e; -fx-background-radius: 5px;");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-control-inner-background: #0b0e14; -fx-text-fill: #a1a1aa; -fx-font-family: monospace;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        content.getChildren().addAll(stages, logArea);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(true); // Disable until done

        client.startProcessingAsync(caseId).thenAccept(status -> {
            Platform.runLater(() -> {
                javafx.animation.Timeline[] timelineArr = new javafx.animation.Timeline[1];
                timelineArr[0] = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                            client.getProcessingStatusAsync(caseId).thenAccept(currStatus -> {
                                Platform.runLater(() -> {
                                    if (currStatus.getLogs() != null) {
                                        String allLogs = String.join("\n", currStatus.getLogs());
                                        logArea.setText(allLogs);
                                        logArea.setScrollTop(Double.MAX_VALUE); // scroll down

                                        // Update Stage highlights
                                        if (allLogs.contains("STAGE: Relation Mapping")) {
                                            lblChunk.setStyle(doneStyle);
                                            lblChunk.setText("✅ Chunking");
                                            lblEntity.setStyle(doneStyle);
                                            lblEntity.setText("✅ Entity Extraction");
                                            lblRel.setStyle(doneStyle);
                                            lblRel.setText("✅ Relationship Extraction");
                                            lblMap.setStyle(activeStyle);
                                            lblMap.setText("🔄 Relation Mapping...");
                                        } else if (allLogs.contains("STAGE: Relationship Extraction")) {
                                            lblChunk.setStyle(doneStyle);
                                            lblChunk.setText("✅ Chunking");
                                            lblEntity.setStyle(doneStyle);
                                            lblEntity.setText("✅ Entity Extraction");
                                            lblRel.setStyle(activeStyle);
                                            lblRel.setText("🔄 Relationship Extraction...");
                                        } else if (allLogs.contains("STAGE: Entity Extraction")) {
                                            lblChunk.setStyle(doneStyle);
                                            lblChunk.setText("✅ Chunking");
                                            lblEntity.setStyle(activeStyle);
                                            lblEntity.setText("🔄 Entity Extraction...");
                                        } else if (allLogs.contains("STAGE: Chunking")) {
                                            lblChunk.setStyle(activeStyle);
                                            lblChunk.setText("🔄 Chunking...");
                                        }
                                    }

                                    if (currStatus.isCompleted()) {
                                        lblMap.setStyle(doneStyle);
                                        lblMap.setText("✅ Relation Mapping");
                                        logArea.appendText("\n\nPipeline Completed!");
                                        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(false);
                                        if (timelineArr[0] != null)
                                            timelineArr[0].stop();
                                        refreshGraphData();
                                    }
                                });
                            });
                        }));
                timelineArr[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
                timelineArr[0].play();

                dialog.setOnHidden(ev -> {
                    if (timelineArr[0] != null)
                        timelineArr[0].stop();
                });
            });
        });

        dialog.show();
    }

    private HBox buildMainSplit() {
        HBox split = new HBox(12);
        split.setPadding(new Insets(12));
        split.setFillHeight(true);

        // Left Column (Graph Canvas on Top + Details Panel on Bottom)
        VBox leftCol = new VBox(12);
        HBox.setHgrow(leftCol, Priority.ALWAYS);
        leftCol.setFillWidth(true);

        HBox toggleBox = new HBox(0);
        toggleBox.setAlignment(Pos.CENTER);
        ToggleGroup group = new ToggleGroup();
        ToggleButton graphBtn = new ToggleButton("🕸 Graph View");
        ToggleButton timelineBtn = new ToggleButton("⏳ Timeline View");
        
        graphBtn.setToggleGroup(group);
        timelineBtn.setToggleGroup(group);
        graphBtn.setSelected(true);
        
        Runnable updateStyles = () -> {
            String baseStyle = "-fx-font-weight: bold; -fx-padding: 6px 16px; -fx-border-color: #0f172a; ";
            String selColors = "-fx-background-color: #38bdf8; -fx-text-fill: #0f172a; ";
            String unselColors = "-fx-background-color: #1e293b; -fx-text-fill: #94a3b8; ";
            
            graphBtn.setStyle(baseStyle + (graphBtn.isSelected() ? selColors : unselColors) 
                + "-fx-background-radius: 6px 0 0 6px; -fx-border-width: 1px 0 1px 1px; -fx-border-radius: 6px 0 0 6px;");
                
            timelineBtn.setStyle(baseStyle + (timelineBtn.isSelected() ? selColors : unselColors) 
                + "-fx-background-radius: 0 6px 6px 0; -fx-border-width: 1px 1px 1px 0; -fx-border-radius: 0 6px 6px 0;");
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
            if (newVal == null) old.setSelected(true);
        });
        
        updateStyles.run();
        toggleBox.getChildren().addAll(graphBtn, timelineBtn);

        StackPane viewStack = new StackPane();
        VBox.setVgrow(viewStack, Priority.ALWAYS);
        viewStack.setMinHeight(280);
        
        timelinePanel.setVisible(false);
        timelinePanel.setManaged(false);
        viewStack.getChildren().addAll(graphCanvas, timelinePanel);

        detailsPanel.setMinHeight(160);
        detailsPanel.setPrefHeight(180);
        detailsPanel.setMaxHeight(200);

        leftCol.getChildren().addAll(toggleBox, viewStack, detailsPanel);

        // Right Column (Chat Panel)
        chatPanel.setMinWidth(320);
        chatPanel.setPrefWidth(350);
        chatPanel.setMaxWidth(400);

        split.getChildren().addAll(leftCol, chatPanel);
        return split;
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
    }

    private void showCaseHistoryDialog() {
        Dialog<CaseDto> dialog = new Dialog<>();
        dialog.setTitle("Sherlock Case History");
        dialog.setHeaderText("Select an existing case to open:");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.CLOSE);
        dialogPane.setStyle("-fx-background-color: #121824;");

        VBox listContainer = new VBox(8);
        listContainer.setPadding(new Insets(10));
        listContainer.setPrefWidth(420);
        listContainer.setPrefHeight(280);

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        dialogPane.setContent(scroll);

        client.listCasesAsync().thenAccept(cases -> Platform.runLater(() -> {
            listContainer.getChildren().clear();
            if (cases.isEmpty()) {
                Label empty = new Label("No cases found.");
                empty.setStyle("-fx-text-fill: #94a3b8;");
                listContainer.getChildren().add(empty);
            } else {
                for (CaseDto c : cases) {
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setStyle(
                            "-fx-background-color: #161e2e; -fx-padding: 8px 12px; -fx-background-radius: 6px; -fx-border-color: #243048; -fx-border-radius: 6px;");

                    VBox info = new VBox(2);
                    Label nameLbl = new Label(c.getCaseName() + " (" + c.getCaseId() + ")");
                    nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-font-size: 12px;");

                    Label detailLbl = new Label(c.getFileCount() + " files • Status: " + c.getStatus());
                    detailLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8;");
                    info.getChildren().addAll(nameLbl, detailLbl);

                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);

                    Button openBtn = new Button("Open ➔");
                    openBtn.getStyleClass().add("secondary-button");
                    openBtn.setOnAction(e -> {
                        loadCase(c);
                        dialog.close();
                    });

                    row.getChildren().addAll(info, sp, openBtn);
                    listContainer.getChildren().add(row);
                }
            }
        }));

        dialog.showAndWait();
    }

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

        ComboBox<String> provider = new ComboBox<>();
        provider.setEditable(true);
        provider.getItems().addAll("Ollama", "OpenAI", "DeepSeek", "OpenRouter", "Groq", "Mistral", "Custom");
        provider.setPromptText("openai, ollama, deepseek, etc.");

        ComboBox<String> model = new ComboBox<>();
        model.setEditable(true);
        model.setPromptText("Model name (e.g. gpt-4o-mini)");

        TextField baseUrl = new TextField();
        baseUrl.setPromptText("API Base URL (optional)");
        PasswordField apiKey = new PasswordField();
        apiKey.setPromptText("API Key (optional)");

        // Pre-fill existing config if available
        String existingProvider = null;
        String existingModel = null;
        if (currentCase.getLlmConfig() != null) {
            existingProvider = currentCase.getLlmConfig().getProvider();
            existingModel = currentCase.getLlmConfig().getModel();
            if (currentCase.getLlmConfig().getBaseUrl() != null) baseUrl.setText(currentCase.getLlmConfig().getBaseUrl());
            if (currentCase.getLlmConfig().getApiKey() != null) apiKey.setText(currentCase.getLlmConfig().getApiKey());
        }

        // Resolve the configured provider against the known list (keep custom values)
        final String configuredProvider = existingProvider;
        final String configuredModel = existingModel;
        if (configuredProvider != null && !configuredProvider.isBlank()
                && provider.getItems().stream().noneMatch(i -> i.equalsIgnoreCase(configuredProvider))) {
            provider.getItems().add(configuredProvider);
        }
        String providerValue = (configuredProvider == null || configuredProvider.isBlank()) ? "OpenAI" : configuredProvider;
        // Set the value before attaching the action handler so the initial
        // population below runs exactly once.
        provider.setValue(providerValue);

        provider.setOnAction(e -> applyLlmProviderDefaults(provider.getValue(), model, apiKey, configuredModel));

        // Populate the model selector for the initial provider (Ollama -> `ollama list`)
        applyLlmProviderDefaults(provider.getValue(), model, apiKey, configuredModel);

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
                config.setProvider(provider.getValue() != null ? provider.getValue().trim() : "");
                config.setModel(model.getValue() != null ? model.getValue().trim() : "");
                config.setBaseUrl(baseUrl.getText() != null ? baseUrl.getText().trim() : "");
                config.setApiKey(apiKey.getText() != null ? apiKey.getText().trim() : "");
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

    /**
     * Populates the settings dialog's model selector based on the chosen
     * provider. When Ollama is selected, the available local models are fetched
     * from the backend (the equivalent of `ollama list`) and the API key field
     * is disabled; other providers get their conventional default model lists.
     *
     * @param preferredModel the previously configured model to keep selected if present
     */
    private void applyLlmProviderDefaults(String providerValue, ComboBox<String> modelCombo,
                                          PasswordField apiKey, String preferredModel) {
        if (providerValue == null || providerValue.isBlank()) {
            return;
        }

        if ("Ollama".equalsIgnoreCase(providerValue)) {
            apiKey.setDisable(true);
            apiKey.clear();
            apiKey.setPromptText("Local Ollama (No API Key Required)");

            modelCombo.getItems().clear();
            modelCombo.setValue(null);
            modelCombo.setPromptText("Loading Ollama models...");
            client.getOllamaModelsAsync().thenAccept(models -> Platform.runLater(() -> {
                if (models != null && !models.isEmpty()) {
                    modelCombo.getItems().setAll(models);
                    if (preferredModel != null && !preferredModel.isBlank() && models.contains(preferredModel)) {
                        modelCombo.setValue(preferredModel);
                    } else {
                        modelCombo.setValue(models.get(0));
                    }
                    modelCombo.setDisable(false);
                    modelCombo.setPromptText(null);
                } else {
                    modelCombo.getItems().clear();
                    modelCombo.setValue(null);
                    modelCombo.setDisable(true);
                    modelCombo.setPromptText("No Ollama models found (is Ollama running?)");
                }
            }));
            return;
        }

        apiKey.setDisable(false);
        apiKey.setPromptText("sk-... (required for cloud provider)");
        modelCombo.setDisable(false);
        modelCombo.setPromptText(null);

        if ("DeepSeek".equalsIgnoreCase(providerValue)) {
            modelCombo.getItems().setAll("deepseek-chat", "deepseek-coder");
        } else if ("Groq".equalsIgnoreCase(providerValue)) {
            modelCombo.getItems().setAll("llama-3.3-70b-versatile", "mixtral-8x7b-32768");
        } else if ("OpenRouter".equalsIgnoreCase(providerValue)) {
            modelCombo.getItems().setAll("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "meta-llama/llama-3.3-70b-instruct");
        } else if ("Mistral".equalsIgnoreCase(providerValue)) {
            modelCombo.getItems().setAll("mistral-large-latest", "mistral-small-latest");
        } else {
            // OpenAI / Custom
            modelCombo.getItems().setAll("gpt-4o-mini", "gpt-4o", "gpt-4-turbo");
        }

        if (preferredModel != null && !preferredModel.isBlank()) {
            if (!modelCombo.getItems().contains(preferredModel)) {
                modelCombo.getItems().add(preferredModel);
            }
            modelCombo.setValue(preferredModel);
        } else {
            modelCombo.getSelectionModel().selectFirst();
        }
    }
}
