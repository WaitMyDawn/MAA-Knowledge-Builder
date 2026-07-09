package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 扫描用户选定的文件夹，递归发现所有 .jar 文件。
 */
public class JarScannerService {

    private static final Logger log = LoggerFactory.getLogger(JarScannerService.class);

    /**
     * 递归扫描目录，返回所有 .jar 文件的绝对路径。
     */
    public List<Path> scan(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.warn("Not a valid directory: {}", directory);
            return Collections.emptyList();
        }

        List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                  .forEach(jars::add);
        } catch (IOException e) {
            log.error("Failed to scan directory: {}", e.getMessage(), e);
        }

        log.info("Scanning {} reveals {} JAR files", directory, jars.size());
        return jars;
    }
}
