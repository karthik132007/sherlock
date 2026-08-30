package com.sherlock.ui.view;

import com.sherlock.ui.model.OpinionDto;
import com.sherlock.ui.model.OpinionDto.AlternativeHypothesisDto;
import com.sherlock.ui.model.OpinionDto.EvidencePointDto;
import com.sherlock.ui.model.OpinionDto.FlawPointDto;
import com.sherlock.ui.model.OpinionDto.InvestigativeLeadDto;
import com.sherlock.ui.model.OpinionDto.PossibleCauseDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.function.Consumer;

public class OpinionPanel extends ScrollPane {

    private final VBox container;
    private final Runnable onGenerateOpinionRequested;
    private Consumer<String> onNavigateToEntity;

    public OpinionPanel(Runnable onGenerateOpinionRequested) {
        this.onGenerateOpinionRequested = onGenerateOpinionRequested;
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

        Label icon = new Label("🧠");
        icon.setStyle("-fx-font-size: 44px;");

        Label placeholder = new Label("No opinion formulated yet.");
        placeholder.setStyle("-fx-text-fill: #0f172a; -fx-font-size: 19.2px; -fx-font-weight: bold;");

        Label hint = new Label("Sherlock will systematically explore every scenario, debate competing possibilities, examine root causes, and formulate an evidence-backed deduction with thinking mode enabled.");
        hint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 15.6px;");
        hint.setWrapText(true);
        hint.setMaxWidth(540);
        hint.setAlignment(Pos.CENTER);

        Button generateBtn = new Button("💡 Brainstorm & Formulate Opinion");
        generateBtn.getStyleClass().add("primary-button");
        generateBtn.setStyle("-fx-font-weight: bold; -fx-padding: 10px 20px; -fx-font-size: 16px; -fx-background-radius: 6px; -fx-background-color: #6366f1; -fx-text-fill: white; -fx-cursor: hand;");
        generateBtn.setOnAction(e -> {
            if (onGenerateOpinionRequested != null) {
                onGenerateOpinionRequested.run();
            }
        });

        placeholderBox.getChildren().addAll(icon, placeholder, hint, generateBtn);
        container.getChildren().add(placeholderBox);
    }

    public void setOpinionData(OpinionDto dto) {
        container.getChildren().clear();
        container.setAlignment(Pos.TOP_LEFT);

        if (dto == null || ((dto.getExecutiveSummary() == null || dto.getExecutiveSummary().isBlank())
                && (dto.getPrimaryHypothesis() == null || dto.getPrimaryHypothesis().isBlank())
                && (dto.getPreliminaryAnalysis() == null || dto.getPreliminaryAnalysis().isBlank()))) {
            showPlaceholder();
            return;
        }

        // 1. Header Card (Title, Confidence Meter, Refresh Action)
        container.getChildren().add(buildHeaderCard(dto));

        // 2. Primary Theory & Executive Summary Card (TOP PRIORITY)
        container.getChildren().add(buildTheoryCard(dto));

        // 3. Thinking Mode / Deep Reasoning Trace (Collapsible)
        if (dto.getReasoningTrace() != null && !dto.getReasoningTrace().isBlank()) {
            container.getChildren().add(buildThinkingTraceCard(dto.getReasoningTrace()));
        }

        // 4. Preliminary Analysis & Possible Causes / Catalysts
        if ((dto.getPreliminaryAnalysis() != null && !dto.getPreliminaryAnalysis().isBlank())
                || (dto.getPossibleCauses() != null && !dto.getPossibleCauses().isEmpty())) {
            container.getChildren().add(buildPreliminaryAnalysisCard(dto));
        }

        // 5. Adversarial Self-Debate / Scenario Evaluation Card
        if (dto.getSelfDebateSummary() != null && !dto.getSelfDebateSummary().isBlank()) {
            container.getChildren().add(buildSelfDebateCard(dto.getSelfDebateSummary()));
        }

        // 6. Supporting Evidence Section
        if (dto.getSupportingEvidence() != null && !dto.getSupportingEvidence().isEmpty()) {
            container.getChildren().add(buildSectionTitle("✅ Supporting Evidence & Dot-Connecting", "#10b981", dto.getSupportingEvidence().size() + " points"));
            for (EvidencePointDto point : dto.getSupportingEvidence()) {
                container.getChildren().add(buildEvidenceCard(point));
            }
        }

        // 7. Flaws & Counter-Evidence Section
        if (dto.getFlawsAndCounterEvidence() != null && !dto.getFlawsAndCounterEvidence().isEmpty()) {
            container.getChildren().add(buildSectionTitle("⚠️ Flaws, Inconsistencies & Counter-Evidence", "#ef4444", dto.getFlawsAndCounterEvidence().size() + " challenges"));
            for (FlawPointDto flaw : dto.getFlawsAndCounterEvidence()) {
                container.getChildren().add(buildFlawCard(flaw));
            }
        }

        // 8. Alternative Hypotheses Section
        if (dto.getAlternativeHypotheses() != null && !dto.getAlternativeHypotheses().isEmpty()) {
            container.getChildren().add(buildSectionTitle("🔀 Competing Theories & Alternative Hypotheses", "#3b82f6", dto.getAlternativeHypotheses().size() + " alternatives"));
            for (AlternativeHypothesisDto alt : dto.getAlternativeHypotheses()) {
                container.getChildren().add(buildAlternativeHypothesisCard(alt));
            }
        }

        // 9. Recommended Investigative Leads Section
        if (dto.getInvestigativeLeads() != null && !dto.getInvestigativeLeads().isEmpty()) {
            container.getChildren().add(buildSectionTitle("🎯 Recommended Next Steps & Investigative Leads", "#8b5cf6", dto.getInvestigativeLeads().size() + " leads"));
            for (InvestigativeLeadDto lead : dto.getInvestigativeLeads()) {
                container.getChildren().add(buildLeadCard(lead));
            }
        }
    }

