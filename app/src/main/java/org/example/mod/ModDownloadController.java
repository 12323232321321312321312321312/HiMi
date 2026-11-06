package org.example.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ModDownloadController {

    @FXML private ComboBox<String> platformFilter;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> gameVersionFilter;
    @FXML private ComboBox<String> loaderFilter;
    @FXML private Button searchBtn;
    @FXML private ListView<ModProject> modListView;
    @FXML private Label statusLabel;

    private final ObservableList<ModProject> mods = FXCollections.observableArrayList();
    private final OkHttpClient imageClient = new OkHttpClient.Builder().build();

    private int currentOffset = 0;
    private final int PAGE_SIZE = 20;
    private boolean isLoading = false;
    private boolean hasMore = true;
    private String currentQuery = "";
    private String currentGv = "";
    private String currentLoader = "";
    private String currentPlatform = "";

    @FXML
    public void initialize() {
        modListView.setItems(mods);
        modListView.setCellFactory(lv -> new ModProjectCell());
        modListView.setOnMouseClicked(e -> {
            ModProject selected = modListView.getSelectionModel().getSelectedItem();
            if (selected != null) openModDetail(selected);
        });

        modListView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            for (Node node : modListView.lookupAll(".scroll-bar")) {
                if (node instanceof ScrollBar) {
                    ScrollBar sb = (ScrollBar) node;
                    if (sb.getOrientation() == Orientation.VERTICAL) {
                        sb.valueProperty().addListener((o, oldVal, newVal) -> {
                            if (newVal.doubleValue() > 0.9 && !isLoading && hasMore) {
                                loadNextPage();
                            }
                        });
                    }
                }
            }
        });

        platformFilter.getItems().addAll("全部", "Modrinth", "CurseForge");
        platformFilter.setValue("全部");

        gameVersionFilter.getItems().addAll("全部", "1.21.5", "1.21.1", "1.20.6", "1.20.1", "1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.8.9");
        gameVersionFilter.setValue("全部");

        loaderFilter.getItems().addAll("全部", "fabric", "forge", "neoforge", "quilt");
        loaderFilter.setValue("全部");

        searchBtn.setOnAction(e -> startNewSearch());
        searchField.setOnAction(e -> startNewSearch());
        startNewSearch();
    }

    private String getComboValue(ComboBox<String> combo) {
        String text = combo.isEditable() ? combo.getEditor().getText() : combo.getValue();
        if (text == null || text.trim().isEmpty() || "全部".equals(text.trim())) return "";
        return text.trim();
    }

    private void startNewSearch() {
        currentQuery = searchField.getText();
        currentGv = getComboValue(gameVersionFilter);
        currentLoader = getComboValue(loaderFilter);
        currentPlatform = platformFilter.getValue();

        currentOffset = 0;
        hasMore = true;
        isLoading = false;
        mods.clear();
        statusLabel.setText("搜索中...");
        loadNextPage();
    }

    private void loadNextPage() {
        if (isLoading || !hasMore) return;
        isLoading = true;
        statusLabel.setText("加载中...");

        int offset = currentOffset;
        new Thread(() -> {
            List<ModProject> results = new ArrayList<>();
            Map<String, String[]> translations = new HashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            boolean[] hasData = {false};

            try {
                if (!"CurseForge".equals(currentPlatform)) {
                    pool.submit(() -> {
                        try {
                            List<String> gvs = currentGv.isEmpty() ? null : List.of(currentGv);
                            List<String> loaders = currentLoader.isEmpty() ? null : List.of(currentLoader);
                            JsonObject res = ModrinthAPI.search(currentQuery, gvs, loaders, offset, PAGE_SIZE);
                            JsonArray hits = res.getAsJsonArray("hits");
                            if (hits.size() > 0) hasData[0] = true;
                            List<ModProject> list = new ArrayList<>();
                            for (var e : hits) list.add(ModProject.fromModrinthSearch(e.getAsJsonObject()));
                            synchronized (results) { results.addAll(list); }
                        } catch (Exception ex) { System.err.println("MR 搜索失败: " + ex.getMessage()); }
                    });
                }

                if (!"Modrinth".equals(currentPlatform)) {
                    pool.submit(() -> {
                        try {
                            int cfLoader = currentLoader.isEmpty() ? 0 : getCfLoaderType(currentLoader);
                            JsonObject res = CurseForgeAPI.search(currentQuery, currentGv, cfLoader, offset, PAGE_SIZE);
                            JsonArray data = res.getAsJsonArray("data");
                            if (data.size() > 0) hasData[0] = true;
                            List<ModProject> list = new ArrayList<>();
                            for (var e : data) list.add(ModProject.fromCurseForgeSearch(e.getAsJsonObject()));
                            synchronized (results) { results.addAll(list); }
                        } catch (Exception ex) { System.err.println("CF 搜索失败: " + ex.getMessage()); }
                    });
                }

                // 🚨 翻译请求
                pool.submit(() -> {
                    try {
                        JsonObject transRes = MinekuaiAPI.search(currentQuery, PAGE_SIZE, offset);
                        if (transRes == null) return;

                        JsonArray items = null;
                        if (transRes.has("items") && transRes.get("items").isJsonArray()) {
                            items = transRes.getAsJsonArray("items");
                        } else if (transRes.has("data")) {
                            JsonObject dataObj = transRes.getAsJsonObject("data");
                            if (dataObj.has("items") && dataObj.get("items").isJsonArray()) items = dataObj.getAsJsonArray("items");
                            else if (dataObj.has("list") && dataObj.get("list").isJsonArray()) items = dataObj.getAsJsonArray("list");
                            else if (dataObj.has("records") && dataObj.get("records").isJsonArray()) items = dataObj.getAsJsonArray("records");
                        } else if (transRes.has("list") && transRes.get("list").isJsonArray()) {
                            items = transRes.getAsJsonArray("list");
                        }

                        if (items != null && items.size() > 0) {
                            // 🚨 打印第一条完整 JSON 样例，让你看清到底有啥字段
                            System.out.println("[翻译] 原始 JSON 样例: " + items.get(0).toString());

                            for (var item : items) {
                                JsonObject obj = item.getAsJsonObject();
                                String slug = obj.has("slug") ? obj.get("slug").getAsString() : 
                                             (obj.has("mod_slug") ? obj.get("mod_slug").getAsString() : "");
                                
                                // 🚨 极其贪婪的中文字段提取
                                String title = "";
                                if (obj.has("translated_title")) title = obj.get("translated_title").getAsString();
                                else if (obj.has("zh_title")) title = obj.get("zh_title").getAsString();
                                else if (obj.has("title_zh")) title = obj.get("title_zh").getAsString();
                                else if (obj.has("name_zh")) title = obj.get("name_zh").getAsString();
                                else if (obj.has("zh_cn")) title = obj.get("zh_cn").getAsString();
                                else if (obj.has("title")) title = obj.get("title").getAsString();
                                else if (obj.has("name")) title = obj.get("name").getAsString();
                                
                                String desc = "";
                                if (obj.has("translated_description")) desc = obj.get("translated_description").getAsString();
                                else if (obj.has("zh_description")) desc = obj.get("zh_description").getAsString();
                                else if (obj.has("description_zh")) desc = obj.get("description_zh").getAsString();
                                else if (obj.has("summary_zh")) desc = obj.get("summary_zh").getAsString();
                                else if (obj.has("description")) desc = obj.get("description").getAsString();
                                else if (obj.has("summary")) desc = obj.get("summary").getAsString();

                                if (!slug.isEmpty() && !title.isEmpty()) {
                                    synchronized (translations) {
                                        translations.put(slug.toLowerCase(), new String[]{title, desc});
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("[翻译] 请求异常: " + ex.getMessage());
                    }
                });

                pool.shutdown();
                pool.awaitTermination(15, TimeUnit.SECONDS);

                // 合并翻译数据
                synchronized (translations) {
                    int matched = 0;
                    for (ModProject p : results) {
                        String[] trans = translations.get(p.slug.toLowerCase());
                        if (trans != null) {
                            p.translatedTitle = trans[0];
                            p.translatedDesc = trans[1];
                            matched++;
                        }
                    }
                    System.out.println("[翻译] 麦块API返回了 " + translations.size() + " 条翻译，成功匹配到 " + matched + " 个模组");
                }

                Platform.runLater(() -> {
                    if (results.isEmpty() || !hasData[0]) {
                        hasMore = false;
                        statusLabel.setText("已无更多结果 (共 " + mods.size() + " 个)");
                    } else {
                        mods.addAll(results);
                        modListView.refresh(); 
                        currentOffset += PAGE_SIZE;
                        statusLabel.setText("已加载 " + mods.size() + " 个模组");
                    }
                    isLoading = false;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("加载失败: " + e.getMessage());
                    isLoading = false;
                });
            }
        }).start();
    }

    private void openModDetail(ModProject project) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/mod_detail.fxml"));
            Parent root = loader.load();
            ModDetailController controller = loader.getController();
            controller.setProject(project, currentGv, currentLoader);
            Stage stage = new Stage();
            stage.setTitle(project.title + " - HiMi");
            stage.setScene(new Scene(root));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.show();
        } catch (Exception ex) { ex.printStackTrace(); }
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

    private class ModProjectCell extends ListCell<ModProject> {
        private final HBox box = new HBox(10);
        private final ImageView icon = new ImageView();
        private final VBox info = new VBox(4);
        private final HBox titleRow = new HBox(8);
        private final Label platformLabel = new Label();
        private final Label titleLabel = new Label();
        private final Label descLabel = new Label();
        private final Label metaLabel = new Label();

        ModProjectCell() {
            icon.setFitWidth(48); icon.setFitHeight(48); icon.setPreserveRatio(true);
            platformLabel.setStyle("-fx-font-size: 10px; -fx-padding: 1 4; -fx-background-radius: 3;");
            titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");
            titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            titleRow.getChildren().addAll(platformLabel, titleLabel);
            
            descLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");
            descLabel.setWrapText(true);
            metaLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
            
            info.getChildren().addAll(titleRow, descLabel, metaLabel);
            HBox.setHgrow(info, Priority.ALWAYS);
            box.getChildren().addAll(icon, info);
            box.setStyle("-fx-padding: 8; -fx-background-color: #2b2b2b; -fx-background-radius: 6;");
        }

        @Override
        protected void updateItem(ModProject item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null); setText(null);
            } else {
                String displayTitle = (item.translatedTitle != null && !item.translatedTitle.isEmpty()) 
                                        ? item.translatedTitle : item.title;
                String displayDesc = (item.translatedDesc != null && !item.translatedDesc.isEmpty()) 
                                        ? item.translatedDesc : item.description;

                titleLabel.setText(displayTitle);
                descLabel.setText(displayDesc);
                metaLabel.setText("⬇ " + formatNumber(item.downloads) + " · 更新于 " + item.dateModified);

                if (item.platform == ModProject.Platform.MODRINTH) {
                    platformLabel.setText("Modrinth");
                    platformLabel.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: black; -fx-font-weight: bold;");
                } else {
                    platformLabel.setText("CurseForge");
                    platformLabel.setStyle("-fx-background-color: #f16436; -fx-text-fill: white; -fx-font-weight: bold;");
                }

                if (item.iconUrl != null && !item.iconUrl.isEmpty()) {
                    loadIconAsync(icon, item.iconUrl);
                } else {
                    icon.setImage(null);
                }
                setGraphic(box);
            }
        }
    }

    private void loadIconAsync(ImageView iv, String url) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(url).build();
                try (Response resp = imageClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        Image img = new Image(resp.body().byteStream(), 48, 48, true, true);
                        Platform.runLater(() -> iv.setImage(img));
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
}