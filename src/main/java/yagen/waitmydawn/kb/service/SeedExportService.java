package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.model.TrainingSeed;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 导出训练种子 (.maa-seed.json)。
 */
public class SeedExportService {

    private static final Logger log = LoggerFactory.getLogger(SeedExportService.class);
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final DatabaseBuilder db;

    public SeedExportService(DatabaseBuilder db) {
        this.db = db;
    }

    /**
     * 从数据库导出当前知识库的模组列表到 .maa-seed.json。
     */
    public TrainingSeed export(String name, String description, String author) {
        TrainingSeed seed = new TrainingSeed();
        seed.setName(name);
        seed.setDescription(description);
        seed.setAuthor(author);
        seed.setCreatedAt(LocalDate.now().toString());

        List<ModEntry> mods = db.findAllModEntries();
        for (ModEntry e : mods) {
            seed.getMods().add(TrainingSeed.SeedMod.from(e));
        }

        log.info("导出训练种子: {} 个模组", seed.getMods().size());
        return seed;
    }

    /**
     * 将种子写入文件。
     */
    public void writeToFile(TrainingSeed seed, File file) throws IOException {
        mapper.writeValue(file, seed);
        log.info("训练种子已保存: {}", file.getAbsolutePath());
    }

    /**
     * 一键导出到文件。
     */
    public File exportToFile(String name, String description, String author, File outputFile) throws IOException {
        TrainingSeed seed = export(name, description, author);
        writeToFile(seed, outputFile);
        return outputFile;
    }
}
