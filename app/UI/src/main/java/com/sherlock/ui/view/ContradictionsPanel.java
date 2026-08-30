package com.sherlock.ui.view;

import com.sherlock.ui.model.ContradictionDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.function.Consumer;

public class ContradictionsPanel extends ScrollPane {

    private final VBox container;
    private final Runnable onGenerateContradictionsRequested;
    private Consumer<String> onNavigateToEntity;

    public ContradictionsPanel(Runnable onGenerateContradictionsRequested) {
        this.onGenerateContradictionsRequested = onGenerateContradictionsRequested;
        container = new VBox(16);
        container.setPadding(new Insets(24));
        container.setStyle("-fx-background-color: transparent;");

        setFitToWidth(true);
        setContent(container);
        setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent; -fx-control-inner-background: transparent;");

        showPlaceholder();
    }

    public void setOnNavigateToEntity(Consumer<String> onNavigateToEntity) {
        this.onNavigateToEntity = onNavigateToEntity;
    }

    public void showPlaceholder() {
        container.getChildren().clear();
        container.setAlignment(Pos.CENTER);

        VBox placeholderBox = new VBox(14);
        placeholderBox.setAlignment(Pos.CENTER);
        placeholderBox.setPadding(new Insets(40, 20, 40, 20));

        Label icon = new Label("⚠️");
        icon.setStyle("-fx-font-size: 43.2px;");

        Label placeholder = new Label("No contradictions detected yet.");
        placeholder.setStyle("-fx-text-fill: #0f172a; -fx-font-size: 19.2px; -fx-font-weight: bold;");

        Label hint = new Label("Sherlock can compare witness statements, alibis, CCTV logs, and evidence across sources to uncover contradictory facts.");
        hint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 15.6px;");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        hint.setAlignment(Pos.CENTER);

        Button generateBtn = new Button("⚡ Detect Contradictions");
        generateBtn.getStyleClass().add("primary-button");
        generateBtn.setStyle("-fx-font-weight: bold; -fx-padding: 10px 20px; -fx-font-size: 16.8px; -fx-background-radius: 6px; -fx-background-color: #ef4444; -fx-text-fill: white; -fx-cursor: hand;");
        generateBtn.setOnAction(e -> {
            if (onGenerateContradictionsRequested != null) {
                onGenerateContradictionsRequested.run();
            }
        });

        placeholderBox.getChildren().addAll(icon, placeholder, hint, generateBtn);
        container.getChildren().add(placeholderBox);
    }

    public void setContradictionsData(ContradictionDto dto) {
        container.getChildren().clear();
        container.setAlignment(Pos.TOP_LEFT);

        if (dto == null || dto.getContradictions() == null || dto.getContradictions().isEmpty()) {
            showPlaceholder();
            return;
        }

        // Summary Header Card
        VBox summaryCard = buildSummaryCard(dto);
        container.getChildren().add(summaryCard);

        // List of Contradictions
        for (ContradictionDto.ContradictionItemDto item : dto.getContradictions()) {
            container.getChildren().add(buildContradictionCard(item));
        }
    }

