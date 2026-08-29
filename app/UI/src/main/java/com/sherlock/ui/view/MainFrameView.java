package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.model.GraphDataDto;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.GraphDataDto.NodeDto;
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
    private final DetailsPanel detailsPanel = new DetailsPanel();
    private final ChatPanel chatPanel;

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
        if (currentCase == null || currentCase.getCaseId() == null) return;

        refreshIndicator.setVisible(true);
        String caseId = currentCase.getCaseId();

        client.getGraphDataAsync(caseId)
                .thenAccept(graphData -> Platform.runLater(() -> {
                    refreshIndicator.setVisible(false);
                    this.currentGraph = graphData;
                    graphCanvas.setGraphData(graphData);

                    int nodes = graphData != null && graphData.getNodes() != null ? graphData.getNodes().size() : 0;
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
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-letter-spacing: 1.5px;");
        brand.getChildren().addAll(icon, title);

        // Case Badge
        caseBadge.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #38bdf8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 4px 12px; -fx-background-radius: 6px; -fx-border-color: #243048; -fx-border-radius: 6px;");

        // Stats Badges
        nodeCountBadge.setStyle("-fx-background-color: rgba(59, 130, 246, 0.15); -fx-text-fill: #60a5fa; -fx-font-size: 11px; -fx-padding: 4px 10px; -fx-background-radius: 6px;");
        edgeCountBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.15); -fx-text-fill: #34d399; -fx-font-size: 11px; -fx-padding: 4px 10px; -fx-background-radius: 6px;");

        refreshIndicator.setMaxSize(16, 16);
        refreshIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Action Buttons
        Button investigateBtn = new Button("🚀 Start Investigation");
        investigateBtn.getStyleClass().add("primary-button");
        investigateBtn.setStyle("-fx-background-color: #8b5cf6; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6px 14px;");
        investigateBtn.setOnAction(e -> startInvestigation());

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
            if (onNewCaseRequested != null) onNewCaseRequested.run();
        });

        bar.getChildren().addAll(brand, caseBadge, nodeCountBadge, edgeCountBadge, refreshIndicator, spacer, investigateBtn, historyBtn, refreshBtn, newCaseBtn);
        return bar;
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
                                        lblChunk.setStyle(doneStyle); lblChunk.setText("✅ Chunking");
                                        lblEntity.setStyle(doneStyle); lblEntity.setText("✅ Entity Extraction");
                                        lblRel.setStyle(doneStyle); lblRel.setText("✅ Relationship Extraction");
                                        lblMap.setStyle(activeStyle); lblMap.setText("🔄 Relation Mapping...");
                                    } else if (allLogs.contains("STAGE: Relationship Extraction")) {
                                        lblChunk.setStyle(doneStyle); lblChunk.setText("✅ Chunking");
                                        lblEntity.setStyle(doneStyle); lblEntity.setText("✅ Entity Extraction");
                                        lblRel.setStyle(activeStyle); lblRel.setText("🔄 Relationship Extraction...");
                                    } else if (allLogs.contains("STAGE: Entity Extraction")) {
                                        lblChunk.setStyle(doneStyle); lblChunk.setText("✅ Chunking");
                                        lblEntity.setStyle(activeStyle); lblEntity.setText("🔄 Entity Extraction...");
                                    } else if (allLogs.contains("STAGE: Chunking")) {
                                        lblChunk.setStyle(activeStyle); lblChunk.setText("🔄 Chunking...");
                                    }
                                }
                                
                                if (currStatus.isCompleted()) {
                                    lblMap.setStyle(doneStyle); lblMap.setText("✅ Relation Mapping");
                                    logArea.appendText("\n\nPipeline Completed!");
                                    dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(false);
                                    if (timelineArr[0] != null) timelineArr[0].stop();
                                    refreshGraphData();
                                }
                            });
                        });
                    })
                );
                timelineArr[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
                timelineArr[0].play();
                
                dialog.setOnHidden(ev -> {
                    if (timelineArr[0] != null) timelineArr[0].stop();
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

        VBox.setVgrow(graphCanvas, Priority.ALWAYS);
        graphCanvas.setMinHeight(280);

        detailsPanel.setMinHeight(160);
        detailsPanel.setPrefHeight(180);
        detailsPanel.setMaxHeight(200);

        leftCol.getChildren().addAll(graphCanvas, detailsPanel);

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
                        .filter(e -> e.getSource().equalsIgnoreCase(node.getName()) || e.getTarget().equalsIgnoreCase(node.getName()))
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
                Label empty = new Label("No cases found in Documents/Sherlock.");
                empty.setStyle("-fx-text-fill: #94a3b8;");
                listContainer.getChildren().add(empty);
            } else {
                for (CaseDto c : cases) {
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setStyle("-fx-background-color: #161e2e; -fx-padding: 8px 12px; -fx-background-radius: 6px; -fx-border-color: #243048; -fx-border-radius: 6px;");

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
}
