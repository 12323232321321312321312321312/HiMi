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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ModrinthAPI {
    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "HiMi/1.0.0";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

    public static JsonObject search(String query, List<String> gameVersions, List<String> loaders, int offset, int limit) throws IOException {
        StringBuilder url = new StringBuilder(BASE + "/search?");
        if (query != null && !query.isEmpty()) {
            url.append("query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8)).append("&");
        }
        url.append("limit=").append(limit).append("&offset=").append(offset);

        List<String> facets = new java.util.ArrayList<>();
        facets.add("[\"project_type:mod\"]");
        if (gameVersions != null && !gameVersions.isEmpty()) {
            facets.add("[" + gameVersions.stream().map(v -> "\"versions:" + v + "\"").collect(Collectors.joining(",")) + "]");
        }
        if (loaders != null && !loaders.isEmpty()) {
            facets.add("[" + loaders.stream().map(l -> "\"categories:" + l + "\"").collect(Collectors.joining(",")) + "]");
        }
        url.append("&facets=").append(URLEncoder.encode("[" + String.join(",", facets) + "]", StandardCharsets.UTF_8));
        return getJson(url.toString());
    }

    public static JsonArray getProjectVersions(String id, List<String> gameVersions, List<String> loaders) throws IOException {
        StringBuilder url = new StringBuilder(BASE + "/project/" + id + "/version?");
        if (gameVersions != null && !gameVersions.isEmpty()) url.append("game_versions=").append(URLEncoder.encode(toJsonArray(gameVersions), StandardCharsets.UTF_8));
        if (loaders != null && !loaders.isEmpty()) {
            if (url.toString().contains("=")) url.append("&");
            url.append("loaders=").append(URLEncoder.encode(toJsonArray(loaders), StandardCharsets.UTF_8));
        }
        return getJsonArray(url.toString());
    }

    // 🚨 新增：获取项目详情
    public static JsonObject getProject(String id) throws IOException {
        return getJson(BASE + "/project/" + id);
    }

    private static String toJsonArray(List<String> list) {
        return "[" + list.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
    }

    private static JsonObject getJson(String url) throws IOException { return JsonParser.parseString(getString(url)).getAsJsonObject(); }
    private static JsonArray getJsonArray(String url) throws IOException { return JsonParser.parseString(getString(url)).getAsJsonArray(); }

    private static String getString(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (response.body() == null) throw new IOException("Empty body");
            return response.body().string();
        }
    }
}