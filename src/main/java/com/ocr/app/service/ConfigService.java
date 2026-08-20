package com.ocr.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ConfigService {

    @Value("${app.config.file:config.json}")
    private String configFilePath;

    public String getConfigFilePath() {
        return configFilePath;
    }

    private static final String KEY_API_URL = "lmstudio.api.url";
    private static final String KEY_MODEL_NAME = "lmstudio.model.name";
    private static final String KEY_OCR_PROMPT = "lmstudio.ocr.prompt";
    private static final String KEY_MAX_TOKENS = "lmstudio.max.tokens";

    private static final String DEFAULT_API_URL = "http://192.168.100.54:1234/v1/chat/completions";
    private static final String DEFAULT_MODEL_NAME = "minicpm-v";
    private static final String DEFAULT_OCR_PROMPT = "提取图中所有文字";
    private static final String DEFAULT_MAX_TOKENS = "100000";

    private final Map<String, String> configCache = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        loadAllConfigs();
    }

    public void loadAllConfigs() {
        configCache.clear();

        File configFile = new File(configFilePath);
        if (configFile.exists()) {
            try {
                Map<String, String> loadedConfig = objectMapper.readValue(configFile, Map.class);
                configCache.putAll(loadedConfig);
                log.info("Configuration loaded from file: {}", configFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("Failed to load config file, using defaults", e);
            }
        }

        if (!configCache.containsKey(KEY_API_URL)) {
            configCache.put(KEY_API_URL, DEFAULT_API_URL);
        }
        if (!configCache.containsKey(KEY_MODEL_NAME)) {
            configCache.put(KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
        }
        if (!configCache.containsKey(KEY_OCR_PROMPT)) {
            configCache.put(KEY_OCR_PROMPT, DEFAULT_OCR_PROMPT);
        }
        if (!configCache.containsKey(KEY_MAX_TOKENS)) {
            configCache.put(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS);
        }

        saveToFile();

        log.info("Configuration loaded. Total keys: {}", configCache.size());
    }

    private void saveToFile() {
        try {
            File configFile = new File(configFilePath);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, configCache);
            log.info("Configuration saved to file: {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save config file", e);
        }
    }

    public String getApiUrl() {
        return configCache.getOrDefault(KEY_API_URL, DEFAULT_API_URL);
    }

    public String getModelName() {
        return configCache.getOrDefault(KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
    }

    public String getOcrPrompt() {
        return configCache.getOrDefault(KEY_OCR_PROMPT, DEFAULT_OCR_PROMPT);
    }

    public int getMaxTokens() {
        try {
            return Integer.parseInt(configCache.getOrDefault(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS));
        } catch (NumberFormatException e) {
            return 100000;
        }
    }

    public String getConfig(String key) {
        return configCache.get(key);
    }

    public void setConfig(String key, String value) {
        configCache.put(key, value);
        saveToFile();
    }
}
