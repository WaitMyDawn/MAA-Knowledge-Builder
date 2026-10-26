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
    public record ChatMessage(String role, String content, String timestamp, yagen.waitmydawn.kb.model.AnswerArtifacts artifacts) {
        public ChatMessage(String role,String content,String timestamp) { this(role,content,timestamp,null); }
    }

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
        addMessage(session, role, content, null);
    }

    public void addMessage(ChatSession session, String role, String content, yagen.waitmydawn.kb.model.AnswerArtifacts artifacts) {
        session.messages.add(new ChatMessage(role, content,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), artifacts));
    }

    /** Save session to file */
    public void save(ChatSession session) throws IOException {
        Path dir = cacheDir.resolve("sessions");
        Files.createDirectories(dir);
        validateSessionId(session.sessionId);
        Path file = dir.resolve(session.sessionId + ".json");
        Path temporary = Files.createTempFile(dir, "session-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), session);
            try { Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void validateSessionId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid session ID");
    }

    /** Load session from file */
    public ChatSession load(String sessionId) throws IOException {
        validateSessionId(sessionId);
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
                var session = mapper.readValue(f, ChatSession.class);
                validateSessionId(session.sessionId);
                if (!f.getName().equals(session.sessionId + ".json")) throw new IOException("Session identity mismatch");
                list.add(session);
            } catch (Exception e) { log.warn("Failed to load session: {}", f.getName()); }
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }
}
