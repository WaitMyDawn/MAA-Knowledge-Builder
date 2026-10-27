package yagen.waitmydawn.kb.model;

import java.nio.file.Path;
import java.util.*;

/** 用户确认的安装环境用于检索筛选，不改变 JAR 声明的模组加载器身份。 */
public record GameEnvironment(String jarPath,String loader,String minecraftVersion) {
    public GameEnvironment {
        jarPath=Path.of(Objects.requireNonNull(jarPath)).toAbsolutePath().normalize().toString();
        loader=Objects.requireNonNullElse(loader,"").toLowerCase(Locale.ROOT);
        if(!Set.of("neoforge","forge","fabric").contains(loader))throw new IllegalArgumentException("请选择 NeoForge、Forge 或 Fabric");
        minecraftVersion=Objects.requireNonNullElse(minecraftVersion,"").strip();
        if(!minecraftVersion.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}"))throw new IllegalArgumentException("请输入有效的 Minecraft 版本号");
    }
    public String loaderLabel(){return switch(loader){case "neoforge"->"NeoForge";case "forge"->"Forge";default->"Fabric";};}
    public String summary(){return Path.of(jarPath).getFileName()+" + "+loaderLabel()+" + "+minecraftVersion;}
}
