package yagen.waitmydawn.kb.service;

/** 已确认的知识来源继承；安装身份和 JAR 数据始终保持原命名空间。 */
public final class KnowledgeSourcePolicy {
    private KnowledgeSourcePolicy() {}
    public static String canonicalClassId(String modId) {
        // 社区版沿用 iceandfire 命名空间，机制条目集中在原作目录。
        return "iceandfire".equals(modId)?"770":null;
    }
    public static String describe(String modId, String minecraftVersion) {
        String policy = describe(modId);
        if ("minecraft".equals(modId) && before(minecraftVersion, "1.21.11"))
            policy += "；版本限制：矛（spear）是 Java 1.21.11 新增武器，当前安装版没有此物品。不要把新版 Wiki 的矛及其配套战术推荐为当前版本玩法。来源：https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11";
        return policy;
    }
    public static String describe(yagen.waitmydawn.kb.model.ModEntry mod) {
        if ("minecraft".equals(mod.getModId())) return describe(mod.getModId(),mod.getMcVersion());
        var binding=mod.getMcmodBinding();
        return binding==null?"百科来源按已构建目录确定":binding.url()==null?"仅使用本地模组资料":"已绑定百科来源："+binding.url()+"；JAR 配方与注册名优先；百科绑定为用户可校对的候选";
    }
    private static boolean before(String version, String minimum) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) return false;
        try {
            String[] actual = version.split("\\."), bound = minimum.split("\\.");
            for (int i = 0; i < bound.length; i++) {
                int a = i < actual.length ? Integer.parseInt(actual[i]) : 0;
                int b = Integer.parseInt(bound[i]);
                if (a != b) return a < b;
            }
        } catch (NumberFormatException invalid) { return false; }
        return false;
    }
    public static String describe(String modId) {
        String id=canonicalClassId(modId);
        return id==null?"百科来源按已构建目录确定":"基础机制来源：https://www.mcmod.cn/class/"+id+".html；JAR 配方与注册名优先；有证据的分支新增／改动单独处理";
    }
}
