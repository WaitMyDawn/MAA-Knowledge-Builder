package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.reviewSmoke",matches="true")
class SourceManagerSmokeTest {
    @TempDir Path temp;
    @Test @Timeout(45) void previewIsRequiredBeforeDeletingLastSource()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        var worker=Executors.newSingleThreadExecutor();var ready=new CompletableFuture<Void>();
        try(var embedding=new EmbeddingService(null)) {
            Path folder=Files.createDirectory(temp.resolve("docs"));Files.writeString(folder.resolve("guide.txt"),"傀儡操作指南：先组装核心。");
            new KnowledgeBuilder(config,db,embedding).build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,folder.toString()))),config.getDataDir()),null);
            Platform.startup(()->ready.complete(null));ready.get(10,TimeUnit.SECONDS);
            var pane=fx(()->new SourceManagerPane(config,db,embedding,worker::execute,()->{}));
            var scene=fx(()->{var value=new Scene(pane,1250,820);pane.applyCss();pane.layout();return value;});
            assertTrue(fx(()->button(pane,"执行已预览操作").isDisabled()));
            fx(()->{button(pane,"读取来源").fire();return null;});
            waitFor(()->list(pane).getItems().size()==2);
            fx(()->{for(int i=0;i<list(pane).getItems().size();i++)if(((SourceLifecycleService.Entry)list(pane).getItems().get(i)).source().location().equals(folder.toString()))list(pane).getSelectionModel().select(i);return null;});
            waitFor(()->field(pane,"items",ComboBox.class).getItems().size()==1);
            fx(()->{button(pane,"预览影响范围").fire();return null;});waitFor(()->!button(pane,"执行已预览操作").isDisabled());
            fx(()->{
                pane.applyCss();pane.layout();var snapshot=scene.snapshot(null);int w=(int)snapshot.getWidth(),h=(int)snapshot.getHeight();var image=new java.awt.image.BufferedImage(w,h,2);var pixels=new int[w*h];snapshot.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,w);image.setRGB(0,0,w,h,pixels,0,w);javax.imageio.ImageIO.write(image,"png",Path.of("target/r6-source-manager-preview.png").toFile());
                @SuppressWarnings("unchecked") var operation=(ComboBox<SourceLifecycleService.Operation>)field(pane,"operation",ComboBox.class);operation.setValue(SourceLifecycleService.Operation.DELETE);assertTrue(button(pane,"执行已预览操作").isDisabled());button(pane,"预览影响范围").fire();return null;
            });
            waitFor(()->!button(pane,"执行已预览操作").isDisabled());fx(()->{button(pane,"执行已预览操作").fire();return null;});waitFor(()->list(pane).getItems().size()==1);
            assertEquals(1,new SourceLifecycleService(config,db,embedding).entries().size());assertEquals(0,new VectorStore(db,384).count());
        }finally{worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);Platform.exit();db.shutdown();LogSetup.shutdown();}
    }
    private static <T>T fx(Callable<T> action)throws Exception {var result=new CompletableFuture<T>();Platform.runLater(()->{try{result.complete(action.call());}catch(Throwable e){result.completeExceptionally(e);}});return result.get(10,TimeUnit.SECONDS);}
    private static void waitFor(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(fx(condition::getAsBoolean))return;Thread.sleep(25);}fail("UI state did not settle");}
    private static Button button(SourceManagerPane pane,String text){return pane.lookupAll(".button").stream().filter(n->n instanceof Button b&&b.getText().equals(text)).map(Button.class::cast).findFirst().orElseThrow();}
    private static ListView<?> list(SourceManagerPane pane){return field(pane,"sources",ListView.class);}
    private static <T>T field(Object object,String name,Class<T> type){try{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(object));}catch(Exception e){throw new IllegalStateException(e);}}
}
