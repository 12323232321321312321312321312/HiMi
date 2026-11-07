package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class VersionScanner {

    public static List<MinecraftVersion> scan(String mcDirPath) {
        List<MinecraftVersion> versions = new ArrayList<>();
        File versionsDir = new File(mcDirPath, "versions");
        
        if (!versionsDir.exists() || !versionsDir.isDirectory()) {
            System.out.println("未找到 versions 目录，请检查路径: " + versionsDir.getAbsolutePath());
            return versions;
        }

        File[] folders = versionsDir.listFiles(File::isDirectory);
        if (folders == null) return versions;

        for (File folder : folders) {
            String folderName = folder.getName();
            File jsonFile = new File(folder, folderName + ".json");

            if (jsonFile.exists()) {
                try (FileReader reader = new FileReader(jsonFile)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    String id = root.has("id") ? root.get("id").getAsString() : folderName;
                    
                    String type = detectLoaderType(root, folderName, id);
                    
                    versions.add(new MinecraftVersion(id, type));
                } catch (Exception e) {
                    System.err.println("解析失败: " + jsonFile.getName());
                }
            }
        }
        return versions;
    }

    /**
     * 🚨 核心修复：智能识别加载器类型
     * 不再单纯依赖文件夹名称，而是深入解析 JSON 的 mainClass 和 libraries
     */
    private static String detectLoaderType(JsonObject root, String folderName, String id) {
        String lowerMain = "";
        if (root.has("mainClass")) {
            lowerMain = root.get("mainClass").getAsString().toLowerCase();
        }

        // 1. 通过 mainClass 判断（最准确）
        if (lowerMain.contains("fabric")) {
            return "Fabric";
        }
        if (lowerMain.contains("quilt")) {
            return "Quilt";
        }
        if (lowerMain.contains("neoforge") || lowerMain.contains("bootstraplauncher")) {
            return "NeoForge";
        }
        
        // Forge 的 mainClass 通常是 cpw.mods.modlauncher.Launcher 或 launchwrapper
        // 但 NeoForge 有时也用这个，所以需要进一步看 libraries 区分
        if (lowerMain.contains("modlauncher") || lowerMain.contains("launchwrapper") || lowerMain.contains("minecraftforge")) {
            // 进入下方 libraries 判断
        }

        // 2. 通过 libraries 判断（查找核心依赖库）
        if (root.has("libraries")) {
            JsonArray libs = root.getAsJsonArray("libraries");
            for (JsonElement el : libs) {
                if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                    String libName = el.getAsJsonObject().get("name").getAsString().toLowerCase();
                    
                    if (libName.contains("net.fabricmc:fabric-loader")) {
                        return "Fabric";
                    }
                    if (libName.contains("org.quiltmc:quilt-loader")) {
                        return "Quilt";
                    }
                    if (libName.contains("net.neoforged:neoforge")) {
                        return "NeoForge";
                    }
                    if (libName.contains("net.minecraftforge:forge") || libName.contains("net.minecraftforge:fmlloader")) {
                        return "Forge";
                    }
                    if (libName.contains("optifine")) {
                        return "OptiFine";
                    }
                }
            }
        }

        // 3. 通过 inheritsFrom 判断
        if (root.has("inheritsFrom")) {
            String parent = root.get("inheritsFrom").getAsString().toLowerCase();
            if (parent.contains("fabric")) return "Fabric";
            if (parent.contains("quilt")) return "Quilt";
            if (parent.contains("neoforge")) return "NeoForge";
            if (parent.contains("forge")) return "Forge";
            if (parent.contains("optifine")) return "OptiFine";
        }

        // 4. 最后通过文件夹名或 ID 包含的字眼判断（兜底）
        String lowerName = folderName.toLowerCase() + " " + id.toLowerCase();
        if (lowerName.contains("fabric")) return "Fabric";
        if (lowerName.contains("quilt")) return "Quilt";
        if (lowerName.contains("neoforge")) return "NeoForge";
        if (lowerName.contains("forge")) return "Forge";
        if (lowerName.contains("optifine")) return "OptiFine";

        // 5. 判断是否为原版
        // 如果没有 inheritsFrom 且 mainClass 就是纯净的原版主类，说明是原版
        if (!root.has("inheritsFrom") && (lowerMain.isEmpty() || lowerMain.equals("net.minecraft.client.main.main"))) {
            return "原版";
        }

        // 如果上述都没匹配上，但又有 inheritsFrom 或不纯净的 mainClass，说明是未知模组端
        return "模组端";
    }
}