package yagen.waitmydawn.kb.service;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** 用户网址与正文资源只能访问公共 HTTP(S) 服务，重定向逐跳应用同一策略。 */
final class WebUrlPolicy {
    private WebUrlPolicy() {}

    static String normalize(String url) {
        URI uri = URI.create(url.strip()).normalize();
        String host = uri.getHost(), scheme = uri.getScheme();
        if (host == null || scheme == null || uri.getRawUserInfo() != null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443))
            throw new IllegalArgumentException("不支持的网页地址");
        host = host.toLowerCase(Locale.ROOT);
        String name = host.replaceFirst("\\.$", "");
        if (name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local")
                || name.endsWith(".internal") || !name.contains("."))
            throw new IllegalArgumentException("网页地址必须指向公共站点");
        return yagen.waitmydawn.kb.model.BuildRequest.Source.of(
                yagen.waitmydawn.kb.model.BuildRequest.Kind.URL, uri.toString()).location();
    }

    static void validateResolved(String url) throws java.net.UnknownHostException {
        String normalized = normalize(url);
        for (InetAddress address : InetAddress.getAllByName(URI.create(normalized).getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                    || (bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) >= 224
                    || ((bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127))))
                throw new IllegalArgumentException("拒绝访问非公共网络地址");
        }
    }
}
