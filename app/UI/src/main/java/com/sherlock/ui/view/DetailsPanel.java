package com.sherlock.ui.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.GraphDataDto.NodeDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.*;
import java.util.function.Consumer;

public class DetailsPanel extends VBox {

    private final StackPane contentContainer = new StackPane();
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private Consumer<String> onNavigateToEntity;

    public DetailsPanel() {
        getStyleClass().add("glass-panel");
        setPadding(new Insets(12, 16, 12, 16));
        setSpacing(8);
        setPrefHeight(230);
        setMinHeight(200);

        Label headerTitle = new Label("INVESTIGATION INSPECTOR & METADATA");
        headerTitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-letter-spacing: 1px;");

        contentContainer.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        getChildren().addAll(headerTitle, contentContainer);
        showPlaceholder();
    }

    public void setOnNavigateToEntity(Consumer<String> onNavigateToEntity) {
        this.onNavigateToEntity = onNavigateToEntity;
    }

    public void showPlaceholder() {
        HBox placeholder = new HBox(14);
        placeholder.setAlignment(Pos.CENTER_LEFT);
        placeholder.setStyle("-fx-background-color: rgba(255, 255, 255, 0.6); -fx-padding: 18px 24px; -fx-background-radius: 8px; -fx-border-color: rgba(226, 232, 240, 0.5); -fx-border-radius: 8px;");

        Label icon = new Label("🕵️");
        icon.setStyle("-fx-font-size: 28px;");

        VBox textCol = new VBox(4);
        Label title = new Label("Investigation Knowledge Graph Inspector");
        title.setStyle("-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        Label desc = new Label("Click any node or relationship to inspect its complete metadata, evidence provenance, and connected investigation entities.");
        desc.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569;");
        textCol.getChildren().addAll(title, desc);

        placeholder.getChildren().addAll(icon, textCol);
        contentContainer.getChildren().setAll(placeholder);
    }

    public void showNodeDetails(NodeDto node, List<EdgeDto> relatedEdges) {
        if (node == null) {
            showPlaceholder();
            return;
        }

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-background-color: transparent;");

        Tab attrTab = new Tab("📋 Overview & Attributes", buildNodeOverview(node, relatedEdges));
        Tab relTab = new Tab("🔗 Connected Relationships (" + (relatedEdges != null ? relatedEdges.size() : 0) + ")", buildConnectionsList(node, relatedEdges));
        Tab provTab = new Tab("📄 Evidence & Sources", buildProvenanceTab(node));
        Tab jsonTab = new Tab("{ } Raw JSON", buildRawJsonTab(node));

        tabPane.getTabs().addAll(attrTab, relTab, provTab, jsonTab);
        contentContainer.getChildren().setAll(tabPane);
    }

    private HBox buildNodeOverview(NodeDto node, List<EdgeDto> relatedEdges) {
        HBox root = new HBox(16);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(8, 4, 4, 4));

        StackPane avatarPane = new StackPane();
        Circle bg = new Circle(28, Color.web("#f8fafc"));
        bg.setStroke(getNodeColor(node.getType()));
        bg.setStrokeWidth(2.2);
        Label avatarIcon = new Label(getNodeIcon(node.getType()));
        avatarIcon.setStyle("-fx-font-size: 20px;");
        avatarPane.getChildren().addAll(bg, avatarIcon);

        VBox mainCol = new VBox(5);
        mainCol.setPrefWidth(220);
        mainCol.setMinWidth(180);

        HBox nameRow = new HBox(6);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(node.getName());
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        nameLabel.setWrapText(true);
        nameRow.getChildren().add(nameLabel);

        HBox badgesRow = new HBox(6);
        badgesRow.setAlignment(Pos.CENTER_LEFT);

        Label typeBadge = new Label(node.getType() != null ? node.getType() : "ENTITY");
        typeBadge.getStyleClass().addAll("badge-pill", getTypeBadgeClass(node.getType()));

        int degree = relatedEdges != null ? relatedEdges.size() : 0;
        Label degreeBadge = new Label(degree + " Conn");
        degreeBadge.setStyle("-fx-background-color: rgba(56, 189, 248, 0.1); -fx-text-fill: #0284c7; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 2px 6px; -fx-background-radius: 4px;");

        Label mentionsBadge = new Label((node.getMentions() != null ? node.getMentions() : 1) + " Mentions");
        mentionsBadge.setStyle("-fx-background-color: rgba(148, 163, 184, 0.1); -fx-text-fill: #475569; -fx-font-size: 10px; -fx-padding: 2px 6px; -fx-background-radius: 4px;");

        badgesRow.getChildren().addAll(typeBadge, degreeBadge, mentionsBadge);
        mainCol.getChildren().addAll(nameRow, badgesRow);

        if (node.getAliases() != null && !node.getAliases().isEmpty()) {
            Label aliasLbl = new Label("Aliases: " + String.join(", ", node.getAliases()));
            aliasLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b; -fx-font-style: italic;");
            aliasLbl.setWrapText(true);
            mainCol.getChildren().add(aliasLbl);
        }

        VBox attrContainer = new VBox(4);
        HBox.setHgrow(attrContainer, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);

        int row = 0;
        addGridRow(grid, row++, "Node ID", node.getId() != null ? node.getId() : "—");
        addGridRow(grid, row++, "Confidence", node.getConfidence() != null ? String.format("%.0f%%", node.getConfidence() * 100) : "100%");

        if (node.getData() != null && !node.getData().isEmpty()) {
            for (Map.Entry<String, Object> entry : node.getData().entrySet()) {
                String valStr = formatValue(entry.getValue());
                addGridRow(grid, row++, entry.getKey(), valStr);
            }
        } else {
            addGridRow(grid, row++, "Attributes", "No additional properties");
        }

        ScrollPane gridScroll = new ScrollPane(grid);
        gridScroll.setFitToWidth(true);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        attrContainer.getChildren().add(gridScroll);
        root.getChildren().addAll(avatarPane, mainCol, attrContainer);
        return root;
    }

