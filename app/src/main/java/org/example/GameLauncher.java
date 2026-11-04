package org.example;

import com.google.gson.*;
import org.example.config.ConfigManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GameLauncher {

    private static final int MAX_BUFFER_LINES = 500;

    public static void launch(String versionId, String playerName, String baseDir, int maxMemoryMB, boolean isolateVersion) throws Exception {
        LogManager.init(baseDir, versionId);
        LogManager.logLauncher("====== 正在准备启动游戏: " + versionId + " ======");

        Map<String, Object> setup = ConfigManager.loadSetup();
        String javaPath = (String) setup.get("javaPath");

        if (javaPath != null) {
            javaPath = javaPath.trim();
            int firstExe = javaPath.toLowerCase().indexOf(".exe");
            int lastExe = javaPath.toLowerCase().lastIndexOf(".exe");
            if (firstExe != -1 && firstExe != lastExe) {
                javaPath = javaPath.substring(0, firstExe + 4);
                LogManager.logLauncher("⚠️ 检测到配置文件中的 Java 路径重复，已自动修正为: " + javaPath);
                ConfigManager.updateSingleConfig("javaPath", javaPath);
            }
        }

        if (javaPath == null || javaPath.isEmpty() || !new File(javaPath).exists()) {
            javaPath = System.getProperty("java.home") + "\\bin\\java.exe";
            LogManager.logLauncher("❌ 错误：未找到有效配置的 Java 路径，已强制回退到默认: " + javaPath);
            throw new Exception("未找到有效的 Java 路径，请前往设置界面重新选择 java.exe！");
        } else {
            LogManager.logLauncher("📌 使用的 Java 路径: " + javaPath);
        }

        String offlineUUID = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)
        ).toString().replace("-", "");

        File versionDir = new File(baseDir, "versions/" + versionId);
        File jsonFile = new File(versionDir, versionId + ".json");
        if (!jsonFile.exists()) throw new Exception("❌ 未找到版本配置文件: " + jsonFile.getAbsolutePath());

        JsonObject versionJson = JsonParser.parseReader(new FileReader(jsonFile)).getAsJsonObject();

        JsonObject parentJson = null;
        String parentId = versionId;
        if (versionJson.has("inheritsFrom")) {
            parentId = versionJson.get("inheritsFrom").getAsString();
            File parentFile = new File(baseDir, "versions/" + parentId + "/" + parentId + ".json");
            if (parentFile.exists()) {
                parentJson = JsonParser.parseReader(new FileReader(parentFile)).getAsJsonObject();
            }
        }

        String assetsIndex = "legacy";
        if (parentJson != null && parentJson.has("assetIndex")) {
            assetsIndex = parentJson.getAsJsonObject("assetIndex").get("id").getAsString();
        } else if (versionJson.has("assetIndex")) {
            assetsIndex = versionJson.getAsJsonObject("assetIndex").get("id").getAsString();
        }

        String mainClass = versionJson.has("mainClass") ? versionJson.get("mainClass").getAsString()
                : (parentJson != null ? parentJson.get("mainClass").getAsString() : "net.minecraft.client.main.Main");

        List<String> classpathList = new ArrayList<>();

        // 标准 <version>-natives 文件夹
        File nativesDir = new File(versionDir, versionId + "-natives");
        nativesDir.mkdirs();

        List<JsonObject> allLibraries = new ArrayList<>();
        if (parentJson != null && parentJson.has("libraries")) {
            for (JsonElement e : parentJson.getAsJsonArray("libraries")) allLibraries.add(e.getAsJsonObject());
        }
        if (versionJson.has("libraries")) {
            for (JsonElement e : versionJson.getAsJsonArray("libraries")) allLibraries.add(e.getAsJsonObject());
        }

        String currentOS = System.getProperty("os.name").toLowerCase();
        String osName = currentOS.contains("win") ? "windows" : (currentOS.contains("mac") ? "osx" : "linux");

        for (JsonObject lib : allLibraries) {
            if (!isLibraryAllowed(lib, osName)) continue;
            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    String path = downloads.getAsJsonObject("artifact").get("path").getAsString();
                    File libFile = new File(baseDir, "libraries/" + path);
                    if (libFile.exists()) classpathList.add(libFile.getAbsolutePath());
                }
                if (downloads.has("classifiers")) {
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    String nativeKey = "natives-" + osName;
                    if (classifiers.has(nativeKey)) {
                        String path = classifiers.getAsJsonObject(nativeKey).get("path").getAsString();
                        File nativeJar = new File(baseDir, "libraries/" + path);
                        if (nativeJar.exists()) extractNatives(nativeJar, nativesDir);
                    }
                }
            }
        }

        File clientJar = new File(baseDir, "versions/" + parentId + "/" + parentId + ".jar");
        if (clientJar.exists()) {
            classpathList.add(clientJar.getAbsolutePath());
        } else {
            throw new Exception("❌ 致命错误: 未找到游戏核心 jar 包 -> " + clientJar.getAbsolutePath());
        }

        String classpath = String.join(";", classpathList);
        String nativesDirPath = nativesDir.getAbsolutePath();

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add(javaPath);
        jvmArgs.add("-Xmx" + Math.max(1024, maxMemoryMB) + "M");
        jvmArgs.add("-Djava.library.path=" + nativesDirPath);

        if (parentJson != null && parentJson.has("arguments")) {
            addArguments(jvmArgs, parentJson.getAsJsonObject("arguments").getAsJsonArray("jvm"), nativesDirPath, baseDir, versionId, playerName, classpath, assetsIndex, offlineUUID, osName);
        }
        if (versionJson.has("arguments")) {
            addArguments(jvmArgs, versionJson.getAsJsonObject("arguments").getAsJsonArray("jvm"), nativesDirPath, baseDir, versionId, playerName, classpath, assetsIndex, offlineUUID, osName);
        }

        jvmArgs.add(mainClass);

        List<String> gameArgs = new ArrayList<>();
        if (parentJson != null && parentJson.has("arguments")) {
            addArguments(gameArgs, parentJson.getAsJsonObject("arguments").getAsJsonArray("game"), nativesDirPath, baseDir, versionId, playerName, classpath, assetsIndex, offlineUUID, osName);
        }
        if (versionJson.has("arguments")) {
            addArguments(gameArgs, versionJson.getAsJsonObject("arguments").getAsJsonArray("game"), nativesDirPath, baseDir, versionId, playerName, classpath, assetsIndex, offlineUUID, osName);
        }

        jvmArgs.addAll(gameArgs);

        String gameDirPath = isolateVersion ? versionDir.getAbsolutePath() : baseDir;
        if (isolateVersion) {
            LogManager.logLauncher("📂 启用了版本隔离，游戏目录: " + gameDirPath);
        }

        List<String> finalArgs = new ArrayList<>();
        for (String arg : jvmArgs) {
            if (arg.equals("${game_directory}")) {
                finalArgs.add(gameDirPath);
            } else {
                finalArgs.add(arg.replace("${game_directory}", gameDirPath));
            }
        }

        LogManager.logLauncher("🚀 最终启动命令: " + String.join(" ", finalArgs));

        ProcessBuilder pb = new ProcessBuilder(finalArgs);
        pb.directory(isolateVersion ? versionDir : new File(baseDir));
        pb.redirectErrorStream(true);

        // 🚨 保留最近 N 行输出，用于崩溃分析
        List<String> recentOutput = Collections.synchronizedList(new ArrayList<>());

        try {
            Process process = pb.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (recentOutput) {
                            recentOutput.add(line);
                            if (recentOutput.size() > MAX_BUFFER_LINES) {
                                recentOutput.remove(0);
                            }
                        }
                        // 🚨 不打印到控制台，保持启动器控制台干净
                    }
                } catch (IOException ignored) {}
            }).start();

            int exitCode = process.waitFor();
            LogManager.logLauncher("🛑 Minecraft 进程已退出，退出码: " + exitCode);

            if (exitCode != 0) {
                // 🚨 崩溃分析：即使 exitCode == 1（游戏内部崩溃），也能给出建议
                LogManager.logLauncher("🔍 开始崩溃日志分析...");
                CrashAnalyzer.CrashReport report = CrashAnalyzer.analyze(versionId, recentOutput, baseDir);
                if (report.matched) {
                    LogManager.logLauncher("✅ 崩溃分析完成，命中 " + report.reasons.size() + " 条建议");
                    for (String r : report.reasons) {
                        LogManager.logLauncher("  → " + r);
                    }
                } else {
                    LogManager.logLauncher("❌ 崩溃分析未能匹配到已知问题");
                }
                throw new GameLaunchException(
                        "游戏异常退出 (退出码: " + exitCode + ")",
                        versionId, baseDir, exitCode, report);
            }

        } catch (GameLaunchException e) {
            throw e;
        } catch (Exception e) {
            LogManager.logLauncher("❌ 启动 ProcessBuilder 失败: " + e.getMessage());
            throw e;
        }
    }

    private static boolean isLibraryAllowed(JsonObject lib, String currentOS) {
        if (!lib.has("rules")) return true;
        return isRuleAllowed(lib.getAsJsonArray("rules"), currentOS);
    }

    private static void extractNatives(File zipFile, File destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().contains("META-INF")) continue;
                File outFile = new File(destDir, entry.getName());
                if (!outFile.getParentFile().exists()) outFile.getParentFile().mkdirs();
                if (!outFile.exists()) {
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[1024];
                        int len;
                        while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
        }
    }

    private static void addArguments(List<String> targetList, JsonArray argsArray, String nativesDirPath, String baseDir, String versionId, String playerName, String classpath, String assetsIndex, String offlineUUID, String osName) {
        if (argsArray == null) return;
        boolean skipNextArg = false;

        for (JsonElement el : argsArray) {
            if (el.isJsonPrimitive()) {
                String arg = el.getAsString();
                if (skipNextArg) { skipNextArg = false; continue; }
                if (arg.startsWith("--quickPlay")) { skipNextArg = true; continue; }
                if (osName.equals("windows") && arg.contains("-XstartOnFirstThread")) continue;

                targetList.add(replacePlaceholders(arg, playerName, versionId, baseDir, assetsIndex, offlineUUID, classpath, nativesDirPath));
            }
            else if (el.isJsonObject()) {
                JsonObject argObj = el.getAsJsonObject();
                if (argObj.has("rules")) {
                    if (isRuleAllowed(argObj.getAsJsonArray("rules"), osName)) {
                        if (argObj.has("value")) {
                            if (argObj.get("value").isJsonPrimitive()) {
                                String val = argObj.get("value").getAsString();
                                if (val.startsWith("--quickPlay")) continue;
                                targetList.add(replacePlaceholders(val, playerName, versionId, baseDir, assetsIndex, offlineUUID, classpath, nativesDirPath));
                            }
                            else if (argObj.get("value").isJsonArray()) {
                                for (JsonElement v : argObj.getAsJsonArray("value")) {
                                    String val = v.getAsString();
                                    if (skipNextArg) { skipNextArg = false; continue; }
                                    if (val.startsWith("--quickPlay")) { skipNextArg = true; continue; }
                                    targetList.add(replacePlaceholders(val, playerName, versionId, baseDir, assetsIndex, offlineUUID, classpath, nativesDirPath));
                                }
                            }
                        }
                    }
                } else {
                    if (argObj.has("value")) {
                        if (argObj.get("value").isJsonPrimitive()) {
                            targetList.add(replacePlaceholders(argObj.get("value").getAsString(), playerName, versionId, baseDir, assetsIndex, offlineUUID, classpath, nativesDirPath));
                        } else if (argObj.get("value").isJsonArray()) {
                            for (JsonElement v : argObj.getAsJsonArray("value")) {
                                targetList.add(replacePlaceholders(v.getAsString(), playerName, versionId, baseDir, assetsIndex, offlineUUID, classpath, nativesDirPath));
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isRuleAllowed(JsonArray rulesArray, String osName) {
        boolean allowed = false;
        for (JsonElement ruleEl : rulesArray) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            boolean matches = true;

            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                if (osObj.has("name")) {
                    String ruleOs = osObj.get("name").getAsString();
                    if (!ruleOs.equals(osName) && !ruleOs.equals("unknown")) matches = false;
                }
                if (osObj.has("arch")) {
                    String ruleArch = osObj.get("arch").getAsString();
                    if (!System.getProperty("os.arch").contains(ruleArch)) matches = false;
                }
            }

            if (rule.has("features")) {
                JsonObject features = rule.getAsJsonObject("features");
                if (features.has("is_demo_user")) {
                    if (features.get("is_demo_user").getAsBoolean()) matches = false;
                }
                if (features.has("has_custom_resolution")) {
                    if (!features.get("has_custom_resolution").getAsBoolean()) matches = false;
                }
                if (features.has("has_quick_plays_support") || features.has("is_quick_play_singleplayer")
                        || features.has("is_quick_play_multiplayer") || features.has("is_quick_play_realms")) {
                    matches = false;
                }
            }

            if (matches) allowed = action.equals("allow");
        }
        return allowed;
    }

    private static String replacePlaceholders(String arg, String playerName, String versionId, String baseDir, String assetsIndex, String offlineUUID, String classpath, String nativesDirPath) {
        return arg.replace("${auth_player_name}", playerName)
                .replace("${version_name}", versionId)
                .replace("${game_directory}", baseDir)
                .replace("${assets_root}", baseDir + "\\assets")
                .replace("${assets_index_name}", assetsIndex)
                .replace("${auth_uuid}", offlineUUID)
                .replace("${auth_access_token}", "0")
                .replace("${clientid}", "0")
                .replace("${auth_xuid}", "0")
                .replace("${library_directory}", baseDir + "\\libraries")
                .replace("${classpath_separator}", ";")
                .replace("${classpath}", classpath)
                .replace("${natives_directory}", nativesDirPath)
                .replace("${launcher_name}", "HiMi")
                .replace("${launcher_version}", "1.0")
                .replace("${user_type}", "legacy")
                .replace("${version_type}", "release")
                .replace("${resolution_width}", "854")
                .replace("${resolution_height}", "480")
                .replace("${quickPlayPath}", "")
                .replace("${quickPlaySingleplayer}", "")
                .replace("${quickPlayMultiplayer}", "")
                .replace("${quickPlayRealms}", "");
    }
}