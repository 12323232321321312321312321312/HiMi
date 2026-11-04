package org.example;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.util.LoaderSupportChecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DownloadPlayerController {

    @FXML private Button downloadBtn;
    @FXML private Button downloadAssetsBtn;
    @FXML private Label lblTargetVersion;
    @FXML private TextField versionFolderName; 
    @FXML private VBox loaderListContainer;

    private String baseVersionId = "";
    private String currentSelectedLoaderVersion = "原版 (Vanilla)"; 
    
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final Map<String, LoaderCard> cardMap = new HashMap<>();
    private final String[] OTHER_LOADERS = {"Forge", "NeoForge", "Fabric", "OptiFine"};

    public void setVersion(String version) {
        this.baseVersionId = version;
        lblTargetVersion.setText("准备下载版本: " + version);
        versionFolderName.setText(version);
        loadLoaderSupporters(version);
    }

    @FXML
    public void initialize() {
        // 全量下载
        downloadBtn.setOnAction(event -> {
            String folderName = versionFolderName.getText();
            if (folderName == null || folderName.trim().isEmpty() || folderName.contains("/") || folderName.contains("\\") || folderName.contains(":")) {
                showAlert("文件夹名称无效，请勿包含特殊字符！");
                return;
            }
            startDownloadTask(folderName, false);
        });

        // 🚨 仅下载 Assets
        downloadAssetsBtn.setOnAction(event -> {
            String folderName = versionFolderName.getText();
            if (folderName == null || folderName.trim().isEmpty()) {
                showAlert("请填写实例名称！");
                return;
            }
            startDownloadTask(folderName, true);
        });
    }

    private void startDownloadTask(String folderName, boolean assetsOnly) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/download.fxml"));
            Parent root = loader.load();
            DownloadController downloadController = loader.getController();
            
            String targetDir = System.getProperty("user.dir") + "\\.minecraft";
            if (assetsOnly) {
                downloadController.startAssetsDownload(baseVersionId, targetDir);
            } else {
                downloadController.startDownload(baseVersionId, currentSelectedLoaderVersion, targetDir, folderName);
            }
            
            Stage stage = new Stage();
            stage.setTitle(assetsOnly ? "补全资源 - " + baseVersionId : "正在下载 - " + baseVersionId);
            stage.setScene(new Scene(root));
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("无法打开下载界面: " + e.getMessage());
        }
    }

    private void loadLoaderSupporters(String mcVersion) {
        loaderListContainer.getChildren().clear();
        cardMap.clear();

        // 原版按钮
        Button vanillaBtn = new Button("原版 (Vanilla)");
        vanillaBtn.setMaxWidth(Double.MAX_VALUE);
        vanillaBtn.setAlignment(Pos.CENTER_LEFT);
        vanillaBtn.setStyle("-fx-background-color: white; -fx-text-fill: #333; -fx-font-size: 14px; -fx-font-weight: bold; -fx-border-color: #eee; -fx-border-radius: 5px; -fx-background-radius: 5px; -fx-padding: 10 15; -fx-cursor: hand;");
        vanillaBtn.setOnMouseEntered(e -> vanillaBtn.setStyle("-fx-background-color: #f0f8ff; -fx-text-fill: #333; -fx-font-size: 14px; -fx-font-weight: bold; -fx-border-color: #66b2ff; -fx-border-radius: 5px; -fx-background-radius: 5px; -fx-padding: 10 15; -fx-cursor: hand;"));
        vanillaBtn.setOnMouseExited(e -> vanillaBtn.setStyle("-fx-background-color: white; -fx-text-fill: #333; -fx-font-size: 14px; -fx-font-weight: bold; -fx-border-color: #eee; -fx-border-radius: 5px; -fx-background-radius: 5px; -fx-padding: 10 15; -fx-cursor: hand;"));
        vanillaBtn.setOnAction(e -> {
            versionFolderName.setText(baseVersionId);
            currentSelectedLoaderVersion = "原版 (Vanilla)";
        });
        loaderListContainer.getChildren().add(vanillaBtn);

        for (String loader : OTHER_LOADERS) {
            LoaderCard card = new LoaderCard(loader);
            cardMap.put(loader, card);
            loaderListContainer.getChildren().add(card.getRoot());
        }

        new Thread(() -> {
            List<String> supportedFromCache = LoaderSupportChecker.getSupportedLoaders(mcVersion);
            
            // Forge
            List<String> forgeVersions = fetchForgeVersions(mcVersion);
            Platform.runLater(() -> {
                if (!forgeVersions.isEmpty()) cardMap.get("Forge").setSuccess(forgeVersions);
                else if (supportedFromCache.contains("Forge")) cardMap.get("Forge").setSuccess(List.of("最新版"));
                else cardMap.get("Forge").setNo();
            });

            // NeoForge
            List<String> neoforgeVersions = fetchNeoForgeVersions(mcVersion);
            Platform.runLater(() -> {
                if (!neoforgeVersions.isEmpty()) cardMap.get("NeoForge").setSuccess(neoforgeVersions);
                else if (supportedFromCache.contains("NeoForge")) cardMap.get("NeoForge").setSuccess(List.of("最新版"));
                else cardMap.get("NeoForge").setNo();
            });

            // Fabric
            List<String> fabricVersions = fetchFabricVersions(mcVersion);
            Platform.runLater(() -> {
                if (!fabricVersions.isEmpty()) cardMap.get("Fabric").setSuccess(fabricVersions);
                else if (supportedFromCache.contains("Fabric")) cardMap.get("Fabric").setSuccess(List.of("最新版"));
                else cardMap.get("Fabric").setNo();
            });

            // OptiFine
            List<String> ofVersions = fetchOptiFineVersions(mcVersion);
            Platform.runLater(() -> {
                if (!ofVersions.isEmpty()) cardMap.get("OptiFine").setSuccess(ofVersions);
                else if (supportedFromCache.contains("OptiFine")) cardMap.get("OptiFine").setSuccess(List.of("最新版"));
                else cardMap.get("OptiFine").setNo();
            });
        }).start();
    }

    private List<String> fetchForgeVersions(String mcVersion) {
        List<String> list = new ArrayList<>();
        try {
            String url = "https://bmclapi2.bangbang93.com/forge/minecraft/" + mcVersion;
            String json = fetchString(url);
            if (json != null) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                for (JsonElement el : arr) list.add(el.getAsJsonObject().get("version").getAsString());
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<String> fetchNeoForgeVersions(String mcVersion) {
        List<String> list = new ArrayList<>();
        try {
            String url = "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml";
            String xml = fetchString(url);
            if (xml != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<version>(.*?)</version>").matcher(xml);
                while (m.find()) {
                    String ver = m.group(1);
                    String cleanVer = ver.split("-")[0]; 
                    String[] parts = cleanVer.split("\\.");
                    if (parts.length >= 2) {
                        try {
                            int major = Integer.parseInt(parts[0]);
                            int minor = Integer.parseInt(parts[1]);
                            String mcVer = "";
                            if (major >= 22) mcVer = major + "." + minor;
                            else if (major >= 20) {
                                mcVer = "1." + major;
                                if (minor != 0) mcVer += "." + minor;
                            }
                            if (mcVer.equals(mcVersion)) list.add(ver);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<String> fetchFabricVersions(String mcVersion) {
        List<String> list = new ArrayList<>();
        try {
            String url = "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/" + mcVersion;
            String json = fetchString(url);
            if (json != null) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                for (JsonElement el : arr) {
                    list.add(el.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString());
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<String> fetchOptiFineVersions(String mcVersion) {
        List<String> list = new ArrayList<>();
        try {
            String url = "https://bmclapi2.bangbang93.com/optifine/" + mcVersion;
            String json = fetchString(url);
            if (json != null) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    list.add(obj.get("type").getAsString() + "_" + obj.get("patch").getAsString());
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private String fetchString(String url) {
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) return response.body().string();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.show();
    }

    // 内部类：折叠卡片
    private class LoaderCard {
        private final VBox root;
        private final Label statusLabel;
        private final VBox contentBox;
        private final Label arrowLabel;
        private boolean isExpanded = false;

        public LoaderCard(String loaderName) {
            Label nameLabel = new Label(loaderName);
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #333;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            statusLabel = new Label("获取中...");
            statusLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 13px;");
            arrowLabel = new Label("▶");
            arrowLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 12px; -fx-padding: 0 0 0 10;");

            HBox header = new HBox(nameLabel, spacer, statusLabel, arrowLabel);
            header.setAlignment(Pos.CENTER_LEFT);
            header.setPadding(new Insets(10, 15, 10, 15));
            header.setStyle("-fx-background-color: white; -fx-background-radius: 5px; -fx-border-color: #eee; -fx-border-radius: 5px; -fx-cursor: hand;");

            contentBox = new VBox();
            contentBox.setVisible(false);
            contentBox.setManaged(false);
            VBox.setMargin(contentBox, new Insets(5, 0, 0, 0));
            root = new VBox(header, contentBox);

            header.setOnMouseClicked(e -> {
                if (statusLabel.getText().equals("无")) return;
                isExpanded = !isExpanded;
                contentBox.setVisible(isExpanded);
                contentBox.setManaged(isExpanded);
                arrowLabel.setText(isExpanded ? "▼" : "▶");
                header.setStyle("-fx-background-color: " + (isExpanded ? "#f0f8ff" : "white") + "; -fx-background-radius: 5px; -fx-border-color: " + (isExpanded ? "#66b2ff" : "#eee") + "; -fx-border-radius: 5px; -fx-cursor: hand;");
            });
        }

        public VBox getRoot() { return root; }

        public void setSuccess(List<String> versions) {
            statusLabel.setText("可以添加");
            statusLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 13px;");
            contentBox.getChildren().clear();
            
            ListView<String> listView = new ListView<>();
            listView.getItems().addAll(versions);
            listView.setPrefHeight(200); 
            listView.setStyle("-fx-background-color: white; -fx-control-inner-background: white; -fx-padding: 0; -fx-border-color: #eee; -fx-border-radius: 5px;");

            listView.setCellFactory(lv -> new ListCell<String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null); setGraphic(null);
                    } else {
                        HBox row = new HBox(10.0);
                        row.setAlignment(Pos.CENTER_LEFT);
                        row.setPadding(new Insets(5.0));
                        Label lblVer = new Label(item);
                        lblVer.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);
                        Button btnAdd = new Button("选择");
                        btnAdd.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-cursor: hand;");
                        Runnable selectAction = () -> {
                            String loaderName = ((Label) ((HBox) root.getChildren().get(0)).getChildren().get(0)).getText();
                            String suffix = loaderName.toLowerCase();
                            versionFolderName.setText(baseVersionId + "-" + suffix + "-" + item);
                            currentSelectedLoaderVersion = loaderName + " " + item;
                        };
                        row.setOnMouseClicked(e -> selectAction.run());
                        btnAdd.setOnAction(e -> selectAction.run());
                        row.getChildren().addAll(lblVer, spacer, btnAdd);
                        setGraphic(row);
                    }
                }
            });
            contentBox.getChildren().add(listView);
        }

        public void setNo() {
            statusLabel.setText("无");
            statusLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 13px;");
            contentBox.getChildren().clear();
            isExpanded = false;
            contentBox.setVisible(false);
            contentBox.setManaged(false);
            arrowLabel.setText("▶");
        }
    }
}