    private VBox buildHeaderCard(OpinionDto dto) {
        VBox card = new VBox(12);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10px; -fx-border-color: #e2e8f0; -fx-border-radius: 10px; -fx-padding: 16px;");

        HBox topRow = new HBox(12);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("🧠 Sherlock's Deductive Opinion & Case Theory");
        title.setStyle("-fx-font-size: 19.2px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        double confVal = dto.getConfidence() != null ? dto.getConfidence() : 0.85;
        int confPercent = (int) Math.round(confVal * 100);
        String confColor = confPercent >= 80 ? "#10b981" : (confPercent >= 60 ? "#f59e0b" : "#ef4444");

        Label confBadge = new Label(confPercent + "% Confidence");
        confBadge.setStyle("-fx-background-color: " + confColor + "22; -fx-text-fill: " + confColor + "; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px; -fx-font-size: 13px;");

        Button reBrainstormBtn = new Button("↻ Re-Brainstorm");
        reBrainstormBtn.getStyleClass().add("tool-button");
        reBrainstormBtn.setStyle("-fx-padding: 6px 12px; -fx-font-size: 13px; -fx-cursor: hand;");
        reBrainstormBtn.setOnAction(e -> {
            if (onGenerateOpinionRequested != null) {
                onGenerateOpinionRequested.run();
            }
        });

        topRow.getChildren().addAll(title, spacer, confBadge, reBrainstormBtn);

        HBox metaRow = new HBox(16);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        String caseIdStr = dto.getCaseId() != null ? dto.getCaseId().toUpperCase() : "CASE";
        Label caseLabel = new Label("Case: " + caseIdStr);
        caseLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b; -fx-font-weight: 600;");

        int evCount = dto.getSupportingEvidence() != null ? dto.getSupportingEvidence().size() : 0;
        int flawCount = dto.getFlawsAndCounterEvidence() != null ? dto.getFlawsAndCounterEvidence().size() : 0;

        Label countsLabel = new Label("•  " + evCount + " Evidence Points  •  " + flawCount + " Identified Flaws");
        countsLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

        metaRow.getChildren().addAll(caseLabel, countsLabel);

        card.getChildren().addAll(topRow, metaRow);
        return card;
    }

    private VBox buildThinkingTraceCard(String reasoningTrace) {
        VBox card = new VBox(10);
        card.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 10px; -fx-border-color: #334155; -fx-border-radius: 10px; -fx-padding: 14px;");

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label brainIcon = new Label("💭");
        brainIcon.setStyle("-fx-font-size: 16px;");

        Label traceTitle = new Label("Sherlock's Thinking Process & Brainstorming Trace (Reasoning Mode)");
        traceTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #38bdf8;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button toggleBtn = new Button("▼ Collapse");
        toggleBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-cursor: hand; -fx-padding: 2px 6px;");

        titleRow.getChildren().addAll(brainIcon, traceTitle, spacer, toggleBtn);

        Label traceContent = new Label(reasoningTrace);
        traceContent.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; -fx-text-fill: #cbd5e1; -fx-line-spacing: 3px;");
        traceContent.setWrapText(true);

        ScrollPane traceScroll = new ScrollPane(traceContent);
        traceScroll.setFitToWidth(true);
        traceScroll.setMaxHeight(260);
        traceScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        toggleBtn.setOnAction(e -> {
            boolean isVisible = traceScroll.isVisible();
            traceScroll.setVisible(!isVisible);
            traceScroll.setManaged(!isVisible);
            toggleBtn.setText(isVisible ? "▶ Expand" : "▼ Collapse");
        });

        card.getChildren().addAll(titleRow, traceScroll);
        return card;
    }

    private VBox buildPreliminaryAnalysisCard(OpinionDto dto) {
        VBox card = new VBox(14);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10px; -fx-border-color: #e2e8f0; -fx-border-radius: 10px; -fx-padding: 18px;");

        Label header = new Label("🔍 Preliminary Forensic Analysis");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        VBox contentBox = new VBox(10);

        if (dto.getPreliminaryAnalysis() != null && !dto.getPreliminaryAnalysis().isBlank()) {
            Label analysisText = new Label(dto.getPreliminaryAnalysis());
            analysisText.setStyle("-fx-font-size: 14px; -fx-text-fill: #334155; -fx-line-spacing: 3px;");
            analysisText.setWrapText(true);
            contentBox.getChildren().add(analysisText);
        }

        // Possible Causes / Underlying Catalysts at the bottom of preliminary analysis
        if (dto.getPossibleCauses() != null && !dto.getPossibleCauses().isEmpty()) {
            VBox causesBox = new VBox(8);
            causesBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-padding: 12px;");

            Label causesTitle = new Label("⚡ Possible Root Causes & Contributing Catalysts");
            causesTitle.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 800; -fx-text-fill: #475569;");
            causesBox.getChildren().add(causesTitle);

            for (PossibleCauseDto cause : dto.getPossibleCauses()) {
                VBox causeCard = new VBox(4);
                causeCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8px 10px;");

                HBox topRow = new HBox(8);
                topRow.setAlignment(Pos.CENTER_LEFT);

                String cat = cause.getCategory() != null ? cause.getCategory() : "MOTIVE";
                Label catBadge = new Label(cat);
                catBadge.setStyle("-fx-background-color: #ede9fe; -fx-text-fill: #6d28d9; -fx-font-weight: bold; -fx-padding: 2px 6px; -fx-background-radius: 4px; -fx-font-size: 10.5px;");

                Label causeName = new Label(cause.getCause() != null ? cause.getCause() : "—");
                causeName.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #0f172a;");
                causeName.setWrapText(true);

                topRow.getChildren().addAll(catBadge, causeName);
                causeCard.getChildren().add(topRow);

                if (cause.getSignificance() != null && !cause.getSignificance().isBlank()) {
                    Label sig = new Label("Impact: " + cause.getSignificance());
                    sig.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
                    sig.setWrapText(true);
                    causeCard.getChildren().add(sig);
                }

                if (cause.getEvidenceIndicators() != null && !cause.getEvidenceIndicators().isEmpty()) {
                    HBox indBox = new HBox(6);
                    indBox.setAlignment(Pos.CENTER_LEFT);
                    Label indLabel = new Label("Key Indicators: " + String.join(", ", cause.getEvidenceIndicators()));
                    indLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #475569; -fx-font-style: italic;");
                    indLabel.setWrapText(true);
                    indBox.getChildren().add(indLabel);
                    causeCard.getChildren().add(indBox);
                }

                causesBox.getChildren().add(causeCard);
            }

            contentBox.getChildren().add(causesBox);
        }

        card.getChildren().addAll(header, contentBox);
        return card;
    }

    private VBox buildSelfDebateCard(String debateSummary) {
        VBox card = new VBox(10);
        card.setStyle("-fx-background-color: #faf5ff; -fx-background-radius: 10px; -fx-border-color: #d8b4fe; -fx-border-radius: 10px; -fx-padding: 16px;");

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("⚖️");
        icon.setStyle("-fx-font-size: 16px;");

        Label title = new Label("Sherlock's Adversarial Self-Debate (Cross-Examining Competing Options)");
        title.setStyle("-fx-font-size: 14.5px; -fx-font-weight: 800; -fx-text-fill: #6b21a8;");

        titleRow.getChildren().addAll(icon, title);

        Label debateText = new Label(debateSummary);
        debateText.setStyle("-fx-font-size: 13.5px; -fx-text-fill: #4c1d95; -fx-line-spacing: 3px;");
        debateText.setWrapText(true);

        card.getChildren().addAll(titleRow, debateText);
        return card;
    }

    private VBox buildTheoryCard(OpinionDto dto) {
        VBox card = new VBox(14);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10px; -fx-border-color: #e2e8f0; -fx-border-radius: 10px; -fx-padding: 20px;");

        // Primary Hypothesis Banner
        VBox hypBox = new VBox(6);
        hypBox.setStyle("-fx-background-color: #eff6ff; -fx-border-color: #3b82f6; -fx-border-width: 0 0 0 4px; -fx-padding: 12px 16px; -fx-background-radius: 0 8px 8px 0;");

        Label hypHeader = new Label("PRIMARY INVESTIGATION HYPOTHESIS");
        hypHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: #1d4ed8; -fx-letter-spacing: 0.5px;");

        Label hypText = new Label(dto.getPrimaryHypothesis() != null ? dto.getPrimaryHypothesis() : "—");
        hypText.setStyle("-fx-font-size: 15.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        hypText.setWrapText(true);

        hypBox.getChildren().addAll(hypHeader, hypText);

        // Executive Summary
        VBox summaryBox = new VBox(6);
        Label summaryHeader = new Label("Executive Forensic Synthesis");
        summaryHeader.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        Label summaryText = new Label(dto.getExecutiveSummary() != null ? dto.getExecutiveSummary() : "—");
        summaryText.setStyle("-fx-font-size: 14px; -fx-text-fill: #475569; -fx-line-spacing: 3px;");
        summaryText.setWrapText(true);

        summaryBox.getChildren().addAll(summaryHeader, summaryText);

        card.getChildren().addAll(hypBox, summaryBox);

        if (dto.getConfidenceExplanation() != null && !dto.getConfidenceExplanation().isBlank()) {
            Label confExp = new Label("⚖️ Confidence Rationale: " + dto.getConfidenceExplanation());
            confExp.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b; -fx-font-style: italic;");
            confExp.setWrapText(true);
            card.getChildren().add(confExp);
        }

        return card;
    }

    private HBox buildSectionTitle(String titleText, String accentColor, String countText) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 0, 4, 0));

