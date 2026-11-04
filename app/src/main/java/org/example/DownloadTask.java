package org.example;

public class DownloadTask {
    private String taskName;
    private String status; // "等待中", "下载中...", "完成", "失败"

    public DownloadTask(String taskName) {
        this.taskName = taskName;
        this.status = "等待中";
    }

    public String getTaskName() { return taskName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public String toString() {
        return taskName + " : " + status;
    }
}