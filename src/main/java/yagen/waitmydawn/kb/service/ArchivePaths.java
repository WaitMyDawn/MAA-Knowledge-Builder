package yagen.waitmydawn.kb.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 不可信归档条目只能写入指定目录，禁止穿越及已有符号链接。 */
public final class ArchivePaths {
    private ArchivePaths() {}

    public static Path resolve(Path root, String relative) throws IOException {
        if (relative.contains("\\") || relative.contains(":")) throw new IOException("Invalid archive path");
        Path base = root.toAbsolutePath().normalize();
        Path entry = Path.of(relative);
        if (entry.isAbsolute()) throw new IOException("Absolute archive path");
        for (Path segment : entry) if (segment.toString().equals("..")) throw new IOException("Archive path traversal");
        Path target = base.resolve(entry).normalize();
        if (!target.startsWith(base)) throw new IOException("Archive path escapes destination");
        for (Path parent = target; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Symbolic link in archive destination");
        }
        return target;
    }
}
