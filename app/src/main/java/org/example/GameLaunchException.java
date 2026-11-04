package org.example;

/**
 * 携带崩溃分析结果的启动异常。
 * MainController 捕获此异常时，可以展示人性化的崩溃分析弹窗。
 */
public class GameLaunchException extends Exception {

    private final String versionId;
    private final String baseDir;
    private final int exitCode;
    private final CrashAnalyzer.CrashReport report;

    public GameLaunchException(String message, String versionId, String baseDir, int exitCode, CrashAnalyzer.CrashReport report) {
        super(message);
        this.versionId = versionId;
        this.baseDir = baseDir;
        this.exitCode = exitCode;
        this.report = report;
    }

    public String getVersionId() { return versionId; }
    public String getBaseDir() { return baseDir; }
    public int getExitCode() { return exitCode; }
    public CrashAnalyzer.CrashReport getReport() { return report; }
}