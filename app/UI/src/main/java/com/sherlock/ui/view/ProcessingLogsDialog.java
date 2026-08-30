package com.sherlock.ui.view;

import com.sherlock.ui.model.ProcessingStatusDto;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ProcessingLogsDialog {

    private final Stage stage;
    private final String taskTitle;
    private final Supplier<CompletableFuture<ProcessingStatusDto>> statusSupplier;
    private final Runnable onComplete;

    private final Label titleLabel;
    private final Label statusBadge;
    private final Label messageLabel;
    private final ProgressBar progressBar;
    private final TextArea logsArea;
    private final Button actionBtn;
    private final Circle statusDot;

    private Timeline pollingTimeline;
    private int lastLogIndex = 0;
    private boolean isFinished = false;

    public ProcessingLogsDialog(
            Stage ownerStage,
            String taskTitle,
            String initialMessage,
            Supplier<CompletableFuture<ProcessingStatusDto>> statusSupplier,
            Runnable onComplete
    ) {
        this.taskTitle = taskTitle;
        this.statusSupplier = statusSupplier;
        this.onComplete = onComplete;

        stage = new Stage();
        if (ownerStage != null) {
            stage.initOwner(ownerStage);
        }
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(taskTitle);
        stage.initStyle(StageStyle.UTILITY);
        stage.setResizable(true);
        stage.setMinWidth(640);
        stage.setMinHeight(480);
        stage.setWidth(720);
        stage.setHeight(520);

        // Header
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 20, 12, 20));
        header.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1px 0;");

        statusDot = new Circle(5, Color.web("#3b82f6"));

        titleLabel = new Label(taskTitle);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBadge = new Label("PROCESSING");
        statusBadge.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #2563eb; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3px 8px; -fx-background-radius: 4px;");

        header.getChildren().addAll(statusDot, titleLabel, spacer, statusBadge);

        // Progress message & bar
        VBox progressBox = new VBox(8);
        progressBox.setPadding(new Insets(14, 20, 10, 20));
        progressBox.setStyle("-fx-background-color: #ffffff;");

        messageLabel = new Label(initialMessage != null ? initialMessage : "Starting task...");
        messageLabel.setStyle("-fx-font-size: 12.5px; -fx-text-fill: #475569; -fx-font-weight: 600;");
        messageLabel.setWrapText(true);

        progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(6);
        progressBar.setStyle("-fx-accent: #3b82f6;");

        progressBox.getChildren().addAll(messageLabel, progressBar);

        // Terminal Console Area
        VBox consoleContainer = new VBox(6);
        consoleContainer.setPadding(new Insets(8, 20, 12, 20));
        consoleContainer.setStyle("-fx-background-color: #f8fafc;");
        VBox.setVgrow(consoleContainer, Priority.ALWAYS);

        Label consoleHeader = new Label("EXECUTION LOGS & OUTPUT");
        consoleHeader.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1px;");

        logsArea = new TextArea();
        logsArea.setEditable(false);
        logsArea.setWrapText(true);
        logsArea.setStyle("-fx-control-inner-background: #0f172a; -fx-font-family: 'JetBrains Mono', 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; -fx-text-fill: #38bdf8; -fx-highlight-fill: #334155; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-border-color: #334155;");
        VBox.setVgrow(logsArea, Priority.ALWAYS);

        consoleContainer.getChildren().addAll(consoleHeader, logsArea);

        // Footer
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 20, 16, 20));
        footer.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-width: 1px 0 0 0;");

        Label hintLabel = new Label("You can keep this open to watch real-time logs or close it at any time.");
        hintLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #94a3b8;");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        actionBtn = new Button("Close");
        actionBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: 600; -fx-font-size: 13px; -fx-padding: 8px 18px; -fx-background-radius: 6px; -fx-cursor: hand;");
        actionBtn.setOnAction(e -> {
            stopPolling();
            stage.close();
        });

        footer.getChildren().addAll(hintLabel, footerSpacer, actionBtn);

        // Root Layout
        VBox root = new VBox();
        root.getChildren().addAll(header, progressBox, consoleContainer, footer);
        VBox.setVgrow(consoleContainer, Priority.ALWAYS);

        Scene scene = new Scene(root);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> stopPolling());
    }

    public void showAndStart() {
        stage.show();
        startPolling();
    }

    private void startPolling() {
        pollingTimeline = new Timeline(new KeyFrame(Duration.millis(500), e -> pollStatus()));
        pollingTimeline.setCycleCount(Animation.INDEFINITE);
        pollingTimeline.play();
        pollStatus();
    }

    private void stopPolling() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
            pollingTimeline = null;
        }
    }

    private void pollStatus() {
        if (isFinished || statusSupplier == null) return;

        statusSupplier.get()
                .thenAccept(status -> Platform.runLater(() -> updateStatus(status)))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        messageLabel.setText("Status check: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void updateStatus(ProcessingStatusDto status) {
        if (status == null) return;

        // Append new logs
        List<String> logs = status.getLogs();
        if (logs != null && logs.size() > lastLogIndex) {
            StringBuilder sb = new StringBuilder();
            for (int i = lastLogIndex; i < logs.size(); i++) {
                sb.append(logs.get(i)).append("\n");
            }
            logsArea.appendText(sb.toString());
            lastLogIndex = logs.size();
        }

        if (status.getMessage() != null && !status.getMessage().isBlank()) {
            messageLabel.setText(status.getMessage());
        }

        if (status.isCompleted()) {
            isFinished = true;
            stopPolling();
            progressBar.setProgress(1.0);

            if (status.isSuccess()) {
                statusDot.setFill(Color.web("#10b981"));
                statusBadge.setText("COMPLETED");
                statusBadge.setStyle("-fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3px 8px; -fx-background-radius: 4px;");
                messageLabel.setText(status.getMessage() != null ? status.getMessage() : "Task completed successfully.");
                actionBtn.setText("Done / View Results");
                actionBtn.setStyle("-fx-background-color: #0f172a; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 8px 18px; -fx-background-radius: 6px; -fx-cursor: hand;");
            } else {
                statusDot.setFill(Color.web("#ef4444"));
                statusBadge.setText("FAILED");
                statusBadge.setStyle("-fx-background-color: #fef2f2; -fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3px 8px; -fx-background-radius: 4px;");
                messageLabel.setText("Task failed: " + (status.getMessage() != null ? status.getMessage() : "Unknown error"));
                actionBtn.setText("Close");
            }

            if (onComplete != null) {
                onComplete.run();
            }
        }
    }
}
