package org.example.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {
    public static final String DEFAULT_MC_DIR = System.getProperty("user.dir") + File.separator + ".minecraft";
    public static final String CONFIG_DIR = DEFAULT_MC_DIR + File.separator + "config";

    @SuppressWarnings("unchecked")
    public static void init() {
        File mcDir = new File(DEFAULT_MC_DIR);
        if (!mcDir.exists()) {
            mcDir.mkdirs();
            new File(DEFAULT_MC_DIR, "versions").mkdirs();
            new File(DEFAULT_MC_DIR, "mods").mkdirs();
            new File(DEFAULT_MC_DIR, "saves").mkdirs();
        }

        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        File setupFile = new File(CONFIG_DIR, "Setup.json");
        if (!setupFile.exists()) {
            Map<String, Object> setup = new HashMap<>();
            List<String> dirs = new ArrayList<>();
            dirs.add(DEFAULT_MC_DIR);
            setup.put("playerId", "Steve");
            setup.put("selectedVersion", "未选择");
            setup.put("memory", 4096);
            setup.put("minecraftDirs", dirs);
            saveFullSetup(setup);
        }
    }

    public static void saveVersions(JsonArray versions) {
        File file = new File(CONFIG_DIR, "version.json");
        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(versions, writer);
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static JsonArray loadVersions() {
        File file = new File(CONFIG_DIR, "version.json");
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                return JsonParser.parseReader(reader).getAsJsonArray();
            } catch (IOException e) { e.printStackTrace(); }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadSetup() {
        File file = new File(CONFIG_DIR, "Setup.json");
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Map<String, Object> map = new Gson().fromJson(reader, Map.class);
                if (map == null) return new HashMap<>();
                return map;
            } catch (IOException e) { e.printStackTrace(); }
        }
        return new HashMap<>();
    }

    public static void saveFullSetup(Map<String, Object> setup) {
        File file = new File(CONFIG_DIR, "Setup.json");
        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(setup, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 🚨 新增：只更新某一个键值对，绝不覆盖其他数据
    public static void updateSingleConfig(String key, Object value) {
        Map<String, Object> setup = loadSetup();
        setup.put(key, value);
        saveFullSetup(setup);
    }
}