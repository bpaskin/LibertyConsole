package com.ibm.liberty.admin.service;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.json.Json;
import com.ibm.liberty.admin.model.UserSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Service for managing user settings persistence
 */
public class SettingsService {

    private static final Logger LOGGER = Logger.getLogger(SettingsService.class.getName());
    static final String SETTINGS_FILENAME = "admin-console-settings.json";

    private final Supplier<ConsoleRuntime> runtime;

    public SettingsService(Supplier<ConsoleRuntime> runtime) {
        this.runtime = runtime;
    }

    /**
     * Get the settings file path (${server.config.dir}/admin-console-settings.json)
     */
    private Path getSettingsFilePath() {
        return runtime.get().getServerConfigDir().resolve(SETTINGS_FILENAME);
    }

    /**
     * Load settings from file
     */
    public UserSettings loadSettings() {
        Path settingsPath = getSettingsFilePath();

        try {
            if (Files.exists(settingsPath)) {
                String json = Files.readString(settingsPath, StandardCharsets.UTF_8);
                UserSettings settings = Json.read(json, UserSettings.class);
                LOGGER.fine("Loaded settings from: " + settingsPath);
                return settings;
            }
            LOGGER.fine("Settings file not found, using defaults: " + settingsPath);
        } catch (Exception e) {
            LOGGER.warning("Error loading settings: " + e.getMessage());
        }

        // Return default settings if file doesn't exist or error occurs
        return new UserSettings();
    }

    /**
     * Save settings to file
     */
    public void saveSettings(UserSettings settings) {
        Path settingsPath = getSettingsFilePath();

        try {
            Files.createDirectories(settingsPath.getParent());
            Files.writeString(settingsPath, Json.write(settings), StandardCharsets.UTF_8);
            LOGGER.fine("Saved settings to: " + settingsPath);
        } catch (IOException e) {
            LOGGER.severe("Error saving settings: " + e.getMessage());
            throw new IllegalStateException("Failed to save settings", e);
        }
    }
}
