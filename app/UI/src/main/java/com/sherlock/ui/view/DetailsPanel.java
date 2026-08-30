package com.sherlock.ui.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.GraphDataDto.NodeDto;
import com.sherlock.ui.model.TimelineEventDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.scene.Scene;

import java.util.*;
import java.util.function.Consumer;

public class DetailsPanel extends VBox {

    private final StackPane contentContainer = new StackPane();
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private Consumer<String> onNavigateToEntity;
    private NodeDto currentNode;
    private List<EdgeDto> currentEdges;
    private String currentTab = "overview";
    
    private com.sherlock.ui.SherlockBackendClient client;
    private String currentCaseId;
    
    private TimelineEventDto currentTimeline;
    
    // Local session storage for investigation notes (keyed by node ID)
    private final Map<String, String> nodeNotes = new HashMap<>();

    public void setTimelineData(TimelineEventDto timeline) {
        this.currentTimeline = timeline;
        if ("timeline".equals(currentTab) && currentNode != null) {
            javafx.application.Platform.runLater(() -> showNodeDetails(currentNode, currentEdges));
        }
    }

    public DetailsPanel() {
        getStyleClass().add("details-panel");
        setPadding(new Insets(0));
        setSpacing(0);
        setPrefHeight(260);
        setMinHeight(200);

        contentContainer.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        getChildren().add(contentContainer);
        showPlaceholder();
    }

    public void setOnNavigateToEntity(Consumer<String> onNavigateToEntity) {
        this.onNavigateToEntity = onNavigateToEntity;
    }
    
    public void setClientAndCaseId(com.sherlock.ui.SherlockBackendClient client, String caseId) {
        this.client = client;
        this.currentCaseId = caseId;
    }