        Label title = new Label(titleText);
        title.setStyle("-fx-font-size: 16.5px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label badge = new Label(countText);
        badge.setStyle("-fx-background-color: " + accentColor + "22; -fx-text-fill: " + accentColor + "; -fx-font-weight: bold; -fx-padding: 2px 8px; -fx-background-radius: 10px; -fx-font-size: 12px;");

        row.getChildren().addAll(title, badge);
        return row;
    }

    private VBox buildEvidenceCard(EvidencePointDto point) {
        VBox card = new VBox(10);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 8px; -fx-border-color: #e2e8f0; -fx-border-radius: 8px; -fx-padding: 14px;");

        // Top claim
        HBox claimRow = new HBox(8);
        claimRow.setAlignment(Pos.CENTER_LEFT);
        Label check = new Label("✔");
        check.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold; -fx-font-size: 14px;");
        Label claim = new Label(point.getClaim() != null ? point.getClaim() : "—");
        claim.setStyle("-fx-font-weight: bold; -fx-font-size: 14.5px; -fx-text-fill: #0f172a;");
        claim.setWrapText(true);
        claimRow.getChildren().addAll(check, claim);

        // Verbatim Quote Block
        VBox quoteBox = new VBox(4);
        quoteBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #10b981; -fx-border-width: 0 0 0 3px; -fx-padding: 8px 12px; -fx-background-radius: 0 6px 6px 0;");
        Label quote = new Label(point.getQuote() != null && !point.getQuote().isBlank() ? "“" + point.getQuote() + "”" : "No direct quote available.");
        quote.setStyle("-fx-font-size: 13px; -fx-text-fill: #334155; -fx-font-style: italic;");
        quote.setWrapText(true);
        quoteBox.getChildren().add(quote);

        // Relevance
        Label rel = new Label("Dot Connection: " + (point.getRelevance() != null ? point.getRelevance() : "Direct evidence linking facts."));
        rel.setStyle("-fx-font-size: 13px; -fx-text-fill: #475569;");
        rel.setWrapText(true);

        // Bottom chips: provenance + entities
        HBox bottomRow = new HBox(8);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        if (point.getSourceFile() != null && !point.getSourceFile().isBlank()) {
            Label srcChip = new Label("📄 " + point.getSourceFile() + (point.getChunkId() != null && !point.getChunkId().isBlank() ? " (" + point.getChunkId() + ")" : ""));
            srcChip.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-padding: 3px 8px; -fx-background-radius: 6px; -fx-font-size: 11.5px; -fx-font-weight: 600;");
            bottomRow.getChildren().add(srcChip);
        }

        if (point.getEntitiesInvolved() != null) {
            for (String entity : point.getEntitiesInvolved()) {
                bottomRow.getChildren().add(createEntityChip(entity));
            }
        }

        card.getChildren().addAll(claimRow, quoteBox, rel, bottomRow);
        return card;
    }

