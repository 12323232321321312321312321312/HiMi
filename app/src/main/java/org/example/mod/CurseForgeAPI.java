package org.example.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class CurseForgeAPI {
    private static final String BASE = "https://api.curseforge.com/v1";
    private static final String USER_AGENT = "HiMi/1.0.0";

    private static String getApiKey() {
        try {
            String b64 = "JDJhJDEwJDdXV1YxV0VMY3dYOFhwN2Q2OE1Icy41Z3JUeVpWYTZTeW55ZVN5TWgxcnNFODluSWEwOXpP";
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

    public static JsonObject search(String query, String gameVersion, int modLoaderType, int index, int pageSize) throws IOException {
        StringBuilder url = new StringBuilder(BASE + "/mods/search?gameId=432&classId=6&sortField=2&sortOrder=desc");
        if (query != null && !query.isEmpty()) url.append("&searchFilter=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (gameVersion != null && !gameVersion.isEmpty()) url.append("&gameVersion=").append(gameVersion);
        if (modLoaderType > 0) url.append("&modLoaderType=").append(modLoaderType);
        url.append("&index=").append(index).append("&pageSize=").append(pageSize);
        return getJson(url.toString());
    }

    public static JsonArray getModFiles(String modId, String gameVersion, int modLoaderType) throws IOException {
        StringBuilder url = new StringBuilder(BASE + "/mods/" + modId + "/files?");
        if (gameVersion != null && !gameVersion.isEmpty()) url.append("gameVersion=").append(gameVersion).append("&");
        if (modLoaderType > 0) url.append("modLoaderType=").append(modLoaderType);
        
        JsonObject res = getJson(url.toString());
        if (res.has("data") && res.get("data").isJsonArray()) {
            return res.getAsJsonArray("data");
        }
        return new JsonArray();
    }

    public static JsonObject getModFile(String modId, String fileId) throws IOException {
        JsonObject res = getJson(BASE + "/mods/" + modId + "/files/" + fileId);
        return res.has("data") && !res.get("data").isJsonNull() ? res.getAsJsonObject("data") : null;
    }

    // 🚨 新增：通过项目 ID 获取模组详情（用于获取依赖的真实名称）
    public static JsonObject getMod(String modId) throws IOException {
        JsonObject res = getJson(BASE + "/mods/" + modId);
        return res.has("data") && !res.get("data").isJsonNull() ? res.getAsJsonObject("data") : null;
    }

    public static String getDownloadUrl(String modId, String fileId) throws IOException {
        JsonObject res = getJson(BASE + "/mods/" + modId + "/files/" + fileId + "/download-url");
        return res.has("data") && !res.get("data").isJsonNull() ? res.get("data").getAsString() : null;
    }

    private static JsonObject getJson(String url) throws IOException { 
        return JsonParser.parseString(getString(url)).getAsJsonObject(); 
    }

    private static String getString(String url) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("x-api-key", getApiKey())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            if (resp.body() == null) throw new IOException("Empty body");
            return resp.body().string();
        }
    }
}