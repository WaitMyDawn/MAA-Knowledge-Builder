package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.control.TitledPane;
import javafx.geometry.Insets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** 显式运行的 JavaFX 渲染检查，不打开窗口、不占用用户会话。 */
@EnabledIfSystemProperty(named="maa.ui.smoke",matches="true")
class AnswerRendererSmokeTest {
    @TempDir Path temp;
    @Test void snapshotsInterleavedTextImageAndSourceCards() throws Exception {
        var sample=new BufferedImage(760,230,BufferedImage.TYPE_INT_RGB);var g=sample.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,760,230);
            g.setColor(new java.awt.Color(90,130,180));g.fillRoundRect(30,65,200,110,12,12);
            g.setColor(new java.awt.Color(75,150,100));g.fillRoundRect(480,65,240,110,12,12);
            g.setColor(java.awt.Color.BLACK);g.setFont(new java.awt.Font("Arial",java.awt.Font.BOLD,30));
            g.drawString("CORE",80,130);g.drawString("UPGRADE",515,130);g.drawString("-------->",270,130);
        } finally {g.dispose();}
        var asset=new ImageAssetStore(temp).put(PdfDocumentReaderForTest.png(sample));
        var source=new AnswerArtifacts.Source("s1","d1","傀儡组装指南.pdf",temp.resolve("guide.pdf").toString(),"升级",2,"OCR","安装核心后，再安装升级模块。");
        var image=new AnswerArtifacts.Picture("i1",asset.id(),asset.relativePath(),"核心与升级模块的关系","s1","ILLUSTRATION",List.of("c1"),source.evidence(),null,null,
                new AnswerArtifacts.ImageGrounding("UI","CONTEXT_INFERRED","b1",source.evidence(),"INLINE_STEP","OCR_OK"));
        var artifacts=new AnswerArtifacts("sample","龙之冒险：新征程",List.of(source),List.of(image));
        String answer=AnswerMedia.bind("## 组装与升级\n\n先组装核心，再安装升级模块。[[source:s1]]\n\n[[image:i1]]\n\n如果资料没有说明等级限制，应保留未知。",artifacts);
        var done=new CompletableFuture<Void>();
        Platform.startup(()->{
            try {
                var root=new VBox(12,AnswerRenderer.render(answer,artifacts,temp));root.setPadding(new Insets(20));root.setStyle("-fx-background-color: white; -fx-font-size: 15px;");
                var scene=new Scene(root,860,620);root.applyCss();root.layout();
                assertEquals(1,root.lookupAll(".answer-picture").size());
                assertTrue(root.lookupAll(".label").stream().filter(javafx.scene.control.Label.class::isInstance)
                        .map(javafx.scene.control.Label.class::cast).anyMatch(label -> label.getText().contains("按上下文推定") && label.getText().contains(source.evidence())));
                var code=new VBox(AnswerRenderer.render("```markdown\n\n![示例](kb-image:i1) [来源](kb-source:s1)\n\n```",artifacts,temp));
                assertTrue(code.lookupAll(".answer-picture").isEmpty());
                var snapshot=scene.snapshot(null);int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();
                int[] pixels=new int[width*height];snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                var out=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);out.setRGB(0,0,width,height,pixels,0,width);
                ImageIO.write(out,"png",Path.of("target/stage4-answer-preview.png").toFile());
                var web=new AnswerArtifacts.Source("s1","d1","龙之冒险：新征程 — v2.4a全流程发展路线","https://www.mcmod.cn/post/5454.html","组装",null,null,source.evidence());
                var other=new AnswerArtifacts.Source("s2","d1",web.title(),web.location()+"#upgrade","升级",null,null,"扩充槽位后安装升级模块。");
                var pack=new AnswerArtifacts("mcmod:modpack:737","龙之冒险：新征程",List.of(web,other),List.of(image));
                String legacy="## 傀儡组装与升级\n\n先加工部件，再组装傀儡。["+web.title()+"](kb-source:s1)\n\n![组装示意](kb-image:i1)\n\n安装升级模块前，先确认槽位。["+web.title()+"](kb-source:s2)";
                var packRoot=new VBox(AnswerRenderer.render(legacy,pack,temp));packRoot.setPadding(new Insets(20));packRoot.setStyle(root.getStyle());
                scene.setRoot(packRoot);packRoot.applyCss();packRoot.layout();
                assertEquals(1,packRoot.lookupAll(".modpack-source-link").size());
                assertEquals(1,packRoot.lookupAll(".answer-picture").size());
                assertTrue(packRoot.lookupAll(".titled-pane").stream().allMatch(n -> !((TitledPane)n).isExpanded()));
                snapshot=scene.snapshot(null);snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                out.setRGB(0,0,width,height,pixels,0,width);
                ImageIO.write(out,"png",Path.of("target/p01-modpack-answer-preview.png").toFile());
                var recipePicture=new AnswerArtifacts.Picture(image.id(),image.assetId(),image.relativePath(),"配方图",image.sourceId(),"RECIPE",List.of(),image.context());
                var recipeRoot=new VBox(AnswerRenderer.render("![配方](kb-image:i1)",new AnswerArtifacts(null,null,List.of(source),List.of(recipePicture)),temp));
                recipeRoot.setPadding(new Insets(20));scene.setRoot(recipeRoot);recipeRoot.applyCss();recipeRoot.layout();
                var recipeBox=(VBox)recipeRoot.lookup(".answer-picture");
                var recipeView=(javafx.scene.image.ImageView)recipeBox.getChildren().getFirst();
                assertTrue(recipeView.getFitWidth()>640,"Recipe cards use the available answer width");
                assertTrue(recipeView.getFitWidth()<=recipeBox.getWidth());
                recipeRoot.resize(360,620);recipeRoot.layout();
                assertTrue(recipeView.getFitWidth()<=recipeBox.getWidth(),"Narrow windows must not overflow");
                Files.delete(AnswerMedia.resolveImage(temp,image));
                assertNotNull(AnswerRenderer.render(answer,artifacts,temp));
                done.complete(null);
            } catch(Throwable failure) {done.completeExceptionally(failure);}
        });
        try {done.get(30,TimeUnit.SECONDS);} finally {Platform.exit();}
    }
    private static final class PdfDocumentReaderForTest {
        static byte[] png(BufferedImage image) throws Exception {var out=new java.io.ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    }
}
