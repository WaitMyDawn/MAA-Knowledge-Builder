package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.cacheSmoke",matches="true")
class ImageCachePaneSmokeTest {
    @TempDir Path temp;
    @Test void auditsThenCleansOnlyOldUnreferencedImages() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();new VectorStore(db,384);
        var store=new ImageAssetStore(temp);
        var referenced=store.put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));
        new KnowledgeContentStore(db).saveImage(referenced);Files.delete(store.resolve(referenced.id(),referenced.relativePath()));
        var sample=new java.awt.image.BufferedImage(12,12,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(sample,"png",bytes);
        var orphan=store.put(bytes.toByteArray());
        Files.setLastModifiedTime(store.resolve(orphan.id(),orphan.relativePath()),FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        var executor=Executors.newSingleThreadExecutor();var done=new CompletableFuture<Void>();var step=new AtomicInteger();
        Platform.startup(()->{
            try {
                var pane=new ImageCachePane(db,temp,()->false,executor::submit);var root=new VBox(12,pane);root.setPadding(new javafx.geometry.Insets(20));
                var scene=new Scene(root,1000,480);root.applyCss();root.layout();
                var status=(TextArea)pane.lookupAll(".text-area").iterator().next();
                var clean=(Button)pane.lookupAll(".button").stream().filter(n -> n instanceof Button b && b.getText().startsWith("清理7天")).findFirst().orElseThrow();
                var inspect=(Button)pane.lookupAll(".button").stream().filter(n -> n instanceof Button b && b.getText().equals("检查图片缓存")).findFirst().orElseThrow();
                status.textProperty().addListener((observable,old,text)->{
                    try {
                        if(text.startsWith("图片文件：") && step.compareAndSet(0,1)) {
                            assertTrue(text.contains("缺失：1"));assertTrue(text.contains("可清理：1"));
                            Platform.runLater(clean::fire);
                        } else if(text.startsWith("已清理：") && step.compareAndSet(1,2)) {
                            assertTrue(text.startsWith("已清理：1"));assertTrue(text.contains("缺失：1"));
                            assertFalse(Files.exists(store.resolve(orphan.id(),orphan.relativePath())));
                            Platform.runLater(()->{
                                try {
                                    root.applyCss();root.layout();var snapshot=scene.snapshot(null);
                                    int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();int[] pixels=new int[width*height];
                                    snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                                    var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);
                                    javax.imageio.ImageIO.write(image,"png",Path.of("target/stage5-cache-preview.png").toFile());done.complete(null);
                                } catch(Throwable e) {done.completeExceptionally(e);}
                            });
                        } else if(text.startsWith("维护未完成")) throw new AssertionError(text);
                    } catch(Throwable e) {done.completeExceptionally(e);}
                });
                inspect.fire();
            } catch(Throwable e) {done.completeExceptionally(e);}
        });
        try {done.get(30,TimeUnit.SECONDS);} finally {executor.shutdownNow();Platform.exit();db.shutdown();}
    }
}
