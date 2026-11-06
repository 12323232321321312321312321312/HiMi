package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.config.ConfigManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainController {

    @FXML private Label chooseLabel;
    @FXML private Button ace;
    @FXML private Button reloadVersionsBtn;
    @FXML private VBox categoryContainer;
    @FXML private Button Setting;

    @FXML private TextField name;
    @FXML private Button start_game;
    @FXML private Label lblSelectedVersion;
    @FXML private Button choose;
    @FXML private CheckBox chkGlobalIsolation;
    @FXML private Button mod;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private String currentLatestRelease = "";
    private String currentLatestSnapshot = "";
    private JsonArray onlineVersionsCache = null;
    private List<MinecraftVersion> localVersions = new ArrayList<>();
    private String selectedVersionId = "未选择";
    private int maxMemory = 4096;
    private boolean globalIsolateVersion = true;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("VersionCheckScheduler");
        return t;
    });

    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ConfigSaveScheduler");
        return t;
    });
    private ScheduledFuture<?> pendingSaveTask = null;

    // ============================================================
    // 🚨 初始化（分段 try-catch，保证任何一步出错都不会导致整个界面失效）
    // ============================================================
    @FXML
    public void initialize() {
        System.out.println("[Init] MainController.initialize() 开始");
        System.out.println("[Init] chkGlobalIsolation " + (chkGlobalIsolation != null ? "已注入" : "= null !!!"));

        try {
            ConfigManager.init();
        } catch (Exception e) {
            System.err.println("[Init] ConfigManager.init() 失败: " + e.getMessage());
        }

        if (mod != null) {
            mod.setOnAction(e -> openModDownloadWindow());
        }

        Map<String, Object> setup;
        try {
            setup = ConfigManager.loadSetup();
        } catch (Exception e) {
            System.err.println("[Init] loadSetup() 失败: " + e.getMessage());
            setup = new java.util.HashMap<>();
        }

        // ---------- 读取 playerId ----------
        try {
            if (setup.get("playerId") != null && name != null) {
                name.setText((String) setup.get("playerId"));
            }
        } catch (Exception e) {
            System.err.println("[Init] 读取 playerId 失败: " + e.getMessage());
        }

        // ---------- 读取 memory ----------
        try {
            if (setup.get("memory") != null) {
                maxMemory = ((Number) setup.get("memory")).intValue();
            }
        } catch (Exception e) {
            System.err.println("[Init] 读取 memory 失败: " + e.getMessage());
        }

        // ---------- 🚨 读取 isolateVersion（带 lookup 兜底） ----------
        try {
            Object rawIsolation = setup.get("isolateVersion");
            System.out.println("[BOOT] Setup.json 中 isolateVersion = " + rawIsolation
                    + " (类型: " + (rawIsolation == null ? "null" : rawIsolation.getClass().getName()) + ")");

            if (rawIsolation != null) {
                globalIsolateVersion = parseBoolean(rawIsolation, true);
            } else {
                ConfigManager.updateSingleConfig("isolateVersion", true);
                globalIsolateVersion = true;
            }
            final boolean initialValue = globalIsolateVersion;
            System.out.println("[BOOT] 解析为 globalIsolateVersion = " + initialValue);

            if (chkGlobalIsolation != null) {
                chkGlobalIsolation.setSelected(initialValue);
                chkGlobalIsolation.selectedProperty().addListener((obs, oldVal, newVal) -> {
                    globalIsolateVersion = newVal;
                    scheduleConfigSave();
                    chooseLabel.setText("状态: 版本隔离已" + (newVal ? "开启" : "关闭"));
                });
                System.out.println("[BOOT] ✅ 立即同步成功，chkGlobalIsolation.isSelected() = " + chkGlobalIsolation.isSelected());
            } else {
                System.err.println("[BOOT] ❌ @FXML 注入失败，chkGlobalIsolation = null，将用 Scene.lookup 兜底");
            }

            // 兜底：等 FX 线程下一帧，用 lookup 再强制同步一次
            Platform.runLater(() -> {
                CheckBox cb = chkGlobalIsolation;
                if (cb == null && categoryContainer != null && categoryContainer.getScene() != null) {
                    javafx.scene.Node node = categoryContainer.getScene().lookup("#chkGlobalIsolation");
                    if (node instanceof CheckBox) {
                        cb = (CheckBox) node;
                        chkGlobalIsolation = cb;
                        System.out.println("[BOOT] ✅ 通过 Scene.lookup 找到 chkGlobalIsolation");
                    }
                }
                if (cb != null) {
                    cb.setSelected(initialValue);
                    if (cb.getProperties().get("himi_listener_registered") == null) {
                        cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                            globalIsolateVersion = newVal;
                            scheduleConfigSave();
                            chooseLabel.setText("状态: 版本隔离已" + (newVal ? "开启" : "关闭"));
                        });
                        cb.getProperties().put("himi_listener_registered", true);
                    }
                    System.out.println("[BOOT] ✅ 兜底同步完成，chkGlobalIsolation.isSelected() = " + cb.isSelected()
                            + "，config = " + initialValue);
                } else {
                    System.err.println("[BOOT] ❌❌❌ 无论如何都找不到 chkGlobalIsolation！");
                }
            });

        } catch (Exception e) {
            System.err.println("[BOOT] 初始化版本隔离失败: " + e.getMessage());
            e.printStackTrace();
            globalIsolateVersion = true;
        }

        // ---------- 读取 selectedVersion ----------
        try {
            if (setup.get("selectedVersion") != null) {
                selectedVersionId = (String) setup.get("selectedVersion");
                if (chooseLabel != null) {
                    chooseLabel.setText("状态: 已加载本地版本 " + selectedVersionId);
                }
                if (lblSelectedVersion != null) {
                    lblSelectedVersion.setText(selectedVersionId);
                }
            } else {
                if (chooseLabel != null) {
                    chooseLabel.setText("状态: 准备就绪");
                }
            }
        } catch (Exception e) {
            System.err.println("[Init] 读取 selectedVersion 失败: " + e.getMessage());
        }

        // ---------- 扫描本地版本 ----------
        try {
            localVersions = VersionScanner.scan(ConfigManager.DEFAULT_MC_DIR);
        } catch (Exception e) {
            System.err.println("[Init] 扫描本地版本失败: " + e.getMessage());
            localVersions = new ArrayList<>();
        }

        // ---------- 注册玩家名称监听 ----------
        if (name != null) {
            name.textProperty().addListener((obs, oldVal, newVal) -> scheduleConfigSave());
            name.setOnAction(e -> {
                forceSaveConfigNow();
                chooseLabel.setText("状态: 玩家名称已保存");
            });
            name.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) forceSaveConfigNow();
            });
        }

        // ---------- 绑定按钮 ----------
        try {
            if (ace != null) {
                ace.setOnAction(event -> {
                    chooseLabel.setText("状态: 原版游戏");
                    loadOnlineVersions();
                });
            }
        } catch (Exception e) {
            System.err.println("[Init] 绑定 ace 失败: " + e.getMessage());
        }

        try {
            if (reloadVersionsBtn != null) {
                SVGPath refreshIcon = new SVGPath();
                refreshIcon.setContent("M512.0 838.3c-80.2 0-153.4-29.3-210.2-77.4l75.5-75.5c11.5-11.5 25.8-22.0 25.8-37.0a27.2 27.2 0 0 0-27.1-27.1H104.0c-27.1 0-27.1 23.9-27.1 27.1v271.9a27.1 27.1 0 0 0 27.1 27.1c15.0 0 27.8-16.6 42.5-31.2l77.9-77.9c76.6 67.7 177.1 108.9 287.4 108.9 221.7 0 404.5-166.0 431.2-380.6h-109.8c-25.9 154.2-159.7 271.9-321.3 271.9zM919.9 76.6c-15.0 0-27.8 16.6-42.5 31.3L799.5 185.8c-76.5-67.7-177.1-108.9-287.4-108.9-221.8 0-404.5 166.1-431.3 380.6H190.6c25.9-154.2 159.7-271.9 321.4-271.9 80.2 0 153.4 29.3 210.1 77.4l-75.5 75.5c-11.6 11.5-25.8 22.0-25.8 37.1a27.2 27.2 0 0 0 27.1 27.1h271.9c27.1 0 27.1-23.9 27.1-27.1V103.8a27.1 27.1 0 0 0-27.1-27.1z");
                refreshIcon.setFill(Color.WHITE);
                refreshIcon.setScaleX(0.018);
                refreshIcon.setScaleY(0.018);
                StackPane iconWrapper = new StackPane(refreshIcon);
                iconWrapper.setPrefSize(30, 30);
                reloadVersionsBtn.setGraphic(iconWrapper);
                reloadVersionsBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                reloadVersionsBtn.setOnAction(e -> {
                    chooseLabel.setText("状态: 正在重新加载版本列表...");
                    localVersions = VersionScanner.scan(ConfigManager.DEFAULT_MC_DIR);
                    loadOnlineVersions();
                });
            }
        } catch (Exception e) {
            System.err.println("[Init] 初始化重新加载按钮失败: " + e.getMessage());
        }

        try {
            if (Setting != null) {
                Setting.setOnAction(e -> openGameSettings());
            }
        } catch (Exception e) {
            System.err.println("[Init] 绑定设置按钮失败: " + e.getMessage());
        }

        try {
            if (start_game != null) {
                start_game.setOnAction(e -> {
                    String playerId = name.getText();
                    forceSaveConfigNow();

                    if (selectedVersionId == null || selectedVersionId.equals("未选择")) {
                        new Alert(Alert.AlertType.WARNING, "请先选择一个游戏版本！").show();
                        return;
                    }

                    chooseLabel.setText("状态: 正在启动游戏，请稍后...");
                    start_game.setDisable(true);

                    new Thread(() -> {
                        try {
                            GameLauncher.launch(selectedVersionId, playerId, ConfigManager.DEFAULT_MC_DIR, maxMemory, globalIsolateVersion);
                            Platform.runLater(() -> {
                                chooseLabel.setText("状态: 游戏已启动！");
                                start_game.setDisable(false);
                            });
                        } catch (GameLaunchException gle) {
                            Platform.runLater(() -> {
                                showCrashReportDialog(gle);
                                chooseLabel.setText("状态: 游戏崩溃（分析已展示）");
                                start_game.setDisable(false);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("启动失败");
                                alert.setHeaderText("无法启动游戏: " + ex.getMessage());
                                StringWriter sw = new StringWriter();
                                PrintWriter pw = new PrintWriter(sw);
                                ex.printStackTrace(pw);
                                TextArea textArea = new TextArea(sw.toString());
                                textArea.setEditable(false);
                                textArea.setWrapText(true);
                                alert.getDialogPane().setExpandableContent(new ScrollPane(textArea));
                                alert.show();
                                chooseLabel.setText("状态: 启动失败");
                                start_game.setDisable(false);
                            });
                        }
                    }).start();
                });
            }
        } catch (Exception e) {
            System.err.println("[Init] 绑定启动按钮失败: " + e.getMessage());
        }

        try {
            loadOnlineVersions();
        } catch (Exception e) {
            System.err.println("[Init] 首次加载在线版本失败: " + e.getMessage());
        }

        scheduler.scheduleAtFixedRate(this::checkForUpdates, 1, 30, TimeUnit.MINUTES);
        System.out.println("[Init] MainController.initialize() 完成");
    }

    /**
     * 健壮地把配置文件里的值转为 boolean。
     */
    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String) {
            String s = ((String) raw).trim().toLowerCase();
            if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        }
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        return defaultValue;
    }

    // ============================================================
    // 配置自动保存（防抖合并写入）
    // ============================================================

    private synchronized void scheduleConfigSave() {
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }
        pendingSaveTask = saveScheduler.schedule(this::doSaveConfig, 500, TimeUnit.MILLISECONDS);
    }

    private synchronized void forceSaveConfigNow() {
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
            pendingSaveTask = null;
        }
        doSaveConfig();
    }

    private void doSaveConfig() {
        try {
            Map<String, Object> setup = ConfigManager.loadSetup();

            if (name != null) {
                String playerId = name.getText().trim();
                if (!playerId.isEmpty()) {
                    setup.put("playerId", playerId);
                }
            }

            setup.put("selectedVersion", selectedVersionId);

            if (chkGlobalIsolation != null) {
                setup.put("isolateVersion", chkGlobalIsolation.isSelected());
                globalIsolateVersion = chkGlobalIsolation.isSelected();
            }

            setup.put("memory", maxMemory);

            ConfigManager.saveFullSetup(setup);
        } catch (Exception e) {
            System.err.println("[Config] 保存配置失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 崩溃报告弹窗 + 导出错误报告
    // ============================================================
    private void showCrashReportDialog(GameLaunchException gle) {
        CrashAnalyzer.CrashReport report = gle.getReport();

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("游戏崩溃了");
        alert.setHeaderText("😢 HiMi 检测到游戏异常退出\n" +
                "退出码: " + gle.getExitCode() + " · 版本: " + gle.getVersionId());

        StringBuilder content = new StringBuilder();
        for (String reason : report.reasons) {
            content.append(reason).append("\n\n");
        }
        alert.setContentText(content.toString().trim());

        TextArea textArea = new TextArea(report.detailLog.isEmpty() ? "（未捕获到详细日志）" : report.detailLog);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(25);
        textArea.setPrefColumnCount(100);
        alert.getDialogPane().setExpandableContent(new ScrollPane(textArea));

        ButtonType btnExport = new ButtonType("导出错误报告", ButtonBar.ButtonData.LEFT);
        alert.getButtonTypes().add(0, btnExport);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == btnExport) {
            exportCrashReport(gle);
        }
    }

    private void exportCrashReport(GameLaunchException gle) {
        FileChooser fc = new FileChooser();
        fc.setTitle("导出错误报告");
        fc.setInitialFileName("HiMi-Crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".zip");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip 文件", "*.zip"));
        File file = fc.showSaveDialog(null);
        if (file == null) return;

        CrashAnalyzer.CrashReport report = gle.getReport();
        String baseDir = gle.getBaseDir();
        String versionId = gle.getVersionId();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("HiMi-分析结果.txt"));
            zos.write(String.join("\n\n", report.reasons).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("系统信息.txt"));
            String sysInfo = "系统: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                    + "架构: " + System.getProperty("os.arch") + "\n"
                    + "Java 版本: " + System.getProperty("java.version") + "\n"
                    + "Java 供应商: " + System.getProperty("java.vendor") + "\n"
                    + "Java 路径: " + System.getProperty("java.home") + "\n"
                    + "启动器: HiMi 1.0\n"
                    + "游戏版本: " + versionId + "\n"
                    + "退出码: " + gle.getExitCode() + "\n";
            zos.write(sysInfo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("完整日志.txt"));
            zos.write(report.detailLog.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            addFileToZip(zos, baseDir, "versions/" + versionId + "/logs/latest.log", "latest.log");
            addFileToZip(zos, baseDir, "versions/" + versionId + "/logs/launcher.log", "launcher.log");
            File crashDir = new File(baseDir, "versions/" + versionId + "/crash-reports");
            if (crashDir.exists()) {
                File[] reports = crashDir.listFiles((d, n) -> n.endsWith(".txt"));
                if (reports != null && reports.length > 0) {
                    Arrays.sort(reports, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    addFileToZip(zos, baseDir, "versions/" + versionId + "/crash-reports/" + reports[0].getName(), "crash-report.txt");
                }
            }

            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("导出成功");
            done.setHeaderText("错误报告已导出");
            done.setContentText("文件已保存到:\n" + file.getAbsolutePath());
            done.showAndWait();

        } catch (Exception ex) {
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("导出失败");
            err.setHeaderText("无法导出错误报告");
            err.setContentText(ex.getMessage());
            err.showAndWait();
        }
    }

    private void addFileToZip(ZipOutputStream zos, String baseDir, String relativePath, String entryName) {
        File src = new File(baseDir, relativePath);
        if (!src.exists()) return;
        try (FileInputStream fis = new FileInputStream(src)) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                zos.write(buf, 0, len);
            }
            zos.closeEntry();
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 以下为常规功能
    // ============================================================
    private void openGameSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/game_setting.fxml"));
            Parent root = loader.load();

            GameSettingController controller = loader.getController();
            controller.initData(selectedVersionId, () -> {
                Map<String, Object> setup = ConfigManager.loadSetup();
                if (setup.get("memory") != null) {
                    maxMemory = ((Number) setup.get("memory")).intValue();
                }
                forceSaveConfigNow();
                chooseLabel.setText("状态: 已保存设置");
            });

            Stage stage = new Stage();
            stage.setTitle("游戏设置");
            stage.setScene(new Scene(root));
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void loadOnlineVersions() {
        categoryContainer.getChildren().clear();
        Label loading = new Label("正在获取版本列表...");
        loading.setStyle("-fx-text-fill: white;");
        categoryContainer.getChildren().add(loading);

        new Thread(() -> {
            JsonArray versions = fetchVersionsFromNetwork();
            Platform.runLater(() -> {
                categoryContainer.getChildren().clear();
                if (versions != null) {
                    ConfigManager.saveVersions(versions);
                    onlineVersionsCache = versions;
                    extractLatestVersions(versions);
                    buildCategoryUI(versions);
                    chooseLabel.setText("状态: 版本列表已更新");
                } else {
                    JsonArray cachedVersions = ConfigManager.loadVersions();
                    if (cachedVersions != null) {
                        buildCategoryUI(cachedVersions);
                        chooseLabel.setText("状态: 网络异常，已加载本地版本缓存");
                    } else {
                        Label error = new Label("获取版本列表失败，且无本地缓存！");
                        error.setStyle("-fx-text-fill: #ff5555; -fx-font-size: 16px; -fx-padding: 20;");
                        categoryContainer.getChildren().add(error);
                        chooseLabel.setText("状态: 网络连接失败");
                    }
                }
            });
        }).start();
    }

    private void checkForUpdates() {
        JsonArray newVersions = fetchVersionsFromNetwork();
        if (newVersions == null) return;
        String newRelease = "", newSnapshot = "";
        for (JsonElement element : newVersions) {
            JsonObject v = element.getAsJsonObject();
            String type = v.get("type").getAsString();
            String id = v.get("id").getAsString();
            if ("release".equals(type) && newRelease.isEmpty()) newRelease = id;
            else if ("snapshot".equals(type) && newSnapshot.isEmpty()) newSnapshot = id;
            if (!newRelease.isEmpty() && !newSnapshot.isEmpty()) break;
        }
        boolean hasNew = false;
        StringBuilder msg = new StringBuilder();
        if (!newRelease.equals(currentLatestRelease) && !currentLatestRelease.isEmpty()) {
            msg.append("发布正式版: ").append(newRelease).append("\n");
            currentLatestRelease = newRelease; hasNew = true;
        }
        if (!newSnapshot.equals(currentLatestSnapshot) && !currentLatestSnapshot.isEmpty()) {
            msg.append("发布快照版: ").append(newSnapshot).append("\n");
            currentLatestSnapshot = newSnapshot; hasNew = true;
        }
        if (hasNew) {
            onlineVersionsCache = newVersions;
            Platform.runLater(() -> {
                chooseLabel.setText("状态: 发现新版本！");
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("更新提示");
                alert.setHeaderText("检测到 Minecraft 版本更新");
                alert.setContentText(msg.toString() + "已为您自动更新列表！");
                alert.show();
                buildCategoryUI(newVersions);
            });
        }
    }

    private JsonArray fetchVersionsFromNetwork() {
        String[] urls = {
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        };
        for (String url : urls) {
            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        return JsonParser.parseString(response.body().string()).getAsJsonObject().getAsJsonArray("versions");
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void extractLatestVersions(JsonArray versions) {
        for (JsonElement element : versions) {
            JsonObject v = element.getAsJsonObject();
            String type = v.get("type").getAsString();
            String id = v.get("id").getAsString();
            if ("release".equals(type) && currentLatestRelease.isEmpty()) currentLatestRelease = id;
            else if ("snapshot".equals(type) && currentLatestSnapshot.isEmpty()) currentLatestSnapshot = id;
            if (!currentLatestRelease.isEmpty() && !currentLatestSnapshot.isEmpty()) return;
        }
    }

    private void buildCategoryUI(JsonArray versions) {
        categoryContainer.getChildren().clear();

        List<JsonObject> releases = new ArrayList<>(), snapshots = new ArrayList<>(), olds = new ArrayList<>(), fools = new ArrayList<>();
        Set<String> FOOL_VERSIONS = Set.of("2.0", "15w14a", "1.rv-pre1", "3d shareware v1.34", "20w14infinite", "22w13oneblockatatime", "23w13a_or_b", "23w13aor_b", "24w14potato", "25w14craftmine", "26w14a");

        for (JsonElement element : versions) {
            JsonObject v = element.getAsJsonObject();
            String type = v.get("type").getAsString(), id = v.get("id").getAsString(), lowerId = id.toLowerCase();
            if (FOOL_VERSIONS.contains(lowerId)) fools.add(v);
            else if ("release".equals(type)) releases.add(v);
            else if ("snapshot".equals(type)) snapshots.add(v);
            else if (id.startsWith("a") || id.startsWith("b") || "old_beta".equals(type) || "old_alpha".equals(type)) olds.add(v);
        }

        VBox latestBox = createCategoryBox("最新版本");
        VBox latestContent = new VBox(5);

        if (!releases.isEmpty()) latestContent.getChildren().add(createVersionItem(releases.get(0), "最新正式版"));
        if (!snapshots.isEmpty()) latestContent.getChildren().add(createVersionItem(snapshots.get(0), "最新预览版"));

        latestBox.getChildren().add(latestContent);
        categoryContainer.getChildren().add(latestBox);

        categoryContainer.getChildren().add(createCollapsibleCategory("正式版", releases, false));
        categoryContainer.getChildren().add(createCollapsibleCategory("预览版", snapshots, false));
        categoryContainer.getChildren().add(createCollapsibleCategory("远古版", olds, false));
        if (!fools.isEmpty()) categoryContainer.getChildren().add(createCollapsibleCategory("愚人节版", fools, false));
    }

    private VBox createCategoryBox(String title) {
        VBox box = new VBox(10);
        box.setStyle("-fx-background-color: #3b3b3b; -fx-background-radius: 8; -fx-padding: 15;");
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");
        box.getChildren().add(titleLabel);
        return box;
    }

    // 🚨 修复：HBox 包裹标题 + 撑满宽度 + 三角箭头
    private VBox createCollapsibleCategory(String title, List<JsonObject> versionList, boolean isExpanded) {
        VBox box = new VBox(10);
        box.setStyle("-fx-background-color: #3b3b3b; -fx-background-radius: 8; -fx-padding: 15;");

        Label arrow = new Label(isExpanded ? "▼" : "▶");
        arrow.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px; -fx-font-family: monospace;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");

        HBox header = new HBox(8, arrow, titleLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);
        header.setPickOnBounds(true);
        header.setStyle("-fx-cursor: hand; -fx-padding: 3 0 3 0;");
        HBox.setHgrow(header, Priority.ALWAYS);

        VBox contentBox = new VBox(5);
        contentBox.setVisible(isExpanded);
        contentBox.setManaged(isExpanded);

        for (JsonObject v : versionList) {
            contentBox.getChildren().add(createVersionItem(v, ""));
        }

        header.setOnMouseClicked(e -> {
            boolean newVisible = !contentBox.isVisible();
            contentBox.setVisible(newVisible);
            contentBox.setManaged(newVisible);
            arrow.setText(newVisible ? "▼" : "▶");
        });

        box.getChildren().addAll(header, contentBox);
        return box;
    }

    private Button createVersionItem(JsonObject v, String tag) {
        String id = v.get("id").getAsString(), type = v.get("type").getAsString(), displayName = id + (tag.isEmpty() ? " (" + type + ")" : " - " + tag);
        Button btn = new Button(displayName);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dddddd; -fx-font-size: 14px; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dddddd; -fx-font-size: 14px;"));
        btn.setOnAction(e -> { chooseLabel.setText("状态: 准备下载 " + id); openDownloadWindow(id); });
        return btn;
    }

    private void openDownloadWindow(String versionId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/download_player.fxml"));
            Parent root = loader.load();
            DownloadPlayerController controller = loader.getController();
            controller.setVersion(versionId);
            Stage stage = new Stage();
            stage.setTitle("下载游戏 - " + versionId);
            stage.setScene(new Scene(root));
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void openVersionManager() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/set_play.fxml"));
            Parent root = loader.load();
            VersionSelectController controller = loader.getController();
            controller.setOnSelected(selectedVersion -> {
                selectedVersionId = selectedVersion.getId();
                if (chooseLabel != null) chooseLabel.setText("状态: 已选择本地版本 " + selectedVersionId);
                if (lblSelectedVersion != null) lblSelectedVersion.setText(selectedVersionId);
                forceSaveConfigNow();
            });
            Stage stage = new Stage();
            stage.setTitle("版本管理器");
            stage.setScene(new Scene(root));
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @FXML
    public void selectVersion() {
        openVersionManager();
    }

    public String getNameFieldValue() {
        return name != null ? name.getText() : "Steve";
    }

    public String getSelectedVersion() {
        return selectedVersionId;
    }

    private void openModDownloadWindow() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/mod_download.fxml"));
            Parent root = loader.load();
            Stage stage = new Stage();
            stage.setTitle("HiMi - 模组下载");
            stage.setScene(new Scene(root));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.show();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}