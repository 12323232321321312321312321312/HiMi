package org.example;

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
                    
                    String type = "原版";
                    if (root.has("inheritsFrom")) {
                        if (folderName.toLowerCase().contains("fabric")) {
                            type = "Fabric";
                        } else if (folderName.toLowerCase().contains("forge")) {
                            type = "Forge";
                        } else {
                            type = "模组端";
                        }
                    }
                    
                    versions.add(new MinecraftVersion(id, type));
                } catch (Exception e) {
                    System.err.println("解析失败: " + jsonFile.getName());
                }
            }
        }
        return versions;
    }
}