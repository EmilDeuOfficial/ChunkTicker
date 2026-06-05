package de.emilo.chunkticker;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public final class Lang {

    private static final Map<String, String> MESSAGES = new HashMap<>();

    private Lang() {}

    public static void load(ChunkTicker plugin) {
        MESSAGES.clear();

        String lang     = plugin.getConfig().getString("language", "en").toLowerCase();
        String resource = "lang/messages_" + lang + ".yml";

        // If unknown language, fall back to English
        if (plugin.getResource(resource) == null) {
            plugin.getLogger().warning("Language '" + lang + "' not found — falling back to 'en'.");
            lang     = "en";
            resource = "lang/messages_en.yml";
        }

        // Extract to data folder so admins can customise it
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }

        // Load requested language
        YamlConfiguration config = loadYaml(plugin, file);

        // English as fallback for any missing keys
        try (InputStream in = plugin.getResource("lang/messages_en.yml")) {
            if (in != null) {
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load fallback language file", e);
        }

        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                String value = config.getString(key);
                if (value != null) MESSAGES.put(key, value);
            }
        }

        plugin.getLogger().info("Language loaded: " + lang
                + " (" + MESSAGES.size() + " keys)");
    }

    /**
     * Returns the translated message for {@code key}.
     * Replacements are applied as interleaved key-value pairs:
     * {@code Lang.get("set.success", "added", "3", "skipped", "")}
     */
    public static String get(String key, String... pairs) {
        String msg = MESSAGES.getOrDefault(key, "[" + key + "]");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            msg = msg.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return msg;
    }

    // -------------------------------------------------------

    private static YamlConfiguration loadYaml(ChunkTicker plugin, File file) {
        YamlConfiguration config = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            config.load(reader);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load language file: " + file.getName(), e);
        }
        return config;
    }
}