    private ScrollPane buildConnectionsList(NodeDto node, List<EdgeDto> relatedEdges) {
        VBox list = new VBox(6);
        list.setPadding(new Insets(6));

        if (relatedEdges == null || relatedEdges.isEmpty()) {
            Label empty = new Label("No connected relationships found for this entity.");
            empty.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px; -fx-font-style: italic;");
            list.getChildren().add(empty);
        } else {
            for (EdgeDto edge : relatedEdges) {
                HBox card = new HBox(8);
                card.setAlignment(Pos.CENTER_LEFT);
                card.setStyle("-fx-background-color: rgba(255, 255, 255, 0.6); -fx-padding: 6px 10px; -fx-background-radius: 6px; -fx-border-color: rgba(226, 232, 240, 0.5); -fx-border-radius: 6px;");

                boolean isOutgoing = node.getName() != null && node.getName().equalsIgnoreCase(edge.getSource());
                String otherNodeName = isOutgoing ? edge.getTarget() : edge.getSource();
                String directionSymbol = isOutgoing ? "➔" : "⬅";

                Label dirLbl = new Label(directionSymbol);
                dirLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #3b82f6; -fx-font-weight: bold;");

                Label relBadge = new Label(edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED");
                relBadge.setStyle("-fx-background-color: rgba(37, 99, 235, 0.1); -fx-text-fill: #1d4ed8; -fx-font-weight: bold; -fx-font-size: 10px; -fx-padding: 2px 6px; -fx-background-radius: 4px;");

                Button targetBtn = new Button(otherNodeName);
                targetBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0f172a; -fx-font-size: 11.5px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 0;");
                targetBtn.setOnAction(e -> {
                    if (onNavigateToEntity != null) onNavigateToEntity.accept(otherNodeName);
                });

                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);

                String conf = edge.getConfidence() != null ? String.format("%.0f%%", edge.getConfidence() * 100) : "100%";
                Label confLbl = new Label(conf);
                confLbl.setStyle("-fx-text-fill: #10b981; -fx-font-size: 10px; -fx-font-weight: bold;");

                card.getChildren().addAll(dirLbl, relBadge, targetBtn, sp, confLbl);
                list.getChildren().add(card);
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    private ScrollPane buildProvenanceTab(NodeDto node) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(8));

        Label provTitle = new Label("Source Evidence Files:");
        provTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        box.getChildren().add(provTitle);

        FlowPane sourceBadges = new FlowPane(6, 6);
        if (node.getSourceFiles() != null && !node.getSourceFiles().isEmpty()) {
            for (String file : node.getSourceFiles()) {
                Label fileBadge = new Label("📄 " + file);
                fileBadge.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #0f172a; -fx-font-size: 11px; -fx-padding: 4px 10px; -fx-background-radius: 4px; -fx-border-color: #cbd5e1; -fx-border-radius: 4px;");
                sourceBadges.getChildren().add(fileBadge);
            }
        } else {
            Label noFiles = new Label("Extracted from case warehouse.txt evidence.");
            noFiles.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
            sourceBadges.getChildren().add(noFiles);
        }
        box.getChildren().add(sourceBadges);

        if (node.getChunkIds() != null && !node.getChunkIds().isEmpty()) {
            Label chunkTitle = new Label("Source Chunk IDs:");
            chunkTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
            box.getChildren().add(chunkTitle);

            FlowPane chunkBadges = new FlowPane(6, 6);
            for (String chunkId : node.getChunkIds()) {
                Label chunkBadge = new Label("🧩 " + chunkId);
                chunkBadge.setStyle("-fx-background-color: #f8fafc; -fx-text-fill: #0284c7; -fx-font-size: 10px; -fx-padding: 3px 8px; -fx-background-radius: 4px; border-color: #e0e7ff;");
                chunkBadges.getChildren().add(chunkBadge);
            }
            box.getChildren().add(chunkBadges);
        }

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    private ScrollPane buildRawJsonTab(Object dataObj) {
        TextArea jsonArea = new TextArea();
        jsonArea.setEditable(false);
        jsonArea.setStyle("-fx-control-inner-background: #f8fafc; -fx-text-fill: #0369a1; -fx-font-family: monospace; -fx-font-size: 11px;");

        try {
            jsonArea.setText(objectMapper.writeValueAsString(dataObj));
        } catch (Exception e) {
            jsonArea.setText(String.valueOf(dataObj));
        }

        ScrollPane scroll = new ScrollPane(jsonArea);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    public void showEdgeDetails(EdgeDto edge) {
        if (edge == null) {
            showPlaceholder();
            return;
        }

        HBox root = new HBox(16);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(8, 4, 4, 4));

        StackPane iconPane = new StackPane();
        Circle bg = new Circle(26, Color.web("#f1f5f9"));
        bg.setStroke(Color.web("#3B82F6"));
        bg.setStrokeWidth(2.0);
        Label icon = new Label("➔");
        icon.setStyle("-fx-font-size: 18px; -fx-text-fill: #3b82f6;");
        iconPane.getChildren().addAll(bg, icon);

        VBox mainCol = new VBox(6);
        mainCol.setPrefWidth(280);
        mainCol.setMinWidth(240);

        HBox entitiesRow = new HBox(6);
        entitiesRow.setAlignment(Pos.CENTER_LEFT);

        Button srcBtn = new Button(edge.getSource());
        srcBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0284c7; -fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 0;");
        srcBtn.setOnAction(e -> {
            if (onNavigateToEntity != null) onNavigateToEntity.accept(edge.getSource());
        });

        Label arrow = new Label("➔");
        arrow.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        Button tgtBtn = new Button(edge.getTarget());
        tgtBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #0284c7; -fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 0;");
        tgtBtn.setOnAction(e -> {
            if (onNavigateToEntity != null) onNavigateToEntity.accept(edge.getTarget());
        });

        entitiesRow.getChildren().addAll(srcBtn, arrow, tgtBtn);

        HBox badges = new HBox(8);
        badges.setAlignment(Pos.CENTER_LEFT);

        Label relBadge = new Label(edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED");
        relBadge.setStyle("-fx-background-color: rgba(37, 99, 235, 0.1); -fx-text-fill: #1d4ed8; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3px 10px; -fx-background-radius: 6px;");

        String confPct = edge.getConfidence() != null ? String.format("%.0f%%", edge.getConfidence() * 100) : "100%";
        Label confBadge = new Label("Confidence: " + confPct);
        confBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.1); -fx-text-fill: #059669; -fx-font-size: 10px; -fx-padding: 3px 8px; -fx-background-radius: 6px;");

        badges.getChildren().addAll(relBadge, confBadge);

        if (edge.getSourceFile() != null && !edge.getSourceFile().isBlank()) {
            Label srcFile = new Label("📄 " + edge.getSourceFile() + (edge.getChunkId() != null ? " (" + edge.getChunkId() + ")" : ""));
            srcFile.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");
            mainCol.getChildren().addAll(entitiesRow, badges, srcFile);
        } else {
            mainCol.getChildren().addAll(entitiesRow, badges);
        }

        VBox evidenceBox = new VBox(4);
        evidenceBox.setStyle("-fx-background-color: rgba(248, 250, 252, 0.6); -fx-border-color: rgba(226, 232, 240, 0.5); -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8px;");
        HBox.setHgrow(evidenceBox, Priority.ALWAYS);

        Label evTitle = new Label("Ground Truth Evidence Citation");
        evTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #475569;");

        String snippet = edge.getEvidenceText() != null && !edge.getEvidenceText().isBlank()
                ? edge.getEvidenceText()
                : "Relationship verified from case evidence documentation.";
        Label snippetLbl = new Label("\"" + snippet + "\"");
        snippetLbl.setStyle("-fx-font-style: italic; -fx-text-fill: #334155; -fx-font-size: 11px;");
        snippetLbl.setWrapText(true);

        ScrollPane evScroll = new ScrollPane(snippetLbl);
        evScroll.setFitToWidth(true);
        evScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        evidenceBox.getChildren().addAll(evTitle, evScroll);

        root.getChildren().addAll(iconPane, mainCol, evidenceBox);
        contentContainer.getChildren().setAll(root);
    }

    private void addGridRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #475569; -fx-font-weight: 600; -fx-min-width: 90px;");

        Label val = new Label(value != null ? value : "—");
        val.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #0f172a;");
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

    private Color getNodeColor(String type) {
        if (type == null) return Color.web("#94A3B8");
        return switch (type.toUpperCase()) {
            case "PERSON" -> Color.web("#3B82F6");
            case "PHONE_NUMBER", "PHONE" -> Color.web("#06B6D4");
            case "DOCUMENT", "TRANSCRIPT" -> Color.web("#10B981");
            case "LOCATION" -> Color.web("#F59E0B");
            case "ORGANIZATION", "ORG" -> Color.web("#8B5CF6");
            case "EVENT" -> Color.web("#F43F5E");
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
