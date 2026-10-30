package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.KnowledgeContent.ImageAsset;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 不可变图片先落盘、再发布数据库引用；旧资产保留以支持历史会话。 */
public final class ImageAssetStore {
    private final Path root;
    public ImageAssetStore(Path dataDir) { root = dataDir.toAbsolutePath().normalize().resolve("assets/images"); }
    Path directory(){return root.getParent().getParent();}

    public ImageAsset put(byte[] bytes) throws IOException {
        return put(bytes,null);
    }

    /** 只恢复快照原有身份；来源内容变化时不能悄悄替换历史插图。 */
    public ImageAsset restore(String expectedId, byte[] bytes) throws IOException {
        if(expectedId==null || !expectedId.matches("[0-9a-f]{64}")) throw new IOException("无效图片身份");
        return put(bytes,expectedId);
    }

    private ImageAsset put(byte[] bytes,String expectedId) throws IOException {
        if (bytes.length == 0 || bytes.length > 32 * 1024 * 1024) throw new IOException("图片文件大小超限");
        String format;
        int width, height;
        try (var stream = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("无法识别图片格式");
            var reader = readers.next();
            try {
                reader.setInput(stream); format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                width = reader.getWidth(0); height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 40_000_000)
                    throw new IOException("图片像素数量超限");
                if (!java.util.Set.of("png", "jpeg", "jpg", "gif", "webp").contains(format)) throw new IOException("不支持的图片格式");
                var decoded = reader.read(0);
                if (decoded == null) throw new IOException("图片内容不完整");
                if (format.equals("webp")) {
                    // JavaFX 的图片加载器不能直接显示 WebP，资产统一保存为可展示的 PNG。
                    var output = new java.io.ByteArrayOutputStream();
                    if (!ImageIO.write(decoded, "png", output)) throw new IOException("WebP 转换失败");
                    bytes = output.toByteArray(); format = "png";
                    if (bytes.length > 32 * 1024 * 1024) throw new IOException("转换后的图片文件大小超限");
                }
            } finally { reader.dispose(); }
        }
        String hash = hash(bytes);
        if(expectedId!=null && !expectedId.equals(hash)) throw new IOException("来源图片或解码结果与原快照不一致，无法恢复原图");
        String extension = format.equals("jpeg") ? "jpg" : format;
        String relative = hash.substring(0, 2) + "/" + hash + "." + extension;
        Path file = ArchivePaths.resolve(root, relative);
        Files.createDirectories(file.getParent());
        if (!valid(file,hash)) {
            var task=BuildTaskJournal.current();
            Path temp = Files.createTempFile(task==null?file.getParent():task.temporaryDirectory(), "asset-", ".tmp");
            try {
                Files.write(temp, bytes);
                try { Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file,java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                if(task!=null)task.created(hash,bytes.length);
            } finally { Files.deleteIfExists(temp); }
        }
        if (!hash.equals(hash(Files.readAllBytes(file)))) throw new IOException("图片缓存内容校验失败");
        return new ImageAsset(hash, relative, "image/" + (extension.equals("jpg") ? "jpeg" : extension), width, height, null, null);
    }

    public Path resolve(String id,String relative) throws IOException {
        if(id==null || !id.matches("[0-9a-f]{64}") || relative==null) throw new IOException("无效图片身份或路径");
        Path file=ArchivePaths.resolve(root,relative);
        if(!relative.matches(id.substring(0,2)+"/"+id+"\\.(png|jpg|gif)")) throw new IOException("图片路径与身份不匹配");
        return file;
    }

    public boolean available(String id,String relative) {
        try {return valid(resolve(id,relative),id);} catch(IOException | IllegalArgumentException e) {return false;}
    }

    private static boolean valid(Path file,String id) throws IOException {
        return Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(file)<=32L*1024*1024
                && id.equals(hash(Files.readAllBytes(file)));
    }

    public void validate(KnowledgeContentStore content, yagen.waitmydawn.kb.model.DatabaseBuilder db) throws IOException {
        content.validate();
        try (var c = db.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT id,relative_path FROM rag_image_asset")) {
            while (r.next()) {
                Path file = ArchivePaths.resolve(root, r.getString(2));
                if (!Files.isRegularFile(file) || Files.size(file) > 32L * 1024 * 1024
                        || !r.getString(1).equals(hash(Files.readAllBytes(file))))
                    throw new IOException("图片资产丢失或损坏，拒绝发布");
            }
        } catch (java.sql.SQLException e) { throw new IOException("图片资产校验失败", e); }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
