package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.MaaKnowledgeBuilderApp;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.catalogSmoke",matches="true")
class CatalogRefreshSmokeTest {
    @TempDir Path temp;
    @Test @Timeout(60) void publicationReloadsProfilesAndSameProfileCachesWithoutRestart()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);var db=new DatabaseBuilder(config);db.initDatabase();
        var worker=Executors.newSingleThreadExecutor();var ready=new CompletableFuture<Void>();
        Platform.startup(()->ready.complete(null));ready.get(10,TimeUnit.SECONDS);
        try {
            var browser=fx(()->new RuntimeBrowser(db,config,worker,RuntimeCatalog.Kind.LANGUAGE,"语言目录"));
            var web=fx(()->new KnowledgeBrowser(db,worker));
            fx(()->new Scene(new VBox(browser,web),1000,900));
            waitFor(()->status(browser).getText().contains("没有MKB-Export"));
            String first="a".repeat(64),second="b".repeat(64);snapshot(db,first,1);
            var mod=new ModEntry();mod.setModId("test");mod.setDisplayName("Test");db.saveModEntry(mod);
            new WebCatalogStore(db).save(new WebCatalog(List.of(new WebCatalog.Node("category",null,"test","https://example.com/list",1,"物品",List.of("物品"))),List.of(new WebCatalog.Member("category","https://example.com/item","示例物品","示例物品","Example","ITEM"))));
            var app=fx(MaaKnowledgeBuilderApp::new);
            fx(()->{var field=MaaKnowledgeBuilderApp.class.getDeclaredField("knowledgeBrowser");field.setAccessible(true);field.set(app,web);field=MaaKnowledgeBuilderApp.class.getDeclaredField("runtimeBrowsers");field.setAccessible(true);@SuppressWarnings("unchecked")var browsers=(List<RuntimeBrowser>)field.get(app);browsers.add(browser);return null;});
            refresh(app);waitFor(()->entries(browser).getItems().size()==1);
            assertEquals(first,fx(()->profile(browser).getValue().id()));
            waitFor(()->web.lookupAll(".label").stream().anyMatch(n->n instanceof Label l&&l.getText().contains("目录成员关系 1 条")));
            snapshot(db,first,2);refresh(app);waitFor(()->entries(browser).getItems().size()==2);
            try(var c=db.getConnection();var s=c.createStatement()){s.executeUpdate("DELETE FROM rag_runtime_record");s.executeUpdate("DELETE FROM rag_runtime_snapshot");}
            snapshot(db,second,1);refresh(app);waitFor(()->profile(browser).getValue()!=null&&profile(browser).getValue().id().equals(second)&&entries(browser).getItems().size()==1);
            try(var c=db.getConnection();var s=c.createStatement()){s.executeUpdate("DELETE FROM rag_runtime_record");s.executeUpdate("DELETE FROM rag_runtime_snapshot");}
            refresh(app);waitFor(()->profile(browser).getValue()==null&&entries(browser).getItems().isEmpty());
        }finally{worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);Platform.exit();db.shutdown();LogSetup.shutdown();}
    }
    private void snapshot(DatabaseBuilder db,String id,int count)throws Exception {
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_runtime_snapshot KEY(source_id) VALUES('source',?,?)")){p.setString(1,id);p.setString(2,"{\"categories\":{\"language\":{\"status\":\"COMPLETE\"}}}");p.executeUpdate();}
        var row=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id","zh_cn");var translations=row.putObject("translations");for(int i=0;i<count;i++)translations.put("item.test.item"+i,"条目"+i);
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_runtime_record KEY(profile_id,category,record_id) VALUES(?,'language','zh_cn',?)")){p.setString(1,id);p.setString(2,row.toString());p.executeUpdate();}
    }
    private static void refresh(MaaKnowledgeBuilderApp app)throws Exception {fx(()->{var method=MaaKnowledgeBuilderApp.class.getDeclaredMethod("refreshKnowledgeDirectories");method.setAccessible(true);method.invoke(app);return null;});}
    @SuppressWarnings("unchecked")private static ComboBox<RuntimeCatalog.Profile> profile(RuntimeBrowser pane){return (ComboBox<RuntimeCatalog.Profile>)field(pane,"profiles");}
    private static ListView<?> entries(RuntimeBrowser pane){return (ListView<?>)field(pane,"list");}
    private static Label status(RuntimeBrowser pane){return (Label)field(pane,"status");}
    private static Object field(Object target,String name){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
    private static <T>T fx(Callable<T> action)throws Exception {var result=new CompletableFuture<T>();Platform.runLater(()->{try{result.complete(action.call());}catch(Throwable failure){result.completeExceptionally(failure);}});return result.get(10,TimeUnit.SECONDS);}
    private static void waitFor(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){if(fx(condition::getAsBoolean))return;Thread.sleep(25);}fail("Catalog did not reload");}
}
