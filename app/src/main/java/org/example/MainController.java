package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
// 🚨 注意这里的导入：必须是 javafx.scene.control.*
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainController {

    @FXML private Label chooseLabel;
    @FXML private Button ace;
    @FXML private VBox categoryContainer;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private String currentLatestRelease = "";
    private String currentLatestSnapshot = "";
    private JsonArray onlineVersionsCache = null;
    private List<MinecraftVersion> localVersions = new ArrayList<>();
    private String selectedVersionId = "未选择";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        chooseLabel.setText("状态: 准备就绪");
        
        String mcDirPath = System.getProperty("user.home") + "\\.minecraft"; 
        localVersions = VersionScanner.scan(mcDirPath);

        ace.setOnAction(event -> {
            chooseLabel.setText("状态: 原版游戏");
            loadOnlineVersions();
        });

        loadOnlineVersions();
        scheduler.scheduleAtFixedRate(this::checkForUpdates, 1, 30, TimeUnit.MINUTES);
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
                    onlineVersionsCache = versions;
                    extractLatestVersions(versions); 
                    buildCategoryUI(versions);
                    chooseLabel.setText("状态: 版本列表已加载");
                } else {
                    Label error = new Label("获取版本列表失败，请检查网络连接！");
                    error.setStyle("-fx-text-fill: #ff5555; -fx-font-size: 16px; -fx-padding: 20;");
                    categoryContainer.getChildren().add(error);
                    chooseLabel.setText("状态: 网络连接失败");
                }
            });
        }).start();
    }

    private void checkForUpdates() {
        JsonArray newVersions = fetchVersionsFromNetwork();
        if (newVersions == null) return;

        String newRelease = "";
        String newSnapshot = "";
        
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
            currentLatestRelease = newRelease;
            hasNew = true;
        }
        if (!newSnapshot.equals(currentLatestSnapshot) && !currentLatestSnapshot.isEmpty()) {
            msg.append("发布快照版: ").append(newSnapshot).append("\n");
            currentLatestSnapshot = newSnapshot;
            hasNew = true;
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

            if ("release".equals(type) && currentLatestRelease.isEmpty()) {
                currentLatestRelease = id;
            } else if ("snapshot".equals(type) && currentLatestSnapshot.isEmpty()) {
                currentLatestSnapshot = id;
            }
            if (!currentLatestRelease.isEmpty() && !currentLatestSnapshot.isEmpty()) return;
        }
    }

        private void buildCategoryUI(JsonArray versions) {
        List<JsonObject> releases = new ArrayList<>();
        List<JsonObject> snapshots = new ArrayList<>();
        List<JsonObject> olds = new ArrayList<>();
        List<JsonObject> fools = new ArrayList<>(); 

        // 🎯 精确的愚人节版本白名单（全部转为小写，防止匹配失败）
        Set<String> FOOL_VERSIONS = Set.of(
                "2.0", "15w14a", "1.rv-pre1", "3d shareware v1.34", 
                "20w14infinite", "22w13oneblockatatime", "23w13a_or_b", "23w13aor_b",
                "24w14potato", "25w14craftmine", "26w14a"
        );

        for (JsonElement element : versions) {
            JsonObject v = element.getAsJsonObject();
            String type = v.get("type").getAsString();
            String id = v.get("id").getAsString();
            
            // 🚨 核心修复：把 ID 统一转为小写再对比
            String lowerId = id.toLowerCase();

            // 精确白名单匹配
            if (FOOL_VERSIONS.contains(lowerId)) {
                fools.add(v);
            } else if ("release".equals(type)) {
                releases.add(v);
            } else if ("snapshot".equals(type)) {
                snapshots.add(v);
            } else if (id.startsWith("a") || id.startsWith("b") || "old_beta".equals(type) || "old_alpha".equals(type)) {
                olds.add(v);
            }
        }

        // 1. 构建“最新版”模块
        VBox latestBox = createCategoryBox("最新版本");
        VBox latestContent = new VBox(5);
        if (!releases.isEmpty()) latestContent.getChildren().add(createVersionItem(releases.get(0), "最新正式版"));
        if (!snapshots.isEmpty()) latestContent.getChildren().add(createVersionItem(snapshots.get(0), "最新预览版"));
        latestBox.getChildren().add(latestContent);
        categoryContainer.getChildren().add(latestBox);

        // 2. 构建其他分类模块
        categoryContainer.getChildren().add(createCollapsibleCategory("正式版", releases, false));
        categoryContainer.getChildren().add(createCollapsibleCategory("预览版", snapshots, false));
        categoryContainer.getChildren().add(createCollapsibleCategory("远古版", olds, false));
        
        // 只有确实有愚人节版本时才显示这个分类
        if (!fools.isEmpty()) {
            categoryContainer.getChildren().add(createCollapsibleCategory("愚人节版", fools, false));
        }
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

        box.setOnMouseClicked(e -> {
            contentBox.setVisible(!contentBox.isVisible());
            contentBox.setManaged(contentBox.isVisible());
        });
        box.getChildren().add(contentBox);
        return box;
    }

    private Button createVersionItem(JsonObject v, String tag) {
        String id = v.get("id").getAsString();
        String type = v.get("type").getAsString();
        String displayName = id + (tag.isEmpty() ? " (" + type + ")" : " - " + tag);
        
        Button btn = new Button(displayName);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dddddd; -fx-font-size: 14px; -fx-cursor: hand;");
        
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dddddd; -fx-font-size: 14px;"));

        btn.setOnAction(e -> {
            chooseLabel.setText("状态: 准备下载 " + id);
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("你点击了下载版本: " + id + "\n（下载逻辑待开发）");
            alert.show();
        });
        return btn;
    }

    @FXML
    public void selectVersion() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("选择本地版本");
        dialog.setHeaderText("请选择一个已经下载到本地的版本");

        ListView<String> listView = new ListView<>();
        for (MinecraftVersion v : localVersions) {
            listView.getItems().add(v.getId() + " (" + v.getType() + ")");
        }
        if (!localVersions.isEmpty()) {
            listView.getSelectionModel().select(0);
        }

        dialog.getDialogPane().setContent(listView);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return listView.getSelectionModel().getSelectedItem();
            }
            return null;
        });

        dialog.showAndWait().ifPresent(selected -> {
            selectedVersionId = selected;
            chooseLabel.setText("状态: 已选择本地版本 " + selected);
        });
    }
}