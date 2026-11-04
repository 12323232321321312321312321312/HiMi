package org.example;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Alert; // 🚨 必须加上这一行
import javafx.util.Duration;

public class DownloadController {

    @FXML private ProgressBar totalProgressBar;
    @FXML private ProgressBar fileProgressBar;
    @FXML private Label Download_Speed;
    @FXML private Label Download_Percent;
    @FXML private Label Remaining_Files;
    @FXML private ListView<DownloadTask> taskListView;

    private final ObservableList<DownloadTask> taskList = FXCollections.observableArrayList();
    private Timeline totalProgressAnim;
    private Timeline fileProgressAnim;

    @FXML
    public void initialize() {
        taskListView.setItems(taskList);
        taskListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DownloadTask item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getTaskName() + "\t\t\t\t" + item.getStatus());
                    setStyle("-fx-text-fill: " + (item.getStatus().equals("失败") ? "#ff5555" : (item.getStatus().equals("完成") ? "#4CAF50" : "white")) + "; -fx-font-size: 14px;");
                }
            }
        });
    }

    public void startDownload(String versionId, String loader, String baseDir, String folderName) {
        taskList.clear();
        new Thread(() -> {
            try {
                MinecraftDownloader downloader = new MinecraftDownloader(this);
                downloader.downloadAll(versionId, loader, baseDir, folderName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 🚨 新增：仅下载 Assets
    public void startAssetsDownload(String versionId, String baseDir) {
        taskList.clear();
        new Thread(() -> {
            try {
                MinecraftDownloader downloader = new MinecraftDownloader(this);
                downloader.downloadAssetsOnly(versionId, baseDir);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void addTask(String taskName) {
        Platform.runLater(() -> taskList.add(new DownloadTask(taskName)));
    }

    public void updateTaskStatus(int index, String status) {
        Platform.runLater(() -> {
            if (index >= 0 && index < taskList.size()) {
                taskList.get(index).setStatus(status);
                taskListView.refresh();
            }
        });
    }

    public void updateTotalProgress(double progress, String percentText) {
        Platform.runLater(() -> {
            Download_Percent.setText(percentText);
            if (totalProgressAnim != null) totalProgressAnim.stop();
            totalProgressAnim = new Timeline(
                new KeyFrame(Duration.millis(300), new KeyValue(totalProgressBar.progressProperty(), progress))
            );
            totalProgressAnim.play();
        });
    }

    public void updateFileProgress(double progress) {
        Platform.runLater(() -> {
            if (fileProgressAnim != null) fileProgressAnim.stop();
            fileProgressAnim = new Timeline(
                new KeyFrame(Duration.millis(200), new KeyValue(fileProgressBar.progressProperty(), progress))
            );
            fileProgressAnim.play();
        });
    }

    public void updateSpeed(String speedText) {
        Platform.runLater(() -> Download_Speed.setText(speedText));
    }

    public void updateRemainingFiles(int count) {
        Platform.runLater(() -> Remaining_Files.setText(count + " 个"));
    }
    
    public void onDownloadFinished() {
        Platform.runLater(() -> {
            Download_Speed.setText("下载完成！");
            Download_Percent.setText("100%");
            updateTotalProgress(1.0, "100%");
            updateRemainingFiles(0);
        });
    }

    // 🚨 新增：下载完成后展示资源缺失情况
    public void showRemainingAssets(int failedCount) {
        Platform.runLater(() -> {
            Alert alert;
            if (failedCount > 0) {
                alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("资源补全提示");
                alert.setHeaderText("部分资源文件下载失败");
                alert.setContentText("有 " + failedCount + " 个资源文件未能成功下载。\n\n"
                        + "这可能导致游戏内出现无声音、无语言、紫黑方块等问题。\n"
                        + "建议：请检查网络，并再次点击“仅补全资源”。");
            } else {
                alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("资源补全完成");
                alert.setHeaderText("所有资源文件已完美就位！");
                alert.setContentText("音频、语言、贴图等文件全部成功下载，去启动游戏吧！");
            }
            alert.showAndWait();
        });
    }
}