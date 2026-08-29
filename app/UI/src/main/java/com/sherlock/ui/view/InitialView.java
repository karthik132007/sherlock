package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.model.LlmConfigDto;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class InitialView extends BorderPane {

    private final SherlockBackendClient client;
    private final Consumer<CaseDto> onCaseLockedIn;

    private final TextField caseNameField = new TextField("CASE-001");
    private final List<File> selectedFiles = new ArrayList<>();
    private final FlowPane fileBadgesPane = new FlowPane(6, 6);

    private final PasswordField apiKeyField = new PasswordField();
    private final TextField apiKeyVisibleField = new TextField();
    private final Button toggleKeyVisibilityBtn = new Button("👁");

    private final ComboBox<String> providerCombo = new ComboBox<>();
    private final ComboBox<String> modelCombo = new ComboBox<>();
    private final CheckBox termsCheckBox = new CheckBox("Accept our Terms and conditions");
    private final Button proceedBtn = new Button("Let's Lock in with SHERLOCK");
    private final Label statusLabel = new Label();
    private final ProgressIndicator loadingIndicator = new ProgressIndicator();

    private final VBox historyContainer = new VBox(6);

    public InitialView(SherlockBackendClient client, Consumer<CaseDto> onCaseLockedIn) {
        this.client = client;
        this.onCaseLockedIn = onCaseLockedIn;

        getStyleClass().add("root");
        setStyle("-fx-background-color: #0b0e14;");

        ScrollPane mainScroll = new ScrollPane(buildMainLayout());
        mainScroll.setFitToWidth(true);
        mainScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        setCenter(mainScroll);

        loadRecentCases();
    }

    public void loadRecentCases() {
        client.listCasesAsync().thenAccept(cases -> Platform.runLater(() -> {
            historyContainer.getChildren().clear();
            if (cases.isEmpty()) {
                Label empty = new Label("No cases found in project data directory.");
                empty.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-style: italic;");
                historyContainer.getChildren().add(empty);
            } else {
                for (CaseDto c : cases) {
                    HBox row = new HBox(8);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setStyle("-fx-background-color: #161e2e; -fx-padding: 6px 10px; -fx-background-radius: 6px; -fx-border-color: #243048; -fx-border-radius: 6px;");

                    VBox info = new VBox(2);
                    Label nameLbl = new Label(c.getCaseName() + " (" + c.getCaseId() + ")");
                    nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #38bdf8; -fx-font-size: 11.5px;");

                    Label detailLbl = new Label(c.getFileCount() + " files • " + c.getStatus());
                    detailLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8;");
                    info.getChildren().addAll(nameLbl, detailLbl);

                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);

                    Button openBtn = new Button("Open ➔");
                    openBtn.getStyleClass().add("secondary-button");
                    openBtn.setStyle("-fx-font-size: 10.5px; -fx-padding: 4px 10px;");
                    openBtn.setOnAction(e -> {
                        if (onCaseLockedIn != null) {
                            onCaseLockedIn.accept(c);
                        }
                    });

                    row.getChildren().addAll(info, sp, openBtn);
                    historyContainer.getChildren().add(row);
                }
            }
            updateSuggestedCaseId(cases);
        })).exceptionally(ex -> null);
    }

    private void updateSuggestedCaseId(List<CaseDto> cases) {
        int maxNum = 0;
        for (CaseDto c : cases) {
            String id = c.getCaseId();
            if (id != null) {
                String upper = id.toUpperCase();
                if (upper.startsWith("CASE-") || upper.startsWith("CASE_")) {
                    try {
                        String numStr = upper.substring(5).replaceAll("[^0-9]", "");
                        if (!numStr.isEmpty()) {
                            int n = Integer.parseInt(numStr);
                            if (n > maxNum) maxNum = n;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        String nextId = String.format("CASE-%03d", maxNum + 1);
        if (caseNameField.getText() == null || caseNameField.getText().isBlank() || caseNameField.getText().matches("(?i)CASE-\\d+")) {
            caseNameField.setText(nextId);
        }
    }

    private VBox buildMainLayout() {
        VBox layout = new VBox(14);
        layout.setAlignment(Pos.TOP_CENTER);
        layout.setPadding(new Insets(20, 36, 20, 36));
        layout.setMaxWidth(960);

        // 1. Header Bar
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label logo = new Label("🕵️");
        logo.setStyle("-fx-font-size: 24px;");
        Label title = new Label("SHERLOCK");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-letter-spacing: 2px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label subTitle = new Label("AI Investigation Intelligence");
        subTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

        header.getChildren().addAll(logo, title, spacer, subTitle);

        // 2. Top Grid (Welcome Card + Case Card)
        GridPane topGrid = new GridPane();
        topGrid.setHgap(14);
        topGrid.setVgap(14);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        topGrid.getColumnConstraints().addAll(col1, col2);

        VBox welcomeCard = buildWelcomeCard();
        VBox caseCard = buildCaseCard();

        topGrid.add(welcomeCard, 0, 0);
        topGrid.add(caseCard, 1, 0);

        // 3. Settings Card (LLM Configuration)
        VBox settingsCard = buildSettingsCard();

        // 4. Action Row (Lock-in Button + Terms)
        VBox actionBox = buildActionBox();

        // 5. Recent Cases / History Section
        VBox historyCard = buildHistorySection();

        layout.getChildren().addAll(header, topGrid, settingsCard, actionBox, historyCard);
        return layout;
    }

    private VBox buildWelcomeCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("dark-panel");
        card.setPadding(new Insets(16));
        card.setPrefHeight(220);

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("ℹ️");
        icon.setStyle("-fx-font-size: 16px;");
        Label title = new Label("Welcome to SHERLOCK");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
        titleRow.getChildren().addAll(icon, title);

        VBox bulletPoints = new VBox(6);
        bulletPoints.getChildren().add(createBullet("- Converts raw case evidence into an interactive, explainable Knowledge Graph."));
        bulletPoints.getChildren().add(createBullet("- Automatic chunking, entity extraction, relationship discovery, and timeline reconstruction."));
        bulletPoints.getChildren().add(createBullet("- Grounded reasoning with source document citations and evidence provenance."));

        card.getChildren().addAll(titleRow, bulletPoints);
        return card;
    }

    private Label createBullet(String text) {
        Label lbl = new Label(text);
        lbl.setWrapText(true);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8; -fx-line-spacing: 2px;");
        return lbl;
    }

    private VBox buildCaseCard() {
        VBox card = new VBox(8);
        card.getStyleClass().add("dark-panel");
        card.setPadding(new Insets(16));
        card.setPrefHeight(220);

        HBox headerRow = new HBox(8);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("📁");
        icon.setStyle("-fx-font-size: 16px;");

        caseNameField.setPromptText("Enter Case Name or ID");
        caseNameField.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38bdf8; -fx-background-color: #161e2e; -fx-border-color: #243048;");
        HBox.setHgrow(caseNameField, Priority.ALWAYS);

        headerRow.getChildren().addAll(icon, caseNameField);

        // Upload Dropzone
        VBox dropzone = new VBox(4);
        dropzone.getStyleClass().add("dropzone");
        dropzone.setAlignment(Pos.CENTER);
        VBox.setVgrow(dropzone, Priority.ALWAYS);

        Label dropTitle = new Label("Upload files here (or drag & drop)");
        dropTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #e2e8f0;");

        Label formats = new Label("• .txt   • .pdf   • .png allowed");
        formats.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");

        Button browseBtn = new Button("Browse Files");
        browseBtn.getStyleClass().add("secondary-button");
        browseBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
        browseBtn.setOnAction(e -> handleBrowseFiles());

        dropzone.getChildren().addAll(dropTitle, formats, browseBtn);
        setupDragAndDrop(dropzone);

        ScrollPane badgeScroll = new ScrollPane(fileBadgesPane);
        badgeScroll.setFitToWidth(true);
        badgeScroll.setPrefHeight(40);
        badgeScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        card.getChildren().addAll(headerRow, dropzone, badgeScroll);
        return card;
    }

    private VBox buildSettingsCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("dark-panel");
        card.setPadding(new Insets(16));

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        // 1. API Key Input with Eye Toggle
        VBox keyBox = new VBox(4);
        Label keyLabel = new Label("Provide API key (Optional for local/demo)");
        keyLabel.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: #cbd5e1;");

        apiKeyField.setPromptText("sk-... (optional)");
        apiKeyVisibleField.setPromptText("sk-... (optional)");
        apiKeyVisibleField.setManaged(false);
        apiKeyVisibleField.setVisible(false);

        apiKeyField.textProperty().bindBidirectional(apiKeyVisibleField.textProperty());

        toggleKeyVisibilityBtn.getStyleClass().add("tool-button");
        toggleKeyVisibilityBtn.setOnAction(e -> {
            if (apiKeyField.isVisible()) {
                apiKeyField.setVisible(false);
                apiKeyField.setManaged(false);
                apiKeyVisibleField.setVisible(true);
                apiKeyVisibleField.setManaged(true);
                toggleKeyVisibilityBtn.setText("🔒");
            } else {
                apiKeyVisibleField.setVisible(false);
                apiKeyVisibleField.setManaged(false);
                apiKeyField.setVisible(true);
                apiKeyField.setManaged(true);
                toggleKeyVisibilityBtn.setText("👁");
            }
        });

        HBox keyInputRow = new HBox(6);
        keyInputRow.setAlignment(Pos.CENTER_LEFT);
        StackPane keyStack = new StackPane(apiKeyField, apiKeyVisibleField);
        HBox.setHgrow(keyStack, Priority.ALWAYS);
        keyInputRow.getChildren().addAll(keyStack, toggleKeyVisibilityBtn);

        keyBox.getChildren().addAll(keyLabel, keyInputRow);

        // 2. Model Selection
        VBox modelBox = new VBox(4);
        Label modelLabel = new Label("Model Name");
        modelLabel.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: #cbd5e1;");

        modelCombo.setEditable(true);
        modelCombo.getItems().addAll(
                "gpt-4o-mini",
                "gpt-4o",
                "deepseek-chat",
                "llama-3.3-70b-versatile",
                "mistral-large-latest",
                "ollama/llama3"
        );
        modelCombo.setValue("gpt-4o-mini");
        modelCombo.setMaxWidth(Double.MAX_VALUE);

        modelBox.getChildren().addAll(modelLabel, modelCombo);

        // 3. Provider Selection
        VBox providerBox = new VBox(4);
        Label providerLabel = new Label("Provider Name");
        providerLabel.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: #cbd5e1;");

        providerCombo.getItems().addAll("OpenAI", "OpenRouter", "DeepSeek", "Groq", "Together", "Mistral", "Ollama", "Custom");
        providerCombo.setValue("OpenAI");
        providerCombo.setMaxWidth(Double.MAX_VALUE);

        providerCombo.setOnAction(e -> {
            String p = providerCombo.getValue();
            if ("Ollama".equalsIgnoreCase(p)) {
                apiKeyField.setDisable(true);
                apiKeyField.setText("");
                apiKeyField.setPromptText("Local Ollama (No API Key Required)");
                keyLabel.setText("API Key (Zero-key Local Ollama Mode)");
                modelLabel.setText("Ollama Local Model (from `ollama list`)");

                client.getOllamaModelsAsync().thenAccept(models -> Platform.runLater(() -> {
                    if (models != null && !models.isEmpty()) {
                        modelCombo.getItems().setAll(models);
                        modelCombo.setValue(models.get(0));
                        modelCombo.setDisable(false);
                    } else {
                        modelCombo.getItems().clear();
                        modelCombo.setPromptText("No Ollama models found");
                        modelCombo.setValue(null);
                        modelCombo.setDisable(true);
                    }
                }));
            } else {
                apiKeyField.setDisable(false);
                apiKeyField.setPromptText("sk-... (required for cloud provider)");
                keyLabel.setText("Provide API key");
                modelLabel.setText("Model Name");
                modelCombo.setDisable(false);

                if ("DeepSeek".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("deepseek-chat", "deepseek-coder");
                    modelCombo.setValue("deepseek-chat");
                } else if ("Groq".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("llama-3.3-70b-versatile", "mixtral-8x7b-32768");
                    modelCombo.setValue("llama-3.3-70b-versatile");
                } else if ("OpenRouter".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "meta-llama/llama-3.3-70b-instruct");
                    modelCombo.setValue("openai/gpt-4o-mini");
                } else if ("Mistral".equalsIgnoreCase(p)) {
                    modelCombo.getItems().setAll("mistral-large-latest", "mistral-small-latest");
                    modelCombo.setValue("mistral-large-latest");
                } else {
                    modelCombo.getItems().setAll("gpt-4o-mini", "gpt-4o", "gpt-4-turbo");
                    modelCombo.setValue("gpt-4o-mini");
                }
            }
        });

        providerBox.getChildren().addAll(providerLabel, providerCombo);

        // 4. Docs Hint
        VBox hintBox = new VBox(4);
        hintBox.setAlignment(Pos.CENTER_LEFT);
        Label hintLbl = new Label("*Please use the exact Provider and Model Name from the Docs");
        hintLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b; -fx-font-style: italic;");
        hintBox.getChildren().add(hintLbl);

        grid.add(keyBox, 0, 0);
        grid.add(modelBox, 1, 0);
        grid.add(providerBox, 0, 1);
        grid.add(hintBox, 1, 1);

        card.getChildren().add(grid);
        return card;
    }

    private VBox buildActionBox() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);

        proceedBtn.getStyleClass().add("primary-button");
        proceedBtn.setMaxWidth(Double.MAX_VALUE);
        proceedBtn.setPrefHeight(44);
        proceedBtn.setOnAction(e -> handleProceed());

        HBox termsRow = new HBox(8);
        termsRow.setAlignment(Pos.CENTER);

        termsCheckBox.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;");
        termsCheckBox.setSelected(true);

        Hyperlink termsLink = new Hyperlink("Read here");
        termsLink.setStyle("-fx-text-fill: #38bdf8; -fx-font-size: 11.5px;");
        termsLink.setOnAction(e -> showTermsDialog());

        termsRow.getChildren().addAll(termsCheckBox, termsLink);

        loadingIndicator.setMaxSize(18, 18);
        loadingIndicator.setVisible(false);

        statusLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #f87171;");
        statusLabel.setWrapText(true);

        HBox statusRow = new HBox(8, loadingIndicator, statusLabel);
        statusRow.setAlignment(Pos.CENTER);

        box.getChildren().addAll(proceedBtn, termsRow, statusRow);
        return box;
    }

    private VBox buildHistorySection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("dark-panel");
        section.setPadding(new Insets(14));

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("🕒");
        icon.setStyle("-fx-font-size: 14px;");
        Label title = new Label("Recent Sherlock Cases (in project data/ folder)");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refreshBtn = new Button("↻ Refresh List");
        refreshBtn.getStyleClass().add("secondary-button");
        refreshBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3px 8px;");
        refreshBtn.setOnAction(e -> loadRecentCases());

        header.getChildren().addAll(icon, title, sp, refreshBtn);

        historyContainer.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(historyContainer);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(120);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        section.getChildren().addAll(header, scroll);
        return section;
    }

    private void setupDragAndDrop(VBox dropzone) {
        dropzone.setOnDragOver(event -> {
            if (event.getGestureSource() != dropzone && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        dropzone.setOnDragDropped((DragEvent event) -> {
            var db = event.getDragboard();
            if (db.hasFiles()) {
                addFiles(db.getFiles());
                event.setDropCompleted(true);
            } else {
                event.setDropCompleted(false);
            }
            event.consume();
        });
    }

    private void handleBrowseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Case Evidence Files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Evidence Files (*.txt, *.pdf, *.png)", "*.txt", "*.pdf", "*.png", "*.jpg", "*.doc", "*.docx", "*.json", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        List<File> files = chooser.showOpenMultipleDialog(getScene() != null ? getScene().getWindow() : null);
        if (files != null && !files.isEmpty()) {
            addFiles(files);
        }
    }

    private void addFiles(List<File> files) {
        for (File f : files) {
            if (!selectedFiles.contains(f)) {
                selectedFiles.add(f);
            }
        }
        updateFileBadges();
    }

    private void updateFileBadges() {
        fileBadgesPane.getChildren().clear();
        for (File file : selectedFiles) {
            HBox badge = new HBox(4);
            badge.setAlignment(Pos.CENTER_LEFT);
            badge.setStyle("-fx-background-color: #1e293b; -fx-padding: 3px 8px; -fx-background-radius: 6px; -fx-border-color: #334155; -fx-border-radius: 6px;");

            Label name = new Label(file.getName());
            name.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #e2e8f0;");

            Button removeBtn = new Button("✕");
            removeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 9px; -fx-padding: 0 2px; -fx-cursor: hand;");
            removeBtn.setOnAction(e -> {
                selectedFiles.remove(file);
                updateFileBadges();
            });

            badge.getChildren().addAll(name, removeBtn);
            fileBadgesPane.getChildren().add(badge);
        }
    }

    private void handleProceed() {
        String caseName = caseNameField.getText() != null ? caseNameField.getText().trim() : "";
        if (caseName.isBlank()) {
            statusLabel.setText("Please specify a case name or ID.");
            return;
        }

        if (!termsCheckBox.isSelected()) {
            statusLabel.setText("Please accept the terms and conditions before proceeding.");
            return;
        }

        statusLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #38bdf8;");
        statusLabel.setText("Locking in with SHERLOCK...");
        proceedBtn.setDisable(true);
        loadingIndicator.setVisible(true);

        LlmConfigDto llmConfig = new LlmConfigDto();
        llmConfig.setProvider(providerCombo.getValue() != null ? providerCombo.getValue().toLowerCase() : "openai");
        llmConfig.setModel(modelCombo.getValue() != null ? modelCombo.getValue() : "gpt-4o-mini");
        llmConfig.setApiKey(apiKeyField.getText() != null ? apiKeyField.getText().trim() : "");

        client.createCaseAsync(caseName, caseName, llmConfig)
                .thenCompose(createdCase -> {
                    if (!selectedFiles.isEmpty()) {
                        Platform.runLater(() -> statusLabel.setText("Uploading " + selectedFiles.size() + " files to data/" + createdCase.getCaseId() + "..."));
                        return client.uploadFilesAsync(createdCase.getCaseId(), selectedFiles)
                                .thenCompose(uploaded -> {
                                    Platform.runLater(() -> statusLabel.setText("Triggering Python extraction pipeline..."));
                                    return client.startProcessingAsync(uploaded.getCaseId()).thenApply(p -> uploaded);
                                });
                    }
                    return client.createCaseAsync(caseName, caseName, llmConfig);
                })
                .thenAccept(finalCase -> Platform.runLater(() -> {
                    proceedBtn.setDisable(false);
                    loadingIndicator.setVisible(false);
                    statusLabel.setText("");
                    loadRecentCases();
                    if (onCaseLockedIn != null) {
                        onCaseLockedIn.accept(finalCase);
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        proceedBtn.setDisable(false);
                        loadingIndicator.setVisible(false);
                        statusLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #f87171;");
                        statusLabel.setText("Error: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void showTermsDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Sherlock Terms & Conditions");
        alert.setHeaderText("Evidence Handling & Privacy Protocol");
        alert.setContentText("Sherlock processes evidence locally on your workstation. Document metadata, entities, and relationships are stored in your local data directory. If an external LLM provider is configured, document chunks are transmitted securely to your chosen provider for inference according to their respective API terms.");
        alert.showAndWait();
    }
}