    private VBox buildFlawCard(FlawPointDto flaw) {
        VBox card = new VBox(10);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 8px; -fx-border-color: #fee2e2; -fx-border-radius: 8px; -fx-padding: 14px;");

        // Top flaw row with type badge
        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        String typeStr = flaw.getType() != null ? flaw.getType() : "WEAKNESS";
        Label typeBadge = new Label(typeStr);
        typeBadge.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #b91c1c; -fx-font-weight: bold; -fx-padding: 3px 8px; -fx-background-radius: 6px; -fx-font-size: 11px;");

        Label point = new Label(flaw.getPoint() != null ? flaw.getPoint() : "—");
        point.setStyle("-fx-font-weight: bold; -fx-font-size: 14.5px; -fx-text-fill: #991b1b;");
        point.setWrapText(true);
        topRow.getChildren().addAll(typeBadge, point);

        // Quote / observation block
        if (flaw.getQuote() != null && !flaw.getQuote().isBlank()) {
            VBox quoteBox = new VBox(4);
            quoteBox.setStyle("-fx-background-color: #fef2f2; -fx-border-color: #ef4444; -fx-border-width: 0 0 0 3px; -fx-padding: 8px 12px; -fx-background-radius: 0 6px 6px 0;");
            Label q = new Label("“" + flaw.getQuote() + "”");
            q.setStyle("-fx-font-size: 13px; -fx-text-fill: #7f1d1d; -fx-font-style: italic;");
            q.setWrapText(true);
            quoteBox.getChildren().add(q);
            card.getChildren().addAll(topRow, quoteBox);
        } else {
            card.getChildren().add(topRow);
        }

        // Impact explanation
        if (flaw.getImpact() != null && !flaw.getImpact().isBlank()) {
            Label impact = new Label("Investigative Challenge: " + flaw.getImpact());
            impact.setStyle("-fx-font-size: 13px; -fx-text-fill: #475569;");
            impact.setWrapText(true);
            card.getChildren().add(impact);
        }

        // Bottom provenance & entities
        HBox bottomRow = new HBox(8);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        if (flaw.getSourceFile() != null && !flaw.getSourceFile().isBlank()) {
            Label srcChip = new Label("📄 " + flaw.getSourceFile());
            srcChip.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-padding: 3px 8px; -fx-background-radius: 6px; -fx-font-size: 11.5px; -fx-font-weight: 600;");
            bottomRow.getChildren().add(srcChip);
        }

        if (flaw.getEntitiesInvolved() != null) {
            for (String entity : flaw.getEntitiesInvolved()) {
                bottomRow.getChildren().add(createEntityChip(entity));
            }
        }

        if (!bottomRow.getChildren().isEmpty()) {
            card.getChildren().add(bottomRow);
        }

        return card;
    }

