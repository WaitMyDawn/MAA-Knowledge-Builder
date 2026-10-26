package yagen.waitmydawn.kb.model;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** 重建使用完整来源集合，补充构建只声明本次需要导入的来源。 */
public record BuildRequest(List<Source> sources, Mode mode) {
    public enum Mode {
        REBUILD("重建基础库"), APPEND("补充基础库");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public BuildRequest(List<Source> sources) { this(sources, Mode.REBUILD); }
    public enum Kind { JAR_DIRECTORY, URL, DOCUMENT_DIRECTORY }

    public record Source(String id, Kind kind, String location) {
        public Source {
            Objects.requireNonNull(kind, "来源类型不能为空");
            location = normalize(kind, location);
            if (!fingerprint(kind + "\n" + location).equals(id))
                throw new IllegalArgumentException("来源 ID 与位置不匹配");
        }

        public static Source of(Kind kind, String location) {
            String normalized = normalize(kind, location);
            return new Source(fingerprint(kind + "\n" + normalized), kind, normalized);
        }

        private static String normalize(Kind kind, String location) {
            if (location == null || location.isBlank()) throw new IllegalArgumentException("来源位置不能为空");
            if (kind != Kind.URL) return Path.of(location.trim()).toAbsolutePath().normalize().toString();
            URI uri = URI.create(location.trim()).normalize();
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null)
                throw new IllegalArgumentException("网址必须为不含认证信息的 HTTP(S) 地址");
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            String path = uri.getRawPath();
            return scheme + "://" + host + (port < 0 ? "" : ":" + port)
                    + (path == null || path.isEmpty() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        }
    }

    public BuildRequest {
        mode = mode == null ? Mode.REBUILD : mode;
        Objects.requireNonNull(sources, "来源集合不能为空");
        var unique = new LinkedHashMap<String, Source>();
        for (Source source : sources) unique.put(Objects.requireNonNull(source).id(), source);
        if (unique.isEmpty()) throw new IllegalArgumentException("至少配置一个构建来源");
        sources = List.copyOf(unique.values());
    }

    public static BuildRequest jars(Path directory) {
        return new BuildRequest(List.of(Source.of(Kind.JAR_DIRECTORY, directory.toString())));
    }

    public static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
