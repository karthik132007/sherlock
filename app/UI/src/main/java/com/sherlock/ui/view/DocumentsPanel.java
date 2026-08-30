package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DocumentsPanel extends VBox {

    private final SherlockBackendClient client;
    private CaseDto currentCase;
    private String selectedFileName = null;
    private final List<String> fileList = new ArrayList<>();

    // UI Components
    private final TextField searchField = new TextField();
    private final VBox fileListContainer = new VBox(6);
    private final Label headerDocTitle = new Label("Select a Document");
    private final Label docStatsLbl = new Label("");
    private final TextArea contentArea = new TextArea();
    private final ProgressIndicator loadingIndicator = new ProgressIndicator();
    private final Label docCountBadge = new Label("0 files");
    private Runnable onFilesChanged;

    public DocumentsPanel(SherlockBackendClient client) {
        this.client = client;

        getStyleClass().add("glass-panel");
        setPadding(new Insets(16));
        setSpacing(12);
        VBox.setVgrow(this, Priority.ALWAYS);

        // Top Toolbar
        HBox topToolbar = buildTopToolbar();

        // Main Split: Left (Files List) | Right (Document Content)
        SplitPane splitPane = new SplitPane();
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        splitPane.getItems().addAll(buildLeftPane(), buildRightPane());
        splitPane.setDividerPositions(0.30);

        getChildren().addAll(topToolbar, splitPane);
    }

    private HBox buildTopToolbar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size: 20px;");

        Label title = new Label("Case Evidence & Documents");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        docCountBadge.getStyleClass().add("chat-badge-pill");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button uploadBtn = new Button("＋ Upload Evidence");
        uploadBtn.getStyleClass().add("primary-button");
        uploadBtn.setStyle("-fx-padding: 6px 12px; -fx-font-size: 13px; -fx-font-weight: bold;");
        uploadBtn.setOnAction(e -> handleUploadFiles());

        Button refreshBtn = new Button("↻ Refresh");
        refreshBtn.getStyleClass().add("tool-button");
        refreshBtn.setStyle("-fx-padding: 6px 10px; -fx-font-size: 13px;");
        refreshBtn.setOnAction(e -> refreshFiles());

        bar.getChildren().addAll(icon, title, docCountBadge, spacer, uploadBtn, refreshBtn);
        return bar;
    }

    private VBox buildLeftPane() {
        VBox left = new VBox(8);
        left.setPadding(new Insets(8));
        left.setMinWidth(220);
        left.setPrefWidth(280);

        searchField.setPromptText("Filter documents...");
        searchField.getStyleClass().add("text-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> renderFileList());

        fileListContainer.setFillWidth(true);
        ScrollPane scrollPane = new ScrollPane(fileListContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        left.getChildren().addAll(searchField, scrollPane);
        return left;
    }

    private VBox buildRightPane() {
        VBox right = new VBox(10);
        right.setPadding(new Insets(8));
        VBox.setVgrow(right, Priority.ALWAYS);

        // Header for selected document
        HBox docHeader = new HBox(10);
        docHeader.setAlignment(Pos.CENTER_LEFT);

        headerDocTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        HBox.setHgrow(headerDocTitle, Priority.ALWAYS);

        docStatsLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setVisible(false);

        Button copyBtn = new Button("📋 Copy Text");
        copyBtn.getStyleClass().add("tool-button");
        copyBtn.setStyle("-fx-font-size: 12px; -fx-padding: 4px 8px;");
        copyBtn.setOnAction(e -> {
            String text = contentArea.getText();
            if (text != null && !text.isBlank()) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                clipboard.setContent(content);
                copyBtn.setText("✓ Copied!");
                new Thread(() -> {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    Platform.runLater(() -> copyBtn.setText("📋 Copy Text"));
                }).start();
            }
        });

        docHeader.getChildren().addAll(headerDocTitle, loadingIndicator, docStatsLbl, copyBtn);

        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.setStyle("-fx-font-family: 'JetBrains Mono', 'Fira Code', 'DejaVu Sans Mono', monospace; -fx-font-size: 13.5px; -fx-text-fill: #1e293b; -fx-control-inner-background: #ffffff; -fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6px;");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        right.getChildren().addAll(docHeader, contentArea);
        return right;
    }

    public void setCase(CaseDto caseDto) {
        this.currentCase = caseDto;
        refreshFiles();
    }

    public void setOnFilesChanged(Runnable onFilesChanged) {
        this.onFilesChanged = onFilesChanged;
    }

    public void refreshFiles() {
        if (currentCase == null || currentCase.getCaseId() == null) return;
        String caseId = currentCase.getCaseId();

        client.getCaseAsync(caseId).thenAccept(caseData -> Platform.runLater(() -> {
            this.currentCase = caseData;
            fileList.clear();
            if (caseData != null && caseData.getFiles() != null) {
                fileList.addAll(caseData.getFiles());
            }
            // Always include warehouse.txt
            if (!fileList.contains("warehouse.txt")) {
                fileList.add("warehouse.txt");
            }
            docCountBadge.setText(fileList.size() + " files");
            renderFileList();

            if (selectedFileName != null && fileList.contains(selectedFileName)) {
                selectFile(selectedFileName);
            } else if (!fileList.isEmpty()) {
                selectFile(fileList.get(0));
            } else {
                headerDocTitle.setText("No documents in case");
                contentArea.setText("No evidence documents uploaded yet. Click '＋ Upload Evidence' to add files.");
                docStatsLbl.setText("");
            }
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                System.err.println("Failed to refresh case files: " + ex.getMessage());
            });
            return null;
        });
    }

    private void renderFileList() {
        fileListContainer.getChildren().clear();
        String query = searchField.getText() != null ? searchField.getText().trim().toLowerCase() : "";

        for (String fileName : fileList) {
            if (!query.isEmpty() && !fileName.toLowerCase().contains(query)) {
                continue;
            }

            boolean isSelected = fileName.equals(selectedFileName);
            boolean isWarehouse = "warehouse.txt".equalsIgnoreCase(fileName);

            HBox card = new HBox(8);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(8, 10, 8, 10));
            card.setStyle(isSelected
                    ? "-fx-background-color: rgba(59, 130, 246, 0.1); -fx-border-color: #3b82f6; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-cursor: hand;"
                    : "-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-cursor: hand;");

            Label icon = new Label(isWarehouse ? "🏛️" : "📄");
            icon.setStyle("-fx-font-size: 14px;");

            VBox infoBox = new VBox(2);
            HBox.setHgrow(infoBox, Priority.ALWAYS);

            Label nameLbl = new Label(fileName);
            nameLbl.setStyle(isSelected
                    ? "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1d4ed8;"
                    : "-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #1e293b;");

            Label tagLbl = new Label(isWarehouse ? "Compiled Warehouse" : "Evidence File");
            tagLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

            infoBox.getChildren().addAll(nameLbl, tagLbl);

            card.getChildren().addAll(icon, infoBox);
            card.setOnMouseClicked(e -> selectFile(fileName));

            fileListContainer.getChildren().add(card);
        }
    }

    public void selectFile(String fileName) {
        this.selectedFileName = fileName;
        renderFileList();

        if (currentCase == null || currentCase.getCaseId() == null || fileName == null) return;
        String caseId = currentCase.getCaseId();

        headerDocTitle.setText(fileName);
        loadingIndicator.setVisible(true);
        docStatsLbl.setText("Loading...");

        if ("warehouse.txt".equalsIgnoreCase(fileName)) {
            client.getWarehouseContentAsync(caseId).thenAccept(content -> Platform.runLater(() -> {
                loadingIndicator.setVisible(false);
                displayContent(fileName, content);
            })).exceptionally(ex -> {
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    displayContent(fileName, "Failed to load warehouse: " + ex.getMessage());
                });
                return null;
            });
        } else {
            client.getFileContentAsync(caseId, fileName).thenAccept(content -> Platform.runLater(() -> {
                loadingIndicator.setVisible(false);
                displayContent(fileName, content);
            })).exceptionally(ex -> {
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    displayContent(fileName, "Failed to load file: " + ex.getMessage());
                });
                return null;
            });
        }
    }

    private void displayContent(String fileName, String content) {
        String safeContent = content != null ? content : "";
        contentArea.setText(safeContent);
        int lines = safeContent.split("\n", -1).length;
        int chars = safeContent.length();
        docStatsLbl.setText(lines + " lines • " + chars + " chars");
    }

    private void handleUploadFiles() {
        if (currentCase == null || currentCase.getCaseId() == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Evidence Documents to Upload");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Evidence Files (*.txt, *.pdf, *.docx, *.json, *.csv)", "*.txt", "*.pdf", "*.docx", "*.json", "*.csv", "*.md", "*.log"),
                new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );

        List<File> selected = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (selected != null && !selected.isEmpty()) {
            loadingIndicator.setVisible(true);
            client.uploadFilesAsync(currentCase.getCaseId(), selected).thenAccept(updatedCase -> Platform.runLater(() -> {
                loadingIndicator.setVisible(false);
                this.currentCase = updatedCase;
                refreshFiles();
                if (onFilesChanged != null) {
                    onFilesChanged.run();
                }
            })).exceptionally(ex -> {
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Upload failed: " + ex.getMessage(), ButtonType.OK);
                    alert.showAndWait();
                });
                return null;
            });
        }
    }
}
