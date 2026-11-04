package org.example;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LogManager {
    private static File launcherLogFile;
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private static final SimpleDateFormat fileTimeFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    /**
     * 初始化日志文件路径：.minecraft/versions/<gameName>/logs/
     */
    public static void init(String baseDir, String gameName) {
        File logDir = new File(baseDir, "versions/" + gameName + "/logs");
        if (!logDir.exists()) logDir.mkdirs();

        launcherLogFile = new File(logDir, "launcher.log");

        // 🚨 核心优化：启动前先归档旧日志
        archiveOldLog(launcherLogFile, logDir);

        logLauncher("日志系统已初始化");
        logLauncher("目标路径: " + logDir.getAbsolutePath());
    }

    private static void archiveOldLog(File oldFile, File logDir) {
        if (oldFile.exists() && oldFile.length() > 0) {
            String timeStr = fileTimeFormat.format(new Date());
            String fileName = oldFile.getName(); 
            String baseName = fileName.substring(0, fileName.lastIndexOf("."));
            String ext = fileName.substring(fileName.lastIndexOf("."));
            
            File archiveFile = new File(logDir, baseName + "_" + timeStr + ext);
            if (oldFile.renameTo(archiveFile)) {
                System.out.println("旧日志已归档: " + archiveFile.getName());
            } else {
                oldFile.delete();
            }
        }
        
        try (FileWriter fw = new FileWriter(oldFile, false)) {}
        catch (IOException ignored) {}
    }

    /**
     * 写入启动器的日志
     */
    public static void logLauncher(String msg) {
        writeToFile(launcherLogFile, "[启动器] ", msg);
    }

    private static void writeToFile(File file, String prefix, String msg) {
        if (file == null) return;
        try (FileWriter fw = new FileWriter(file, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            String time = timeFormat.format(new Date());
            out.println("[" + time + "] " + prefix + msg);
        } catch (IOException ignored) {}
    }
}