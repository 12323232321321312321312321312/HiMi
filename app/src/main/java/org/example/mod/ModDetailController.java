package org.example.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.config.ConfigManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ModDetailController {

    @FXML private ImageView iconView;
    @FXML private Label titleLabel, authorLabel, descLabel, metaLabel, statusLabel;
    @FXML private ComboBox<String> gvFilter, loaderFilter;
    @FXML private Button applyFilterBtn, downloadBtn, closeBtn;
    @FXML private ListView<ModVersion> versionListView;

    private ModProject project;
    private final ObservableList<ModVersion> versions = FXCollections.observableArrayList();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

    @FXML
    public void initialize() {
        versionListView.setItems(versions);
        versionListView.setCellFactory(lv -> new VersionCell());
        applyFilterBtn.setOnAction(e -> loadVersions());
        downloadBtn.setOnAction(e -> onDownload());
        closeBtn.setOnAction(e -> ((Stage) closeBtn.getScene().getWindow()).close());
    }

    public void setProject(ModProject p, String defaultGv, String defaultLoader) {
        this.project = p;

        String displayTitle = (p.translatedTitle != null && !p.translatedTitle.isEmpty()) ? p.translatedTitle : p.title;
        String displayDesc = (p.translatedDesc != null && !p.translatedDesc.isEmpty()) ? p.translatedDesc : p.description;

        titleLabel.setText(displayTitle);
        authorLabel.setText("Project: " + p.id);
        descLabel.setText(displayDesc);
        metaLabel.setText("⬇ " + formatNumber(p.downloads) + " · 更新于 " + p.dateModified);

        if (p.iconUrl != null && !p.iconUrl.isEmpty()) loadIconAsync(p.iconUrl);

        gvFilter.getItems().add("全部");
        gvFilter.getItems().addAll(p.gameVersions);
        gvFilter.setValue(defaultGv != null && !defaultGv.isEmpty() ? defaultGv : "全部");

        loaderFilter.getItems().addAll("全部", "fabric", "forge", "neoforge", "quilt");
        loaderFilter.setValue(defaultLoader != null && !defaultLoader.isEmpty() ? defaultLoader : "全部");

        loadVersions();
    }

    private String getComboValue(ComboBox<String> combo) {
        String text = combo.isEditable() ? combo.getEditor().getText() : combo.getValue();
        if (text == null || text.trim().isEmpty() || "全部".equals(text.trim())) return "";
        return text.trim();
    }

    private void loadVersions() {
        statusLabel.setText("加载版本列表...");
        versions.clear();

        String gv = getComboValue(gvFilter);
        String loader = getComboValue(loaderFilter);

        new Thread(() -> {
            try {
                List<ModVersion> list = new ArrayList<>();
                if (project.platform == ModProject.Platform.MODRINTH) {
                    List<String> gvs = gv.isEmpty() ? null : List.of(gv);
                    List<String> loaders = loader.isEmpty() ? null : List.of(loader);
                    JsonArray arr = ModrinthAPI.getProjectVersions(project.id, gvs, loaders);
                    for (var e : arr) list.add(ModVersion.fromModrinthJson(e.getAsJsonObject()));
                } else {
                    int cfLoader = loader.isEmpty() ? 0 : getCfLoaderType(loader);
                    JsonArray arr = CurseForgeAPI.getModFiles(project.id, gv.isEmpty() ? null : gv, cfLoader);
                    for (var e : arr) list.add(ModVersion.fromCurseForgeJson(e.getAsJsonObject()));
                }

                Platform.runLater(() -> {
                    versions.setAll(list);
                    statusLabel.setText("共 " + list.size() + " 个版本");
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("加载失败: " + ex.getMessage()));
            }
        }).start();
    }

    // ================== 🚨 核心逻辑：点击查看依赖 ==================
    private void showDependenciesOnly(ModVersion selected) {
        statusLabel.setText("正在获取依赖详情...");
        new Thread(() -> {
            ModVersion fullVersion = fetchFullVersionData(selected);
            Node uiContent = buildDependencyUI(fullVersion);
            
            Platform.runLater(() -> {
                statusLabel.setText("");
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("依赖详情");
                alert.setHeaderText("模组：" + project.title + "\n版本：" + selected.name);
                alert.getDialogPane().setContent(wrapInScrollPane(uiContent));
                alert.show();
            });
        }).start();
    }

    // ================== 核心逻辑：点击下载 ==================
    private void onDownload() {
        ModVersion selected = versionListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "请先选择一个版本").show();
            return;
        }
        if (selected.files.isEmpty() && project.platform == ModProject.Platform.MODRINTH) {
            new Alert(Alert.AlertType.WARNING, "该版本没有可下载文件").show();
            return;
        }

        downloadBtn.setDisable(true);
        statusLabel.setText("正在解析依赖...");

        new Thread(() -> {
            ModVersion fullVersion = fetchFullVersionData(selected);
            Node uiContent = buildDependencyUI(fullVersion);

            Platform.runLater(() -> {
                downloadBtn.setDisable(false);
                statusLabel.setText("");
                
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("确认下载");
                confirm.setHeaderText("即将下载：" + project.title + "\n版本：" + selected.name);
                
                confirm.getDialogPane().setContent(wrapInScrollPane(uiContent));
                confirm.getButtonTypes().addAll(ButtonType.YES, ButtonType.NO);

                if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;

                startDownloadProcess(fullVersion);
            });
        }).start();
    }

    // 将 Node 包装进带滚动条的容器
    private ScrollPane wrapInScrollPane(Node content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(350);
        scrollPane.setPrefWidth(500);
        scrollPane.setStyle("-fx-background-color: white; -fx-background: white;");
        return scrollPane;
    }

    // 提取公共方法：获取完整的版本数据
    private ModVersion fetchFullVersionData(ModVersion selected) {
        if (project.platform == ModProject.Platform.CURSEFORGE) {
            try {
                JsonObject fileObj = CurseForgeAPI.getModFile(project.id, selected.id);
                if (fileObj != null) {
                    ModVersion fullVersion = ModVersion.fromCurseForgeJson(fileObj);
                    fetchDependencyDetails(fullVersion);
                    return fullVersion;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            fetchDependencyDetails(selected);
        }
        return selected;
    }

    // 获取依赖的详细信息（包含图标、简介，构建完整的 ModProject 用于跳转）
    private void fetchDependencyDetails(ModVersion version) {
        for (ModDependency d : version.dependencies) {
            if (d.projectId != null && !d.projectId.isEmpty() && !"0".equals(d.projectId)) {
                try {
                    if (project.platform == ModProject.Platform.CURSEFORGE) {
                        JsonObject modObj = CurseForgeAPI.getMod(d.projectId);
                        if (modObj != null) {
                            d.projectName = modObj.has("name") ? modObj.get("name").getAsString() : "未知模组";
                            
                            ModProject mp = new ModProject();
                            mp.platform = ModProject.Platform.CURSEFORGE;
                            mp.id = d.projectId;
                            mp.title = d.projectName;
                            mp.description = modObj.has("summary") ? modObj.get("summary").getAsString() : "";
                            mp.downloads = modObj.has("downloadCount") ? modObj.get("downloadCount").getAsLong() : 0;
                            if (modObj.has("logo") && modObj.getAsJsonObject("logo").has("thumbnailUrl")) {
                                mp.iconUrl = modObj.getAsJsonObject("logo").get("thumbnailUrl").getAsString();
                            }
                            d.targetProject = mp;
                        }
                    } else {
                        JsonObject modObj = ModrinthAPI.getProject(d.projectId);
                        if (modObj != null) {
                            d.projectName = modObj.has("title") ? modObj.get("title").getAsString() : "未知模组";
                            
                            ModProject mp = new ModProject();
                            mp.platform = ModProject.Platform.MODRINTH;
                            mp.id = d.projectId;
                            mp.title = d.projectName;
                            mp.description = modObj.has("description") ? modObj.get("description").getAsString() : "";
                            mp.downloads = modObj.has("downloads") ? modObj.get("downloads").getAsLong() : 0;
                            mp.iconUrl = modObj.has("icon_url") ? modObj.get("icon_url").getAsString() : "";
                            d.targetProject = mp;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    // ================== 🚨 构建可点击的 UI ==================
    private VBox buildDependencyUI(ModVersion selected) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: white;");

        List<ModDependency> required = new ArrayList<>();
        List<ModDependency> optional = new ArrayList<>();
        List<ModDependency> incompatible = new ArrayList<>();
        List<ModDependency> others = new ArrayList<>();

        for (ModDependency d : selected.dependencies) {
            switch (d.dependencyType) {
                case "required": required.add(d); break;
                case "optional": optional.add(d); break;
                case "incompatible": incompatible.add(d); break;
                default: others.add(d); break;
            }
        }

        if (required.isEmpty() && optional.isEmpty() && incompatible.isEmpty() && others.isEmpty()) {
            Label lbl = new Label("✅ 该版本没有任何依赖。");
            lbl.setStyle("-fx-text-fill: #333; -fx-font-size: 14px;");
            root.getChildren().add(lbl);
            return root;
        }

        if (!required.isEmpty()) {
            root.getChildren().add(createCategoryLabel("🟥 必须安装 (Required)："));
            for (ModDependency d : required) root.getChildren().add(createDepNode(d));
        }
        if (!optional.isEmpty()) {
            root.getChildren().add(createCategoryLabel("🟨 可选安装 (Optional)："));
            for (ModDependency d : optional) root.getChildren().add(createDepNode(d));
        }
        if (!incompatible.isEmpty()) {
            root.getChildren().add(createCategoryLabel("🚫 不兼容 (Incompatible)："));
            for (ModDependency d : incompatible) root.getChildren().add(createDepNode(d));
        }
        if (!others.isEmpty()) {
            root.getChildren().add(createCategoryLabel("📦 内嵌/工具/其他依赖："));
            for (ModDependency d : others) root.getChildren().add(createDepNode(d));
        }
        return root;
    }

    private Label createCategoryLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        return label;
    }

    private Node createDepNode(ModDependency d) {
        // 特殊依赖（特定文件/版本）无法跳转，只能显示文字
        if (d.targetProject == null) {
            Label lbl = new Label("  • " + getDepDisplayName(d));
            lbl.setStyle("-fx-text-fill: #666; -fx-font-size: 13px;");
            return lbl;
        }

        // 普通依赖，创建超链接
        Hyperlink link = new Hyperlink("  • " + getDepDisplayName(d));
        link.setStyle("-fx-text-fill: #2196F3; -fx-font-size: 13px; -fx-underline: true; -fx-padding: 0; -fx-cursor: hand;");
        
        // 🚨 点击跳转到新窗口
        link.setOnAction(e -> openDependencyDetailWindow(d.targetProject));
        
        return link;
    }

    private void openDependencyDetailWindow(ModProject targetProject) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/mod_detail.fxml"));
            Parent root = loader.load();
            ModDetailController controller = loader.getController();
            controller.setProject(targetProject, "", ""); // 传入空字符串表示不预设过滤
            
            Stage stage = new Stage();
            stage.setTitle(targetProject.title + " - HiMi");
            stage.setScene(new Scene(root));
            // 不用模态，这样你可以同时开着好几个详情页对照查看
            stage.initModality(Modality.NONE); 
            stage.show();
        } catch (Exception ex) {
            ex.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "无法打开依赖详情: " + ex.getMessage()).show();
        }
    }

    private String getDepDisplayName(ModDependency d) {
        if ("0".equals(d.projectId) && d.fileId != null) {
            return "特定文件 (需手动前往网页下载) - FileID: " + d.fileId;
        } else if ((d.projectId == null || d.projectId.isEmpty()) && d.versionId != null) {
            return "特定版本 (需手动前往网页下载) - VersionID: " + d.versionId;
        }
        String name = d.projectName != null ? d.projectName : "未知模组";
        return name + " (ID: " + d.projectId + ")";
    }

    private void startDownloadProcess(ModVersion selected) {
        Map<String, Object> setup = ConfigManager.loadSetup();
        boolean isolate = Boolean.TRUE.equals(setup.get("isolateVersion"));
        String baseDir = ConfigManager.DEFAULT_MC_DIR;
        String versionId = (String) setup.get("selectedVersion");
        File modsDir = isolate ? new File(baseDir, "versions/" + versionId + "/mods") : new File(baseDir, "mods");
        modsDir.mkdirs();

        new Thread(() -> {
            try {
                if (project.platform == ModProject.Platform.MODRINTH) {
                    downloadFile(selected.files.get(0), modsDir);
                    for (ModDependency dep : selected.dependencies) {
                        if ("required".equals(dep.dependencyType)) downloadModrinthDependency(dep, modsDir, getComboValue(gvFilter), getComboValue(loaderFilter));
                    }
                } else {
                    String url = selected.files.isEmpty() ? null : selected.files.get(0).url;
                    if (url == null || url.isEmpty()) url = CurseForgeAPI.getDownloadUrl(project.id, selected.id);
                    if (url == null || url.isEmpty()) throw new Exception("无法获取 CF 下载链接");
                    
                    ModFile cfFile = new ModFile();
                    cfFile.url = url;
                    cfFile.filename = selected.versionNumber;
                    downloadFile(cfFile, modsDir);

                    for (ModDependency dep : selected.dependencies) {
                        if ("required".equals(dep.dependencyType)) downloadCfDependency(dep, modsDir, getComboValue(gvFilter), getComboValue(loaderFilter));
                    }
                }

                Platform.runLater(() -> {
                    statusLabel.setText("下载完成！");
                    new Alert(Alert.AlertType.INFORMATION, "下载完成！\n已安装到:\n" + modsDir.getAbsolutePath()).show();
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("下载失败: " + ex.getMessage()));
            }
        }).start();
    }

    private void downloadModrinthDependency(ModDependency dep, File modsDir, String gv, String loader) throws Exception {
        if (dep.projectId == null || dep.projectId.isEmpty()) {
            if (dep.versionId != null && !dep.versionId.isEmpty()) {
                String url = "https://api.modrinth.com/v2/version/" + dep.versionId;
                Request req = new Request.Builder().url(url).header("User-Agent", "HiMi/1.0.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JsonObject vObj = com.google.gson.JsonParser.parseString(resp.body().string()).getAsJsonObject();
                        ModVersion v = ModVersion.fromModrinthJson(vObj);
                        if (!v.files.isEmpty()) downloadFile(v.files.get(0), modsDir);
                    }
                }
            }
            return;
        }
        List<String> gvs = gv.isEmpty() ? null : List.of(gv);
        List<String> loaders = loader.isEmpty() ? null : List.of(loader);
        JsonArray arr = ModrinthAPI.getProjectVersions(dep.projectId, gvs, loaders);
        if (arr.size() == 0) arr = ModrinthAPI.getProjectVersions(dep.projectId, null, null);
        if (arr.size() == 0) throw new Exception("找不到依赖 " + dep.projectId);
        ModVersion v = ModVersion.fromModrinthJson(arr.get(0).getAsJsonObject());
        if (!v.files.isEmpty()) downloadFile(v.files.get(0), modsDir);
    }

    private void downloadCfDependency(ModDependency dep, File modsDir, String gv, String loader) throws Exception {
        if ("0".equals(dep.projectId)) {
            if (dep.fileId != null) {
                Platform.runLater(() -> statusLabel.setText("跳过特定文件依赖 (需手动下载) ID: " + dep.fileId));
            }
            return;
        }
        int cfLoader = loader.isEmpty() ? 0 : getCfLoaderType(loader);
        JsonArray arr = CurseForgeAPI.getModFiles(dep.projectId, gv.isEmpty() ? null : gv, cfLoader);
        if (arr.size() == 0) arr = CurseForgeAPI.getModFiles(dep.projectId, null, 0);
        if (arr.size() == 0) throw new Exception("找不到 CF 依赖 " + dep.projectId);
        
        ModVersion v = ModVersion.fromCurseForgeJson(arr.get(0).getAsJsonObject());
        String url = v.files.isEmpty() ? null : v.files.get(0).url;
        if (url == null || url.isEmpty()) url = CurseForgeAPI.getDownloadUrl(dep.projectId, v.id);
        
        if (url != null && !url.isEmpty()) {
            ModFile f = new ModFile();
            f.url = url;
            f.filename = v.versionNumber;
            downloadFile(f, modsDir);
        }
    }

    private void downloadFile(ModFile file, File targetDir) throws Exception {
        File target = new File(targetDir, file.filename);
        if (target.exists() && target.length() == file.size) return;

        Platform.runLater(() -> statusLabel.setText("下载中: " + file.filename));
        Request req = new Request.Builder().url(file.url).header("User-Agent", "HiMi/1.0.0").build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
            try (InputStream is = resp.body().byteStream(); FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
        }
    }

    private static int getCfLoaderType(String loader) {
        switch (loader) {
            case "forge": return 1;
            case "fabric": return 4;
            case "quilt": return 5;
            case "neoforge": return 6;
            default: return 0;
        }
    }

    private void loadIconAsync(String url) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(url).build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        Image img = new Image(resp.body().byteStream(), 80, 80, true, true);
                        Platform.runLater(() -> iconView.setImage(img));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ================== UI 单元格 ==================
    private class VersionCell extends ListCell<ModVersion> {
        private final HBox box = new HBox(10);
        private final VBox info = new VBox(3);
        private final Label nameLabel = new Label(), typeLabel = new Label(), gvLabel = new Label(), dateLabel = new Label();
        private final Hyperlink depLink = new Hyperlink();

        VersionCell() {
            nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");
            gvLabel.setStyle("-fx-text-fill: #6bc4fc; -fx-font-size: 11px;");
            dateLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
            
            depLink.setStyle("-fx-text-fill: #FFC107; -fx-font-size: 11px; -fx-padding: 0; -fx-border-color: transparent; -fx-underline: true; -fx-cursor: hand;");
            
            HBox bottomRow = new HBox(10, dateLabel, depLink);
            bottomRow.setAlignment(Pos.CENTER_LEFT);

            info.getChildren().addAll(nameLabel, typeLabel, gvLabel, bottomRow);
            box.getChildren().add(info);
            box.setStyle("-fx-padding: 8; -fx-background-color: #2b2b2b; -fx-background-radius: 6;");
        }

        @Override
        protected void updateItem(ModVersion item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { 
                setGraphic(null); 
                setText(null); 
            } else {
                nameLabel.setText(item.name);
                switch (item.versionType) {
                    case "release": typeLabel.setText("✅ 正式版"); typeLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 11px;"); break;
                    case "beta": typeLabel.setText("🟨 Beta"); typeLabel.setStyle("-fx-text-fill: #FFC107; -fx-font-size: 11px;"); break;
                    default: typeLabel.setText("🔴 Alpha"); typeLabel.setStyle("-fx-text-fill: #F44336; -fx-font-size: 11px;");
                }
                gvLabel.setText("🎮 " + String.join(", ", item.gameVersions) + (item.loaders.isEmpty() ? "" : " · 🔧 " + String.join(", ", item.loaders)));
                
                String dateStr = item.datePublished != null ? new SimpleDateFormat("yyyy-MM-dd").format(item.datePublished) : "";
                dateLabel.setText(dateStr);

                if (item.dependencies.isEmpty()) {
                    depLink.setVisible(false);
                    depLink.setManaged(false);
                } else {
                    depLink.setVisible(true);
                    depLink.setManaged(true);
                    depLink.setText("📦 查看 " + item.dependencies.size() + " 个依赖");
                    depLink.setOnAction(e -> showDependenciesOnly(item));
                }
                
                setGraphic(box);
            }
        }
    }
}