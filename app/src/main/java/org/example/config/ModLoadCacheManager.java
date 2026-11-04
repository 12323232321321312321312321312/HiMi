package org.example.config;

import com.google.gson.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ModLoadCacheManager {

    private static final String CACHE_DIR = ConfigManager.CONFIG_DIR + File.separator + "mod_load";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public static void init() {
        File dir = new File(CACHE_DIR);
        if (!dir.exists()) dir.mkdirs();
        updateAll();
    }

    public static void updateAll() {
        new Thread(() -> {
            System.out.println("正在后台更新模组加载器缓存...");
            updateForge();
            updateNeoForge();
            updateFabric();
            updateOptiFine();
            System.out.println("模组加载器缓存更新完毕！");
        }).start();
    }

    private static void updateForge() {
        try {
            String url = "https://bmclapi2.bangbang93.com/forge/promos";
            String json = fetchString(url);
            if (json != null) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                Set<String> supportedVersions = new HashSet<>();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("name")) {
                        String name = obj.get("name").getAsString();
                        if (name.endsWith("-latest") || name.endsWith("-recommended")) {
                            String mcVer = name.substring(0, name.lastIndexOf("-"));
                            if (!mcVer.equals("latest") && !mcVer.equals("recommended")) {
                                supportedVersions.add(mcVer);
                            }
                        }
                    }
                }
                saveToFile("forge.json", supportedVersions);
            }
        } catch (Exception e) { 
            System.err.println("更新 Forge 缓存失败: " + e.getMessage()); 
        }
    }

    private static void updateNeoForge() {
        try {
            String url = "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml";
            String xml = fetchString(url);
            if (xml != null) {
                Set<String> supportedVersions = new HashSet<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<version>(.*?)</version>").matcher(xml);
                while (m.find()) {
                    String ver = m.group(1);
                    String cleanVer = ver.split("-")[0]; 
                    
                    // 🚨 核心修复：去掉死板的正则，改为直接按 '.' 切割
                    String[] parts = cleanVer.split("\\.");
                    if (parts.length >= 2) {
                        try {
                            int major = Integer.parseInt(parts[0]);
                            int minor = Integer.parseInt(parts[1]);
                            
                            String mcVer = "";
                            // 智能判断版本号格式
                            if (major >= 22) {
                                // 比如 26.3.0.99 -> MC 版本就是 26.3
                                mcVer = major + "." + minor;
                            } else if (major >= 20) {
                                // 传统格式：21.1.80 -> 1.21.1
                                mcVer = "1." + major;
                                if (minor != 0) {
                                    mcVer += "." + minor;
                                }
                            }
                            
                            if (!mcVer.isEmpty()) {
                                supportedVersions.add(mcVer);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                // 1.20.1 的 NeoForge artifact 名字是 forge，单独补上
                supportedVersions.add("1.20.1"); 
                saveToFile("neoforge.json", supportedVersions);
            }
        } catch (Exception e) { 
            System.err.println("更新 NeoForge 缓存失败: " + e.getMessage()); 
        }
    }

    private static void updateFabric() {
        try {
            String url = "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/game";
            String json = fetchString(url);
            if (json != null) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                Set<String> supportedVersions = new HashSet<>();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.get("stable").getAsBoolean()) {
                        supportedVersions.add(obj.get("version").getAsString());
                    }
                }
                saveToFile("fabric.json", supportedVersions);
            }
        } catch (Exception e) { 
            System.err.println("更新 Fabric 缓存失败: " + e.getMessage()); 
        }
    }

    private static void updateOptiFine() {
        try {
            String url = "https://bmclapi2.bangbang93.com/optifine/versionList";
            String json = fetchString(url);
            if (json != null) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                Set<String> supportedVersions = new HashSet<>();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("mcversion")) {
                        supportedVersions.add(obj.get("mcversion").getAsString());
                    }
                }
                saveToFile("optifine.json", supportedVersions);
            }
        } catch (Exception e) { 
            System.err.println("更新 OptiFine 缓存失败: " + e.getMessage()); 
        }
    }

    private static void saveToFile(String fileName, Set<String> versions) {
        File file = new File(CACHE_DIR, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(versions, writer);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @SuppressWarnings("unchecked")
    public static Set<String> readFromFile(String fileName) {
        File file = new File(CACHE_DIR, fileName);
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                return new Gson().fromJson(reader, Set.class);
            } catch (Exception ignored) {}
        }
        return new HashSet<>();
    }

    private static String fetchString(String url) {
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}