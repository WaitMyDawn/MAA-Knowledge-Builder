package yagen.waitmydawn.kb.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 文件日志初始化 — 在 dataDir/logs/ 下创建 yyyy-MM-dd-x.log 文件。
 * 类似于 Minecraft 的日志命名规则，每次启动递增编号。
 */
public class LogSetup {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static volatile boolean initialized = false;
    private static FileAppender<ILoggingEvent> fileAppender;

    /** Initialize file logging under dataDir/logs/. Must be called after AppConfig is set. */
    public static synchronized void init(Path dataDir) {
        if (initialized) return;

        try {
            Path logDir = dataDir.resolve("logs");
            Files.createDirectories(logDir);

            String today = LocalDate.now().format(DATE_FMT);
            Path logFile = findNextLogFile(logDir, today);

            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(lc);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
            encoder.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            encoder.start();

            fileAppender = new FileAppender<>();
            fileAppender.setContext(lc);
            fileAppender.setName("FILE");
            fileAppender.setFile(logFile.toAbsolutePath().toString());
            fileAppender.setEncoder(encoder);
            fileAppender.setAppend(true);
            fileAppender.start();

            Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            rootLogger.addAppender(fileAppender);

            initialized = true;
            rootLogger.info("Log file: {}", logFile.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("Failed to initialize file logging: " + e.getMessage());
        }
    }

    /** Find the next available log file number for today. */
    private static Path findNextLogFile(Path logDir, String today) {
        int x = 1;
        while (true) {
            Path candidate = logDir.resolve(today + "-" + x + ".log");
            if (!Files.exists(candidate)) return candidate;
            // File exists but is empty/small → reuse it (crashed session?)
            try {
                if (Files.size(candidate) == 0) return candidate;
            } catch (IOException ignored) {}
            x++;
        }
    }

    /** Stop and remove the file appender (called on app shutdown). */
    public static synchronized void shutdown() {
        if (fileAppender != null) {
            fileAppender.stop();
            fileAppender = null;
            initialized = false;
        }
    }
}
