package org.example;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.example.config.ConfigManager;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class VersionSelectController {

    @FXML private ListView<String> dirListView;
    @FXML private ListView<MinecraftVersion> versionListView;
    @FXML private Label lblVersionId;
    @FXML private Label lblVersionType;

    private List<String> mcDirs = new ArrayList<>();
    private Consumer<MinecraftVersion> onSelected;

    @FXML
    @SuppressWarnings("unchecked")
    public void initialize() {
        Map<String, Object> setup = ConfigManager.loadSetup();
        if (setup.get("minecraftDirs") != null) {
            mcDirs = (List<String>) setup.get("minecraftDirs");
        }
        if (mcDirs.isEmpty()) mcDirs.add(ConfigManager.DEFAULT_MC_DIR);
        
        dirListView.setItems(FXCollections.observableArrayList(mcDirs));

        dirListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) loadVersionsFromDir(newVal);
        });

        versionListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                lblVersionId.setText("版本 ID: " + newVal.getId());
                lblVersionType.setText("类型: " + newVal.getType());
            }
        });

        if (!mcDirs.isEmpty()) dirListView.getSelectionModel().select(0);
    }

    public void setOnSelected(Consumer<MinecraftVersion> onSelected) {
        this.onSelected = onSelected;
    }

    private void loadVersionsFromDir(String dirPath) {
        versionListView.getItems().clear();
        lblVersionId.setText("请选择中间的版本");
        lblVersionType.setText("类型: --");
        
        List<MinecraftVersion> versions = VersionScanner.scan(dirPath);
        versionListView.setItems(FXCollections.observableArrayList(versions));
        if (!versions.isEmpty()) versionListView.getSelectionModel().select(0);
    }

    @FXML
    public void handleCreateDir() {
        File newDir = new File(System.getProperty("user.dir"), ".minecraft");
        if (!newDir.exists()) {
            newDir.mkdirs();
            new File(newDir, "versions").mkdirs();
            mcDirs.add(newDir.getAbsolutePath());
            dirListView.getItems().setAll(mcDirs);
            
            // 🚨 只更新路径，不覆盖其他配置（避免缺少参数或覆盖内存等）
            ConfigManager.updateSingleConfig("minecraftDirs", mcDirs);
            
            showAlert("成功", "已创建新的 .minecraft 文件夹！");
        } else {
            showAlert("提示", "根目录下的 .minecraft 已经存在！");
        }
    }

    @FXML
    public void handleOpenDir() {
        String selectedPath = dirListView.getSelectionModel().getSelectedItem();
        if (selectedPath != null) {
            try { Desktop.getDesktop().open(new File(selectedPath)); } 
            catch (IOException e) { showAlert("错误", "无法打开文件夹: " + e.getMessage()); }
        }
    }

    @FXML
    public void handleConfirm() {
        MinecraftVersion selected = versionListView.getSelectionModel().getSelectedItem();
        if (selected != null && onSelected != null) onSelected.accept(selected);
        closeWindow();
    }

    @FXML
    public void handleCancel() {
        closeWindow();
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void closeWindow() {
        Stage stage = (Stage) dirListView.getScene().getWindow();
        stage.close();
    }
}