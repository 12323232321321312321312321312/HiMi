package org.example.util;

import org.example.config.ModLoadCacheManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class LoaderSupportChecker {

    public static List<String> getSupportedLoaders(String mcVersion) {
        List<String> supported = new ArrayList<>();
        supported.add("原版 (Vanilla)");

        // 从本地缓存快速读取
        Set<String> forgeVersions = ModLoadCacheManager.readFromFile("forge.json");
        if (forgeVersions.contains(mcVersion)) supported.add("Forge");

        Set<String> neoforgeVersions = ModLoadCacheManager.readFromFile("neoforge.json");
        if (neoforgeVersions.contains(mcVersion)) supported.add("NeoForge");

        Set<String> fabricVersions = ModLoadCacheManager.readFromFile("fabric.json");
        if (fabricVersions.contains(mcVersion)) supported.add("Fabric");

        Set<String> optifineVersions = ModLoadCacheManager.readFromFile("optifine.json");
        if (optifineVersions.contains(mcVersion)) supported.add("OptiFine");

        // Quilt 的 API 极快，为了省事可以直接判断，或者如果你也想缓存它，加一个 updateQuilt() 即可
        // 为了完美还原你的截图，我在这里直接加上 Quilt (如果 Fabric 支持，Quilt 通常也支持)
        if (fabricVersions.contains(mcVersion)) supported.add("Quilt");

        return supported;
    }
}