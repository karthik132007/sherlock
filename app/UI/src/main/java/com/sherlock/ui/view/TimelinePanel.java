package com.sherlock.ui.view;

import com.sherlock.ui.model.TimelineEventDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

public class TimelinePanel extends ScrollPane {

    private final VBox container;
    private final Runnable onGenerateTimelineRequested;

    public TimelinePanel(Runnable onGenerateTimelineRequested) {
        this.onGenerateTimelineRequested = onGenerateTimelineRequested;
        container = new VBox();
        container.setPadding(new Insets(20));
        container.setStyle("-fx-background-color: #0b0e14;");

        setFitToWidth(true);
        setContent(container);
        setStyle("-fx-background: #0b0e14; -fx-border-color: transparent; -fx-control-inner-background: #0b0e14;");
        
        showPlaceholder();
    }

    public void showPlaceholder() {
        container.getChildren().clear();
        
        VBox placeholderBox = new VBox(12);
        placeholderBox.setAlignment(Pos.CENTER);
        
        Label placeholder = new Label("No timeline events available.");
        placeholder.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");
        
        Button generateBtn = new Button("⏳ Extract Timeline");
        generateBtn.setStyle("-fx-background-color: #38bdf8; -fx-text-fill: #0f172a; -fx-font-weight: bold; -fx-padding: 8px 16px; -fx-background-radius: 6px;");
        generateBtn.setOnAction(e -> {
            if (onGenerateTimelineRequested != null) {
                onGenerateTimelineRequested.run();
            }
        });
        
        placeholderBox.getChildren().addAll(placeholder, generateBtn);
        
        container.setAlignment(Pos.CENTER);
        container.getChildren().add(placeholderBox);
    }

    public void setTimelineData(TimelineEventDto timelineDto) {
        container.getChildren().clear();
        container.setAlignment(Pos.TOP_LEFT);

        if (timelineDto == null || timelineDto.getTimeline() == null || timelineDto.getTimeline().isEmpty()) {
            showPlaceholder();
            return;
        }

        for (int i = 0; i < timelineDto.getTimeline().size(); i++) {
            TimelineEventDto.TimelineEventItem item = timelineDto.getTimeline().get(i);
            boolean isLast = i == timelineDto.getTimeline().size() - 1;
            container.getChildren().add(buildEventNode(item, isLast));
        }
    }

    private HBox buildEventNode(TimelineEventDto.TimelineEventItem item, boolean isLast) {
        HBox row = new HBox();
        
        // Left: Timestamp
        VBox timeBox = new VBox();
        timeBox.setPrefWidth(120);
        timeBox.setMinWidth(120);
        timeBox.setAlignment(Pos.TOP_RIGHT);
        timeBox.setPadding(new Insets(5, 15, 0, 0));
        
        Label timeLabel = new Label(item.getTimestamp() != null && !item.getTimestamp().isBlank() ? item.getTimestamp() : "Unknown");
        timeLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-weight: bold; -fx-font-size: 12px;");
        timeLabel.setWrapText(true);
        timeBox.getChildren().add(timeLabel);

        // Center: Timeline graphics
        VBox graphicsBox = new VBox();
        graphicsBox.setAlignment(Pos.TOP_CENTER);
        graphicsBox.setPrefWidth(20);
        
        Circle dot = new Circle(6, Color.web("#38bdf8"));
        
        VBox.setMargin(dot, new Insets(8, 0, 0, 0));
        graphicsBox.getChildren().add(dot);
        
        if (!isLast) {
            Region line = new Region();
            line.setPrefWidth(2);
            line.setStyle("-fx-background-color: #1e293b;");
            VBox.setVgrow(line, Priority.ALWAYS);
            VBox.setMargin(line, new Insets(4, 0, 0, 0));
            graphicsBox.getChildren().add(line);
        } else {
            Region spacer = new Region();
            spacer.setMinHeight(20);
            graphicsBox.getChildren().add(spacer);
        }

        // Right: Content card
        VBox contentBox = new VBox(8);
        HBox.setHgrow(contentBox, Priority.ALWAYS);
        contentBox.setPadding(new Insets(0, 10, 25, 15));
        
        VBox card = new VBox(6);
        card.setStyle("-fx-background-color: #161e2e; -fx-background-radius: 8px; -fx-border-color: #1e293b; -fx-border-radius: 8px; -fx-padding: 12px;");
        
        String mainText = item.getTitle() != null && !item.getTitle().isBlank() ? item.getTitle() : (item.getEvent() != null ? item.getEvent() : "Event");
        Label titleLabel = new Label(mainText);
        titleLabel.setStyle("-fx-text-fill: #f8fafc; -fx-font-weight: bold; -fx-font-size: 14px;");
        titleLabel.setWrapText(true);
        card.getChildren().add(titleLabel);
        
        if (item.getDescription() != null && !item.getDescription().isBlank() && !item.getDescription().equals(mainText)) {
            Label descText = new Label(item.getDescription());
            descText.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 13px;");
            descText.setWrapText(true);
            card.getChildren().add(descText);
        }
        
        if (item.getEntitiesInvolved() != null && !item.getEntitiesInvolved().isEmpty()) {
            FlowPane tags = new FlowPane();
            tags.setHgap(6);
            tags.setVgap(6);
            for (String entity : item.getEntitiesInvolved()) {
                Label tag = new Label(entity);
                tag.setStyle("-fx-background-color: rgba(59, 130, 246, 0.15); -fx-text-fill: #60a5fa; -fx-font-size: 11px; -fx-padding: 2px 6px; -fx-background-radius: 4px;");
                tags.getChildren().add(tag);
            }
            card.getChildren().add(tags);
        }
        
        HBox metaBox = new HBox(10);
        if (item.getSourceFile() != null && !item.getSourceFile().isBlank()) {
            Label srcLabel = new Label("📄 " + item.getSourceFile());
            srcLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
            metaBox.getChildren().add(srcLabel);
        }
        if (item.getConfidence() != null) {
            Label confLabel = new Label(String.format("Conf: %.0f%%", item.getConfidence() * 100));
            confLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
            metaBox.getChildren().add(confLabel);
        }
        if (!metaBox.getChildren().isEmpty()) {
            card.getChildren().add(metaBox);
        }
        
        contentBox.getChildren().add(card);
        
        row.getChildren().addAll(timeBox, graphicsBox, contentBox);
        return row;
    }
}
