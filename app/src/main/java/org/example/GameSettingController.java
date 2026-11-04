package org.example;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.example.config.ConfigManager;

import java.io.File;
import java.util.Map;

public class GameSettingController {

    @FXML private TextField txtVersionName;
    @FXML private Slider memorySlider;
    @FXML private Label lblMemoryInfo;
    @FXML private ComboBox<String> javaPathComboBox;
    @FXML private Button btnBrowseJava;
    @FXML private Button SaveJavaSettings;
    @FXML private Button btnConfirmName;

    private String currentVersionId = "";
    private Runnable onSaveCallback;

    @FXML
    public void initialize() {
        if (btnConfirmName != null) btnConfirmName.setOnAction(e -> handleConfirmName());
        if (btnBrowseJava != null) btnBrowseJava.setOnAction(e -> handleBrowseJava());
        if (SaveJavaSettings != null) SaveJavaSettings.setOnAction(e -> handleSaveJavaSettings());
    }

    public void initData(String versionId, Runnable onSaveCallback) {
        this.currentVersionId = versionId;
        this.onSaveCallback = onSaveCallback;

        Map<String, Object> setup = ConfigManager.loadSetup();

        if (txtVersionName != null) {
            txtVersionName.setText(versionId);
        }

        if (memorySlider != null) {
            int memory = parseMemory(setup.get("memory"), 4096);
            memorySlider.setValue(memory);
            if (lblMemoryInfo != null) {
                lblMemoryInfo.setText("内存分配: " + memory + " MB");
            }
            memorySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                int currentMemory = (int) Math.round(newVal.doubleValue());
                if (lblMemoryInfo != null) {
                    lblMemoryInfo.setText("内存分配: " + currentMemory + " MB");
                }
            });
        }

        if (javaPathComboBox != null) {
            String currentJava = System.getProperty("java.home") + "\\bin\\java.exe";
            javaPathComboBox.getItems().add(currentJava);

            try {
                String appData = System.getenv("APPDATA");
                if (appData != null) {
                    File runtimeDir = new File(appData + "\\.minecraft\\runtime");
                    if (runtimeDir.exists()) {
                        File[] runtimes = runtimeDir.listFiles(File::isDirectory);
                        if (runtimes != null) {
                            for (File rt : runtimes) {
                                File javaExe = new File(rt, "bin\\java.exe");
                                if (javaExe.exists() && !javaPathComboBox.getItems().contains(javaExe.getAbsolutePath())) {
                                    javaPathComboBox.getItems().add(javaExe.getAbsolutePath());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("扫描官方 runtime 失败: " + e.getMessage());
            }

            String savedJavaPath = setup.get("javaPath") instanceof String ? (String) setup.get("javaPath") : null;
            if (savedJavaPath != null && !savedJavaPath.isEmpty()) {
                savedJavaPath = cleanJavaPath(savedJavaPath);
                if (!javaPathComboBox.getItems().contains(savedJavaPath)) {
                    javaPathComboBox.getItems().add(savedJavaPath);
                }
                javaPathComboBox.setValue(savedJavaPath);
            } else {
                javaPathComboBox.setValue(currentJava);
            }
        }
    }

    private int parseMemory(Object raw, int defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return (int) Double.parseDouble(raw.toString().trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void handleBrowseJava() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择 Java 可执行文件");
        File currentJavaDir = new File(System.getProperty("java.home"), "bin");
        if (currentJavaDir.exists()) fileChooser.setInitialDirectory(currentJavaDir);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Executable", "java.exe", "javaw.exe"));

        File selectedFile = fileChooser.showOpenDialog(btnBrowseJava.getScene().getWindow());
        if (selectedFile != null) {
            String path = selectedFile.getAbsolutePath();
            if (!javaPathComboBox.getItems().contains(path)) {
                javaPathComboBox.getItems().add(path);
            }
            javaPathComboBox.setValue(path);
        }
    }

    private void handleSaveJavaSettings() {
        String rawJavaPath = javaPathComboBox.getValue();
        if (rawJavaPath == null || rawJavaPath.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "请先选择或输入 Java 路径！");
            return;
        }

        String cleanPath = cleanJavaPath(rawJavaPath);
        if (!new File(cleanPath).exists()) {
            showAlert(Alert.AlertType.ERROR, "路径无效！\n系统找不到文件: " + cleanPath + "\n请重新选择 java.exe");
            return;
        }

        javaPathComboBox.setValue(cleanPath);
        ConfigManager.updateSingleConfig("javaPath", cleanPath);

        showAlert(Alert.AlertType.INFORMATION, "Java 设置已保存！\n路径: " + cleanPath);
        if (onSaveCallback != null) onSaveCallback.run();
    }

    private void handleConfirmName() {
        String newName = txtVersionName.getText().trim();
        if (newName.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "名称不能为空！");
            return;
        }
        saveSetting(currentVersionId, newName);
    }

    private void saveSetting(String versionId, String customName) {
        String rawJavaPath = javaPathComboBox.getValue();
        if (rawJavaPath == null || rawJavaPath.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "请先选择或输入 Java 路径！");
            return;
        }

        String cleanPath = cleanJavaPath(rawJavaPath);
        if (!new File(cleanPath).exists()) {
            showAlert(Alert.AlertType.ERROR, "路径无效！\n系统找不到文件: " + cleanPath + "\n请重新选择 java.exe");
            return;
        }

        javaPathComboBox.setValue(cleanPath);

        Map<String, Object> setup = ConfigManager.loadSetup();
        int memory = (int) memorySlider.getValue();

        setup.put("memory", memory);
        setup.put("version_" + versionId + "_name", customName);
        setup.put("javaPath", cleanPath);

        ConfigManager.saveFullSetup(setup);
        showAlert(Alert.AlertType.INFORMATION, "设置已保存！\nJava路径: " + cleanPath);
        if (onSaveCallback != null) onSaveCallback.run();
    }

    private String cleanJavaPath(String path) {
        if (path == null) return "";
        path = path.trim();
        int firstExeIndex = path.toLowerCase().indexOf(".exe");
        int lastExeIndex = path.toLowerCase().lastIndexOf(".exe");
        if (firstExeIndex != -1 && firstExeIndex != lastExeIndex) {
            return path.substring(0, firstExeIndex + 4);
        }
        return path;
    }

    private void showAlert(Alert.AlertType type, String content) {
        Alert alert = new Alert(type);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}