package yagen.waitmydawn.kb.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 数据目录独占锁（进程级）。
 *
 * <p>背景：MKB 使用嵌入式 H2 文件库，**同一 dataDir 被两个进程同时写入会损坏 MVStore**
 * （2026-09-15 实测：IntelliJ Database 工具与评测 CLI 同时连接 → `Double mark` 损坏）。
 * 本锁在进程启动时获取，GUI 与 CLI 之间互斥；获取失败时给出明确提示，而不是继续写坏数据。
 *
 * <p>注意：它无法阻止外部工具（如 IDE 的 JDBC 客户端）直接连接 H2 文件；这类连接仍需避免。
 */
public final class DataDirLock implements AutoCloseable {

    private static final String LOCK_FILE = ".mkb.lock";

    private final FileChannel channel;
    private final FileLock lock;
    private final Path file;
    private String recoverySummary="";
    public String recoverySummary(){return recoverySummary;}

    private DataDirLock(FileChannel channel, FileLock lock, Path file) {
        this.channel = channel;
        this.lock = lock;
        this.file = file;
    }

    /**
     * 尝试获取 dataDir 的独占锁。
     *
     * @throws IOException 已被其它 MKB 进程占用（或 IO 失败）时抛出，消息可直接展示给用户
     */
    public static DataDirLock acquire(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(LOCK_FILE);
        FileChannel channel;
        try {
            channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IOException(occupiedMessage(dataDir) + "（" + e.getMessage() + "）", e);
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (Exception e) {
            channel.close();
            throw new IOException(occupiedMessage(dataDir) + "（" + e.getMessage() + "）", e);
        }
        if (lock == null) {
            channel.close();
            throw new IOException(occupiedMessage(dataDir));
        }
        var held=new DataDirLock(channel,lock,file);
        try{var recovery=yagen.waitmydawn.kb.service.BuildTaskJournal.recover(dataDir);if(recovery.files()>0||!recovery.warnings().isEmpty())held.recoverySummary="遗留构建清理：任务 "+recovery.tasks()+" 个，暂存文件 "+recovery.files()+" 个；缓存图片保留。"+(recovery.warnings().isEmpty()?"":" 部分文件未能清理，详情见日志。");}
        catch(IOException failure){org.slf4j.LoggerFactory.getLogger(DataDirLock.class).warn("Abandoned build cleanup incomplete",failure);}
        return held;
    }

    private static String occupiedMessage(Path dataDir) {
        return "数据目录已被其他进程占用/锁定：" + dataDir
                + "\nMKB 使用嵌入式 H2 文件库，同一 dataDir 不能被多个进程同时写入（会损坏数据）。"
                + "\n请先关闭已打开的 GUI / 评测进程，并断开 IDE（如 IntelliJ Database 工具）的数据库连接后重试。";
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } catch (Exception ignored) {
        }
        try {
            channel.close();
        } catch (Exception ignored) {
        }
        // 保留锁文件，避免释放后删除另一个进程刚获取锁的文件。
    }
}
