package org.example.mod;

public class ModDependency {
    public String projectId;
    public String versionId;
    public String fileId;
    public String dependencyType;
    public String projectName; 
    public ModProject targetProject; // 🚨 新增：缓存依赖的完整项目详情
}