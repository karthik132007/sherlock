package com.sherlock.ui;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SherlockJavaFxApp extends Application {

    private final List<File> selectedFiles = new ArrayList<>();
    private final Label fileListLabel = new Label("No files selected");
    private final TextField caseNameField = new TextField();
    private final Label statusLabel = new Label("Ready");

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Sherlock");

        Label title = new Label("Sherlock");
        title.setFont(Font.font("System", 28));

        Label caseLabel = new Label("Case Name");
        caseNameField.setPromptText("Operation Rose");
        caseNameField.setPrefWidth(420);

        Button addFilesButton = new Button("Add Files");
        Button createCaseButton = new Button("Create Case");
        Button clearButton = new Button("Clear");

        HBox buttonRow = new HBox(12, addFilesButton, clearButton, createCaseButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        fileListLabel.setWrapText(true);
        fileListLabel.setMaxWidth(520);

        VBox formBox = new VBox(14,
                title,
                caseLabel,
                caseNameField,
                buttonRow,
                fileListLabel,
                statusLabel
        );
        formBox.setPadding(new Insets(24));
        formBox.setPrefWidth(600);
        formBox.setAlignment(Pos.TOP_LEFT);

        BorderPane root = new BorderPane();
        root.setCenter(formBox);
        root.setStyle("-fx-background-color: #0b0d12; -fx-text-fill: white;");

        Scene scene = new Scene(root, 760, 460);
        stage.setScene(scene);
        stage.show();

        setupFileChooser(addFilesButton);
        setupDragAndDrop(formBox, root);
        createCaseButton.setOnAction(event -> createCase());
        clearButton.setOnAction(event -> clearSelection());
    }

    private void setupFileChooser(Button addFilesButton) {
        addFilesButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select case evidence files");
            List<File> files = chooser.showOpenMultipleDialog(null);
            if (files != null && !files.isEmpty()) {
                selectedFiles.clear();
                selectedFiles.addAll(files);
                updateFileList();
            }
        });
    }

    private void setupDragAndDrop(VBox formBox, BorderPane root) {
        formBox.setOnDragOver(event -> {
            if (event.getGestureSource() != formBox && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        formBox.setOnDragDropped((DragEvent event) -> {
            var db = event.getDragboard();
            if (db.hasFiles()) {
                selectedFiles.clear();
                selectedFiles.addAll(db.getFiles());
                updateFileList();
                event.setDropCompleted(true);
            } else {
                event.setDropCompleted(false);
            }
            event.consume();
        });

        root.setOnDragOver(event -> {
            if (event.getGestureSource() != root && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
    }

    private void updateFileList() {
        if (selectedFiles.isEmpty()) {
            fileListLabel.setText("No files selected");
            return;
        }

        String joined = selectedFiles.stream()
                .limit(10)
                .map(File::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        if (selectedFiles.size() > 10) {
            joined += " ... (" + selectedFiles.size() + " total)";
        }

        fileListLabel.setText(joined);
    }

    private void clearSelection() {
        selectedFiles.clear();
        fileListLabel.setText("No files selected");
        statusLabel.setText("Files cleared");
    }

    private void createCase() {
        String caseName = caseNameField.getText() == null ? "" : caseNameField.getText().trim();
        if (caseName.isBlank()) {
            statusLabel.setText("Please enter a case name.");
            return;
        }

        if (selectedFiles.isEmpty()) {
            statusLabel.setText("Please select at least one file.");
            return;
        }

        statusLabel.setText("Creating case and sending files to backend...");

        try {
            SherlockBackendClient client = new SherlockBackendClient();
            var caseResponse = client.createCase(caseName);
            var uploadResponse = client.uploadFiles(caseResponse.getCaseId(), selectedFiles);
            var processResponse = client.startProcessing(caseResponse.getCaseId());
            statusLabel.setText("Case created: " + caseResponse.getCaseId() + " | " + processResponse.getStatus());
        } catch (Exception e) {
            statusLabel.setText("Error: " + e.getMessage());
        }
    }
}
