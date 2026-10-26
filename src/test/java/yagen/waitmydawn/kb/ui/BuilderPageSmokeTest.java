package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.MaaKnowledgeBuilderApp;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.builderSmoke",matches="true")
class BuilderPageSmokeTest {
    @TempDir Path temp;
    @Test void existingKnowledgeDefaultsToAppendAndExposesTargetSelection() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);
        var db=new DatabaseBuilder(config);db.initDatabase();var vectors=new VectorStore(db,384);
        try(var embedder=new EmbeddingService(null)) {
            vectors.storeBatch(List.of(new TextChunker.TextChunk("old","已有资料",null,"示例","local_document","test")),embedder);
        }
        var done=new CompletableFuture<Void>();
        Platform.startup(()->{
            try {
                var app=new MaaKnowledgeBuilderApp();
                field(app,"config",config);field(app,"db",db);field(app,"vectorStore",vectors);
                var method=MaaKnowledgeBuilderApp.class.getDeclaredMethod("buildBuilderPage",javafx.stage.Stage.class);method.setAccessible(true);
                var root=(VBox)method.invoke(app,new Object[]{null});
                var scene=new Scene(root,1150,900);root.applyCss();root.layout();
                var mode=(ComboBox<?>)field(app,"buildModeChoice",null);
                assertEquals(BuildRequest.Mode.APPEND,mode.getValue());
                assertTrue(root.lookupAll(".button").stream().anyMatch(node -> node instanceof Button b && b.getText().equals("选择已有知识库")));
                var snapshot=scene.snapshot(null);int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();
                int[] pixels=new int[width*height];snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);
                javax.imageio.ImageIO.write(image,"png",Path.of("target/append-builder-preview.png").toFile());
                done.complete(null);
            } catch(Throwable failure) {done.completeExceptionally(failure);}
        });
        try {done.get(30,TimeUnit.SECONDS);} finally {Platform.exit();db.shutdown();LogSetup.shutdown();}
    }
    private Object field(Object target,String name,Object value) throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);
        if(value!=null) field.set(target,value);
        return field.get(target);
    }
}
