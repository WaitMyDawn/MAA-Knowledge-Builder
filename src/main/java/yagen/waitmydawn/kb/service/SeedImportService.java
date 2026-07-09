package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.model.TrainingSeed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 导入训练种子 — 读取 .maa-seed.json，匹配本地 JAR。
 */
public class SeedImportService {

    private static final Logger log = LoggerFactory.getLogger(SeedImportService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseBuilder db;
    private final JarScannerService scanner;
    private final ModMetadataParser parser;

    public SeedImportService(DatabaseBuilder db) {
        this.db = db;
        this.scanner = new JarScannerService();
        this.parser = new ModMetadataParser();
    }

    /**
     * 解析种子文件。
     */
    public TrainingSeed readSeed(File file) throws IOException {
        return mapper.readValue(file, TrainingSeed.class);
    }

    /**
     * 对比种子与本地 JAR 目录。
     * @return 匹配结果: matched / missing / unknown entries
     */
    public MatchResult match(TrainingSeed seed, Path localModsDir) {
        MatchResult result = new MatchResult();

        // 扫描本地 JAR
        List<Path> localJars = scanner.scan(localModsDir);
        Map<String, Path> localByModId = new HashMap<>();
        for (Path jar : localJars) {
            ModEntry me = parser.parse(jar);
            if (me != null && me.getModId() != null) {
                localByModId.put(me.getModId(), jar);
            }
        }

        // 对比种子的模组列表
        for (TrainingSeed.SeedMod sm : seed.getMods()) {
            if (sm.modId != null && localByModId.containsKey(sm.modId)) {
                result.matched.add(sm);
            } else if (sm.slug != null) {
                result.missingDownloadable.add(sm);  // 有 slug → 可下载
            } else {
                result.missingManual.add(sm);         // 无 slug → 需手动
            }
        }

        result.seed = seed;
        result.localModPaths = localByModId;

        log.info("种子匹配: {} matched, {} downloadable, {} manual",
                result.matched.size(), result.missingDownloadable.size(), result.missingManual.size());
        return result;
    }

    /** 种子匹配结果 */
    public static class MatchResult {
        public TrainingSeed seed;
        public List<TrainingSeed.SeedMod> matched = new ArrayList<>();
        public List<TrainingSeed.SeedMod> missingDownloadable = new ArrayList<>();
        public List<TrainingSeed.SeedMod> missingManual = new ArrayList<>();
        public Map<String, Path> localModPaths = new HashMap<>();  // modId → local JAR path

        public boolean allMatched() {
            return missingDownloadable.isEmpty() && missingManual.isEmpty();
        }
    }
}
