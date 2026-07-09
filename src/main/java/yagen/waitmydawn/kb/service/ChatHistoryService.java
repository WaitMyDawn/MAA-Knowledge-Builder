package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * 对话历史持久化服务。
 * 保存为 {cacheDir}/sessions/{sessionId}.json
 */
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Path cacheDir;

    public ChatHistoryService(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public void setCacheDir(Path dir) { this.cacheDir = dir; }
    public Path getCacheDir() { return cacheDir; }

    /** A single chat message */
    public record ChatMessage(String role, String content, String timestamp) {}

    /** A chat session */
    public static class ChatSession {
        public String sessionId;
        public String title;
        public String dataDir;
        public String createdAt;
        public List<ChatMessage> messages = new ArrayList<>();

        public ChatSession() {}
    }

    private static final DateTimeFormatter SESSION_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Start a new session. ID format: session_yyyyMMddHHmmss, with _N dedup. */
    public ChatSession newSession(String title, Path dataDir) {
        ChatSession s = new ChatSession();
        String baseId = "session_" + LocalDateTime.now().format(SESSION_ID_FMT);
        s.sessionId = dedupSessionId(baseId);
        s.title = title;
        s.dataDir = dataDir.toString();
        s.createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return s;
    }

    /** Ensure unique session ID: append _1, _2, ... if already exists */
    private String dedupSessionId(String baseId) {
        // Collect existing IDs from the sessions directory
        Set<String> existing = new HashSet<>();
        try {
            File[] files = cacheDir.resolve("sessions").toFile()
                    .listFiles(f -> f.getName().endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    existing.add(f.getName().replace(".json", ""));
                }
            }
        } catch (Exception ignored) {}

        if (!existing.contains(baseId)) return baseId;

        int n = 1;
        while (existing.contains(baseId + "_" + n)) n++;
        return baseId + "_" + n;
    }

    /** Add a message to a session */
    public void addMessage(ChatSession session, String role, String content) {
        session.messages.add(new ChatMessage(role, content,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
    }

    /** Save session to file */
    public void save(ChatSession session) throws IOException {
        Path dir = cacheDir.resolve("sessions");
        Files.createDirectories(dir);
        File file = dir.resolve(session.sessionId + ".json").toFile();
        mapper.writeValue(file, session);
    }

    /** Load session from file */
    public ChatSession load(String sessionId) throws IOException {
        File file = cacheDir.resolve("sessions").resolve(sessionId + ".json").toFile();
        if (!file.exists()) return null;
        return mapper.readValue(file, ChatSession.class);
    }

    /** List all saved sessions */
    public List<ChatSession> listSessions() throws IOException {
        List<ChatSession> list = new ArrayList<>();
        Path dir = cacheDir.resolve("sessions");
        if (!Files.exists(dir)) return list;
        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(".json"));
        if (files == null) return list;
        for (File f : files) {
            try {
                list.add(mapper.readValue(f, ChatSession.class));
            } catch (Exception e) { log.warn("Failed to load session: {}", f.getName()); }
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }
}
