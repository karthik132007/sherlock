package com.sherlock.ui.view;

import com.sherlock.ui.model.GraphDataDto.EdgeDto;
import com.sherlock.ui.model.GraphDataDto.NodeDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.Map;

public class DetailsPanel extends VBox {

    private final StackPane contentContainer = new StackPane();

    public DetailsPanel() {
        getStyleClass().add("dark-panel");
        setPadding(new Insets(14, 18, 14, 18));
        setSpacing(10);
        setPrefHeight(180);
        setMinHeight(160);

        Label headerTitle = new Label("When clicked a node or edge Details about it");
        headerTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8; -fx-font-weight: bold;");

        contentContainer.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        getChildren().addAll(headerTitle, contentContainer);
        showPlaceholder();
    }

    public void showPlaceholder() {
        HBox placeholder = new HBox(12);
        placeholder.setAlignment(Pos.CENTER_LEFT);
        placeholder.setStyle("-fx-background-color: #161e2e; -fx-padding: 16px 20px; -fx-background-radius: 8px; -fx-border-color: #243048; -fx-border-radius: 8px;");

        Label icon = new Label("👆");
        icon.setStyle("-fx-font-size: 24px;");

        VBox textCol = new VBox(3);
        Label title = new Label("Interactive Knowledge Graph");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
        Label desc = new Label("Click on any node or edge to inspect full evidence, attributes, and provenance. Drag nodes to explore relationships.");
        desc.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        textCol.getChildren().addAll(title, desc);

        placeholder.getChildren().addAll(icon, textCol);
        contentContainer.getChildren().setAll(placeholder);
    }

    public void showNodeDetails(NodeDto node, List<EdgeDto> relatedEdges) {
        if (node == null) {
            showPlaceholder();
            return;
        }

        HBox root = new HBox(18);
        root.setAlignment(Pos.CENTER_LEFT);

        // 1. Left Avatar
        StackPane avatarPane = new StackPane();
        Circle bg = new Circle(28, Color.web("#1E293B"));
        bg.setStroke(getNodeColor(node.getType()));
        bg.setStrokeWidth(2.0);
        Label avatarIcon = new Label(getNodeIcon(node.getType()));
        avatarIcon.setStyle("-fx-font-size: 20px;");
        avatarPane.getChildren().addAll(bg, avatarIcon);

        // 2. Main Details Column
        VBox mainCol = new VBox(6);
        mainCol.setPrefWidth(220);

        HBox nameRow = new HBox(8);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(node.getName());
        nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Label typeBadge = new Label(node.getType() != null ? node.getType() : "ENTITY");
        typeBadge.getStyleClass().addAll("badge-pill", getTypeBadgeClass(node.getType()));
        nameRow.getChildren().addAll(nameLabel, typeBadge);

        HBox mentionsRow = new HBox(6);
        mentionsRow.setAlignment(Pos.CENTER_LEFT);
        Label mentionsLbl = new Label("Mentions: " + (node.getMentions() != null ? node.getMentions() : 1));
        mentionsLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #38bdf8;");
        mentionsRow.getChildren().add(mentionsLbl);

        if (node.getAliases() != null && !node.getAliases().isEmpty()) {
            Label aliasLbl = new Label("Aliases: " + String.join(", ", node.getAliases()));
            aliasLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8;");
            mainCol.getChildren().addAll(nameRow, mentionsRow, aliasLbl);
        } else {
            mainCol.getChildren().addAll(nameRow, mentionsRow);
        }

        // 3. Metadata Grid
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(4);
        grid.setPrefWidth(300);

        addGridRow(grid, 0, "Type", node.getType());
        addGridRow(grid, 1, "ID", node.getId());

        int connectedCount = relatedEdges != null ? relatedEdges.size() : 0;
        addGridRow(grid, 2, "Connected to", connectedCount + " Relationship" + (connectedCount == 1 ? "" : "s"));

        // Format custom attributes from data map
        if (node.getData() != null && !node.getData().isEmpty()) {
            StringBuilder attrs = new StringBuilder();
            int c = 0;
            for (Map.Entry<String, Object> entry : node.getData().entrySet()) {
                if (c > 0) attrs.append(" • ");
                attrs.append(entry.getKey()).append(": ").append(entry.getValue());
                c++;
                if (c >= 3) break;
            }
            addGridRow(grid, 3, "Attributes", attrs.toString());
        }

        // 4. Source Evidence Provenance
        VBox provCol = new VBox(4);
        HBox.setHgrow(provCol, Priority.ALWAYS);
        Label provTitle = new Label("Source Documents");
        provTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #cbd5e1;");

        FlowPane sourceBadges = new FlowPane(6, 6);
        if (node.getSourceFiles() != null && !node.getSourceFiles().isEmpty()) {
            for (String file : node.getSourceFiles()) {
                Label fileBadge = new Label("📄 " + file);
                fileBadge.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #93c5fd; -fx-font-size: 10px; -fx-padding: 3px 8px; -fx-background-radius: 4px;");
                sourceBadges.getChildren().add(fileBadge);
            }
        } else {
            Label noFiles = new Label("Extracted from warehouse evidence");
            noFiles.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");
            sourceBadges.getChildren().add(noFiles);
        }

        ScrollPane provScroll = new ScrollPane(sourceBadges);
        provScroll.setFitToWidth(true);
        provScroll.setPrefHeight(60);
        provScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        provCol.getChildren().addAll(provTitle, provScroll);

        root.getChildren().addAll(avatarPane, mainCol, grid, provCol);
        contentContainer.getChildren().setAll(root);
    }

