package org.example.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ModVersion {
    public String id;
    public String name;
    public String versionNumber;
    public String versionType; // release/beta/alpha
    public long downloads;
    public List<String> gameVersions = new ArrayList<>();
    public List<String> loaders = new ArrayList<>();
    public Date datePublished;
    public List<ModFile> files = new ArrayList<>();
    public List<ModDependency> dependencies = new ArrayList<>();

    // Modrinth 解析
    public static ModVersion fromModrinthJson(JsonObject obj) {
        ModVersion v = new ModVersion();
        v.id = str(obj, "id");
        v.name = str(obj, "name");
        v.versionNumber = str(obj, "version_number");
        v.versionType = str(obj, "version_type");
        v.downloads = obj.has("downloads") ? obj.get("downloads").getAsLong() : 0;
        
        try { 
            v.datePublished = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(str(obj, "date_published")); 
        } catch (Exception ignored) {}
        
        if (obj.has("game_versions")) {
            for (JsonElement e : obj.getAsJsonArray("game_versions")) v.gameVersions.add(e.getAsString());
        }
        if (obj.has("loaders")) {
            for (JsonElement e : obj.getAsJsonArray("loaders")) v.loaders.add(e.getAsString());
        }
        if (obj.has("files")) {
            for (JsonElement e : obj.getAsJsonArray("files")) v.files.add(ModFile.fromModrinth(e.getAsJsonObject()));
        }
        
        if (obj.has("dependencies")) {
            for (JsonElement e : obj.getAsJsonArray("dependencies")) {
                JsonObject d = e.getAsJsonObject();
                ModDependency dep = new ModDependency();
                dep.projectId = str(d, "project_id");
                dep.versionId = str(d, "version_id");
                dep.dependencyType = str(d, "dependency_type");
                v.dependencies.add(dep);
            }
        }
        return v;
    }

    // CurseForge 解析
    public static ModVersion fromCurseForgeJson(JsonObject obj) {
        ModVersion v = new ModVersion();
        v.id = str(obj, "id");
        v.name = str(obj, "displayName");
        v.versionNumber = str(obj, "fileName");
        v.downloads = obj.has("downloadCount") ? obj.get("downloadCount").getAsLong() : 0;
        
        int relType = obj.has("releaseType") ? obj.get("releaseType").getAsInt() : 1;
        v.versionType = relType == 1 ? "release" : (relType == 2 ? "beta" : "alpha");
        
        try { 
            v.datePublished = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(str(obj, "fileDate")); 
        } catch (Exception ignored) {}
        
        if (obj.has("gameVersions")) {
            for (JsonElement e : obj.getAsJsonArray("gameVersions")) v.gameVersions.add(e.getAsString());
        }
        
        if (obj.has("downloadUrl") && !obj.get("downloadUrl").isJsonNull()) {
            ModFile f = new ModFile();
            f.url = obj.get("downloadUrl").getAsString();
            f.filename = obj.get("fileName").getAsString();
            f.size = obj.get("fileLength").getAsLong();
            v.files.add(f);
        }

        if (obj.has("dependencies")) {
            for (JsonElement e : obj.getAsJsonArray("dependencies")) {
                JsonObject d = e.getAsJsonObject();
                ModDependency dep = new ModDependency();
                
                if (d.has("modId")) {
                    int mId = d.get("modId").getAsInt();
                    dep.projectId = String.valueOf(mId);
                    if (mId == 0 && d.has("fileId")) {
                        dep.fileId = d.get("fileId").getAsString();
                    }
                }
                
                int t = d.has("relationType") ? d.get("relationType").getAsInt() : 1;
                switch (t) {
                    case 1: dep.dependencyType = "embedded"; break;     // 内嵌库
                    case 2: dep.dependencyType = "optional"; break;     // 可选
                    case 3: dep.dependencyType = "required"; break;     // 必须
                    case 4: dep.dependencyType = "tool"; break;         // 工具
                    case 5: dep.dependencyType = "incompatible"; break; // 不兼容
                    case 6: dep.dependencyType = "include"; break;      // 包含
                    default: dep.dependencyType = "unknown"; break;
                }
                v.dependencies.add(dep);
            }
        }
        return v;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}