    private VBox buildAlternativeHypothesisCard(AlternativeHypothesisDto alt) {
        VBox card = new VBox(10);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 8px; -fx-border-color: #e2e8f0; -fx-border-radius: 8px; -fx-padding: 14px;");

        Label title = new Label("💡 " + (alt.getTitle() != null ? alt.getTitle() : "Alternative Theory"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14.5px; -fx-text-fill: #1e40af;");

        Label desc = new Label(alt.getDescription() != null ? alt.getDescription() : "—");
        desc.setStyle("-fx-font-size: 13.5px; -fx-text-fill: #334155;");
        desc.setWrapText(true);

        card.getChildren().addAll(title, desc);

        if (alt.getSupportingPoints() != null && !alt.getSupportingPoints().isEmpty()) {
            VBox supBox = new VBox(4);
            Label supLbl = new Label("Points in Favor:");
            supLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #166534;");
            supBox.getChildren().add(supLbl);
            for (String p : alt.getSupportingPoints()) {
                Label item = new Label("  • " + p);
                item.setStyle("-fx-font-size: 12.5px; -fx-text-fill: #15803d;");
                item.setWrapText(true);
                supBox.getChildren().add(item);
            }
            card.getChildren().add(supBox);
        }

        if (alt.getCounterPoints() != null && !alt.getCounterPoints().isEmpty()) {
            VBox cntBox = new VBox(4);
            Label cntLbl = new Label("Why Less Likely:");
            cntLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #991b1b;");
            cntBox.getChildren().add(cntLbl);
            for (String p : alt.getCounterPoints()) {
                Label item = new Label("  • " + p);
                item.setStyle("-fx-font-size: 12.5px; -fx-text-fill: #b91c1c;");
                item.setWrapText(true);
                cntBox.getChildren().add(item);
            }
            card.getChildren().add(cntBox);
        }

        return card;
    }

    private VBox buildLeadCard(InvestigativeLeadDto lead) {
        VBox card = new VBox(8);
        card.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 8px; -fx-border-color: #e2e8f0; -fx-border-radius: 8px; -fx-padding: 12px 14px;");

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        String priority = lead.getPriority() != null ? lead.getPriority().toUpperCase() : "MEDIUM";
        String color = priority.contains("CRIT") ? "#ef4444" : (priority.contains("HIGH") ? "#f59e0b" : "#3b82f6");

        Label priBadge = new Label(priority);
        priBadge.setStyle("-fx-background-color: " + color + "22; -fx-text-fill: " + color + "; -fx-font-weight: bold; -fx-padding: 2px 7px; -fx-background-radius: 6px; -fx-font-size: 11px;");

        Label action = new Label(lead.getAction() != null ? lead.getAction() : lead.getLead());
        action.setStyle("-fx-font-weight: bold; -fx-font-size: 13.5px; -fx-text-fill: #0f172a;");
        action.setWrapText(true);

        topRow.getChildren().addAll(priBadge, action);

        card.getChildren().add(topRow);

        if (lead.getRationale() != null && !lead.getRationale().isBlank()) {
            Label rat = new Label("Objective: " + lead.getRationale());
            rat.setStyle("-fx-font-size: 12.5px; -fx-text-fill: #64748b;");
            rat.setWrapText(true);
            card.getChildren().add(rat);
        }

        return card;
    }

    private Button createEntityChip(String entityName) {
        Button chip = new Button(entityName);
        chip.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #0284c7; -fx-font-weight: 600; -fx-padding: 3px 8px; -fx-background-radius: 12px; -fx-font-size: 11.5px; -fx-cursor: hand; -fx-border-color: #e0f2fe; -fx-border-radius: 12px;");
        chip.setOnAction(e -> {
            if (onNavigateToEntity != null && entityName != null && !entityName.isBlank()) {
                onNavigateToEntity.accept(entityName);
            }
        });
        return chip;
    }
}
