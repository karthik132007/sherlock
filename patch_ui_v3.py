import re

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "r") as f:
    content = f.read()

# 1. Update buildSidebar
sidebar_pattern = r"(navContradictions\.setOnAction\(e -> \{\s*if \(contradictionsBtn != null\) contradictionsBtn\.setSelected\(true\);\s*\}\);).*?(navSettings\.setOnAction\(e -> showSettingsDialog\(\);\s*navMenu\.getChildren\(\)\.addAll\(navHome, navGraph, navTimeline, navContradictions, navSearch, navDocs, navSaved, navData, navSettings\);)"

new_sidebar_part = r"""\1

        Button navDocs = createNavButton("📄", "Documents");
        Button navSaved = createNavButton("🔖", "Saved Views");
        Button navSettings = createNavButton("⚙️", "Settings");
        
        navHome.setOnAction(e -> {
            for (javafx.scene.Node n : navMenu.getChildren()) {
                n.getStyleClass().remove("nav-item-active");
            }
            navHome.getStyleClass().add("nav-item-active");
            centerContainer.getChildren().setAll(buildHomeView());
        });
        
        navGraph.setOnAction(e -> {
            if (graphBtn != null) graphBtn.setSelected(true);
            for (javafx.scene.Node n : navMenu.getChildren()) {
                n.getStyleClass().remove("nav-item-active");
            }
            navGraph.getStyleClass().add("nav-item-active");
            centerContainer.getChildren().setAll(buildCenterView());
        });
        
        navDocs.setOnAction(e -> {
            for (javafx.scene.Node n : navMenu.getChildren()) {
                n.getStyleClass().remove("nav-item-active");
            }
            navDocs.getStyleClass().add("nav-item-active");
            centerContainer.getChildren().setAll(buildDocumentsView());
        });
        
        navSaved.setOnAction(e -> {
            for (javafx.scene.Node n : navMenu.getChildren()) {
                n.getStyleClass().remove("nav-item-active");
            }
            navSaved.getStyleClass().add("nav-item-active");
            centerContainer.getChildren().setAll(buildSavedViews());
        });

        navSettings.setOnAction(e -> showSettingsDialog());

        navMenu.getChildren().addAll(navHome, navGraph, navTimeline, navContradictions, navDocs, navSaved, navSettings);"""

content = re.sub(sidebar_pattern, new_sidebar_part, content, flags=re.DOTALL)

# Add centerContainer field if missing
if "private StackPane centerContainer;" not in content:
    content = content.replace("private Button investigateBtn;", "private Button investigateBtn;\n    private StackPane centerContainer;")

# 2. Update buildCenterView to use StackPane overlay for detailsPanel
center_view_pattern = r"(// Stack for Graph/Timeline/Contradictions.*?)(?=private VBox buildRightPanel)"
new_center_view = r"""// Stack for Graph/Timeline/Contradictions
        StackPane viewStack = new StackPane();
        VBox.setVgrow(viewStack, Priority.ALWAYS);

        timelinePanel.setVisible(false);
        timelinePanel.setManaged(false);
        contradictionsPanel.setVisible(false);
        contradictionsPanel.setManaged(false);
        viewStack.getChildren().addAll(graphCanvas, timelinePanel, contradictionsPanel);
        
        viewStack.setOnMouseClicked(e -> {
            detailsPanel.setPrefHeight(345.0);
        });

        detailsPanel.setMinHeight(270.0);
        detailsPanel.setPrefHeight(345.0);

        StackPane mainAreaStack = new StackPane();
        VBox.setVgrow(mainAreaStack, Priority.ALWAYS);
        
        StackPane.setAlignment(detailsPanel, Pos.BOTTOM_CENTER);
        mainAreaStack.getChildren().addAll(viewStack, detailsPanel);

        center.getChildren().addAll(header, mainAreaStack);
        return center;
    }
    
    private VBox buildHomeView() {
        VBox home = new VBox(20);
        home.setAlignment(Pos.CENTER);
        Label lbl = new Label("Sherlock Home");
        lbl.setStyle("-fx-font-size: 34.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        Button saveBtn = new Button("💾 Save Checkpoint");
        saveBtn.getStyleClass().add("primary-button");
        saveBtn.setStyle("-fx-font-size: 23px; -fx-padding: 10px 20px;");
        saveBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setHeaderText("Checkpoint Saved");
            alert.setContentText("Current investigation view has been saved.");
            alert.show();
        });
        
        home.getChildren().addAll(lbl, saveBtn);
        return home;
    }
    
    private VBox buildSavedViews() {
        VBox saved = new VBox(20);
        saved.setPadding(new Insets(40));
        saved.setAlignment(Pos.TOP_LEFT);
        
        Label title = new Label("Saved Checkpoints");
        title.setStyle("-fx-font-size: 30.7px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        ListView<String> savedList = new ListView<>();
        savedList.getItems().addAll("Checkpoint 1 - Initial Graph", "Checkpoint 2 - Suspect Connections");
        VBox.setVgrow(savedList, Priority.ALWAYS);
        
        saved.getChildren().addAll(title, savedList);
        return saved;
    }
    
    """

