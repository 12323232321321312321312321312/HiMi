package org.example;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.TextField;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

public class SettingsController {

    @FXML private TextField txtMcPath;

    // 默认的 .minecraft 路径
    private final String mcPath = System.getProperty("user.home") + "\\.minecraft";

    @FXML
    public void initialize() {
        // 初始化时，显示路径
        txtMcPath.setText(mcPath);

        // 给按钮绑定事件 (因为我们在 FXML 里设置了 fx:id，所以可以直接在这里获取)
        // 注意：要在 Main 主程序中或者这里拿到按钮，这里简写，如果想用 FXML 的 onAction 也可以。
    }

    @FXML
    public void handleOpenDir() {
        File dir = new File(mcPath);
        if (dir.exists()) {
            try {
                Desktop.getDesktop().open(dir);
            } catch (IOException e) {
                showAlert("打开失败", "无法打开文件夹: " + e.getMessage());
            }
        } else {
            showAlert("找不到文件夹", "该目录不存在，请先新建！");
        }
    }

    @FXML
    public void handleCreateDir() {
        File dir = new File(mcPath);
        if (!dir.exists()) {
            boolean success = dir.mkdirs();
            if (success) {
                showAlert("成功", "已成功创建 .minecraft 文件夹！");
            } else {
                showAlert("失败", "创建文件夹失败，请检查权限。");
            }
        } else {
            showAlert("提示", "文件夹已经存在了！");
        }
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}