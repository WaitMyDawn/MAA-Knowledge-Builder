package yagen.waitmydawn.kb.config;

/** 地址顺序遵循操作系统策略；显式 JVM 配置具有优先级。须在首次 DNS 解析前调用。 */
public final class NetworkBootstrap {
    private NetworkBootstrap() {}
    public static void configure() {
        if (System.getProperty("java.net.preferIPv6Addresses") == null)
            System.setProperty("java.net.preferIPv6Addresses", "system");
    }
}
