package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.MaaKnowledgeBuilderApp;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.ChatHistoryService;
import yagen.waitmydawn.kb.service.MultiDBManager;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立空库中的真实问答页面检查，避免启动应用时读取保存的模型设置。 */
@EnabledIfSystemProperty(named="maa.ui.layoutSmoke",matches="true")
class WorkspaceLayoutSmokeTest {
    @TempDir Path temp;

    @Test void preservesUsablePanelsAndRemembersDividerPositions() throws Exception {
        Path preferences=temp.resolve("layout.properties");
        String previous=System.getProperty("maa.layout.file");
        System.setProperty("maa.layout.file",preferences.toString());
        var done=new CompletableFuture<Void>();
        Platform.startup(()->{
            DatabaseBuilder database=null;MultiDBManager manager=null;MaaKnowledgeBuilderApp app=null;
            try {
                app=new MaaKnowledgeBuilderApp();
                database=DatabaseBuilder.atPath(temp.resolve("rag_data"));database.initDatabase();
                manager=new MultiDBManager(temp,database);manager.scan();
                var history=new ChatHistoryService(temp);
                history.save(history.newSession("用于检查长会话标题在调整侧栏后能显示更多内容的测试对话",temp));
                inject(app,"config",AppConfig.forIsolatedRun(false).forBuildTarget(temp));
                inject(app,"db",database);inject(app,"dbManager",manager);inject(app,"chatHistoryService",history);
                var method=MaaKnowledgeBuilderApp.class.getDeclaredMethod("buildQAPage");method.setAccessible(true);
                var root=(VBox)method.invoke(app);
                var scene=new Scene(root,1200,800);
                scene.getStylesheets().add(getClass().getResource("/ui/workspace.css").toExternalForm());
                root.getStyleClass().add("theme-dark");root.applyCss();root.layout();
                var historySplit=(SplitPane)root.lookup("#layout-qa-history");
                assertNotNull(root.lookup("#layout-qa-lists"));assertNotNull(root.lookup("#layout-qa-composer"));
                for(var split:root.lookupAll(".workspace-split")) {
                    var pane=(SplitPane)split;
                    var handle=pane.lookupAll(".split-pane-divider").stream().filter(node->node.getParent()==pane).findFirst().orElseThrow();
                    assertTrue((pane.getOrientation()==javafx.geometry.Orientation.VERTICAL?handle.getBoundsInParent().getHeight():handle.getBoundsInParent().getWidth())>=4,"Divider must remain visible and draggable: "+pane.getId());
                }
                historySplit.setDividerPositions(.01);root.layout();
                assertTrue(historySplit.getItems().getFirst().getBoundsInParent().getWidth()>=219);
                historySplit.setDividerPositions(.36);root.layout();
                var divider=historySplit.lookupAll(".split-pane-divider").stream().filter(node->node.getParent()==historySplit).findFirst().orElseThrow();
                divider.fireEvent(mouse(MouseEvent.MOUSE_RELEASED,1));
                assertTrue(Files.isRegularFile(preferences));
                double saved=historySplit.getDividerPositions()[0];
                var restored=new WorkspaceLayout(preferences).horizontal("qa-history",new VBox(),new VBox(),.25,220,360);
                assertEquals(saved,restored.getDividerPositions()[0],.001);
                divider.fireEvent(mouse(MouseEvent.MOUSE_CLICKED,2));root.layout();
                assertEquals(.25,historySplit.getDividerPositions()[0],.01);
                snapshot(root,"wide");
                root.resize(860,700);root.layout();
                assertTrue(historySplit.getItems().getFirst().getBoundsInParent().getWidth()>=219);
                assertTrue(historySplit.getItems().get(1).getBoundsInParent().getWidth()>=359);
                snapshot(root,"narrow");
                done.complete(null);
            } catch(Throwable failure){done.completeExceptionally(failure);}
            finally {
                if(manager!=null)manager.close();if(database!=null)database.shutdown();
                if(app!=null)try {var field=MaaKnowledgeBuilderApp.class.getDeclaredField("worker");field.setAccessible(true);((ExecutorService)field.get(app)).shutdownNow();}catch(ReflectiveOperationException ignored){}
            }
        });
        try {done.get(60,TimeUnit.SECONDS);}
        finally {Platform.exit();if(previous==null)System.clearProperty("maa.layout.file");else System.setProperty("maa.layout.file",previous);}
    }
    private static void inject(Object target,String name,Object value) throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type,int count) {
        return new MouseEvent(type,0,0,0,0,MouseButton.PRIMARY,count,false,false,false,false,false,false,false,false,false,true,null);
    }
    private static void snapshot(VBox root,String name) throws Exception {
        var image=root.snapshot(null,null);int w=(int)image.getWidth(),h=(int)image.getHeight();int[] pixels=new int[w*h];
        image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,w);
        var output=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);output.setRGB(0,0,w,h,pixels,0,w);
        javax.imageio.ImageIO.write(output,"png",Path.of("target/ui-layout-"+name+".png").toFile());
    }
}
