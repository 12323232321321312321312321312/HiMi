package org.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.config.ConfigManager;

import java.util.Map;

public class App extends Application {

    @Override
    @SuppressWarnings("unchecked")
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/main.fxml"));
        Parent root = loader.load();
        
        MainController controller = loader.getController();
        
        Scene scene = new Scene(root, 841, 627);
        primaryStage.setTitle("我的 MC 启动器 v0.1");
        primaryStage.setScene(scene);
        
        // 🚨 关窗时：只保存当前输入的名字和选中的版本，绝不覆盖 javaPath 和 memory
        primaryStage.setOnCloseRequest(event -> {
            System.out.println("正在保存玩家配置...");
            String currentPlayerId = controller.getNameFieldValue();
            String currentVersion = controller.getSelectedVersion();
            
            Map<String, Object> setup = ConfigManager.loadSetup();
            setup.put("playerId", currentPlayerId);
            setup.put("selectedVersion", currentVersion);
            ConfigManager.saveFullSetup(setup);
        });
        
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}