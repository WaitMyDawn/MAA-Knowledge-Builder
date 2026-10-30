package yagen.waitmydawn.kb.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.AnswerMedia;
import yagen.waitmydawn.kb.service.AnswerSourceLinks;
import java.awt.Desktop;
import java.nio.file.*;
import java.util.*;

/** 仅使用消息中保存的资源与来源快照，预览操作不发起下载或查询当前基础库。 */
final class AnswerImageViewer {
    private AnswerImageViewer() {}

    static void show(Node owner, List<AnswerArtifacts.Picture> pictures, String selected,
                     Map<String,AnswerArtifacts.Source> sources, Path directory) {
        create(owner,pictures,selected,sources,directory).show();
    }

    static Dialog<Void> create(Node owner,List<AnswerArtifacts.Picture> pictures,String selected,
                               Map<String,AnswerArtifacts.Source> sources,Path directory) {
        if(pictures.isEmpty())throw new IllegalArgumentException("预览列表不能为空");
        var dialog=new Dialog<Void>();dialog.setTitle("图片查看");dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(980,760);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);configureDialog(dialog,owner);
        var view=new ImageView();view.setPreserveRatio(true);view.getStyleClass().add("answer-preview-image");
        var surface=new StackPane(view);surface.setAlignment(Pos.CENTER);surface.setPadding(new Insets(16));
        surface.setStyle("-fx-background-color: #303640;");
        var scroll=new ScrollPane(surface);scroll.setPannable(true);scroll.setPrefViewportWidth(850);scroll.setPrefViewportHeight(520);
        surface.minWidthProperty().bind(scroll.widthProperty().subtract(4));surface.minHeightProperty().bind(scroll.heightProperty().subtract(4));
        var caption=new Label();caption.setWrapText(true);caption.setMinHeight(Region.USE_PREF_SIZE);caption.getStyleClass().add("answer-preview-caption");
        var detail=new Label();detail.setWrapText(true);detail.getStyleClass().add("answer-preview-detail");
        var location=new VBox(4);location.setMinHeight(Region.USE_PREF_SIZE);var counter=new Label();var percent=new Label();percent.setMinWidth(55);
        var zoom=new Slider(.05,8,1);zoom.setPrefWidth(180);zoom.setAccessibleText("图片缩放");
        var previous=new Button("上一张");var next=new Button("下一张");var fit=new Button("适应窗口");var actual=new Button("原始大小");
        previous.getStyleClass().add("answer-preview-previous");next.getStyleClass().add("answer-preview-next");
        var toolbar=new FlowPane(8,8,previous,counter,next,fit,actual,new Label("缩放"),zoom,percent);toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(Region.USE_PREF_SIZE);scroll.setMinHeight(100);
        var content=new VBox(10,caption,toolbar,scroll,detail,location);content.setPadding(new Insets(12));
        VBox.setVgrow(scroll,Priority.ALWAYS);dialog.getDialogPane().setContent(content);
        int initial=0;for(int n=0;n<pictures.size();n++)if(pictures.get(n).id().equals(selected)){initial=n;break;}
        int[] index={initial};boolean[] fitting={true};
        Runnable resize=()->{
            var image=view.getImage();if(image==null)return;
            if(fitting[0])zoom.setValue(Math.max(.05,Math.min(1,Math.min(Math.max(80,scroll.getViewportBounds().getWidth()-32)/image.getWidth(),Math.max(80,scroll.getViewportBounds().getHeight()-32)/image.getHeight()))));
            view.setFitWidth(image.getWidth()*zoom.getValue());percent.setText(Math.round(zoom.getValue()*100)+"%");
        };
        zoom.valueProperty().addListener((o,a,b)->{
            if(view.getImage()!=null)view.setFitWidth(view.getImage().getWidth()*b.doubleValue());
            percent.setText(Math.round(b.doubleValue()*100)+"%");
        });
        zoom.setOnMousePressed(event->fitting[0]=false);zoom.setOnKeyPressed(event->fitting[0]=false);
        scroll.viewportBoundsProperty().addListener((o,a,b)->resize.run());
        Runnable load=()->{
            var picture=pictures.get(index[0]);view.setImage(null);surface.getChildren().setAll(view);
            caption.setText(Objects.toString(picture.caption(),"参考插图"));counter.setText((index[0]+1)+" / "+pictures.size());
            previous.setDisable(index[0]==0);next.setDisable(index[0]==pictures.size()-1);
            location.getChildren().clear();var source=sources.get(picture.sourceId());
            if(source!=null) {
                location.getChildren().add(AnswerRenderer.sourceLink(source));
                if(AnswerSourceLinks.safeUrl(source.location()))location.getChildren().add(SourceLinks.create("打开原网页",source.location()));
                else if(isLocalLocation(source.location()))location.getChildren().add(localSource(source));
            }
            if(AnswerSourceLinks.safeUrl(picture.originalUrl()))location.getChildren().add(SourceLinks.create("打开原始图片",picture.originalUrl()));
            String scale="ENTITY".equals(picture.role())?" · 独立缩放，不能据此比较实际体型":"";
            try {
                var image=new Image(AnswerMedia.resolveImage(directory,picture).toUri().toString());
                if(image.isError()||image.getWidth()<=0)throw new IllegalArgumentException("图片无法解码");
                view.setImage(image);view.setSmooth(!Set.of("ENTITY","ITEM","RECIPE").contains(Objects.toString(picture.role(),"")));
                detail.setText((int)image.getWidth()+" × "+(int)image.getHeight()+" px"+scale+" · 滚轮缩放，拖动平移");
                fitting[0]=true;resize.run();scroll.setHvalue(.5);scroll.setVvalue(.5);
            } catch(Exception missing) {
                surface.getChildren().setAll(new Label("图片缓存缺失或损坏；文字、状态和来源仍可查看。"));
                detail.setText("图片不可用"+scale);
            }
        };
        previous.setOnAction(e->{if(index[0]>0){index[0]--;load.run();}});
        next.setOnAction(e->{if(index[0]+1<pictures.size()){index[0]++;load.run();}});
        fit.setOnAction(e->{fitting[0]=true;resize.run();});actual.setOnAction(e->{fitting[0]=false;zoom.setValue(1);resize.run();});
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL,event->{
            if(view.getImage()==null||event.getDeltaY()==0)return;
            fitting[0]=false;zoom.setValue(Math.max(.05,Math.min(8,zoom.getValue()*Math.pow(1.12,event.getDeltaY()/40))));event.consume();
        });
        content.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,event->{
            if(event.getTarget() instanceof Slider)return;
            if(event.getCode()==KeyCode.LEFT){previous.fire();event.consume();}
            else if(event.getCode()==KeyCode.RIGHT){next.fire();event.consume();}
        });
        load.run();return dialog;
    }

    static void configureDialog(Dialog<?> dialog,Node owner) {
        if(owner.getScene()==null)return;
        if(owner.getScene().getWindow()!=null)dialog.initOwner(owner.getScene().getWindow());
        dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().addAll(owner.getScene().getRoot().getStyleClass());
    }

    static Button localSource(AnswerArtifacts.Source source) {
        var button=new Button("打开本地来源文件");Path path=null;
        try {if(isLocalLocation(source.location())){var candidate=Path.of(source.location());
            String filename=candidate.getFileName().toString().toLowerCase(Locale.ROOT);
            if(filename.matches(".*\\.(pdf|docx?|txt|md|png|jpe?g|gif|bmp|webp)")&&Files.isRegularFile(candidate))path=candidate;
        }}catch(InvalidPathException ignored){}
        var target=path;button.setDisable(target==null);button.setTooltip(new Tooltip(target==null?"本地文件不在原位置；仍可查看已保存的依据":target.toString()));
        button.setOnAction(event->{
            try {if(!Desktop.isDesktopSupported())throw new UnsupportedOperationException();Desktop.getDesktop().open(target.toFile());}
            catch(Exception failure){button.setTooltip(new Tooltip("无法打开文件，请复制来源位置后手动打开。"));}
        });return button;
    }

    static boolean isLocalLocation(String location) {
        try {return location!=null&&Path.of(location).isAbsolute();}catch(InvalidPathException invalid){return false;}
    }
}
