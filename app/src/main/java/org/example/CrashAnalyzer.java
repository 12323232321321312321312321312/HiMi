package org.example;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * HiMi 崩溃日志分析器（参考 PCL2 ModCrash.vb 精简版）。
 * 从「内存输出 + latest.log + 最新 crash-report」中提取关键字，转成人类可读建议。
 */
public class CrashAnalyzer {

    /** 只读日志文件末尾的字节数（防止 GB 级日志把启动器撑爆） */
    private static final int MAX_READ_BYTES = 2 * 1024 * 1024;

    public static class CrashReport {
        public List<String> reasons = new ArrayList<>();
        public String detailLog = "";
        public String versionId = "";
        public String baseDir = "";
        public boolean matched = false;
    }

    /**
     * 主入口：分析一次崩溃
     * @param versionId 版本 ID
     * @param recentOutput 游戏进程输出（已保留的最后若干行）
     * @param baseDir .minecraft 根目录
     */
    public static CrashReport analyze(String versionId, List<String> recentOutput, String baseDir) {
        CrashReport report = new CrashReport();
        report.versionId = versionId;
        report.baseDir = baseDir;

        StringBuilder allLog = new StringBuilder();

        // 1. 内存保留的游戏输出
        if (recentOutput != null && !recentOutput.isEmpty()) {
            allLog.append("=== 游戏进程最后输出 ===\n");
            for (String line : recentOutput) {
                allLog.append(line).append('\n');
            }
        }

        // 2. latest.log
        File latestLog = new File(baseDir, "versions/" + versionId + "/logs/latest.log");
        if (latestLog.exists() && latestLog.length() > 0) {
            allLog.append("\n=== latest.log（末尾） ===\n");
            allLog.append(readTail(latestLog, MAX_READ_BYTES));
        }

        // 3. crash-reports 里最新的一个
        File crashDir = new File(baseDir, "versions/" + versionId + "/crash-reports");
        if (crashDir.exists()) {
            File[] reports = crashDir.listFiles((d, n) -> n.endsWith(".txt"));
            if (reports != null && reports.length > 0) {
                Arrays.sort(reports, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                allLog.append("\n=== crash-report: ").append(reports[0].getName()).append(" ===\n");
                allLog.append(readTail(reports[0], MAX_READ_BYTES));
            }
        }

        String text = allLog.toString();
        report.detailLog = text;

        List<String> reasons = new ArrayList<>();

        // ---------- 关键字匹配表 ----------

        // 1. 内存不足
        if (text.contains("java.lang.OutOfMemoryError")
                || text.contains("The system is out of physical RAM or swap space")) {
            reasons.add("🟥 内存不足：游戏分配的内存不够，建议在「设置 → 启动」里调大内存，或减少高清材质/大型模组。");
        }

        // 2. Java 版本不兼容
        if (text.contains("Unsupported class file major version")
                || text.contains("Unsupported major.minor version")
                || text.contains("has been compiled by a more recent version of the Java Runtime")) {
            reasons.add("🟥 Java 版本不兼容：当前 Java 版本过低或过高，请在「版本设置」里切换到合适版本（1.16 及以下通常用 Java 8，1.17–1.20 用 Java 17，1.20.5+ 用 Java 21）。");
        }

        // 3. 模组冲突 / 缺少前置
        if (text.contains("ModResolutionException")
                || text.contains("Missing or unsupported mandatory dependencies")
                || text.contains("Incompatible mods found!")
                || text.contains("has mods that were not found")) {
            reasons.add("🟥 模组冲突或缺少前置：请检查是否安装了正确版本的依赖模组，或删除版本不兼容的模组。");
        }

        // 4. 模组重复安装
        if (text.contains("DuplicateModsFoundException")
                || text.contains("Found a duplicate mod")
                || text.contains("Found duplicate mods")) {
            reasons.add("🟥 模组重复安装：mods 文件夹里有同名/同 ID 的模组，请删掉重复的。");
        }

        // 5. MixinBootstrap 缺失
        if (text.contains("ClassNotFoundException: org.spongepowered.asm.launch.MixinTweaker")) {
            reasons.add("🟥 缺少 MixinBootstrap：请安装 MixinBootstrap 模组（1.12.2 以下需要）。");
        }

        // 6. Forge/NeoForge 安装不完整
        if (text.contains("Cannot find launch target fmlclient")
                || text.contains("Invalid paths argument, contained no existing paths")
                || text.contains("Failed to load any Forge libraries")) {
            reasons.add("🟥 Forge/NeoForge 安装不完整：请重新安装一次对应加载器，或删除 versions 下对应版本后重新下载。");
        }

        // 7. OptiFine 与 Forge 冲突
        if (text.contains("java.lang.NoSuchMethodError: 'void net.minecraft")
                || text.contains("has mods that were not found") && text.contains("OptiFine")) {
            reasons.add("🟥 OptiFine 与当前 Forge 版本不兼容：请换用匹配的 OptiFine 与 Forge 组合，或删除 OptiFine 改用其他优化模组。");
        }

        // 8. 显卡/OpenGL 问题
        if (text.contains("Pixel format not accelerated")
                || text.contains("The driver does not appear to support OpenGL")
                || text.contains("GLFW error")
                || text.contains("EXCEPTION_ACCESS_VIOLATION")) {
            reasons.add("🟥 显卡/OpenGL 问题：请更新显卡驱动，或确认使用的是独显而非核显。");
        }

        // 9. 模组文件被解压
        if (text.contains("The directories below appear to be extracted jar files")
                || text.contains("Extracted mod jars found")) {
            reasons.add("🟥 模组文件被解压：mods 文件夹里不能有解压后的文件夹，请删除解压目录，只保留 .jar 文件。");
        }

        // 10. 主类错误（classpath 损坏）
        if (text.contains("Could not find or load main class")
                || text.contains("Error: Could not find or load main class")) {
            reasons.add("🟥 主类缺失：libraries 或核心 jar 损坏，建议删除对应版本重新下载。");
        }

        // 11. 32 位 Java 内存不足
        if (text.contains("Invalid maximum heap size")
                || text.contains("Could not reserve enough space for object heap")) {
            reasons.add("🟥 32 位 Java 无法分配此内存：请改用 64 位 Java，或降低内存设置。");
        }

        // 12. OpenJ9
        if (text.contains("OpenJ9 is not supported") || text.contains(".J9VMInternals.")) {
            reasons.add("🟥 使用了 OpenJ9 版本的 Java：请切换到 HotSpot 版 Java（如 Adoptium/Temurin）。");
        }

        // 13. 日志极短（兜底）
        if (reasons.isEmpty() && text.length() < 300) {
            reasons.add("⚠️ 游戏启动后立即退出，日志极短：可能是 Java 路径错误、内存参数异常或启动参数有误。");
        }

        report.reasons = reasons;
        report.matched = !reasons.isEmpty();
        if (!report.matched) {
            reasons.add("❔ HiMi 未能识别具体崩溃原因，请展开查看详细日志，或点击「导出错误报告」提交到 GitHub Issue。");
        }
        return report;
    }

    /** 读文件末尾（最多 maxBytes 字节），按 UTF-8 解码 */
    private static String readTail(File file, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (length - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}