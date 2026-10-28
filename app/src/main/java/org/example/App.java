package org.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 1. 检查路径是否真的找到了文件
        var fxmlUrl = getClass().getResource("/main.fxml");
        System.out.println("加载的 FXML 路径: " + fxmlUrl);

        if (fxmlUrl == null) {
            System.out.println("未找到 main.fxml 文件！请检查路径。");
            return;
        }

        Parent root = FXMLLoader.load(fxmlUrl);
        
        // 2. 检查根节点是什么，有没有子节点
        System.out.println("根节点类型: " + root.getClass().getSimpleName());
        
        // 3. 强制设置一个足够大的宽高
        Scene scene = new Scene(root, 841, 627); 

        primaryStage.setTitle("我的 MC 启动器 v0.1 (FXML版)");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("窗口已显示！");
    }

    public static void main(String[] args) {
        launch(args);
    }
}