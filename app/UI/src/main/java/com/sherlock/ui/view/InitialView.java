package com.sherlock.ui.view;

import com.sherlock.ui.SherlockBackendClient;
import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.model.LlmConfigDto;
import com.sherlock.ui.util.LlmModelHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

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
    private final Button proceedBtn = new Button("Create investigation workspace");
    private final Label statusLabel = new Label();
    private final ProgressIndicator loadingIndicator = new ProgressIndicator();

    private final VBox historyContainer = new VBox(6);

    // Background video
    private MediaView backgroundVideoView;

    public InitialView(SherlockBackendClient client, Consumer<CaseDto> onCaseLockedIn) {
        this.client = client;
        this.onCaseLockedIn = onCaseLockedIn;

        getStyleClass().add("root");

        // Set up the video background
        setupVideoBackground();

        VBox mainLayout = buildMainLayout();

        StackPane rootStack = new StackPane();
        if (backgroundVideoView != null) {
            rootStack.getChildren().add(backgroundVideoView);
        } else {
            Region fallbackBg = new Region();
            fallbackBg.setStyle("-fx-background-color: #f8fafc;");
            rootStack.getChildren().add(fallbackBg);
        }
        ScrollPane mainScroll = new ScrollPane(mainLayout);
        mainScroll.setFitToWidth(true);
        mainScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        rootStack.getChildren().add(mainScroll);

        setCenter(rootStack);

        loadRecentCases();
    }

    private void setupVideoBackground() {
        try {
            URL resourceUrl = getClass().getResource("/BG_video.mp4");
            if (resourceUrl != null) {
                Media media = new Media(resourceUrl.toExternalForm());
                MediaPlayer mediaPlayer = new MediaPlayer(media);
                mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                mediaPlayer.setMute(true);

                backgroundVideoView = new MediaView(mediaPlayer);
                backgroundVideoView.setPreserveRatio(false); // Stretch to fill
                backgroundVideoView.fitWidthProperty().bind(this.widthProperty());
                backgroundVideoView.fitHeightProperty().bind(this.heightProperty());

                mediaPlayer.play();
            } else {
                System.err.println("Could not find BG_video.mp4 in resources.");
            }
        } catch (Exception e) {
            System.err.println("Failed to load background video: " + e.getMessage());
        }
    }

    public void loadRecentCases() {
        client.listCasesAsync().thenAccept(cases -> Platform.runLater(() -> {
            historyContainer.getChildren().clear();
            if (cases.isEmpty()) {
                Label empty = new Label("No cases found in project data directory.");
                empty.setStyle("-fx-font-size: 13.2px; -fx-text-fill: #64748b; -fx-font-style: italic;");
                historyContainer.getChildren().add(empty);
            } else {
                for (CaseDto c : cases) {
                    HBox row = new HBox(8);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setStyle(
                            "-fx-background-color: rgba(255, 255, 255, 0.8); -fx-padding: 8px 12px; -fx-background-radius: 8px; -fx-border-color: #e2e8f0; -fx-border-radius: 8px;");

                    VBox info = new VBox(4);
                    Label nameLbl = new Label(c.getCaseName() + " (" + c.getCaseId() + ")");
                    nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #0ea5e9; -fx-font-size: 18px;");

                    Label detailLbl = new Label(c.getFileCount() + " files • " + c.getStatus());
                    detailLbl.setStyle("-fx-font-size: 14.4px; -fx-text-fill: #64748b;");
                    info.getChildren().addAll(nameLbl, detailLbl);

                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);

                    Button openBtn = new Button("Open ➔");
                    openBtn.getStyleClass().add("secondary-button");
                    openBtn.setStyle("-fx-font-size: 12.6px; -fx-padding: 4px 10px;");
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
                            if (n > maxNum)
                                maxNum = n;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        String nextId = String.format("CASE-%03d", maxNum + 1);
        if (caseNameField.getText() == null || caseNameField.getText().isBlank()
                || caseNameField.getText().matches("(?i)CASE-\\d+")) {
            caseNameField.setText(nextId);
        }
    }

    private VBox buildMainLayout() {
        VBox layout = new VBox(16);
        layout.setAlignment(Pos.TOP_LEFT);
        layout.setPadding(new Insets(30, 40, 30, 40));

        // 1. Header Bar
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label logo = new Label("✨");
        logo.setStyle("-fx-font-size: 43.2px;");

        VBox titleBox = new VBox(0);
        Label title = new Label("Sherlock");
        title.setStyle("-fx-font-size: 38.4px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Label subTitle = new Label("EVIDENCE INTELLIGENCE WORKSPACE");
        subTitle.setStyle(
                "-fx-font-size: 15.6px; -fx-font-weight: 700; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        titleBox.getChildren().addAll(title, subTitle);

        header.getChildren().addAll(logo, titleBox);

        // 2. Top Grid (Welcome Card + Case Card)
        GridPane topGrid = new GridPane();
        topGrid.setHgap(16);
        topGrid.setVgap(16);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        topGrid.getColumnConstraints().addAll(col1, col2);

        VBox welcomeCard = buildWelcomeCard();
        VBox caseCard = buildCaseCard();

        topGrid.add(welcomeCard, 0, 0);
        topGrid.add(caseCard, 1, 0);
        VBox.setVgrow(topGrid, Priority.ALWAYS);

        // 3. Settings Card (LLM Configuration)
        VBox settingsCard = buildSettingsCard();

        // 4. Action Row (Lock-in Button + Terms)
        VBox actionBox = buildActionBox();

        // 5. Recent Cases / History Section
        VBox historyCard = buildHistorySection();
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        layout.getChildren().addAll(header, topGrid, settingsCard, actionBox, historyCard);
        return layout;
    }

    private VBox buildWelcomeCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("glass-panel");
        card.setPadding(new Insets(20));
        card.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(card, Priority.ALWAYS);

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("👋");
        icon.setStyle("-fx-font-size: 28.8px;");
        Label title = new Label("Turn evidence into answers.");
        title.setStyle("-fx-font-size: 26.4px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        titleRow.getChildren().addAll(icon, title);

        Label subTitle = new Label("A defensible investigation workspace, grounded in source evidence.");
        subTitle.setStyle("-fx-font-size: 16.8px; -fx-font-weight: 600; -fx-text-fill: #64748b;");

        VBox bulletPoints = new VBox(16);
        bulletPoints.setPadding(new Insets(16, 0, 0, 0));

        Label description = new Label(
                "Bring together documents, reports, and logs. Sherlock maps the connections, reconstructs the sequence, and keeps every finding tied to its original evidence.");
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 18px; -fx-text-fill: #334155; -fx-line-spacing: 6px;");

        Label howItWorksTitle = new Label("Your investigation, in three deliberate steps");
        howItWorksTitle.setStyle("-fx-font-size: 16.8px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");
        VBox.setMargin(howItWorksTitle, new Insets(10, 0, 0, 0));

        bulletPoints.getChildren().addAll(
                description,
                howItWorksTitle,
                createBullet("01", "Name the case and add the evidence you want to investigate."),
                createBullet("02", "Choose the analysis model that fits your environment."),
                createBullet("03", "Explore entities, relationships, timelines, and cited findings in one place."));

        card.getChildren().addAll(titleRow, subTitle, bulletPoints);
        return card;
    }

    private HBox createBullet(String emoji, String text) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.TOP_LEFT);
        Label icon = new Label(emoji);
        icon.setStyle("-fx-font-size: 16.8px;");
        Label lbl = new Label(text);
        lbl.setWrapText(true);
        lbl.setStyle("-fx-font-size: 16.8px; -fx-text-fill: #475569; -fx-line-spacing: 4px;");
        row.getChildren().addAll(icon, lbl);
        return row;
    }

    private VBox buildCaseCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("glass-panel");
        card.setPadding(new Insets(20));
        card.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(card, Priority.ALWAYS);

        HBox headerRow = new HBox(8);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("📁");
        icon.setStyle("-fx-font-size: 24px;");

        Label title = new Label("Start a new investigation");
        title.setStyle("-fx-font-size: 21.6px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");
        headerRow.getChildren().addAll(icon, title);

        caseNameField.setPromptText("e.g. CASE-001 or Project Aurora");
        caseNameField.getStyleClass().add("text-field");
        caseNameField.setStyle("-fx-font-weight: bold; -fx-text-fill: #2563eb;");

        // Upload Dropzone
        VBox dropzone = new VBox(6);
        dropzone.getStyleClass().add("dropzone");
        dropzone.setAlignment(Pos.CENTER);
        VBox.setVgrow(dropzone, Priority.ALWAYS);

        Label dropIcon = new Label("☁️");
        dropIcon.setStyle("-fx-font-size: 33.6px;");

        Label dropTitle = new Label("Drop evidence here, or choose files");
        dropTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Label formats = new Label("Reports, statements, logs, text, PDFs, and images");
        formats.setStyle("-fx-font-size: 14.4px; -fx-text-fill: #64748b;");

        Button browseBtn = new Button("Browse Files");
        browseBtn.getStyleClass().add("primary-button");
        browseBtn.setStyle("-fx-font-size: 13.2px; -fx-padding: 6px 14px;");
        browseBtn.setOnAction(e -> handleBrowseFiles());

        dropzone.getChildren().addAll(dropIcon, dropTitle, formats, browseBtn);
        setupDragAndDrop(dropzone);

        ScrollPane badgeScroll = new ScrollPane(fileBadgesPane);
        badgeScroll.setFitToWidth(true);
        badgeScroll.setPrefHeight(45);
        badgeScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        card.getChildren().addAll(headerRow, caseNameField, dropzone, badgeScroll);
        return card;
    }

    private VBox buildSettingsCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("glass-panel");
        card.setPadding(new Insets(20));

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("🔌");
        icon.setStyle("-fx-font-size: 24px;");
        Label title = new Label("Analysis model");
        title.setStyle("-fx-font-size: 21.6px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");
        titleRow.getChildren().addAll(icon, title);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        // 1. API Key Input with Eye Toggle
        VBox keyBox = new VBox(8);
        Label keyLabel = new Label("API key (not needed for local Ollama)");
        keyLabel.setStyle("-fx-font-size: 15.6px; -fx-font-weight: 600; -fx-text-fill: #475569;");

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

        HBox keyInputRow = new HBox(8);
        keyInputRow.setAlignment(Pos.CENTER_LEFT);
        StackPane keyStack = new StackPane(apiKeyField, apiKeyVisibleField);
        HBox.setHgrow(keyStack, Priority.ALWAYS);
        keyInputRow.getChildren().addAll(keyStack, toggleKeyVisibilityBtn);

        keyBox.getChildren().addAll(keyLabel, keyInputRow);

        // 2. Model Selection
        VBox modelBox = new VBox(8);
        Label modelLabel = new Label("Model Name");
        modelLabel.setStyle("-fx-font-size: 15.6px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        modelCombo.setEditable(true);
        modelCombo.setPromptText("Select or enter model...");
        if (modelCombo.getEditor() != null) {
            modelCombo.getEditor().setPromptText("Select or enter model...");
        }
        modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider("OpenAI"));
        modelCombo.setValue(LlmModelHelper.getDefaultModel("OpenAI"));
        modelCombo.setMaxWidth(Double.MAX_VALUE);
        LlmModelHelper.setupCustomModelListener(modelCombo);

        HBox modelInputRow = new HBox(8);
        modelInputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelCombo, Priority.ALWAYS);

        Button addCustomModelBtn = new Button("+ Custom");
        addCustomModelBtn.getStyleClass().add("secondary-button");
        addCustomModelBtn.setStyle("-fx-font-size: 12px; -fx-padding: 6px 10px; -fx-font-weight: 600;");
        addCustomModelBtn.setTooltip(new Tooltip("Add or specify a custom model name"));
        addCustomModelBtn.setOnAction(e -> LlmModelHelper.promptCustomModel(modelCombo));

        modelInputRow.getChildren().addAll(modelCombo, addCustomModelBtn);
        modelBox.getChildren().addAll(modelLabel, modelInputRow);

        // 3. Provider Selection
        VBox providerBox = new VBox(8);
        Label providerLabel = new Label("Model provider");
        providerLabel.setStyle("-fx-font-size: 15.6px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        providerCombo.getItems().addAll("OpenAI", "OpenRouter", "DeepSeek", "Groq", "Together", "Mistral", "Ollama",
                "Custom");
        providerCombo.setValue("OpenAI");
        providerCombo.setMaxWidth(Double.MAX_VALUE);

        providerCombo.setOnAction(e -> {
            String p = providerCombo.getValue();
            if ("Ollama".equalsIgnoreCase(p)) {
                apiKeyField.setDisable(true);
                apiKeyField.setText("");
                apiKeyField.setPromptText("Local Ollama (No API Key Required)");
                keyLabel.setText("API Key (Zero-key Local Ollama Mode)");
                modelLabel.setText("Ollama Local Model");

                client.getOllamaModelsAsync().thenAccept(models -> Platform.runLater(() -> {
                    if (models != null && !models.isEmpty()) {
                        List<String> list = new ArrayList<>(models);
                        if (!list.contains(LlmModelHelper.CUSTOM_MODEL_OPTION)) {
                            list.add(LlmModelHelper.CUSTOM_MODEL_OPTION);
                        }
                        modelCombo.getItems().setAll(list);
                        modelCombo.setValue(models.get(0));
                        modelCombo.setDisable(false);
                    } else {
                        modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider("Ollama"));
                        modelCombo.setValue(LlmModelHelper.getDefaultModel("Ollama"));
                        modelCombo.setDisable(false);
                    }
                })).exceptionally(ex -> {
                    Platform.runLater(() -> {
                        modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider("Ollama"));
                        modelCombo.setValue(LlmModelHelper.getDefaultModel("Ollama"));
                        modelCombo.setDisable(false);
                    });
                    return null;
                });
            } else {
                apiKeyField.setDisable(false);
                apiKeyField.setPromptText("sk-... (required for cloud)");
                keyLabel.setText("Provide API key");
                modelLabel.setText("Model Name");
                modelCombo.setDisable(false);

                modelCombo.getItems().setAll(LlmModelHelper.getModelsForProvider(p));
                modelCombo.setValue(LlmModelHelper.getDefaultModel(p));
            }
        });

        providerBox.getChildren().addAll(providerLabel, providerCombo);

        // 4. Docs Hint
        VBox hintBox = new VBox(4);
        hintBox.setAlignment(Pos.CENTER_LEFT);
        Label hintLbl = new Label("Your configuration is stored with this case and can be changed later.");
        hintLbl.setStyle("-fx-font-size: 14.4px; -fx-text-fill: #94a3b8; -fx-font-style: italic;");
        hintBox.getChildren().add(hintLbl);

        grid.add(keyBox, 0, 0);
        grid.add(modelBox, 1, 0);
        grid.add(providerBox, 0, 1);
        grid.add(hintBox, 1, 1);

        card.getChildren().addAll(titleRow, grid);
        return card;
    }

    private VBox buildActionBox() {
        VBox box = new VBox(16);
        box.setAlignment(Pos.CENTER);

        proceedBtn.getStyleClass().add("primary-button");
        proceedBtn.setMaxWidth(Double.MAX_VALUE);
        proceedBtn.setPrefHeight(60);
        proceedBtn.setStyle("-fx-font-size: 21.6px;");
        proceedBtn.setOnAction(e -> handleProceed());

        HBox termsRow = new HBox(8);
        termsRow.setAlignment(Pos.CENTER);

        termsCheckBox.setStyle("-fx-text-fill: #475569; -fx-font-size: 16.8px; -fx-font-weight: 600;");
        termsCheckBox.setSelected(true);

        Hyperlink termsLink = new Hyperlink("Read here");
        termsLink.setStyle("-fx-text-fill: #2563eb; -fx-font-size: 16.8px; -fx-font-weight: 600;");
        termsLink.setOnAction(e -> showTermsDialog());

        termsRow.getChildren().addAll(termsCheckBox, termsLink);

        loadingIndicator.setMaxSize(20, 20);
        loadingIndicator.setVisible(false);

        statusLabel.setStyle("-fx-font-size: 13.8px; -fx-text-fill: #e11d48; -fx-font-weight: 600;");
        statusLabel.setWrapText(true);

        HBox statusRow = new HBox(8, loadingIndicator, statusLabel);
        statusRow.setAlignment(Pos.CENTER);

        box.getChildren().addAll(proceedBtn, termsRow, statusRow);
        return box;
    }

    private VBox buildHistorySection() {
        VBox section = new VBox(10);
        section.getStyleClass().add("glass-panel-subtle");
        section.setPadding(new Insets(16));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("🕒");
        icon.setStyle("-fx-font-size: 21.6px;");
        Label title = new Label("Recent Sherlock Cases (in project data/ folder)");
        title.setStyle("-fx-font-size: 19.2px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refreshBtn = new Button("↻ Refresh List");
        refreshBtn.getStyleClass().add("secondary-button");
        refreshBtn.setStyle("-fx-font-size: 13.2px;");
        refreshBtn.setOnAction(e -> loadRecentCases());

        header.getChildren().addAll(icon, title, sp, refreshBtn);

        historyContainer.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(historyContainer);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(Double.MAX_VALUE);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

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
                new FileChooser.ExtensionFilter("Evidence Files", "*.txt", "*.pdf", "*.png", "*.jpg", "*.doc", "*.docx",
                        "*.json", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
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
            HBox badge = new HBox(6);
            badge.setAlignment(Pos.CENTER_LEFT);
            badge.setStyle(
                    "-fx-background-color: #ffffff; -fx-padding: 4px 10px; -fx-background-radius: 8px; -fx-border-color: #e2e8f0; -fx-border-radius: 8px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.02), 4, 0, 0, 2);");

            Label name = new Label(file.getName());
            name.setStyle("-fx-font-size: 13.2px; -fx-text-fill: #475569; -fx-font-weight: 600;");

            Button removeBtn = new Button("✕");
            removeBtn.setStyle(
                    "-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-padding: 0 4px; -fx-cursor: hand;");
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

        statusLabel.setStyle("-fx-font-size: 13.8px; -fx-text-fill: #0ea5e9; -fx-font-weight: 600;");
        statusLabel.setText("Locking in with SHERLOCK...");
        proceedBtn.setDisable(true);
        loadingIndicator.setVisible(true);

        LlmConfigDto llmConfig = new LlmConfigDto();
        llmConfig.setProvider(providerCombo.getValue() != null ? providerCombo.getValue().toLowerCase() : "openai");
        llmConfig.setModel(LlmModelHelper.getSelectedModel(modelCombo));
        llmConfig.setApiKey(apiKeyField.getText() != null ? apiKeyField.getText().trim() : "");

        client.createCaseAsync(caseName, caseName, llmConfig)
                .thenCompose(createdCase -> {
                    if (!selectedFiles.isEmpty()) {
                        Platform.runLater(() -> statusLabel.setText("Uploading " + selectedFiles.size()
                                + " files to data/" + createdCase.getCaseId() + "..."));
                        return client.uploadFilesAsync(createdCase.getCaseId(), selectedFiles)
                                .thenCompose(uploaded -> {
                                    Platform.runLater(
                                            () -> statusLabel.setText("Triggering Python extraction pipeline..."));
                                    return client.startProcessingAsync(uploaded.getCaseId()).thenApply(p -> uploaded);
                                });
                    }
                    return CompletableFuture.completedFuture(createdCase);
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
                        statusLabel.setStyle("-fx-font-size: 13.8px; -fx-text-fill: #e11d48; -fx-font-weight: 600;");
                        statusLabel.setText("Error: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void showTermsDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Sherlock Terms & Conditions");
        alert.setHeaderText("Evidence Handling & Privacy Protocol");
        alert.setContentText(
                "Sherlock processes evidence locally on your workstation. Document metadata, entities, and relationships are stored in your local data directory. If an external LLM provider is configured, document chunks are transmitted securely to your chosen provider for inference according to their respective API terms.");
        alert.showAndWait();
    }
}
