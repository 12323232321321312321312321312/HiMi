<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.Tab?>
<?import javafx.scene.control.TabPane?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.VBox?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.text.Font?>

<!-- 最外层直接就是 VBox，去掉了限制尺寸的属性，背景为深色 -->
<VBox style="-fx-background-color: #2b2b2b;" xmlns="http://javafx.com/javafx/21" xmlns:fx="http://javafx.com/fxml/1">
   <children>
      <!-- TabPane 直接填满 VBox (VBox.vgrow="ALWAYS") -->
      <TabPane tabClosingPolicy="UNAVAILABLE" VBox.vgrow="ALWAYS">
        <tabs>
            <!-- 启动 Tab -->
            <Tab text="启动">
               <content>
                  <!-- 用 VBox 来做垂直居中对齐，而不是死板的 AnchorPane -->
                  <VBox alignment="CENTER" spacing="30.0">
                     <children>
                        <Label text="离线模式" textFill="WHITE">
                           <font><Font size="60.0" /></font>
                        </Label>
                        
                        <TextField maxWidth="400.0" prefHeight="60.0" promptText="请输入游戏ID">
                           <font><Font size="30.0" /></font>
                        </TextField>
                        
                        <Button mnemonicParsing="false" prefHeight="60.0" prefWidth="200.0" style="-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 24px;" text="启 动" />
                     </children>
                  </VBox>
               </content>
            </Tab>
            
            <!-- 下载 Tab -->
            <Tab text="下载">
               <content>
                  <!-- 这个 Tab 里用 HBox 做左右布局 -->
                  <HBox>
                     <children>
                        <!-- 左侧导航栏 -->
                        <VBox prefWidth="150.0" style="-fx-background-color: #333333;" spacing="10.0">
                           <padding><Insets top="20.0" right="10.0" bottom="20.0" left="10.0"/></padding>
                           <children>
                              <Button maxWidth="1.7976931348623157E308" style="-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 18px;" text="原版游戏" />
                              <Button maxWidth="1.7976931348623157E308" style="-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 18px;" text="Mod 模组" />
                              <Button maxWidth="1.7976931348623157E308" style="-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 18px;" text="整合包" />
                           </children>
                        </VBox>
                        
                        <!-- 右侧内容区，占满剩余空间 -->
                        <VBox HBox.hgrow="ALWAYS" alignment="CENTER">
                           <children>
                              <Label text="这里是右侧显示区域" textFill="WHITE">
                                 <font><Font size="24.0" /></font>
                              </Label>
                           </children>
                        </VBox>
                     </children>
                  </HBox>
               </content>
            </Tab>
        </tabs>
      </TabPane>
   </children>
</VBox>