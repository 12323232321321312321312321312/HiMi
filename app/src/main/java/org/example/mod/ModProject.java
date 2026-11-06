package org.example.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public class ModProject {
    public enum Platform { MODRINTH, CURSEFORGE }
    
    public Platform platform;
    public String id;
    public String slug;
    public String title;
    public String description;
    public String iconUrl;
    public long downloads;
    public List<String> categories = new ArrayList<>();
    public List<String> gameVersions = new ArrayList<>();
    public String dateModified;

    public String translatedTitle;
    public String translatedDesc;

    public static ModProject fromModrinthSearch(JsonObject obj) {
        ModProject p = new ModProject();
        p.platform = Platform.MODRINTH;
        p.id = str(obj, "project_id");
        p.slug = str(obj, "slug");
        p.title = str(obj, "title");
        p.description = str(obj, "description");
        p.iconUrl = str(obj, "icon_url");
        p.downloads = obj.has("downloads") ? obj.get("downloads").getAsLong() : 0;
        p.dateModified = str(obj, "date_modified");
        if (obj.has("categories")) for (JsonElement e : obj.getAsJsonArray("categories")) p.categories.add(e.getAsString());
        if (obj.has("versions")) for (JsonElement e : obj.getAsJsonArray("versions")) p.gameVersions.add(e.getAsString());
        return p;
    }

    public static ModProject fromCurseForgeSearch(JsonObject obj) {
        ModProject p = new ModProject();
        p.platform = Platform.CURSEFORGE;
        p.id = str(obj, "id");
        p.slug = str(obj, "slug");
        p.title = str(obj, "name");
        p.description = str(obj, "summary");
        p.downloads = obj.has("downloadCount") ? obj.get("downloadCount").getAsLong() : 0;
        p.dateModified = str(obj, "dateModified");
        if (obj.has("logo") && obj.getAsJsonObject("logo").has("thumbnailUrl")) {
            p.iconUrl = str(obj.getAsJsonObject("logo"), "thumbnailUrl");
        }
        if (obj.has("categories")) {
            for (JsonElement e : obj.getAsJsonArray("categories")) {
                p.categories.add(e.getAsJsonObject().get("name").getAsString());
            }
        }
        return p;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}