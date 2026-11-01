package yagen.waitmydawn.kb.ui;

import javafx.animation.AnimationTimer;
import javafx.event.Event;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.ImageAssetStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="maa.ui.smoke",matches="true")
class SelectionAndFitSmokeTest {
    @TempDir Path temp;
    @Test void paragraphSelectionCopiesAndFitConvergesAcrossLayoutPulses()throws Exception {
        var bitmap=new java.awt.image.BufferedImage(1200,1600,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes=new java.io.ByteArrayOutputStream();ImageIO.write(bitmap,"png",bytes);
        var asset=new ImageAssetStore(temp).put(bytes.toByteArray());
        var picture=new AnswerArtifacts.Picture("p1",asset.id(),asset.relativePath(),"测试图","s1","ENTITY",List.of(),"");
        var done=new CompletableFuture<Void>();
        Platform.startup(()->{
            Platform.setImplicitExit(false);var stage=new Stage();Dialog<Void> dialog=null;
            try{
                var text=(VBox)MarkdownRenderer.render("第一段可选择\n\n第二段重点");stage.setScene(new Scene(text,640,300));stage.show();text.applyCss();text.layout();
                var flows=text.getChildren().stream().filter(TextFlow.class::isInstance).map(TextFlow.class::cast).toList();assertEquals(2,flows.size());
                var first=flows.getFirst();var last=flows.getLast();
                var begin=first.localToScene(0,5);var end=last.localToScene(600,5);
                Event.fireEvent(first,new MouseEvent(MouseEvent.MOUSE_PRESSED,begin.getX(),begin.getY(),0,0,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,false,new PickResult(first,0,5)));
                Event.fireEvent(last,new MouseEvent(MouseEvent.MOUSE_DRAGGED,end.getX(),end.getY(),0,0,MouseButton.PRIMARY,1,false,false,false,false,true,false,false,false,false,false,new PickResult(last,600,5)));
                Event.fireEvent(text,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.C,false,true,false,false));
                assertEquals("第一段可选择\n第二段重点",Clipboard.getSystemClipboard().getString());
                assertTrue(((Text)first.getChildren().getFirst()).getSelectionEnd()>0);
                dialog=AnswerImageViewer.create(text,List.of(picture),picture.id(),Map.of(),temp);dialog.show();
                var active=dialog;var content=(VBox)active.getDialogPane().getContent();var scroll=(ScrollPane)content.getChildren().stream().filter(ScrollPane.class::isInstance).findFirst().orElseThrow();var view=(ImageView)((StackPane)scroll.getContent()).getChildren().getFirst();
                new AnimationTimer(){int pulses;double previous=-1;@Override public void handle(long now){
                    try{
                        if(++pulses==5)active.setWidth(active.getWidth()+100);
                        if(pulses>=12){if(previous>=0)assertEquals(previous,view.getFitWidth(),.01,"fit must remain stable across pulses");previous=view.getFitWidth();}
                        if(pulses>=22){stop();Platform.runLater(()->{active.close();stage.close();done.complete(null);});}
                    }catch(Throwable failure){stop();Platform.runLater(()->{active.close();stage.close();done.completeExceptionally(failure);});}
                }}.start();
            }catch(Throwable failure){if(dialog!=null)dialog.close();stage.close();done.completeExceptionally(failure);}
        });
        done.get(30,TimeUnit.SECONDS);
    }
}