    public void showPlaceholder() {
        HBox placeholder = new HBox(14);
        placeholder.setAlignment(Pos.CENTER_LEFT);
        placeholder.setPadding(new Insets(24, 28, 24, 28));
        placeholder.setStyle("-fx-background-color: #ffffff; -fx-border-color: #edf1f6; -fx-border-width: 1 0 0 0;");

        Label icon = new Label("🕵️");
        icon.setStyle("-fx-font-size: 42px;");

        VBox textCol = new VBox(4);
        Label title = new Label("Investigation Inspector & Metadata");
        title.setStyle("-fx-font-size: 21px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        Label desc = new Label("Click any node or relationship to inspect its metadata, evidence provenance, and connected entities.");
        desc.setStyle("-fx-font-size: 18px; -fx-text-fill: #64748b;");
        desc.setWrapText(true);
        textCol.getChildren().addAll(title, desc);

        placeholder.getChildren().addAll(icon, textCol);
        contentContainer.getChildren().setAll(placeholder);
    }

    // =========================================================================
    // NODE DETAILS — 3-SECTION LAYOUT
    // =========================================================================

    public void showNodeDetails(NodeDto node, List<EdgeDto> relatedEdges) {
        if (node == null) {
            this.currentNode = null;
            this.currentEdges = null;
            showPlaceholder();
            return;
        }

        if (this.currentNode == null || !this.currentNode.getId().equals(node.getId())) {
            this.currentTab = "overview";
        }
        this.currentNode = node;
        this.currentEdges = relatedEdges;

        HBox root = new HBox(0);
        root.setStyle("-fx-background-color: #ffffff; -fx-border-color: #edf1f6; -fx-border-width: 1 0 0 0;");
        root.setFillHeight(true);

        // LEFT SECTION: Inspector navigation + entity identity
        VBox leftSection = buildLeftSection(node, relatedEdges);
        ScrollPane leftScroll = new ScrollPane(leftSection);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        leftScroll.getStyleClass().add("edge-to-edge");
        leftScroll.setPrefWidth(280);
        leftScroll.setMinWidth(250);

        // CENTER SECTION: Summary + Metadata
        VBox centerSection = buildCenterSection(node, relatedEdges);
        ScrollPane centerScroll = new ScrollPane(centerSection);
        centerScroll.setFitToWidth(true);
        centerScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        centerScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        centerScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        centerScroll.getStyleClass().add("edge-to-edge");
        HBox.setHgrow(centerScroll, Priority.ALWAYS);

        // RIGHT SECTION: Related Evidence
        VBox rightSection = buildRightSection(node);
        rightSection.setPrefWidth(300);
        rightSection.setMinWidth(260);

        // Separators
        Region sep1 = new Region();
        sep1.setPrefWidth(1);
        sep1.setMinWidth(1);
        sep1.setMaxWidth(1);
        sep1.setStyle("-fx-background-color: #edf1f6;");

        Region sep2 = new Region();
        sep2.setPrefWidth(1);
        sep2.setMinWidth(1);
        sep2.setMaxWidth(1);
        sep2.setStyle("-fx-background-color: #edf1f6;");

        root.getChildren().addAll(leftScroll, sep1, centerScroll, sep2, rightSection);
        contentContainer.getChildren().setAll(root);
    }

    private VBox buildLeftSection(NodeDto node, List<EdgeDto> relatedEdges) {
        VBox left = new VBox(0);
        left.setPadding(new Insets(12, 14, 12, 14));

        // Section title
        Label sectionTitle = new Label("Investigation Inspector & Metadata");
        sectionTitle.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569; -fx-padding: 0 0 10 0;");

        // Navigation tabs as vertical list
        VBox navList = new VBox(2);

        int connectionCount = relatedEdges != null ? relatedEdges.size() : 0;
        int mentions = node.getMentions() != null ? node.getMentions() : 1;

        Button overviewTab = createInspectorTab("📋  Overview", "overview".equals(currentTab));
        overviewTab.setOnAction(e -> switchTab("overview"));

        Button attribTab = createInspectorTab("📝  Attributes", "attributes".equals(currentTab));
        attribTab.setOnAction(e -> switchTab("attributes"));

        Button connTab = createInspectorTab("🔗  Connections  " + connectionCount, "connections".equals(currentTab));
        connTab.setOnAction(e -> switchTab("connections"));

        Button timelineTab = createInspectorTab("📅  Timeline", "timeline".equals(currentTab));
        timelineTab.setOnAction(e -> switchTab("timeline"));

        Button notesTab = createInspectorTab("📌  Notes  " + mentions, "notes".equals(currentTab));
        notesTab.setOnAction(e -> switchTab("notes"));

        navList.getChildren().addAll(overviewTab, attribTab, connTab, timelineTab, notesTab);

        // Entity card
        HBox entityCard = new HBox(10);
        entityCard.setAlignment(Pos.CENTER_LEFT);
        entityCard.setPadding(new Insets(12, 0, 0, 0));

        StackPane avatarPane = new StackPane();
        Circle bg = new Circle(24, getNodeColor(node.getType()));
        bg.setOpacity(0.15);
        Circle border = new Circle(24, Color.TRANSPARENT);
        border.setStroke(getNodeColor(node.getType()));
        border.setStrokeWidth(2.0);
        Label avatarIcon = new Label(getNodeIcon(node.getType()));
        avatarIcon.setStyle("-fx-font-size: 28px;");
        avatarPane.getChildren().addAll(bg, border, avatarIcon);

        VBox entityInfo = new VBox(3);
        Label entityName = new Label(node.getName() != null ? node.getName() : "Entity");
        entityName.setStyle("-fx-font-size: 21px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        entityName.setWrapText(true);

        HBox typeConfRow = new HBox(6);
        typeConfRow.setAlignment(Pos.CENTER_LEFT);
        Label typeLbl = new Label(node.getType() != null ? node.getType() : "ENTITY");
        typeLbl.getStyleClass().addAll("badge-pill", getTypeBadgeClass(node.getType()));
        typeLbl.setStyle(typeLbl.getStyle() + " -fx-font-size: 14px; -fx-padding: 3px 8px;");

        String confText = node.getConfidence() != null ? String.format("%.0f%%", node.getConfidence() * 100) : "98%";
        Label confLbl = new Label("• " + confText + " Confidence");
        confLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #10b981; -fx-font-weight: 600;");
        typeConfRow.getChildren().addAll(typeLbl, confLbl);

        Label descLbl = new Label("Primary individual in this investigation.");
        descLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");
        descLbl.setWrapText(true);

        entityInfo.getChildren().addAll(entityName, typeConfRow, descLbl);

        // Add Note button
        Button addNoteBtn = new Button("+ Add Note");
        addNoteBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-size: 15px; -fx-font-weight: 600; -fx-padding: 4px 10px; -fx-background-radius: 6px; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 6px;");
        addNoteBtn.setOnAction(e -> switchTab("notes"));

        entityCard.getChildren().addAll(avatarPane, entityInfo);

        left.getChildren().addAll(sectionTitle, navList, entityCard, addNoteBtn);
        return left;
    }

    private void switchTab(String tabName) {
        if (!tabName.equals(currentTab)) {
            currentTab = tabName;
            if (currentNode != null) {
                showNodeDetails(currentNode, currentEdges);
            }
        }
    }

    private VBox buildCenterSection(NodeDto node, List<EdgeDto> relatedEdges) {
        switch (currentTab) {
            case "attributes":
                return buildAttributesSection(node);
            case "connections":
                return buildConnectionsSection(node, relatedEdges);
            case "timeline":
                return buildTimelineSection(node);
            case "notes":
                return buildNotesSection(node);
            case "overview":
            default:
                return buildOverviewSection(node, relatedEdges);
        }
    }

    private VBox buildAttributesSection(NodeDto node) {
        VBox center = new VBox(10);
        center.setPadding(new Insets(12, 16, 12, 16));
        Label title = new Label("Raw Attributes");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        
        TextArea jsonArea = new TextArea();
        jsonArea.setEditable(false);
        jsonArea.setStyle("-fx-control-inner-background: #f8fafc; -fx-text-fill: #0369a1; -fx-font-family: monospace; -fx-font-size: 14px;");
        try {
            jsonArea.setText(objectMapper.writeValueAsString(node.getData()));
        } catch (Exception e) {
            jsonArea.setText("{}");
        }
        VBox.setVgrow(jsonArea, Priority.ALWAYS);
        center.getChildren().addAll(title, jsonArea);
        return center;
    }

    private VBox buildTimelineSection(NodeDto node) {
        VBox center = new VBox(10);
        center.setPadding(new Insets(12, 16, 12, 16));
        
        Label title = new Label("Timeline Events");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        
        if (currentTimeline == null || currentTimeline.getTimeline() == null || currentTimeline.getTimeline().isEmpty()) {
            return buildPlaceholderSection("Timeline Events", "No timeline events available for this case yet.");
        }
        
        VBox eventList = new VBox(12);
        boolean found = false;
        
        for (TimelineEventDto.TimelineEventItem event : currentTimeline.getTimeline()) {
            boolean matches = false;
            if (event.getEntitiesInvolved() != null) {
                for (String e : event.getEntitiesInvolved()) {
                    if (e.equals(node.getId()) || e.equals(node.getName())) {
                        matches = true;
                        break;
                    }
                }
            }
            
            // Fallback: If entities_involved didn't match (or is empty), check if the text contains the node name
            if (!matches && node.getName() != null) {
                String nameLower = node.getName().toLowerCase();
                if (event.getTitle() != null && event.getTitle().toLowerCase().contains(nameLower)) matches = true;
                else if (event.getDescription() != null && event.getDescription().toLowerCase().contains(nameLower)) matches = true;
                else if (event.getEvidenceText() != null && event.getEvidenceText().toLowerCase().contains(nameLower)) matches = true;
            }
            
            if (!matches) continue;
            found = true;
            
            VBox eventBox = new VBox(4);
            eventBox.setPadding(new Insets(8));
            eventBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-background-radius: 4px;");
            
            Label timeLbl = new Label(event.getTimestamp() != null ? event.getTimestamp() : "Unknown Time");
            timeLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #64748b;");
            
            Label titleLbl = new Label(event.getTitle());
            titleLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
            titleLbl.setWrapText(true);
            
            Label descLbl = new Label(event.getDescription());
            descLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #475569;");
            descLbl.setWrapText(true);
            
            eventBox.getChildren().addAll(timeLbl, titleLbl, descLbl);
            eventList.getChildren().add(eventBox);
        }
        
        if (!found) {
            return buildPlaceholderSection("Timeline Events", "No timeline events available for this specific entity.");
        }
        
        ScrollPane scrollPane = new ScrollPane(eventList);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-padding: 0;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        
        center.getChildren().addAll(title, scrollPane);
        return center;
    }

    private VBox buildNotesSection(NodeDto node) {
        VBox center = new VBox(10);
        center.setPadding(new Insets(12, 16, 12, 16));
        
        Label title = new Label("Investigation Notes");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        
        TextArea textArea = new TextArea();
        textArea.setPromptText("Enter your investigation notes for " + node.getName() + " here...");
        textArea.setWrapText(true);
        textArea.setStyle("-fx-control-inner-background: #ffffff; -fx-font-size: 14px; -fx-text-fill: #334155;");
        VBox.setVgrow(textArea, Priority.ALWAYS);
        
        // Load existing note if any
        if (node.getId() != null && nodeNotes.containsKey(node.getId())) {
            textArea.setText(nodeNotes.get(node.getId()));
        }
        
        Button saveBtn = new Button("Save Note");
        saveBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 6px 16px; -fx-background-radius: 6px; -fx-cursor: hand;");
        saveBtn.setOnAction(e -> {
            if (node.getId() != null) {
                nodeNotes.put(node.getId(), textArea.getText());
            }
            saveBtn.setText("Saved!");
            saveBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 6px 16px; -fx-background-radius: 6px; -fx-cursor: hand;");
            
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
            pause.setOnFinished(ev -> {
                saveBtn.setText("Save Note");
                saveBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 6px 16px; -fx-background-radius: 6px; -fx-cursor: hand;");
            });
            pause.play();
        });
        
        HBox btnContainer = new HBox(saveBtn);
        btnContainer.setAlignment(Pos.CENTER_RIGHT);
        
        center.getChildren().addAll(title, textArea, btnContainer);
        return center;
    }

    private VBox buildConnectionsSection(NodeDto node, List<EdgeDto> relatedEdges) {
        VBox center = new VBox(10);
        center.setPadding(new Insets(12, 16, 12, 16));
        Label title = new Label("Connections (" + (relatedEdges != null ? relatedEdges.size() : 0) + ")");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        
        VBox list = new VBox(8);
        if (relatedEdges != null && !relatedEdges.isEmpty()) {
            for (EdgeDto edge : relatedEdges) {
                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8));
                row.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 6px; -fx-background-radius: 6px;");
                
                String targetId = edge.getSource().equals(node.getId()) ? edge.getTarget() : edge.getSource();
                String direction = edge.getSource().equals(node.getId()) ? "➔" : "⬅";
                
                Label dirLbl = new Label(direction);
                dirLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #94a3b8;");
                
                Button targetBtn = new Button(targetId);
                targetBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0284c7; -fx-font-size: 16px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 0;");
                targetBtn.setOnAction(e -> {
                    if (onNavigateToEntity != null) onNavigateToEntity.accept(targetId);
                });
                
                Label relBadge = new Label(edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED");
                relBadge.setStyle("-fx-background-color: rgba(37, 99, 235, 0.1); -fx-text-fill: #1d4ed8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 3px 10px; -fx-background-radius: 6px;");
                
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                
                Button viewBtn = new Button("View Edge");
                viewBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: 14px; -fx-cursor: hand; -fx-underline: true; -fx-padding: 0;");
                viewBtn.setOnAction(e -> showEdgeDetails(edge));
                
                row.getChildren().addAll(dirLbl, targetBtn, relBadge, spacer, viewBtn);
                list.getChildren().add(row);
            }
        } else {
            Label noConn = new Label("No connections found.");
            noConn.setStyle("-fx-font-size: 16px; -fx-text-fill: #94a3b8; -fx-font-style: italic;");
            list.getChildren().add(noConn);
        }
        
        ScrollPane scrollPane = new ScrollPane(list);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        
        center.getChildren().addAll(title, scrollPane);
        return center;
    }

    private VBox buildPlaceholderSection(String titleStr, String message) {
        VBox center = new VBox(10);
        center.setPadding(new Insets(12, 16, 12, 16));
        Label title = new Label(titleStr);
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        
        Label msgLbl = new Label(message);
        msgLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #94a3b8; -fx-font-style: italic;");
        
        center.getChildren().addAll(title, msgLbl);
        return center;
    }

    private VBox buildOverviewSection(NodeDto node, List<EdgeDto> relatedEdges) {
        VBox center = new VBox(10);
        center.setPadding(new Insets(12, 16, 12, 16));

        // Summary
        VBox summaryBox = new VBox(4);
        Label summaryTitle = new Label("Summary");
        summaryTitle.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");

        String summaryText = node.getName() + " is a key " + (node.getType() != null ? node.getType().toLowerCase() : "entity")
                + " connected to multiple entities across the investigation.";
        if (relatedEdges != null && !relatedEdges.isEmpty()) {
            summaryText += " Connected to " + relatedEdges.size() + " relationships.";
        }
        Label summaryLbl = new Label(summaryText);
        summaryLbl.setStyle("-fx-font-size: 17px; -fx-text-fill: #334155;");
        summaryLbl.setWrapText(true);
        summaryBox.getChildren().addAll(summaryTitle, summaryLbl);

        // Metadata grid
        VBox metaBox = new VBox(4);
        Label metaTitle = new Label("Metadata");
        metaTitle.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #475569;");

        GridPane metaGrid = new GridPane();
        metaGrid.setHgap(12);
        metaGrid.setVgap(4);

        int row = 0;
        addMetaRow(metaGrid, row++, "Entity ID", node.getId() != null ? node.getId() : "—");
        addMetaRow(metaGrid, row++, "Confidence", node.getConfidence() != null ? String.format("%.0f%%", node.getConfidence() * 100) : "98%");
        addMetaRow(metaGrid, row++, "Connections", String.valueOf(relatedEdges != null ? relatedEdges.size() : 0));
        addMetaRow(metaGrid, row++, "Mentions", String.valueOf(node.getMentions() != null ? node.getMentions() : 1));

        if (node.getSourceFiles() != null && !node.getSourceFiles().isEmpty()) {
            addMetaRow(metaGrid, row++, "Data Sources", String.valueOf(node.getSourceFiles().size()));
        }

        if (node.getData() != null && !node.getData().isEmpty()) {
            for (Map.Entry<String, Object> entry : node.getData().entrySet()) {
                String valStr = formatValue(entry.getValue());
                addMetaRow(metaGrid, row++, entry.getKey(), valStr);
            }
        }

        metaBox.getChildren().addAll(metaTitle, metaGrid);

        // Tags
        FlowPane tags = new FlowPane(6, 4);
        tags.setPadding(new Insets(6, 0, 0, 0));

        int degree = relatedEdges != null ? relatedEdges.size() : 0;
        if (degree >= 3) {
            tags.getChildren().add(createTag("Key Individual", "#1e40af", "#dbeafe"));
        }
        if (degree >= 5) {
            tags.getChildren().add(createTag("High Connectivity", "#7c3aed", "#ede9fe"));
        }
        tags.getChildren().add(createTag("Active", "#059669", "#d1fae5"));

        if (node.getAliases() != null && !node.getAliases().isEmpty()) {
            VBox aliasBox = new VBox(4);
            aliasBox.setPadding(new Insets(4, 0, 0, 0));
            Label aliasTitle = new Label("Aliases");
            aliasTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #475569;");
            Label aliasLbl = new Label(String.join(", ", node.getAliases()));
            aliasLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b; -fx-font-style: italic;");
            aliasLbl.setWrapText(true);
            aliasBox.getChildren().addAll(aliasTitle, aliasLbl);
            center.getChildren().addAll(summaryBox, metaBox, tags, aliasBox);
        } else {
            center.getChildren().addAll(summaryBox, metaBox, tags);
        }

        return center;
    }

    private VBox buildRightSection(NodeDto node) {
        VBox right = new VBox(8); // Increased spacing
        right.setPadding(new Insets(12, 14, 12, 14));

        // Title + View All
        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        int sourceCount = node.getSourceFiles() != null ? node.getSourceFiles().size() : 0;
        Label title = new Label("Related Evidence (" + sourceCount + ")");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label viewAll = new Label("View All");
        viewAll.setStyle("-fx-font-size: 16px; -fx-text-fill: #2563eb; -fx-cursor: hand; -fx-font-weight: 600;");
        titleRow.getChildren().addAll(title, spacer, viewAll);

        right.getChildren().add(titleRow);

        // Evidence file list
        VBox fileList = new VBox(6); // Increased spacing
        if (node.getSourceFiles() != null && !node.getSourceFiles().isEmpty()) {
            for (String file : node.getSourceFiles()) {
                HBox fileRow = new HBox(8);
                fileRow.setAlignment(Pos.CENTER_LEFT);
                fileRow.setPadding(new Insets(8, 8, 8, 8)); // increased padding
                fileRow.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 6px;");

                Label fileIcon = new Label(getFileIcon(file));
                fileIcon.setStyle("-fx-font-size: 20px;");
                fileIcon.setMinWidth(Region.USE_PREF_SIZE); // prevent icon from shrinking

                VBox fileInfo = new VBox(2);
                HBox.setHgrow(fileInfo, Priority.ALWAYS);
                Label fileName = new Label(file);
                fileName.setStyle("-fx-font-size: 17px; -fx-font-weight: 600; -fx-text-fill: #0f172a;");
                fileName.setMinWidth(0); // Allow shrinking and truncation
                fileName.setMaxWidth(Double.MAX_VALUE);
                fileName.setTextOverrun(OverrunStyle.ELLIPSIS);
                
                Label fileSource = new Label("Source: " + getFileSource(file));
                fileSource.setStyle("-fx-font-size: 14px; -fx-text-fill: #94a3b8;");
                fileSource.setMinWidth(0); // Allow shrinking and truncation
                fileSource.setMaxWidth(Double.MAX_VALUE);
                fileSource.setTextOverrun(OverrunStyle.ELLIPSIS);
                
                fileInfo.getChildren().addAll(fileName, fileSource);

                Label viewIcon = new Label("👁");
                viewIcon.setStyle("-fx-font-size: 17px; -fx-text-fill: #94a3b8; -fx-cursor: hand;");
                viewIcon.setMinWidth(Region.USE_PREF_SIZE); // prevent icon from shrinking
                viewIcon.setOnMouseClicked(e -> showDocumentPopup(file, node.getChunkIds()));

                fileRow.getChildren().addAll(fileIcon, fileInfo, viewIcon);
                fileList.getChildren().add(fileRow);
            }
        } else {
            Label noFiles = new Label("Evidence from case warehouse.");
            noFiles.setStyle("-fx-font-size: 16px; -fx-text-fill: #94a3b8; -fx-font-style: italic;");
            fileList.getChildren().add(noFiles);
        }

        // Chunk IDs section
        if (node.getChunkIds() != null && !node.getChunkIds().isEmpty()) {
            Label chunkTitle = new Label("Chunk Sources:");
            chunkTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #475569; -fx-padding: 8 0 2 0;");
            fileList.getChildren().add(chunkTitle);

            FlowPane chunkBadges = new FlowPane(6, 6); // increased spacing
            for (String chunkId : node.getChunkIds()) {
                Label chunkBadge = new Label("🧩 " + chunkId);
                chunkBadge.setStyle("-fx-background-color: #f0f9ff; -fx-text-fill: #0284c7; -fx-font-size: 14px; -fx-padding: 3px 8px; -fx-background-radius: 4px;");
                chunkBadge.setMinWidth(0);
                chunkBadge.setMaxWidth(180); // Prevent gigantic single chunks from breaking layout
                chunkBadge.setWrapText(false);
                chunkBadge.setTextOverrun(OverrunStyle.ELLIPSIS);
                chunkBadges.getChildren().add(chunkBadge);
            }
            fileList.getChildren().add(chunkBadges);
        }

        ScrollPane scrollPane = new ScrollPane(fileList);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); // Hide horizontal bar, let content truncate
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED); // Ensure vertical bar appears
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.getStyleClass().add("edge-to-edge"); // Removes border
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        right.getChildren().add(scrollPane);
        return right;
    }

    private void showDocumentPopup(String filename, List<String> chunkIds) {
        Stage stage = new Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        stage.setTitle("Evidence Viewer - " + filename);
        
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #ffffff;");
        
        // Header
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        
        Label icon = new Label(getFileIcon(filename));
        icon.setStyle("-fx-font-size: 32px;");
        
        VBox titleBox = new VBox(2);
        Label title = new Label(filename);
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        Label subtitle = new Label("Extracted Evidence Chunks");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");
        titleBox.getChildren().addAll(title, subtitle);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button closeBtn = new Button("Close");
        closeBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 8px 16px; -fx-background-radius: 6px; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 6px;");
        closeBtn.setOnAction(e -> stage.close());
        
        header.getChildren().addAll(icon, titleBox, spacer, closeBtn);
        
        // Content Area
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-control-inner-background: #f8fafc; -fx-font-family: monospace; -fx-font-size: 15px; -fx-text-fill: #334155; -fx-padding: 12px; -fx-border-color: #e2e8f0; -fx-border-radius: 6px;");
        
        if (client != null && currentCaseId != null) {
            textArea.setText("Loading evidence for " + filename + "...\n\nFetching from case warehouse...");
            client.getChunksAsync(currentCaseId)
                .thenAccept(content -> {
                    javafx.application.Platform.runLater(() -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode jsonRoot = objectMapper.readTree(content);
                            com.fasterxml.jackson.databind.JsonNode chunksArray = jsonRoot.path("chunks");
                            StringBuilder sb = new StringBuilder();
                            if (chunksArray.isArray()) {
                                for (com.fasterxml.jackson.databind.JsonNode chunk : chunksArray) {
                                    String sourceFile = chunk.path("source_file").asText();
                                    String chunkId = chunk.path("chunk_id").asText();
                                    if (sourceFile.equals(filename) && (chunkIds == null || chunkIds.isEmpty() || chunkIds.contains(chunkId))) {
                                        sb.append("--- CHUNK: ").append(chunkId).append(" ---\n");
                                        sb.append(chunk.path("text").asText()).append("\n\n");
                                    }
                                }
                            }
                            if (sb.length() == 0) {
                                textArea.setText("No specific evidence chunks found for this file.");
                            } else {
                                textArea.setText(sb.toString().trim());
                            }
                        } catch (Exception e) {
                            textArea.setText("Error parsing evidence text:\n\n" + e.getMessage());
                        }
                    });
                })
                .exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        textArea.setText("Error loading evidence file:\n\n" + ex.getMessage());
                    });
                    return null;
                });
        } else {
            textArea.setText("Cannot load file: Client or Case ID not provided.");
        }
        
        VBox.setVgrow(textArea, Priority.ALWAYS);
        
        root.getChildren().addAll(header, textArea);
        
        Scene scene = new Scene(root, 750, 550);
        stage.setScene(scene);
        stage.show();
    }

    // =========================================================================
    // EDGE DETAILS
    // =========================================================================

    public void showEdgeDetails(EdgeDto edge) {
        if (edge == null) {
            showPlaceholder();
            return;
        }

        HBox root = new HBox(16);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(14, 20, 14, 20));
        root.setStyle("-fx-background-color: #ffffff; -fx-border-color: #edf1f6; -fx-border-width: 1 0 0 0;");

        StackPane iconPane = new StackPane();
        Circle bg = new Circle(24, Color.web("#f1f5f9"));
        bg.setStroke(Color.web("#3B82F6"));
        bg.setStrokeWidth(2.0);
        Label icon = new Label("➔");
        icon.setStyle("-fx-font-size: 25px; -fx-text-fill: #3b82f6;");
        iconPane.getChildren().addAll(bg, icon);

        VBox mainCol = new VBox(6);
        mainCol.setPrefWidth(280);
        mainCol.setMinWidth(240);

        HBox entitiesRow = new HBox(6);
        entitiesRow.setAlignment(Pos.CENTER_LEFT);

        Button srcBtn = new Button(edge.getSource());
        srcBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0284c7; -fx-font-size: 20px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 0;");
        srcBtn.setOnAction(e -> {
            if (onNavigateToEntity != null) onNavigateToEntity.accept(edge.getSource());
        });

        Label arrow = new Label("➔");
        arrow.setStyle("-fx-text-fill: #64748b; -fx-font-size: 18px;");

        Button tgtBtn = new Button(edge.getTarget());
        tgtBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0284c7; -fx-font-size: 20px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 0;");
        tgtBtn.setOnAction(e -> {
            if (onNavigateToEntity != null) onNavigateToEntity.accept(edge.getTarget());
        });

        entitiesRow.getChildren().addAll(srcBtn, arrow, tgtBtn);

        HBox badges = new HBox(8);
        badges.setAlignment(Pos.CENTER_LEFT);

        Label relBadge = new Label(edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED");
        relBadge.setStyle("-fx-background-color: rgba(37, 99, 235, 0.1); -fx-text-fill: #1d4ed8; -fx-font-weight: bold; -fx-font-size: 17px; -fx-padding: 3px 10px; -fx-background-radius: 6px;");

        String confPct = edge.getConfidence() != null ? String.format("%.0f%%", edge.getConfidence() * 100) : "100%";
        Label confBadge = new Label("Confidence: " + confPct);
        confBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.1); -fx-text-fill: #059669; -fx-font-size: 16px; -fx-padding: 3px 8px; -fx-background-radius: 6px;");

        badges.getChildren().addAll(relBadge, confBadge);

        if (edge.getSourceFile() != null && !edge.getSourceFile().isBlank()) {
            Label srcFile = new Label("📄 " + edge.getSourceFile() + (edge.getChunkId() != null ? " (" + edge.getChunkId() + ")" : ""));
            srcFile.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");
            mainCol.getChildren().addAll(entitiesRow, badges, srcFile);
        } else {
            mainCol.getChildren().addAll(entitiesRow, badges);
        }

        VBox evidenceBox = new VBox(4);
        evidenceBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #edf1f6; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 10px;");
        HBox.setHgrow(evidenceBox, Priority.ALWAYS);

        Label evTitle = new Label("Evidence Citation");
        evTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #475569;");

        String snippet = edge.getEvidenceText() != null && !edge.getEvidenceText().isBlank()
                ? edge.getEvidenceText()
                : "Relationship verified from case evidence documentation.";
        Label snippetLbl = new Label("\"" + snippet + "\"");
        snippetLbl.setStyle("-fx-font-style: italic; -fx-text-fill: #334155; -fx-font-size: 17px;");
        snippetLbl.setWrapText(true);

        ScrollPane evScroll = new ScrollPane(snippetLbl);
        evScroll.setFitToWidth(true);
        evScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        evidenceBox.getChildren().addAll(evTitle, evScroll);

        root.getChildren().addAll(iconPane, mainCol, evidenceBox);
        contentContainer.getChildren().setAll(root);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private Button createInspectorTab(String text, boolean active) {
        Button tab = new Button(text);
        tab.setMaxWidth(Double.MAX_VALUE);
        tab.setAlignment(Pos.CENTER_LEFT);
        if (active) {
            tab.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #1d4ed8; -fx-font-size: 16px; -fx-font-weight: 600; -fx-padding: 5px 8px; -fx-background-radius: 6px; -fx-cursor: hand;");
        } else {
            tab.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: 16px; -fx-font-weight: 500; -fx-padding: 5px 8px; -fx-background-radius: 6px; -fx-cursor: hand;");
        }
        return tab;
    }

    private Label createTag(String text, String textColor, String bgColor) {
        Label tag = new Label(text);
        tag.setStyle("-fx-background-color: " + bgColor + "; -fx-text-fill: " + textColor + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 3px 8px; -fx-background-radius: 10px;");
        return tag;
    }

    private void addMetaRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #94a3b8; -fx-min-width: 80px;");

        Label val = new Label(value != null ? value : "—");
        val.setStyle("-fx-font-size: 16px; -fx-text-fill: #0f172a; -fx-font-weight: 600;");
        val.setWrapText(true);

        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    private String formatValue(Object val) {
        if (val == null) return "—";
        if (val instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        if (val instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (var entry : map.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(entry.getKey()).append(": ").append(entry.getValue());
            }
            return sb.toString();
        }
        return String.valueOf(val);
    }

    private String getFileIcon(String filename) {
        if (filename == null) return "📄";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "📕";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "🖼";
        if (lower.endsWith(".txt")) return "📝";
        return "📄";
    }

    private String getFileSource(String filename) {
        if (filename == null) return "Evidence";
        String lower = filename.toLowerCase();
        if (lower.contains("interview")) return "Interviews";
        if (lower.contains("bill") || lower.contains("receipt")) return "Documents";
        if (lower.contains("screenshot") || lower.contains("message")) return "Phone Data";
        if (lower.contains("note") || lower.contains("journal")) return "Personal Notes";
        return "Evidence";
    }

    private Color getNodeColor(String type) {
        if (type == null) return Color.web("#94A3B8");
        return switch (type.toUpperCase()) {
            case "PERSON" -> Color.web("#3B82F6");
            case "PHONE_NUMBER", "PHONE" -> Color.web("#06B6D4");
            case "DOCUMENT", "TRANSCRIPT" -> Color.web("#F59E0B");
            case "LOCATION" -> Color.web("#10B981");
            case "ORGANIZATION", "ORG" -> Color.web("#8B5CF6");
            case "MESSAGE", "MSG" -> Color.web("#0D9488");
            case "MEDICAL", "HOSPITAL", "HEALTH" -> Color.web("#F43F5E");
            case "EVENT" -> Color.web("#EF4444");
            default -> Color.web("#64748B");
        };
    }

    private String getNodeIcon(String type) {
        if (type == null) return "●";
        return switch (type.toUpperCase()) {
            case "PERSON" -> "👤";
            case "PHONE_NUMBER", "PHONE" -> "📞";
            case "DOCUMENT", "TRANSCRIPT" -> "📄";
            case "LOCATION" -> "📍";
            case "ORGANIZATION", "ORG" -> "🏢";
            case "MESSAGE", "MSG" -> "💬";
            case "MEDICAL", "HOSPITAL", "HEALTH" -> "✚";
            case "EVENT" -> "⚡";
            default -> "●";
        };
    }

    private String getTypeBadgeClass(String type) {
        if (type == null) return "badge-pill";
        return switch (type.toUpperCase()) {
            case "PERSON" -> "badge-person";
            case "PHONE_NUMBER", "PHONE" -> "badge-phone";
            case "DOCUMENT", "TRANSCRIPT" -> "badge-document";
            case "LOCATION" -> "badge-location";
            case "ORGANIZATION", "ORG" -> "badge-org";
            case "EVENT" -> "badge-event";
            default -> "badge-pill";
        };
    }
}
