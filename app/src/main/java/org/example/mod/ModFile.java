package org.example.mod;

import com.google.gson.JsonObject;

public class ModFile {
    public String url;
    public String filename;
    public long size;
    public String sha1;

    public static ModFile fromModrinth(JsonObject obj) {
        ModFile f = new ModFile();
        f.url = str(obj, "url");
        f.filename = str(obj, "filename");
        f.size = obj.has("size") ? obj.get("size").getAsLong() : 0;
        if (obj.has("hashes")) {
            JsonObject hashes = obj.getAsJsonObject("hashes");
            f.sha1 = str(hashes, "sha1");
        }
        return f;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}