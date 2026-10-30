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

/** 显式运行的 JavaFX 渲染检查；真实预览窗口在截图后立即关闭。 */
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
                var first=new AnswerArtifacts.Picture("entity1",asset.id(),asset.relativePath(),"火龙 · 一阶 · 成年","s1","ENTITY",List.of(),"growthStage=1");
                var second=new AnswerArtifacts.Picture("entity2",asset.id(),asset.relativePath(),"火龙 · 五阶 · 成年","s1","ENTITY",List.of(),"growthStage=5");
                var comparison=new AnswerArtifacts(null,null,List.of(web),List.of(first,second));
                var pair=new VBox(AnswerRenderer.render("![一阶](kb-image:entity1)\n\n![五阶](kb-image:entity2)\n\n![重复](kb-image:entity1)",comparison,temp));
                scene.setRoot(pair);pair.applyCss();pair.layout();
                assertEquals(2,pair.lookupAll(".answer-picture").size(),"Only identical references are suppressed; shared pixels preserve state identity");
                var grid=(javafx.scene.layout.FlowPane)pair.lookup(".answer-picture-grid");
                assertNotNull(grid);assertEquals(2,grid.getChildren().size());
                assertEquals(grid.getChildren().get(0).getLayoutY(),grid.getChildren().get(1).getLayoutY(),1);
                pair.resize(360,900);pair.layout();grid.layout();
                assertTrue(grid.getChildren().get(1).getLayoutY()>grid.getChildren().get(0).getLayoutY(),"Narrow views wrap comparison cards");
                var viewer=AnswerImageViewer.create(pair,List.of(first,second),first.id(),java.util.Map.of("s1",web),temp);
                var pane=viewer.getDialogPane();pane.applyCss();pane.layout();
                assertEquals(first.caption(),((javafx.scene.control.Label)pane.lookup(".answer-preview-caption")).getText());
                ((javafx.scene.control.Button)pane.lookup(".answer-preview-next")).fire();
                assertEquals(second.caption(),((javafx.scene.control.Label)pane.lookup(".answer-preview-caption")).getText());
                assertTrue(((javafx.scene.control.Label)pane.lookup(".answer-preview-detail")).getText().contains("独立缩放"));
                assertTrue(pane.lookupAll(".hyperlink").size()>=2,"Preview preserves both evidence and original page navigation");
                viewer.close();
                String historyRoot=System.getProperty("maa.ui.historyRoot");
                if(historyRoot!=null) {
                    Path history=Path.of(historyRoot);var service=new ChatHistoryService(history);
                    var sessions=service.listSessions().stream().filter(s->s.title.startsWith("P1P2真实问答 | ")).toList();
                    assertEquals(16,sessions.size());int rendered=0;boolean comparisonCaptured=false;
                    for(var session:sessions)for(var message:session.messages)if(message.artifacts()!=null) {
                        var replay=new VBox(AnswerRenderer.render(message.content(),message.artifacts(),history));
                        scene.setRoot(replay);replay.applyCss();replay.layout();
                        assertEquals(AnswerMedia.assess(message.content(),message.artifacts(),history).displayed(),replay.lookupAll(".answer-picture").size());
                        if(rendered++==0)writeSnapshot(scene,"target/p4-history-replay.png");
                        var entities=message.artifacts().pictures().stream().filter(p->"ENTITY".equals(p.role())).toList();
                        if(!comparisonCaptured&&entities.size()>=2) {
                            String markers=entities.stream().map(p->"![外观](kb-image:"+p.id()+")").collect(java.util.stream.Collectors.joining("\n\n"));
                            var display=new VBox(AnswerRenderer.render(markers,message.artifacts(),history));display.setPadding(new Insets(16));
                            scene.setRoot(display);scene.getStylesheets().add(AnswerRenderer.class.getResource("/ui/workspace.css").toExternalForm());
                            for(String theme:List.of("theme-light","theme-dark")) {
                                display.getStyleClass().removeAll("theme-light","theme-dark");display.getStyleClass().add(theme);display.applyCss();display.layout();
                                writeSnapshot(scene,"target/p4-comparison-"+theme+".png");
                            }
                            var sourceMap=new java.util.HashMap<String,AnswerArtifacts.Source>();message.artifacts().sources().forEach(s->sourceMap.put(s.id(),s));
                            var actualViewer=AnswerImageViewer.create(display,entities,entities.getFirst().id(),sourceMap,history);
                            try {
                                actualViewer.show();var dialogPane=actualViewer.getDialogPane();dialogPane.applyCss();dialogPane.layout();
                                assertTrue(dialogPane.lookup(".answer-preview-image").getBoundsInParent().getHeight()>100);
                                writeNode(dialogPane,"target/p4-image-viewer.png");
                            } finally {actualViewer.close();}
                            comparisonCaptured=true;
                        }
                    }
                    assertEquals(16,rendered);
                    assertTrue(comparisonCaptured);
                }
                Files.delete(AnswerMedia.resolveImage(temp,image));
                assertNotNull(AnswerRenderer.render(answer,artifacts,temp));
                var missingViewer=AnswerImageViewer.create(pair,List.of(first,second),first.id(),java.util.Map.of("s1",web),temp);
                assertTrue(((javafx.scene.control.Label)missingViewer.getDialogPane().lookup(".answer-preview-detail")).getText().contains("不可用"));
                ((javafx.scene.control.Button)missingViewer.getDialogPane().lookup(".answer-preview-next")).fire();
                assertEquals(second.caption(),((javafx.scene.control.Label)missingViewer.getDialogPane().lookup(".answer-preview-caption")).getText());
                missingViewer.close();
                done.complete(null);
            } catch(Throwable failure) {done.completeExceptionally(failure);}
        });
        try {done.get(30,TimeUnit.SECONDS);} finally {Platform.exit();}
    }
    private static void writeSnapshot(Scene scene,String file)throws Exception {
        writeImage(scene.snapshot(null),file);
    }
    private static void writeNode(javafx.scene.Node node,String file)throws Exception {writeImage(node.snapshot(null,null),file);}
    private static void writeImage(javafx.scene.image.WritableImage image,String file)throws Exception {
        int w=(int)image.getWidth(),h=(int)image.getHeight();int[] pixels=new int[w*h];
        image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,w);
        var output=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);output.setRGB(0,0,w,h,pixels,0,w);ImageIO.write(output,"png",Path.of(file).toFile());
    }
    private static final class PdfDocumentReaderForTest {
        static byte[] png(BufferedImage image) throws Exception {var out=new java.io.ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    }
}
