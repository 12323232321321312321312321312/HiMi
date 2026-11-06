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
import java.util.concurrent.TimeUnit;

public class MinekuaiAPI {
    private static final String BASE = "https://api.minekuai.cn/panel/public/mc-mods";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).build();

    public static JsonObject search(String query, int limit, int offset) throws IOException {
        String url = BASE + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit + "&offset=" + offset + "&status=translated";
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "HiMi/1.0.0").build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }
}