package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 多数据库管理器 — 扫描数据目录 + sessions/incremental/ 下的 .mv.db 文件。
 * 增量数据库存储在 sessions/incremental/ 中，随会话删除而清理。
 */
public class MultiDBManager {

    private static final Logger log = LoggerFactory.getLogger(MultiDBManager.class);

    private final Path dataDir;
    private final Path incDir;           // sessions/incremental/
    private final Map<String, DBInfo> allDbs = new LinkedHashMap<>();
    private final DatabaseBuilder baseDb;
    private IncrementalDB currentIncDB;
    private String currentSessionId;

    public MultiDBManager(Path dataDir, DatabaseBuilder baseDb) {
        this.dataDir = dataDir;
        this.incDir = dataDir.resolve("sessions").resolve("incremental");
        this.baseDb = baseDb;
        try { Files.createDirectories(incDir); } catch (Exception ignored) {}
    }

    public record DBInfo(String filename, String displayName, long sizeBytes, boolean isBase, boolean enabled) {}

    /** Scan dataDir root + sessions/incremental/ for all .mv.db files */
    public List<DBInfo> scan() {
        allDbs.clear();
        try {
            // Scan dataDir root (base DB only)
            try (Stream<Path> files = Files.list(dataDir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".mv.db"))
                        .filter(f -> { try { return Files.size(f) > 1024; } catch (Exception e) { return false; }})
                        .forEach(f -> registerDb(f, false));
            }
            // Scan incremental dir
            if (Files.exists(incDir)) {
                try (Stream<Path> files = Files.list(incDir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".mv.db"))
                            .filter(f -> { try { return Files.size(f) > 0; } catch (Exception e) { return false; }})
                            .forEach(f -> registerDb(f, true));
                }
            }
            // Ensure base is always present and enabled
            if (!allDbs.containsKey("rag_data.mv.db")) {
                allDbs.put("rag_data.mv.db", new DBInfo("rag_data.mv.db", "rag_data", 0, true, true));
            }
        } catch (Exception e) { log.warn("DB scan failed: {}", e.getMessage()); }
        return new ArrayList<>(allDbs.values());
    }

    private void registerDb(Path f, boolean isInc) {
        String filename = f.getFileName().toString();
        String baseName = filename.replace(".mv.db", "");
        long size;
        try { size = Files.size(f); } catch (Exception e) { size = 0; }
        boolean isBase = "rag_data".equals(baseName);
        boolean enabled = isBase || baseName.equals("rag_data_" + currentSessionId);
        allDbs.put(filename, new DBInfo(filename, baseName, size, isBase, enabled));
    }

    public void setEnabled(String filename, boolean enabled) {
        DBInfo info = allDbs.get(filename);
        if (info != null) {
            allDbs.put(filename, new DBInfo(info.filename, info.displayName, info.sizeBytes, info.isBase, enabled));
        }
    }

    public List<String> getEnabledDbs() {
        return allDbs.values().stream()
                .filter(DBInfo::enabled)
                .map(DBInfo::filename)
                .toList();
    }

    /** Get DatabaseBuilder instances for all enabled DBs. */
    public List<DatabaseBuilder> getActiveDatabases() {
        List<DatabaseBuilder> dbs = new ArrayList<>();
        dbs.add(baseDb);
        for (DBInfo info : allDbs.values()) {
            if (info.enabled && !info.isBase) {
                IncrementalDB incDb = openIncrementalDB(info.displayName);
                if (incDb != null) dbs.add(incDb.getDb());
            }
        }
        return dbs;
    }

    /** Create or get incremental DB for current session (stored in sessions/incremental/) */
    public IncrementalDB createIncrementalDB(String sessionId, int embeddingDim) {
        this.currentSessionId = sessionId;
        String dbName = "rag_data_" + sessionId.replaceAll("[^a-zA-Z0-9_-]", "");
        currentIncDB = new IncrementalDB(incDir, dbName, embeddingDim);
        allDbs.put(currentIncDB.getDbFile().getFileName().toString(),
                new DBInfo(currentIncDB.getDbFile().getFileName().toString(), dbName, 0, false, true));
        return currentIncDB;
    }

    public IncrementalDB getCurrentIncDB() { return currentIncDB; }
    public String getCurrentSessionId() { return currentSessionId; }

    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
        scan();
    }

    /** Open an existing incremental DB from sessions/incremental/ */
    private IncrementalDB openIncrementalDB(String dbName) {
        try {
            Path dbFile = incDir.resolve(dbName + ".mv.db");
            if (!Files.exists(dbFile)) return null;
            return new IncrementalDB(incDir, dbName, 384);
        } catch (Exception e) {
            log.warn("Open incremental DB failed: {}", e.getMessage());
            return null;
        }
    }

    /** Delete an incremental DB from sessions/incremental/ */
    public boolean delete(String filename) {
        DBInfo info = allDbs.get(filename);
        if (info == null || info.isBase) return false;
        try {
            String baseName = info.displayName;
            Files.deleteIfExists(incDir.resolve(baseName + ".mv.db"));
            Files.deleteIfExists(incDir.resolve(baseName + ".trace.db"));
            allDbs.remove(filename);
            return true;
        } catch (Exception e) { return false; }
    }

    /** Delete the incremental DB for a specific session by session ID */
    public boolean deleteBySessionId(String sessionId) {
        String dbName = "rag_data_" + sessionId.replaceAll("[^a-zA-Z0-9_-]", "");
        try {
            Files.deleteIfExists(incDir.resolve(dbName + ".mv.db"));
            Files.deleteIfExists(incDir.resolve(dbName + ".trace.db"));
            return true;
        } catch (Exception e) { return false; }
    }

    public Path getIncDir() { return incDir; }
}