content = re.sub(center_view_pattern, new_center_view, content, flags=re.DOTALL)


# 3. Update buildDocumentsView
docs_view_pattern = r"(private VBox buildDocumentsView\(\) \{.*?return docs;\n    })"

new_docs_view = r"""private VBox buildDocumentsView() {
        VBox docs = new VBox(20);
        docs.setPadding(new Insets(40));
        docs.setAlignment(Pos.TOP_LEFT);
        
        Label title = new Label("Documents & Evidence");
        title.setStyle("-fx-font-size: 24.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        Button uploadBtn = new Button("☁️ Upload New Files");
        uploadBtn.getStyleClass().add("secondary-button");
        uploadBtn.setStyle("-fx-font-size: 13.8px;");
        uploadBtn.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Select Evidence Files");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Text Files", "*.txt"));
            java.util.List<java.io.File> files = fc.showOpenMultipleDialog(null);
            if (files != null && !files.isEmpty()) {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                alert.setHeaderText("Files Selected");
                alert.setContentText(files.size() + " files selected for upload. Click Run Execution to process.");
                alert.show();
            }
        });
        
        Button runExecBtn = new Button("🚀 Run Execution");
        runExecBtn.getStyleClass().add("primary-button");
        runExecBtn.setStyle("-fx-font-size: 13.8px; -fx-background-color: #10b981;");
        runExecBtn.setOnAction(e -> startInvestigation());
        
        HBox actions = new HBox(10, uploadBtn, runExecBtn);
        
        ListView<String> docList = new ListView<>();
        docList.setStyle("-fx-font-size: 17.2px; -fx-padding: 10px;");
        
        if (currentCase != null && currentCase.getCaseId() != null) {
            java.nio.file.Path dataPath = java.nio.file.Paths.get(System.getProperty("user.home"), "Documents", "Sherlock", currentCase.getCaseId(), "data");
            if (java.nio.file.Files.exists(dataPath)) {
                try {
                    java.nio.file.Files.list(dataPath)
                        .filter(p -> p.toString().endsWith(".txt"))
                        .map(p -> p.getFileName().toString())
                        .forEach(docList.getItems()::add);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
        if (docList.getItems().isEmpty()) {
            docList.getItems().addAll("fir.txt", "postmortem_report.txt", "witness_statement.txt", "call_logs.txt");
        }
        
        docList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 || e.getClickCount() == 1) {
                String selected = docList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showDocumentPopup(selected);
                }
            }
        });
        
        VBox.setVgrow(docList, Priority.ALWAYS);
        
        docs.getChildren().addAll(title, actions, docList);
        return docs;
    }
    
    private void showDocumentPopup(String filename) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Document Viewer: " + filename);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-size: 15.3px; -fx-font-family: monospace;");
        
        if (currentCase != null && currentCase.getCaseId() != null) {
            java.nio.file.Path filePath = java.nio.file.Paths.get(System.getProperty("user.home"), "Documents", "Sherlock", currentCase.getCaseId(), "data", filename);
            try {
                if (java.nio.file.Files.exists(filePath)) {
                    textArea.setText(java.nio.file.Files.readString(filePath));
                } else {
                    textArea.setText("Mock content for " + filename + "\n\n(No actual file found at " + filePath + ")");
                }
            } catch (Exception e) {
                textArea.setText("Error reading file: " + e.getMessage());
            }
        } else {
            textArea.setText("Mock content for " + filename);
        }
        
        dialog.getDialogPane().setContent(textArea);
        dialog.getDialogPane().setPrefSize(600, 500);
        dialog.showAndWait();
    }"""

content = re.sub(docs_view_pattern, new_docs_view, content, flags=re.DOTALL)

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "w") as f:
    f.write(content)

print("UI patched.")