    private VBox buildSummaryCard(ContradictionDto dto) {
        VBox card = new VBox(10);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10px; -fx-border-color: #e2e8f0; -fx-border-radius: 10px; -fx-padding: 16px;");

        HBox topRow = new HBox(12);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("⚡ Cross-Source Contradiction Analysis");
        title.setStyle("-fx-font-size: 19.2px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button recheckBtn = new Button("↻ Re-evaluate");
        recheckBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: 600; -fx-font-size: 14.4px; -fx-padding: 6px 12px; -fx-background-radius: 6px; -fx-cursor: hand;");
        recheckBtn.setOnAction(e -> {
            if (onGenerateContradictionsRequested != null) {
                onGenerateContradictionsRequested.run();
            }
        });

        topRow.getChildren().addAll(title, spacer, recheckBtn);

        String summaryText = dto.getSummary() != null && !dto.getSummary().isBlank()
                ? dto.getSummary()
                : "Detected " + dto.getTotalContradictions() + " contradiction(s) across case evidence.";

        Label summaryLbl = new Label(summaryText);
        summaryLbl.setStyle("-fx-text-fill: #475569; -fx-font-size: 15.6px;");
        summaryLbl.setWrapText(true);

        // Breakdown stats badges
        HBox statsRow = new HBox(8);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        int critical = dto.getSeverityBreakdown() != null ? dto.getSeverityBreakdown().getOrDefault("CRITICAL", 0) : 0;
        int high = dto.getSeverityBreakdown() != null ? dto.getSeverityBreakdown().getOrDefault("HIGH", 0) : 0;
        int medium = dto.getSeverityBreakdown() != null ? dto.getSeverityBreakdown().getOrDefault("MEDIUM", 0) : 0;
        int low = dto.getSeverityBreakdown() != null ? dto.getSeverityBreakdown().getOrDefault("LOW", 0) : 0;

        statsRow.getChildren().addAll(
                createBadge(dto.getTotalContradictions() + " Total", "#0f172a", "#f1f5f9"),
                createBadge(critical + " Critical", "#dc2626", "#fee2e2"),
                createBadge(high + " High", "#ea580c", "#ffedd5"),
                createBadge(medium + " Medium", "#ca8a04", "#fef9c3"),
                createBadge(low + " Low", "#2563eb", "#dbeafe")
        );

        card.getChildren().addAll(topRow, summaryLbl, statsRow);
        return card;
    }

    private VBox buildContradictionCard(ContradictionDto.ContradictionItemDto item) {
        VBox card = new VBox(12);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10px; -fx-border-color: #e2e8f0; -fx-border-radius: 10px; -fx-padding: 18px;");

        // Header with badges
        HBox headerRow = new HBox(8);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        String sev = item.getSeverity() != null ? item.getSeverity().toUpperCase() : "HIGH";
        String sevBg = "#fee2e2";
        String sevFg = "#dc2626";
        if ("HIGH".equals(sev)) {
            sevBg = "#ffedd5";
            sevFg = "#ea580c";
        } else if ("MEDIUM".equals(sev)) {
            sevBg = "#fef9c3";
            sevFg = "#ca8a04";
        } else if ("LOW".equals(sev)) {
            sevBg = "#dbeafe";
            sevFg = "#2563eb";
        }

        Label sevBadge = createBadge(sev, sevFg, sevBg);

        String typeStr = item.getType() != null ? item.getType().replace("_", " ") : "FACTUAL INCONSISTENCY";
        Label typeBadge = createBadge(typeStr, "#475569", "#f1f5f9");

        Label idLabel = new Label(item.getContradictionId() != null ? item.getContradictionId() : "");
        idLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13.2px; -fx-font-family: monospace;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        String status = item.getResolutionStatus() != null ? item.getResolutionStatus().replace("_", " ") : "UNRESOLVED";
        Label statusBadge = createBadge(status, "#7c3aed", "#ede9fe");

        headerRow.getChildren().addAll(sevBadge, typeBadge, idLabel, spacer, statusBadge);

        // Title / Summary
        Label titleLabel = new Label(item.getSummary() != null ? item.getSummary() : "Contradiction");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        titleLabel.setWrapText(true);

        // Detailed Explanation
        Label descLabel = new Label(item.getDescription() != null ? item.getDescription() : "");
        descLabel.setStyle("-fx-font-size: 15.6px; -fx-text-fill: #334155;");
        descLabel.setWrapText(true);

        // Conflicting Points (comparative boxes)
        VBox pointsContainer = new VBox(8);
        if (item.getConflictingPoints() != null && !item.getConflictingPoints().isEmpty()) {
            Label pointsHeader = new Label("Conflicting Evidence Points:");
            pointsHeader.setStyle("-fx-font-size: 14.4px; -fx-font-weight: 700; -fx-text-fill: #64748b;");
            pointsContainer.getChildren().add(pointsHeader);

            for (int i = 0; i < item.getConflictingPoints().size(); i++) {
                ContradictionDto.ConflictingPointDto pt = item.getConflictingPoints().get(i);
                VBox ptBox = new VBox(4);
                ptBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-width: 0 0 0 3px; -fx-padding: 8px 12px;");

                HBox ptHeader = new HBox(8);
                ptHeader.setAlignment(Pos.CENTER_LEFT);

                Label speaker = new Label(pt.getSpeakerOrSource() != null && !pt.getSpeakerOrSource().isBlank()
                        ? pt.getSpeakerOrSource() : "Source " + (i + 1));
                speaker.setStyle("-fx-font-weight: bold; -fx-font-size: 14.4px; -fx-text-fill: #0f172a;");

                String fileStr = pt.getSourceFile() != null ? "📄 " + pt.getSourceFile() : "";
                Label fileLbl = new Label(fileStr);
                fileLbl.setStyle("-fx-font-size: 13.2px; -fx-text-fill: #64748b;");

                ptHeader.getChildren().addAll(speaker, fileLbl);

                Label claimLbl = new Label("• Claim: " + (pt.getClaim() != null ? pt.getClaim() : ""));
                claimLbl.setStyle("-fx-font-size: 14.4px; -fx-text-fill: #1e293b;");
                claimLbl.setWrapText(true);

                Label quoteLbl = new Label("❝ " + (pt.getQuote() != null ? pt.getQuote() : "") + " ❞");
                quoteLbl.setStyle("-fx-font-size: 14.4px; -fx-font-style: italic; -fx-text-fill: #475569;");
                quoteLbl.setWrapText(true);

                ptBox.getChildren().addAll(ptHeader, claimLbl, quoteLbl);
                pointsContainer.getChildren().add(ptBox);
            }
        }

        // Entities involved tags
        HBox entityRow = new HBox(6);
        entityRow.setAlignment(Pos.CENTER_LEFT);
        if (item.getEntitiesInvolved() != null && !item.getEntitiesInvolved().isEmpty()) {
            Label entHeader = new Label("Entities:");
            entHeader.setStyle("-fx-font-size: 13.2px; -fx-font-weight: 600; -fx-text-fill: #64748b;");
            entityRow.getChildren().add(entHeader);

            FlowPane tags = new FlowPane();
            tags.setHgap(6);
            tags.setVgap(6);
            for (String entity : item.getEntitiesInvolved()) {
                Button tagBtn = new Button(entity);
                tagBtn.setStyle("-fx-background-color: rgba(59, 130, 246, 0.1); -fx-text-fill: #1d4ed8; -fx-font-size: 13.2px; -fx-padding: 2px 8px; -fx-background-radius: 4px; -fx-cursor: hand;");
                tagBtn.setOnAction(e -> {
                    if (onNavigateToEntity != null) {
                        onNavigateToEntity.accept(entity);
                    }
                });
                tags.getChildren().add(tagBtn);
            }
            entityRow.getChildren().add(tags);
        }

        // Investigation Lead Callout
        VBox leadBox = new VBox(4);
        if (item.getInvestigationLead() != null && !item.getInvestigationLead().isBlank()) {
            leadBox.setStyle("-fx-background-color: #fef2f2; -fx-border-color: #fca5a5; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8px 12px;");
            Label leadTitle = new Label("🔍 Investigation Lead / Interrogation Recommendation:");
            leadTitle.setStyle("-fx-font-size: 13.2px; -fx-font-weight: bold; -fx-text-fill: #991b1b;");
            Label leadText = new Label(item.getInvestigationLead());
            leadText.setStyle("-fx-font-size: 14.4px; -fx-text-fill: #7f1d1d;");
            leadText.setWrapText(true);
            leadBox.getChildren().addAll(leadTitle, leadText);
        }

        card.getChildren().addAll(headerRow, titleLabel, descLabel);
        if (!pointsContainer.getChildren().isEmpty()) {
            card.getChildren().add(pointsContainer);
        }
        if (!entityRow.getChildren().isEmpty()) {
            card.getChildren().add(entityRow);
        }
        if (!leadBox.getChildren().isEmpty()) {
            card.getChildren().add(leadBox);
        }

        return card;
    }

    private Label createBadge(String text, String fgHex, String bgHex) {
        Label badge = new Label(text);
        badge.setStyle("-fx-background-color: " + bgHex + "; -fx-text-fill: " + fgHex + "; -fx-font-size: 13.2px; -fx-font-weight: 700; -fx-padding: 3px 8px; -fx-background-radius: 4px;");
        return badge;
    }
}
