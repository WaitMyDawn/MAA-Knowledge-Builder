package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 一次评测 run 的产物存储。
 *
 * <p>目录布局：{dataDir}/eval-runs/{runId}/
 * config.json / turns.jsonl / summary.json / report.md / bad-cases.md /
 * disagreements.md / judge/{turnId}.json / sessions/（会话 JSON + 增量库）
 */
public final class RunStore implements Closeable {

    private final Path runDir;
    private final Path judgeDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedWriter turnsWriter;

    public RunStore(Path runDir) throws IOException {
        this.runDir = runDir;
        this.judgeDir = runDir.resolve("judge");
        Files.createDirectories(judgeDir);
        this.turnsWriter = Files.newBufferedWriter(runDir.resolve("turns.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path runDir() {
        return runDir;
    }

    public void writeConfig(Map<String, Object> config) throws IOException {
        writeJson(runDir.resolve("config.json"), config);
    }

    public synchronized void appendTurn(Map<String, Object> turn) throws IOException {
        turnsWriter.write(mapper.writeValueAsString(turn));
        turnsWriter.newLine();
        turnsWriter.flush();
    }

    public void writeJudge(String key, Map<String, Object> judge) throws IOException {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        writeJson(judgeDir.resolve(safe + ".json"), judge);
    }

    public void writeSummary(Map<String, Object> summary) throws IOException {
        writeJson(runDir.resolve("summary.json"), summary);
    }

    public void writeText(String fileName, String content) throws IOException {
        Files.writeString(runDir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    /** 追加一行 run 摘要到 {dataDir}/eval-runs/index.jsonl，供跨版本对比检索。 */
    public void appendIndex(Map<String, Object> summary) throws IOException {
        Path indexPath = runDir.getParent().resolve("index.jsonl");
        Files.createDirectories(indexPath.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(summary));
            w.newLine();
        } catch (IOException e) {
            // index 写入失败不影响 run 结果
        }
    }

    @Override
    public void close() throws IOException {
        turnsWriter.close();
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8);
    }
}
