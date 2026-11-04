package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MinecraftDownloader {

    // ==================== 🔥 PCL2 对齐：只用 BMCLAPI2 ====================
    private static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    private static final String OFFICIAL_ASSETS = "https://resources.download.minecraft.net";

    // PCL2 默认镜像优先；如需官方优先，改为 true
    private final boolean preferMojang = false;

    // BMCLAPI 单线程信号量（PCL2：URL 含 bmclapi 时不再开新线程）
    private final Semaphore bmclapiSemaphore = new Semaphore(1);

    private final DownloadController ui;
    private final OkHttpClient client;
    private File logFile;
    private static final SimpleDateFormat LOG_TIME = new SimpleDateFormat("HH:mm:ss");

    private int totalTasks = 0;
    private int completedTasks = 0;

    public MinecraftDownloader(DownloadController ui) {
        this.ui = ui;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    // ==================== 🔥 PCL2 对齐：URL 替换表 ====================

    /** PCL2 DlSourceAssetsGet */
    private List<String> getAssetUrls(String originalUrl) {
        String fixed = originalUrl.replace("http://resources.download.minecraft.net", "https://resources.download.minecraft.net");
        String mirror = fixed
                .replace("https://piston-data.mojang.com", BMCLAPI + "/assets")
                .replace("https://piston-meta.mojang.com", BMCLAPI + "/assets")
                .replace("https://resources.download.minecraft.net", BMCLAPI + "/assets");
        if (preferMojang) {
            return new ArrayList<>(Arrays.asList(fixed, mirror));
        } else {
            return new ArrayList<>(Arrays.asList(mirror, fixed));
        }
    }

    /** PCL2 DlSourceLibraryGet */
    private List<String> getLibraryUrls(String originalUrl) {
        boolean isModLoader = originalUrl.contains("minecraftforge")
                || originalUrl.contains("fabricmc")
                || originalUrl.contains("neoforged");
        String mirrorMaven = originalUrl
                .replace("https://piston-data.mojang.com", BMCLAPI + "/maven")
                .replace("https://piston-meta.mojang.com", BMCLAPI + "/maven")
                .replace("https://libraries.minecraft.net", BMCLAPI + "/maven");
        String mirrorLibraries = originalUrl
                .replace("https://piston-data.mojang.com", BMCLAPI + "/libraries")
                .replace("https://piston-meta.mojang.com", BMCLAPI + "/libraries")
                .replace("https://libraries.minecraft.net", BMCLAPI + "/libraries");
        if (isModLoader) {
            // 不添加原版官方源
            return new ArrayList<>(Arrays.asList(mirrorMaven, mirrorLibraries));
        } else {
            if (preferMojang) {
                return new ArrayList<>(Arrays.asList(originalUrl, mirrorMaven, mirrorLibraries));
            } else {
                return new ArrayList<>(Arrays.asList(mirrorMaven, mirrorLibraries, originalUrl));
            }
        }
    }

    /** PCL2 DlSourceLauncherOrMetaGet */
    private List<String> getLauncherOrMetaUrls(String originalUrl) {
        String mirror = originalUrl
                .replace("https://piston-data.mojang.com", BMCLAPI)
                .replace("https://piston-meta.mojang.com", BMCLAPI)
                .replace("https://launcher.mojang.com", BMCLAPI)
                .replace("https://launchermeta.mojang.com", BMCLAPI);
        if (preferMojang) {
            return new ArrayList<>(Arrays.asList(originalUrl, mirror));
        } else {
            return new ArrayList<>(Arrays.asList(mirror, originalUrl));
        }
    }

    /** 根据 URL 类型自动选择替换表 */
    private List<String> getMirrorUrls(String originalUrl) {
        if (originalUrl.contains("resources.download.minecraft.net")
                || originalUrl.contains("piston-data.mojang.com/assets")
                || originalUrl.contains("piston-meta.mojang.com/assets")) {
            return getAssetUrls(originalUrl);
        } else if (originalUrl.contains("libraries.minecraft.net")
                || originalUrl.contains("minecraftforge")
                || originalUrl.contains("fabricmc")
                || originalUrl.contains("neoforged")) {
            return getLibraryUrls(originalUrl);
        } else {
            return getLauncherOrMetaUrls(originalUrl);
        }
    }

    // ================== 全量下载 ==================
    public void downloadAll(String versionId, String loader, String baseDir, String folderName) throws Exception {
        logFile = new File(baseDir, "versions/" + folderName + "/dl.txt");
        logFile.getParentFile().mkdirs();
        writeLog("================ 开始新的下载任务 ================");
        writeLog("目标原版: " + versionId);
        writeLog("目标加载器: " + loader);
        writeLog("保存目录: " + folderName);
        writeLog("=================================================");

        ui.updateSpeed("解析版本信息...");
        ui.addTask("获取版本 JSON 文件");

        String versionJsonStr = getVersionJson(versionId);
        if (versionJsonStr == null) {
            ui.updateTaskStatus(0, "失败");
            writeLog("[错误] 无法获取版本 JSON（所有源均失败）");
            return;
        }
        ui.updateTaskStatus(0, "完成");
        writeLog("获取版本 JSON 成功");
        completedTasks++;

        JsonObject versionJson = JsonParser.parseString(versionJsonStr).getAsJsonObject();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        totalTasks = 4 + libraries.size() + (loader.equals("原版 (Vanilla)") ? 0 : 1);
        ui.updateTotalProgress(completedTasks / (double) totalTasks, (completedTasks * 100 / totalTasks) + "%");

        // 1. 客户端核心
        ui.addTask("下载客户端核心 (.jar)");
        JsonObject clientInfo = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        String clientUrl = clientInfo.get("url").getAsString();
        File clientJar = new File(baseDir, "versions/" + versionId + "/" + versionId + ".jar");
        clientJar.getParentFile().mkdirs();
        downloadWithMirrorsFallback(clientUrl, clientJar, 1);
        ui.updateTaskStatus(1, "完成");
        completedTasks++;
        ui.updateTotalProgress(completedTasks / (double) totalTasks, (completedTasks * 100 / totalTasks) + "%");

        // 2. 库文件
        ui.addTask("下载游戏运行库 (Libraries)");
        for (JsonElement lib : libraries) {
            JsonObject libObj = lib.getAsJsonObject();
            if (libObj.has("downloads")) {
                JsonObject artifact = libObj.getAsJsonObject("downloads").getAsJsonObject("artifact");
                if (artifact != null && artifact.has("url")) {
                    String libUrl = artifact.get("url").getAsString();
                    String path = artifact.get("path").getAsString();
                    File libFile = new File(baseDir, "libraries/" + path);
                    libFile.getParentFile().mkdirs();
                    downloadWithMirrorsFallback(libUrl, libFile, -1);
                }
            }
            completedTasks++;
            ui.updateTotalProgress(completedTasks / (double) totalTasks, (completedTasks * 100 / totalTasks) + "%");
        }
        ui.updateTaskStatus(2, "完成");

        // 3. Assets
        ui.addTask("下载游戏资源文件 (Assets)");
        downloadAssets(versionJson, baseDir);
        ui.updateTaskStatus(3, "完成");
        completedTasks++;
        ui.updateTotalProgress(completedTasks / (double) totalTasks, (completedTasks * 100 / totalTasks) + "%");

        // 4. 模组加载器
        if (!loader.equals("原版 (Vanilla)")) {
            ui.addTask("下载并安装 " + loader);
            try {
                installModLoader(versionId, loader, baseDir, folderName);
                ui.updateTaskStatus(4, "完成");
                completedTasks++;
                ui.updateTotalProgress(1.0, "100%");
                ui.updateRemainingFiles(0);
            } catch (Exception e) {
                ui.updateTaskStatus(4, "失败");
                writeLog("[错误] 安装模组加载器失败: " + e.getMessage());
                throw new Exception("安装失败: " + e.getMessage());
            }
        }

        writeLog("================ 所有下载任务已完成 ================");
        ui.onDownloadFinished();
    }

    // ================== 仅补全 Assets ==================
    public void downloadAssetsOnly(String versionId, String baseDir) {
        try {
            logFile = new File(baseDir, "versions/" + versionId + "/dl_assets.txt");
            logFile.getParentFile().mkdirs();
            writeLog("================ 开始补全资源任务 ================");

            ui.updateSpeed("正在获取版本信息...");
            ui.addTask("获取版本信息");
            String versionJsonStr = getVersionJson(versionId);
            if (versionJsonStr == null) {
                ui.updateTaskStatus(0, "失败");
                writeLog("[错误] 无法获取版本 JSON（所有源均失败）");
                return;
            }
            JsonObject versionJson = JsonParser.parseString(versionJsonStr).getAsJsonObject();
            ui.updateTaskStatus(0, "完成");

            ui.addTask("检查并补全 Assets 资源");
            downloadAssets(versionJson, baseDir);
            ui.updateTaskStatus(1, "完成");

            ui.onDownloadFinished();
            writeLog("================ 资源补全完成 ================");
        } catch (Exception e) {
            e.printStackTrace();
            writeLog("[错误] 资源补全异常: " + e.getMessage());
        }
    }

    // ================== 核心 Assets 下载 ==================
    private void downloadAssets(JsonObject versionJson, String baseDir) {
        try {
            JsonObject assetIndexObj = versionJson.getAsJsonObject("assetIndex");
            if (assetIndexObj == null) {
                writeLog("未找到 assetIndex 信息，跳过资源下载。");
                return;
            }
            String indexId = assetIndexObj.get("id").getAsString();
            String indexUrl = assetIndexObj.get("url").getAsString();
            String indexSha1 = assetIndexObj.has("sha1") ? assetIndexObj.get("sha1").getAsString() : null;

            File indexesDir = new File(baseDir, "assets/indexes");
            indexesDir.mkdirs();
            File indexFile = new File(indexesDir, indexId + ".json");

            boolean indexOk = indexFile.exists()
                    && (indexSha1 == null || indexSha1.equalsIgnoreCase(calculateSha1(indexFile)));
            if (!indexOk) {
                if (indexFile.exists()) indexFile.delete();
                writeLog("正在下载 Assets Index: " + indexUrl);
                ui.updateSpeed("正在下载资源索引...");
                downloadWithMirrorsFallback(indexUrl, indexFile, -1);
            }

            JsonObject root = JsonParser.parseReader(new FileReader(indexFile)).getAsJsonObject();
            JsonObject objects = root.getAsJsonObject("objects");
            if (objects == null) return;

            File objectsDir = new File(baseDir, "assets/objects");
            objectsDir.mkdirs();

            List<String> hashes = new ArrayList<>(objects.keySet());
            writeLog("需要检查的 Assets 文件总数: " + hashes.size());

            int threadCount = 16;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger downloadedCount = new AtomicInteger(0);
            AtomicInteger failedCount = new AtomicInteger(0);
            int totalCount = hashes.size();

            for (String key : hashes) {
                JsonObject objInfo = objects.getAsJsonObject(key);
                String hash = objInfo.get("hash").getAsString();
                String prefix = hash.substring(0, 2);
                long size = objInfo.has("size") ? objInfo.get("size").getAsLong() : -1;

                File targetFile = new File(objectsDir, prefix + "/" + hash);

                if (targetFile.exists()) {
                    if (hash.equalsIgnoreCase(calculateSha1(targetFile))) {
                        downloadedCount.incrementAndGet();
                        continue;
                    } else {
                        writeLog("[修复] 检测到损坏资源，删除重下: " + hash);
                        targetFile.delete();
                    }
                }

                // PCL2：小文件（< 1MB）不分片、直接单线程
                if (size >= 0 && size < 1024 * 1024) {
                    boolean ok = downloadAssetWithRetry(prefix, hash, targetFile);
                    if (!ok) failedCount.incrementAndGet();
                    int current = downloadedCount.incrementAndGet();
                    if (current % 50 == 0 || current == totalCount) {
                        double progress = (double) current / totalCount;
                        ui.updateSpeed("资源下载: " + current + "/" + totalCount);
                        ui.updateFileProgress(progress);
                    }
                } else {
                    executor.submit(() -> {
                        boolean ok = downloadAssetWithRetry(prefix, hash, targetFile);
                        if (!ok) failedCount.incrementAndGet();
                        int current = downloadedCount.incrementAndGet();
                        if (current % 50 == 0 || current == totalCount) {
                            double progress = (double) current / totalCount;
                            ui.updateSpeed("资源下载: " + current + "/" + totalCount);
                            ui.updateFileProgress(progress);
                        }
                    });
                }
            }

            executor.shutdown();
            while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                writeLog("资源下载仍在进行中，请稍候...");
            }
            ui.updateFileProgress(1.0);

            int finalFailedCount = failedCount.get();
            if (finalFailedCount > 0) {
                writeLog("[警告] 资源下载结束，共有 " + finalFailedCount + " 个文件失败。");
            } else {
                writeLog("所有资源文件已成功补全并通过 SHA1 校验！");
            }
            ui.showRemainingAssets(finalFailedCount);

        } catch (Exception e) {
            writeLog("[错误] 下载 Assets 异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔥 PCL2 对齐的 Assets 下载：
     * - 源顺序按 getAssetUrls 决定
     * - BMCLAPI 强制单线程（Semaphore 限制）
     * - BMCLAPI 的 403/429 不禁用源，多等 5s 继续重试
     */
    private boolean downloadAssetWithRetry(String prefix, String hash, File targetFile) {
        if (targetFile.exists() && hash.equalsIgnoreCase(calculateSha1(targetFile))) return true;
        if (targetFile.exists()) targetFile.delete();

        String originalUrl = OFFICIAL_ASSETS + "/" + prefix + "/" + hash;
        List<String> urls = getAssetUrls(originalUrl);
        File tmpFile = new File(targetFile.getAbsolutePath() + ".tmp");

        for (String url : urls) {
            boolean isBmclapi = url.contains("bmclapi");
            int maxAttempts = isBmclapi ? 5 : 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean acquired = false;
                try {
                    if (isBmclapi) {
                        bmclapiSemaphore.acquire();
                        acquired = true;
                        Thread.sleep(100); // PCL2：每个 BMCLAPI 请求之间间隔 100ms
                    }
                    downloadRaw(url, tmpFile);
                    if (hash.equalsIgnoreCase(calculateSha1(tmpFile))) {
                        if (targetFile.exists()) targetFile.delete();
                        if (tmpFile.renameTo(targetFile)) return true;
                        tmpFile.delete();
                    } else {
                        writeLog("[警告] SHA1 校验失败: " + url);
                        tmpFile.delete();
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    writeLog("[警告] 下载失败 (" + attempt + "/" + maxAttempts + "): " + url + " - " + msg);
                    tmpFile.delete();
                    if (isBmclapi && (msg.contains("403") || msg.contains("429"))) {
                        // PCL2：BMCLAPI 的 403/429 不禁用源，多等一会儿
                        writeLog("[限速] BMCLAPI 返回 " + msg + "，等待 5s 后重试");
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                } finally {
                    if (acquired) bmclapiSemaphore.release();
                }
            }
            writeLog("[信息] 该源不可用，切换下一个: " + url);
        }

        writeLog("[错误] 所有源均无法下载资源: " + hash);
        return false;
    }

    /** 纯下载，不跳过已存在文件 */
    private void downloadRaw(String url, File dest) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new Exception("响应体为空");

            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
        }
    }

    /** 计算文件 SHA1 */
    private String calculateSha1(File file) {
        if (file == null || !file.exists() || file.length() == 0) return "";
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                md.update(buf, 0, len);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ================== 模组安装 ==================
    private void installModLoader(String mcVersion, String loader, String baseDir, String folderName) throws Exception {
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java.exe";
        String[] parts = loader.split(" ");
        String loaderType = parts[0];
        String loaderVersion = parts.length > 1 ? parts[1] : "";

        generateLauncherProfiles(baseDir);

        File installerFile = new File(baseDir, "temp-installer.jar");
        if (installerFile.exists()) installerFile.delete();

        List<String> downloadUrls = new ArrayList<>();
        if (loaderType.equals("NeoForge")) {
            downloadUrls.add("https://maven.neoforged.net/releases/net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar");
            downloadUrls.add("https://neoforged.forgecdn.net/releases/net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar");
            downloadUrls.add(BMCLAPI + "/maven/net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar");
        } else if (loaderType.equals("Forge")) {
            downloadUrls.add("https://maven.minecraftforge.net/net/minecraftforge/forge/" + mcVersion + "-" + loaderVersion + "/forge-" + mcVersion + "-" + loaderVersion + "-installer.jar");
            downloadUrls.add(BMCLAPI + "/forge/download/" + mcVersion + "-" + loaderVersion + "/installer");
        } else if (loaderType.equals("Fabric")) {
            downloadUrls.add("https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.1/fabric-installer-1.0.1.jar");
        }

        boolean downloaded = false;
        for (String url : downloadUrls) {
            try {
                writeLog("尝试下载 Installer: " + url);
                downloadFile(url, installerFile, -1);
                if (installerFile.exists() && installerFile.length() > 0) {
                    downloaded = true;
                    writeLog("Installer 下载成功: " + url);
                    break;
                }
            } catch (Exception e) {
                writeLog("Installer 下载失败: " + e.getMessage());
            }
        }

        if (!downloaded) {
            writeLog("[错误] 无法获取 Installer。");
            throw new Exception("无法获取 Installer。");
        }

        if (loaderType.equals("NeoForge") || loaderType.equals("Forge")) {
            ui.updateSpeed("正在修改 Installer 内部链接...");
            modifyInstallerUrls(installerFile);
        }

        writeLog("开始运行 Installer 进程...");
        List<String> command = new ArrayList<>();
        command.add(javaExe);
        command.add("-jar");
        command.add(installerFile.getAbsolutePath());

        if (loaderType.equals("Forge") || loaderType.equals("NeoForge")) {
            command.add("--installClient");
            command.add(new File(baseDir).getAbsolutePath());
        } else if (loaderType.equals("Fabric")) {
            command.add("client");
            command.add("-dir");
            command.add(new File(baseDir).getAbsolutePath());
            command.add("-mcversion");
            command.add(mcVersion);
            command.add("-loader");
            command.add(loaderVersion);
            command.add("-noprofile");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Installer] " + line);
                writeLog("[Installer] " + line);
            }
        }

        if (process.waitFor() != 0) {
            throw new Exception("安装器执行失败，请检查控制台日志。");
        }

        installerFile.delete();
        writeLog("模组加载器安装完成。");
    }

    private void modifyInstallerUrls(File installerJar) {
        writeLog("正在修改 Installer 内的下载链接...");
        File tempJar = new File(installerJar.getParent(), "temp-modified-installer.jar");
        try (ZipFile zip = new ZipFile(installerJar);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempJar))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".json")) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        String jsonStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        jsonStr = jsonStr.replace("https://maven.neoforged.net/releases/", BMCLAPI + "/maven/")
                                .replace("https://maven.minecraftforge.net/", BMCLAPI + "/maven/");

                        ZipEntry newEntry = new ZipEntry(entry.getName());
                        zos.putNextEntry(newEntry);
                        zos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                        zos.closeEntry();
                    }
                } else {
                    ZipEntry newEntry = new ZipEntry(entry.getName());
                    zos.putNextEntry(newEntry);
                    try (InputStream is = zip.getInputStream(entry)) {
                        is.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
        } catch (Exception e) {
            writeLog("[错误] 修改 Installer 失败: " + e.getMessage());
            System.err.println("修改 Installer 失败: " + e.getMessage());
        }

        installerJar.delete();
        tempJar.renameTo(installerJar);
        writeLog("成功将 Installer 内的下载链接替换为国内镜像！");
    }

    private void generateLauncherProfiles(String baseDir) {
        File profilesFile = new File(baseDir, "launcher_profiles.json");
        if (!profilesFile.exists()) {
            try (FileWriter writer = new FileWriter(profilesFile)) {
                writer.write("{\n  \"profiles\": {},\n  \"settings\": {},\n  \"version\": 3\n}");
                writeLog("生成 launcher_profiles.json 成功");
            } catch (Exception ignored) {}
        }
    }

    /**
     * 多源回退的文件下载（用于 client jar、libraries、installer、index）
     */
    private void downloadWithMirrorsFallback(String originUrl, File saveFile, int taskIndex) {
        if (saveFile.exists() && taskIndex != 1) return;

        if (taskIndex >= 0) ui.updateTaskStatus(taskIndex, "下载中...");

        List<String> candidates = getMirrorUrls(originUrl);
        if (!candidates.contains(originUrl)) candidates.add(originUrl);

        Exception lastEx = null;
        for (String url : candidates) {
            boolean isBmclapi = url.contains("bmclapi");
            int maxAttempts = isBmclapi ? 5 : 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean acquired = false;
                try {
                    if (isBmclapi) {
                        bmclapiSemaphore.acquire();
                        acquired = true;
                        Thread.sleep(100);
                    }
                    writeLog("正在下载: " + url);
                    downloadFile(url, saveFile, taskIndex);
                    if (taskIndex >= 0) ui.updateTaskStatus(taskIndex, "完成");
                    writeLog("下载完成: " + saveFile.getName());
                    return;
                } catch (Exception e) {
                    lastEx = e;
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    writeLog("[警告] 下载失败 (" + attempt + "/" + maxAttempts + "): " + url + " - " + msg);
                    if (isBmclapi && (msg.contains("403") || msg.contains("429"))) {
                        writeLog("[限速] BMCLAPI 返回 " + msg + "，等待 5s 后重试");
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                } finally {
                    if (acquired) bmclapiSemaphore.release();
                }
            }
            writeLog("[信息] 该源不可用，切换下一个: " + url);
        }

        if (taskIndex >= 0) ui.updateTaskStatus(taskIndex, "失败");
        writeLog("[错误] 所有源均失败: " + saveFile.getName());
        throw new RuntimeException("下载失败: " + saveFile.getName(), lastEx);
    }

    /** 单文件下载（带进度回调） */
    private void downloadFile(String url, File saveFile, int taskIndex) {
        if (saveFile.exists() && taskIndex != 1) return;

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
                ResponseBody body = response.body();
                if (body == null) throw new Exception("响应体为空");

                long totalBytes = body.contentLength();
                long downloadedBytes = 0;
                long lastUpdateTime = System.currentTimeMillis();
                long lastDownloadedBytes = 0;

                try (InputStream is = body.byteStream();
                     FileOutputStream fos = new FileOutputStream(saveFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        long now = System.currentTimeMillis();
                        if (now - lastUpdateTime > 200 && taskIndex >= 0) {
                            double fileProgress = totalBytes > 0 ? (double) downloadedBytes / totalBytes : 0;
                            ui.updateFileProgress(fileProgress);

                            long timeDiff = now - lastUpdateTime;
                            long bytesDiff = downloadedBytes - lastDownloadedBytes;
                            double speedKb = (bytesDiff / 1024.0) / (timeDiff / 1000.0);
                            double speedMb = speedKb / 1024.0;
                            if (speedMb > 100.0) speedMb = 100.0;

                            String speedStr = speedMb > 1 ? String.format("%.2f MB/s", speedMb) : String.format("%.1f KB/s", speedKb);
                            ui.updateSpeed(speedStr);

                            lastUpdateTime = now;
                            lastDownloadedBytes = downloadedBytes;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("下载失败: " + url + " - " + e.getMessage(), e);
        }
    }

    /**
     * 获取版本 JSON：BMCLAPI 快速接口 → 官方 manifest
     */
    private String getVersionJson(String versionId) {
        writeLog("正在获取版本 JSON 数据: " + versionId);

        // 1. BMCLAPI 快速接口
        String quickUrl = BMCLAPI + "/version/" + versionId + "/json";
        String str = fetchString(quickUrl);
        if (str != null) {
            writeLog("从 BMCLAPI 获取版本 JSON 成功");
            return str;
        }
        writeLog("[警告] BMCLAPI 获取失败，回退官方 manifest...");

        // 2. 官方 manifest fallback
        String[] manifestUrls = {
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        };
        for (String mUrl : manifestUrls) {
            try {
                String manifestStr = fetchString(mUrl);
                if (manifestStr != null) {
                    JsonObject root = JsonParser.parseString(manifestStr).getAsJsonObject();
                    JsonArray versions = root.getAsJsonArray("versions");
                    for (JsonElement el : versions) {
                        JsonObject v = el.getAsJsonObject();
                        if (v.get("id").getAsString().equals(versionId)) {
                            return fetchString(v.get("url").getAsString());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String fetchString(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) return response.body().string();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 同时写独立下载日志 + 启动器日志
     */
    private void writeLog(String message) {
        String line = "[" + LOG_TIME.format(new Date()) + "] " + message;

        if (logFile != null) {
            try {
                logFile.getParentFile().mkdirs();
                try (FileWriter fw = new FileWriter(logFile, true);
                     BufferedWriter bw = new BufferedWriter(fw);
                     PrintWriter out = new PrintWriter(bw)) {
                    out.println(line);
                }
            } catch (IOException e) {
                System.err.println("写入下载日志失败: " + e.getMessage());
            }
        }

        LogManager.logLauncher("[下载器] " + message);
    }
}