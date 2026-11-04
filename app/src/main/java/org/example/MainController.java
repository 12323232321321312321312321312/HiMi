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
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MainController {

    @FXML private Label chooseLabel;
    @FXML private Button ace;
    @FXML private VBox categoryContainer;
    @FXML private Button Setting;
    
    @FXML private TextField name; 
    @FXML private Button start_game; 
    @FXML private Label lblSelectedVersion;
    @FXML private Button choose;
    @FXML private CheckBox chkGlobalIsolation;

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

    // 🚨 定时器：定期检查版本列表更新
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    // 🚨 独立保存定时器：用于防抖合并配置保存
    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ConfigSaveScheduler");
        return t;
    });
    private ScheduledFuture<?> pendingSaveTask = null;

    @FXML
    public void initialize() {
        ConfigManager.init();
        
        Map<String, Object> setup = ConfigManager.loadSetup();
        
        // ---------- 读取初始配置 ----------
        if (setup.get("playerId") != null && name != null) {
            name.setText((String) setup.get("playerId"));
        }
        
        if (setup.get("memory") != null) {
            maxMemory = ((Number) setup.get("memory")).intValue();
        }
        
        if (setup.get("isolateVersion") != null) {
            globalIsolateVersion = (Boolean) setup.get("isolateVersion");
        } else {
            ConfigManager.updateSingleConfig("isolateVersion", true);
            globalIsolateVersion = true;
        }
        
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

        localVersions = VersionScanner.scan(ConfigManager.DEFAULT_MC_DIR);

        // ---------- 🚨 注册所有"配置变化点"的自动保存监听器 ----------
        
        // 1. 玩家名称实时监听（防抖后自动保存）
        if (name != null) {
            name.textProperty().addListener((obs, oldVal, newVal) -> {
                scheduleConfigSave();
            });
            // 回车时立即保存（不等防抖）
            name.setOnAction(e -> {
                forceSaveConfigNow();
                chooseLabel.setText("状态: 玩家名称已保存");
            });
            // 失焦时立即保存
            name.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    forceSaveConfigNow();
                }
            });
        }
        
        // 2. 版本隔离复选框监听
        if (chkGlobalIsolation != null) {
            chkGlobalIsolation.setSelected(globalIsolateVersion);
            chkGlobalIsolation.selectedProperty().addListener((obs, oldVal, newVal) -> {
                globalIsolateVersion = newVal;
                scheduleConfigSave();
                chooseLabel.setText("状态: 版本隔离已" + (newVal ? "开启" : "关闭"));
            });
        }

        // ---------- 按钮绑定 ----------
        
        if (ace != null) {
            ace.setOnAction(event -> {
                chooseLabel.setText("状态: 原版游戏");
                loadOnlineVersions();
            });
        }
        
        if (Setting != null) {
            Setting.setOnAction(e -> openGameSettings());
        }
        
        if (start_game != null) {
            start_game.setOnAction(e -> {
                String playerId = name.getText(); 
                
                // 🚨 启动前强制保存一次，作为兜底
                forceSaveConfigNow();

                if (selectedVersionId == null || selectedVersionId.equals("未选择")) {
                    Alert alert = new Alert(Alert.AlertType.WARNING, "请先选择一个游戏版本！");
                    alert.show();
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

        // ---------- 🚨 首次加载在线版本（不触发保存） ----------
        loadOnlineVersions();
        
        // 每 30 分钟检查更新（不触发保存）
        scheduler.scheduleAtFixedRate(this::checkForUpdates, 1, 30, TimeUnit.MINUTES);
    }

    // ============================================================
    // 🚨 核心：配置自动保存机制（防抖合并写入）
    // ============================================================

    /**
     * 触发配置保存（防抖：500ms 内的多次触发会合并成一次写入）
     * 避免用户每敲一个字符就写一次硬盘
     */
    private synchronized void scheduleConfigSave() {
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }
        pendingSaveTask = saveScheduler.schedule(this::doSaveConfig, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 立即强制保存配置（不走防抖），用于回车、失焦、启动前等重要时机
     */
    private synchronized void forceSaveConfigNow() {
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
            pendingSaveTask = null;
        }
        doSaveConfig();
    }

    /**
     * 实际执行保存：读取完整配置，只更新 UI 相关的字段，然后整体写回
     */
    private void doSaveConfig() {
        try {
            Map<String, Object> setup = ConfigManager.loadSetup();
            
            // 玩家名称
            if (name != null) {
                String playerId = name.getText().trim();
                if (!playerId.isEmpty()) {
                    setup.put("playerId", playerId);
                }
            }
            
            // 已选版本
            setup.put("selectedVersion", selectedVersionId);
            
            // 版本隔离
            if (chkGlobalIsolation != null) {
                setup.put("isolateVersion", chkGlobalIsolation.isSelected());
                globalIsolateVersion = chkGlobalIsolation.isSelected();
            }
            
            // 内存（如果游戏设置窗口改过，这里从内存变量同步，避免被 UI 未更新覆盖）
            setup.put("memory", maxMemory);
            
            ConfigManager.saveFullSetup(setup);
            System.out.println("[Config] 配置已自动保存");
        } catch (Exception e) {
            System.err.println("[Config] 保存配置失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============================================================
    // 以下方法与上一版保持一致（不涉及配置保存）
    // ============================================================

    private void openGameSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/game_setting.fxml"));
            Parent root = loader.load();
            
            GameSettingController controller = loader.getController();
            controller.initData(selectedVersionId, () -> {
                // 🚨 游戏设置窗口关闭后，重新读取可能变化的配置，并保存一次
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
                    ConfigManager.saveVersions(versions);  // 🚨 注意：这是 version.json，不是 Setup.json
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
                buildCategoryUI(newVersions);   // 🚨 只刷新 UI，不触发 Setup.json 保存
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

    private VBox createCollapsibleCategory(String title, List<JsonObject> versionList, boolean isExpanded) {
        VBox box = createCategoryBox(title); 
        VBox contentBox = new VBox(5); 
        contentBox.setVisible(isExpanded); 
        contentBox.setManaged(isExpanded);
        
        for (JsonObject v : versionList) {
            contentBox.getChildren().add(createVersionItem(v, ""));
        }
        
        Label titleLabel = (Label) box.getChildren().get(0);
        titleLabel.setStyle("-fx-cursor: hand;"); 
        titleLabel.setOnMouseClicked(e -> { 
            contentBox.setVisible(!contentBox.isVisible()); 
            contentBox.setManaged(contentBox.isVisible()); 
        });
        
        box.getChildren().add(contentBox); 
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
                // 🚨 版本选择变化也立即保存
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
}