package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.builderSmoke",matches="true")
class ModBindingSmokeTest {
    @Test void semanticColorsRemainDistinctAndSortedCellEditsTheAssociatedMod()throws Exception {
        var done=new CompletableFuture<Void>();
        Platform.startup(()->{try{
            var config=AppConfig.forIsolatedRun(false);config.setThemeMode(ThemeMode.DARK);
            try(var themes=new ThemeService(config)){
                var selected=new java.util.concurrent.atomic.AtomicReference<ModEntry>();var list=new ListView<ModEntry>();list.setCellFactory(v->new ModListCell(selected::set));list.setPrefHeight(330);
                var one=new ModEntry();one.setModId("aquamirae");one.setDisplayName("Aquamirae");one.setSlug("aquamirae");one.setVersion("7");one.setLoader("neoforge");one.setMcVersion("1.21.1");one.setMcmodBinding(new ModUrlBinding("https://www.mcmod.cn/class/3318.html","海灵物语","Aquamirae",269467L,"FOUND","AUTO",null,null));
                var vanilla=new ModEntry();vanilla.setModId("minecraft");vanilla.setLoader("vanilla");vanilla.setMcVersion("1.21.1");vanilla.setVersion("1.21.1");var unknown=new ModEntry();unknown.setModId("unknown_mod");unknown.setLoader("neoforge");
                list.getItems().addAll(unknown,one,vanilla);list.getItems().sort(ModUrlBinding.reviewOrder());list.getSelectionModel().select(one);
                var palette=new ModPalettePane(config,()->{});var root=new VBox(14,new Label("Scan → Bind Url → Build"),list,new Label("字段颜色可分别为深色和浅色主题设置"),palette);root.setPadding(new javafx.geometry.Insets(20));
                var scene=new Scene(root,1250,1040);themes.prepare(scene);root.applyCss();root.layout();root.applyCss();root.layout();
                assertEquals(vanilla,list.getItems().getFirst());
                var id=(Text)root.lookupAll(".mod-field-id").stream().filter(n->((Text)n).getText().equals("aquamirae")).findFirst().orElseThrow();
                var slug=(Text)root.lookupAll(".mod-field-slug").stream().filter(n->((Text)n).getText().equals("{slug=aquamirae}  ")).findFirst().orElseThrow();
                var url=(Text)root.lookupAll(".mod-field-url").stream().filter(n->((Text)n).getText().contains("3318")).findFirst().orElseThrow();
                assertNotEquals(id.getFill(),slug.getFill());assertNotEquals(slug.getFill(),url.getFill());
                var event=new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,0,0,0,0,javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,false,null);slug.getOnMouseClicked().handle(event);assertSame(one,selected.get());
                snapshot(scene,"target/bind-url-dark-preview.png");
                config.setModFieldColors(true,Map.of("modid","#ff00ff","slug","#ffff00","url","#00ff00"));themes.prepare(scene);root.applyCss();assertEquals(javafx.scene.paint.Color.web("#ff00ff"),id.getFill());
                config.setThemeMode(ThemeMode.LIGHT);themes.setMode(ThemeMode.LIGHT);themes.prepare(scene);root.applyCss();assertEquals(javafx.scene.paint.Color.web(config.getModFieldColor(false,"modid")),id.getFill());snapshot(scene,"target/bind-url-light-preview.png");
                config.resetModFieldColors(true);assertEquals("#67e8f9",config.getModFieldColor(true,"modid"));
                assertEquals("#b2dea3",config.getProgressColor(true,"scan"));assertEquals(config.getModFieldColor(true,"url"),config.getProgressColor(true,"bind"));
                config.setProgressColors(true,Map.of("scan","#ff0000","bind","#00ff00","build","#0000ff"));assertEquals("#ff0000",config.getProgressColor(true,"scan"));
                config.setModFieldColors(true,Map.of("modid","#000000","slug","#ffffff","url","#abcdef"));assertEquals("#ff0000",config.getProgressColor(true,"scan"));config.setProgressColors(true,Map.of());assertEquals("#808080",config.getProgressColor(true,"scan"));assertEquals("#abcdef",config.getProgressColor(true,"bind"));
                Platform.setImplicitExit(false);
                var fixture=Path.of("target/stage1-test-temp/1.20.1-Forge-MKB.jar").toAbsolutePath();Files.write(fixture,new byte[]{0});
                var dialog=new ScanEnvironmentDialog(null,List.of(new GameEnvironmentDetector.Candidate(fixture,"forge","1.20.1")));themes.prepare(dialog.getDialogPane().getScene());
                var loader=(ComboBox<String>)dialog.getDialogPane().lookup("#scan-game-loader");var version=(TextField)dialog.getDialogPane().lookup("#scan-game-version");
                assertEquals("Forge",loader.getValue());assertEquals("1.20.1",version.getText());
                dialog.setOnShown(shownEvent->Platform.runLater(()->{try{snapshot(dialog.getDialogPane().getScene(),"target/scan-environment-preview.png");loader.setValue("Fabric");version.setText("1.20.4");dialog.getDialogPane().lookupAll(".button").stream().filter(n->n instanceof Button b&&b.getText().equals("确认并开始 Scan")).map(n->(Button)n).findFirst().orElseThrow().fire();}catch(Exception failure){dialog.close();done.completeExceptionally(failure);}}));
                var environment=dialog.showAndWait().orElseThrow();assertEquals("fabric",environment.loader());assertEquals("1.20.4",environment.minecraftVersion());
                Files.deleteIfExists(fixture);
            }done.complete(null);
        }catch(Throwable failure){done.completeExceptionally(failure);}});
        try{done.get(40,TimeUnit.SECONDS);}finally{Platform.exit();LogSetup.shutdown();}
    }
    static void snapshot(Scene scene,String file)throws Exception{
        var shot=scene.snapshot(null);int w=(int)shot.getWidth(),h=(int)shot.getHeight();int[] pixels=new int[w*h];shot.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,w);
        var image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,w,h,pixels,0,w);javax.imageio.ImageIO.write(image,"png",Path.of(file).toFile());
    }
}

