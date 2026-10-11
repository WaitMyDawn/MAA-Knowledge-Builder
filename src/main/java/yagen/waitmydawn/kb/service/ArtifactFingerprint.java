package yagen.waitmydawn.kb.service;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** 用于模型、代码与评测输入的可重复内容指纹。 */
public final class ArtifactFingerprint {
    private ArtifactFingerprint() {}

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = input.read(bytes)) >= 0) digest.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sourceTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) return "unavailable-packaged-runtime";
        MessageDigest digest = digest();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(sha256(file).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
