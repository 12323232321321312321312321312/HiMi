package org.example;

public class MinecraftVersion {
    private final String id;
    private final String type; // 比如 Vanilla, Fabric, Forge

    public MinecraftVersion(String id, String type) {
        this.id = id;
        this.type = type;
    }

    public String getId() { return id; }
    public String getType() { return type; }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }
}