package com.sherlock.ui.util;

import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextInputDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Utility helper for managing provider models, presets, and custom model inputs.
 */
public class LlmModelHelper {

    public static final String CUSTOM_MODEL_OPTION = "+ Enter Custom Model...";

    private static final List<String> OPENAI_MODELS = Arrays.asList(
            "gpt-4o-mini",
            "gpt-4o",
            "o3-mini",
            "o1",
            "o1-mini",
            "gpt-4-turbo",
            "gpt-4",
            "gpt-3.5-turbo"
    );

    private static final List<String> OPENROUTER_MODELS = Arrays.asList(
            "openai/gpt-4o-mini",
            "openai/gpt-4o",
            "anthropic/claude-3.7-sonnet",
            "anthropic/claude-3.5-sonnet",
            "anthropic/claude-3.5-haiku",
            "anthropic/claude-3-opus",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-chat",
            "deepseek/deepseek-r1",
            "google/gemini-2.0-flash-001",
            "google/gemini-pro-1.5",
            "mistralai/mistral-large"
    );

    private static final List<String> DEEPSEEK_MODELS = Arrays.asList(
            "deepseek-chat",
            "deepseek-coder",
            "deepseek-reasoner"
    );

    private static final List<String> GROQ_MODELS = Arrays.asList(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "llama3-70b-8192",
            "llama3-8b-8192",
            "mixtral-8x7b-32768",
            "gemma2-9b-it",
            "deepseek-r1-distill-llama-70b"
    );

    private static final List<String> TOGETHER_MODELS = Arrays.asList(
            "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
            "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo",
            "meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo",
            "mistralai/Mistral-7B-Instruct-v0.3",
            "mistralai/Mixtral-8x7B-Instruct-v0.1",
            "deepseek-ai/DeepSeek-V3",
            "deepseek-ai/DeepSeek-R1"
    );

    private static final List<String> MISTRAL_MODELS = Arrays.asList(
            "mistral-large-latest",
            "mistral-medium-latest",
            "mistral-small-latest",
            "codestral-latest",
            "open-mixtral-8x7b",
            "open-mistral-7b"
    );

    private static final List<String> OLLAMA_DEFAULT_MODELS = Arrays.asList(
            "llama3.3",
            "llama3.2",
            "llama3.1",
            "llama3",
            "mistral",
            "qwen2.5",
            "deepseek-r1",
            "phi3",
            "nomic-embed-text"
    );

    private static final List<String> CUSTOM_DEFAULT_MODELS = Arrays.asList(
            "gpt-4o-mini",
            "gpt-4o",
            "llama-3.3-70b-versatile",
            "deepseek-chat",
            "mistral-large-latest"
    );

    public static List<String> getModelsForProvider(String provider) {
        List<String> list = new ArrayList<>();
        if (provider == null) {
            list.addAll(OPENAI_MODELS);
        } else {
            String p = provider.trim().toLowerCase();
            if (p.contains("openrouter")) {
                list.addAll(OPENROUTER_MODELS);
            } else if (p.contains("deepseek")) {
                list.addAll(DEEPSEEK_MODELS);
            } else if (p.contains("groq")) {
                list.addAll(GROQ_MODELS);
            } else if (p.contains("together")) {
                list.addAll(TOGETHER_MODELS);
            } else if (p.contains("mistral")) {
                list.addAll(MISTRAL_MODELS);
            } else if (p.contains("ollama")) {
                list.addAll(OLLAMA_DEFAULT_MODELS);
            } else if (p.contains("custom")) {
                list.addAll(CUSTOM_DEFAULT_MODELS);
            } else {
                list.addAll(OPENAI_MODELS);
            }
        }
        list.add(CUSTOM_MODEL_OPTION);
        return list;
    }

    public static String getDefaultModel(String provider) {
        if (provider == null) return "gpt-4o-mini";
        String p = provider.trim().toLowerCase();
        if (p.contains("openrouter")) return "openai/gpt-4o-mini";
        if (p.contains("deepseek")) return "deepseek-chat";
        if (p.contains("groq")) return "llama-3.3-70b-versatile";
        if (p.contains("together")) return "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo";
        if (p.contains("mistral")) return "mistral-large-latest";
        if (p.contains("ollama")) return "llama3.1";
        return "gpt-4o-mini";
    }

    public static String getSelectedModel(ComboBox<String> modelCombo) {
        if (modelCombo == null) return "gpt-4o-mini";
        
        // First check editor text (for user-typed input)
        if (modelCombo.getEditor() != null) {
            String text = modelCombo.getEditor().getText();
            if (text != null && !text.isBlank() && !CUSTOM_MODEL_OPTION.equals(text.trim())) {
                return text.trim();
            }
        }
        
        // Then check selected value
        String val = modelCombo.getValue();
        if (val != null && !val.isBlank() && !CUSTOM_MODEL_OPTION.equals(val.trim())) {
            return val.trim();
        }
        
        return "gpt-4o-mini";
    }

    public static void promptCustomModel(ComboBox<String> modelCombo, String fallbackModel) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Custom Model");
        dialog.setHeaderText("Add / Specify Custom Model");
        dialog.setContentText("Model Identifier:");
        
        dialog.getEditor().setPromptText("e.g. meta-llama/llama-3.3-70b-instruct, ft:gpt-4o-mini:..., etc.");

        Optional<String> result = dialog.showAndWait();
        if (result.isPresent() && !result.get().trim().isBlank()) {
            String customModel = result.get().trim();
            addAndSelectCustomModel(modelCombo, customModel);
        } else if (fallbackModel != null && !fallbackModel.isBlank() && !CUSTOM_MODEL_OPTION.equals(fallbackModel)) {
            modelCombo.setValue(fallbackModel);
            if (modelCombo.getEditor() != null) {
                modelCombo.getEditor().setText(fallbackModel);
            }
        }
    }

    public static void promptCustomModel(ComboBox<String> modelCombo) {
        String current = getSelectedModel(modelCombo);
        promptCustomModel(modelCombo, current);
    }

    public static void addAndSelectCustomModel(ComboBox<String> modelCombo, String customModel) {
        if (customModel == null || customModel.isBlank() || CUSTOM_MODEL_OPTION.equals(customModel)) {
            return;
        }
        String clean = customModel.trim();
        if (!modelCombo.getItems().contains(clean)) {
            // Insert before CUSTOM_MODEL_OPTION if present
            int customOptIdx = modelCombo.getItems().indexOf(CUSTOM_MODEL_OPTION);
            if (customOptIdx >= 0) {
                modelCombo.getItems().add(customOptIdx, clean);
            } else {
                modelCombo.getItems().add(clean);
            }
        }
        modelCombo.setValue(clean);
        if (modelCombo.getEditor() != null) {
            modelCombo.getEditor().setText(clean);
        }
    }

    public static void setupCustomModelListener(ComboBox<String> modelCombo) {
        modelCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (CUSTOM_MODEL_OPTION.equals(newVal)) {
                Platform.runLater(() -> promptCustomModel(modelCombo, oldVal));
            }
        });
    }
}