    public void showEdgeDetails(EdgeDto edge) {
        if (edge == null) {
            showPlaceholder();
            return;
        }

        HBox root = new HBox(18);
        root.setAlignment(Pos.CENTER_LEFT);

        // 1. Connection Icon
        StackPane iconPane = new StackPane();
        Circle bg = new Circle(26, Color.web("#1E293B"));
        bg.setStroke(Color.web("#3B82F6"));
        bg.setStrokeWidth(2.0);
        Label icon = new Label("➔");
        icon.setStyle("-fx-font-size: 18px; -fx-text-fill: #60a5fa;");
        iconPane.getChildren().addAll(bg, icon);

        // 2. Edge Metadata
        VBox mainCol = new VBox(4);
        mainCol.setPrefWidth(260);

        Label relationRow = new Label(edge.getSource() + " ➔ " + edge.getTarget());
        relationRow.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        HBox badges = new HBox(8);
        badges.setAlignment(Pos.CENTER_LEFT);

        Label relBadge = new Label(edge.getRelation() != null ? edge.getRelation().replace("_", " ") : "RELATED");
        relBadge.setStyle("-fx-background-color: rgba(37, 99, 235, 0.25); -fx-text-fill: #93c5fd; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3px 10px; -fx-background-radius: 6px;");

        String confPct = edge.getConfidence() != null ? String.format("%.0f%%", edge.getConfidence() * 100) : "100%";
        Label confBadge = new Label("Confidence: " + confPct);
        confBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.2); -fx-text-fill: #34d399; -fx-font-size: 10px; -fx-padding: 3px 8px; -fx-background-radius: 6px;");

        badges.getChildren().addAll(relBadge, confBadge);
        mainCol.getChildren().addAll(relationRow, badges);

        // 3. Evidence Citation Box
        VBox evidenceBox = new VBox(4);
        evidenceBox.getStyleClass().add("evidence-quote");
        HBox.setHgrow(evidenceBox, Priority.ALWAYS);

        Label evTitle = new Label("Evidence Citation (" + (edge.getSourceFile() != null ? edge.getSourceFile() : "warehouse") + ")");
        evTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");

        String snippet = edge.getEvidenceText() != null && !edge.getEvidenceText().isBlank()
                ? edge.getEvidenceText()
                : "Relationship verified from case documentation.";
        Label snippetLbl = new Label("\"" + snippet + "\"");
        snippetLbl.getStyleClass().add("evidence-quote-text");
        snippetLbl.setWrapText(true);

        evidenceBox.getChildren().addAll(evTitle, snippetLbl);

        root.getChildren().addAll(iconPane, mainCol, evidenceBox);
        contentContainer.getChildren().setAll(root);
    }

    private void addGridRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: 600;");

        Label val = new Label(value != null ? value : "—");
        val.setStyle("-fx-font-size: 11px; -fx-text-fill: #e2e8f0;");
        val.setMaxWidth(220);

        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    private Color getNodeColor(String type) {
        if (type == null) return Color.web("#94A3B8");
        return switch (type.toUpperCase()) {
            case "PERSON" -> Color.web("#3B82F6");
            case "PHONE_NUMBER", "PHONE" -> Color.web("#06B6D4");
            case "DOCUMENT", "TRANSCRIPT" -> Color.web("#10B981");
            case "LOCATION" -> Color.web("#F59E0B");
            case "ORGANIZATION" -> Color.web("#8B5CF6");
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
            case "ORGANIZATION" -> "🏢";
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
            case "EVENT" -> "badge-event";
            default -> "badge-pill";
        };
    }
}
