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
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.reviewSmoke",matches="true")
class SessionReviewSmokeTest {
    @TempDir Path temp;
    @Test void refreshExposesExternallyImportedSessionsAndTheirFullTitle() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);
        var db=new DatabaseBuilder(config);db.initDatabase();
        Path pack=Path.of("examples/MKB-Export").toAbsolutePath();
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(java.util.List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND),null);
        }
        var manager=new MultiDBManager(temp,db);var history=new ChatHistoryService(temp);
        var done=new CompletableFuture<Void>();
        Platform.startup(()->{
            try {
                var app=new MaaKnowledgeBuilderApp();
                field(app,"config",config);field(app,"db",db);field(app,"dbManager",manager);field(app,"chatHistoryService",history);
                var method=MaaKnowledgeBuilderApp.class.getDeclaredMethod("buildQAPage");method.setAccessible(true);
                var root=(VBox)method.invoke(app);var scene=new Scene(root,1150,900);
                root.applyCss();root.layout();
                var profileField=MaaKnowledgeBuilderApp.class.getDeclaredField("iconProfileChoice");profileField.setAccessible(true);
                @SuppressWarnings("unchecked") var profiles=(ComboBox<ItemIconStore.Profile>)profileField.get(app);
                assertNull(new ItemIconStore(db).activeProfileId());
                var selected=profiles.getItems().stream().filter(p->p.id()!=null).findFirst().orElseThrow();profiles.setValue(selected);
                assertEquals(selected.id(),new ItemIconStore(db).activeProfileId());
                assertTrue(root.lookupAll(".button").stream().anyMatch(n->n instanceof Button b && b.getText().equals("导入评测会话…")));
                String title="R0+R1｜外部导入评测完整标题验证";
                var session=history.newSession(title,temp);history.addMessage(session,"user","回放测试");history.save(session);
                var refresh=(Button)root.lookupAll(".button").stream().filter(n->n instanceof Button b && b.getText().equals("刷新对话")).findFirst().orElseThrow();refresh.fire();
                root.applyCss();root.layout();
                assertTrue(root.lookupAll(".label").stream().anyMatch(n->n instanceof Label l && l.getTooltip()!=null && l.getTooltip().getText().contains(title)));
                var snapshot=scene.snapshot(null);int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();
                int[] pixels=new int[width*height];snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);
                javax.imageio.ImageIO.write(image,"png",Path.of("target/r01-session-review-preview.png").toFile());done.complete(null);
            } catch(Throwable failure){done.completeExceptionally(failure);}
        });
        try{done.get(30,TimeUnit.SECONDS);}finally{Platform.exit();manager.close();db.shutdown();LogSetup.shutdown();}
    }
    private void field(Object target,String name,Object value)throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
