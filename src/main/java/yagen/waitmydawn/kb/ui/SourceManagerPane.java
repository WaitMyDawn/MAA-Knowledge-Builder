package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** 维护预览由后台生成，数据库或选择变化后必须重新预览才能发布。 */
public final class SourceManagerPane extends VBox {
    private final AppConfig config;private final DatabaseBuilder db;private final EmbeddingService embedding;
    private final Consumer<Runnable> runner;private final Runnable changed;
    private final TextField directory=new TextField(),replacement=new TextField();
    private final ListView<SourceLifecycleService.Entry> sources=new ListView<>();
    private final ComboBox<SourceLifecycleService.Item> items=new ComboBox<>();
    private final ComboBox<SourceLifecycleService.Operation> operation=new ComboBox<>();
    private final TextArea detail=new TextArea();private final Label status=new Label("选择已建立的基础数据库，然后读取来源。");
    private final Button apply=new Button("执行已预览操作");
    private SourceLifecycleService.Plan plan;private Path planDirectory;
    private long editVersion;
    public SourceManagerPane(AppConfig config,DatabaseBuilder db,EmbeddingService embedding,Consumer<Runnable> runner,Runnable changed){
        super(12);this.config=config;this.db=db;this.embedding=embedding;this.runner=runner;this.changed=changed;
        setPadding(new Insets(24));getStyleClass().add("workspace-page");
        var heading=new Label("来源管理");heading.getStyleClass().add("page-title");
        directory.setText(config.getDataDir().toString());var choose=new Button("选择基础库…");var load=new Button("读取来源");
        var target=new HBox(10,directory,choose,load);HBox.setHgrow(directory,Priority.ALWAYS);
        choose.setOnAction(e->{var chooser=new DirectoryChooser();var folder=chooser.showDialog(getScene().getWindow());if(folder!=null)directory.setText(folder.toString());});
        load.setOnAction(e->{try{reload();}catch(Exception failure){error(failure);}});
        sources.setCellFactory(view->new ListCell<>(){@Override protected void updateItem(SourceLifecycleService.Entry entry,boolean empty){super.updateItem(entry,empty);setPrefWidth(0);setWrapText(true);setText(empty||entry==null?null:entry.toString());setTooltip(empty||entry==null?null:new Tooltip(entry.toString()));}});
        items.setPromptText("整个来源（可选择单篇文档或单个模组）");items.setMinWidth(0);items.setMaxWidth(Double.MAX_VALUE);
        operation.getItems().setAll(SourceLifecycleService.Operation.values());operation.setValue(SourceLifecycleService.Operation.REFRESH);
        replacement.setPromptText("仅替换来源时填写：同类型的新目录或网址");
        var preview=new Button("预览影响范围");preview.getStyleClass().add("primary-button");apply.setDisable(true);
        preview.setOnAction(e->preview());apply.setOnAction(e->apply());
        var actions=new HBox(10,operation,preview,apply);detail.setEditable(false);detail.setWrapText(true);status.setWrapText(true);
        var all=new Button("整个来源");all.setMinWidth(Region.USE_PREF_SIZE);all.setOnAction(e->items.getSelectionModel().clearSelection());
        var selection=new HBox(10,items,all);HBox.setHgrow(items,Priority.ALWAYS);
        var right=new VBox(10,selection,replacement,actions,detail);VBox.setVgrow(detail,Priority.ALWAYS);
        var split=new SplitPane(sources,right);split.setDividerPositions(.42);VBox.setVgrow(split,Priority.ALWAYS);
        var help=new Label("刷新只更新变化内容；重建用于解析版本升级；删除仅移除当前索引，保留历史会话和图片。模组维护沿用构建流程，可能联网获取百科。网页来源重建只用已有缓存。");help.setWrapText(true);
        getChildren().setAll(heading,help,target,split,status);
        sources.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
            invalidate();items.getItems().clear();
            if(b==null)return;
            detail.setText("来源："+b.source().location()+"\n文档："+b.documents()+"；模组："+b.mods()+"；图标包版本："+b.icons()+"\n内容指纹："+b.hash()+"\n解析版本："+b.parserVersion()+"\n当前解析器："+b.currentVersion()+"\n状态："+(b.needsMigration()?"建议按当前解析器重建":"已索引")+"\n更新时间："+b.indexedAt()+"\n修订："+b.revision());
            Path selected=path();runner.accept(()->{try(var targetDb=BuildTarget.open(selected,config,db)){var values=new SourceLifecycleService(targetDb.config(),targetDb.database(),embedding).items(b.source().id());Platform.runLater(()->{if(sources.getSelectionModel().getSelectedItem()==b)items.getItems().setAll(values);});}catch(Exception failure){error(failure);}});
        });
        items.valueProperty().addListener((o,a,b)->invalidate());operation.valueProperty().addListener((o,a,b)->invalidate());
        replacement.textProperty().addListener((o,a,b)->invalidate());directory.textProperty().addListener((o,a,b)->{invalidate();sources.getItems().clear();});
    }
    private Path path(){return Path.of(directory.getText()).toAbsolutePath().normalize();}
    private void invalidate(){editVersion++;plan=null;apply.setDisable(true);}
    private void error(Exception e){Platform.runLater(()->{status.setText(Objects.toString(e.getMessage(),e.getClass().getSimpleName()));apply.setDisable(true);plan=null;});}
    private void reload(){
        invalidate();long version=editVersion;Path selected=path();runner.accept(()->{try(var target=BuildTarget.open(selected,config,db)){var values=new SourceLifecycleService(target.config(),target.database(),embedding).entries();Platform.runLater(()->{if(editVersion!=version)return;sources.getItems().setAll(values);status.setText("已读取 "+values.size()+" 个来源；"+selected);});}catch(Exception e){error(e);}});
    }
    private void preview(){
        var entry=sources.getSelectionModel().getSelectedItem();if(entry==null){status.setText("请先选择来源");return;}
        Path selected=path();var item=items.getValue();var action=operation.getValue();BuildRequest.Source next;
        try{next=action==SourceLifecycleService.Operation.REPLACE?BuildRequest.Source.of(entry.source().kind(),replacement.getText()):null;}catch(Exception e){error(e);return;}
        invalidate();long version=editVersion;runner.accept(()->{try(var target=BuildTarget.open(selected,config,db)){
            var result=new SourceLifecycleService(target.config(),target.database(),embedding).preview(entry.source().id(),item==null?null:item.id(),action,next);
            Platform.runLater(()->{if(editVersion!=version)return;plan=result;planDirectory=selected;detail.setText(result.summary());apply.setDisable(false);status.setText("预览完成。确认影响范围后点击执行；网页刷新可能访问网络。");});
        }catch(Exception e){error(e);}});
    }
    private void apply(){
        var selectedPlan=plan;Path selected=planDirectory;if(selectedPlan==null)return;invalidate();
        runner.accept(()->{try(var target=BuildTarget.open(selected,config,db)) {
            var result=new SourceLifecycleService(target.config(),target.database(),embedding).apply(selectedPlan,(phase,cur,total,message)->Platform.runLater(()->status.setText(message)));
            if(!target.external())changed.run();
            var values=new SourceLifecycleService(target.config(),target.database(),embedding).entries();
            Platform.runLater(()->{if(path().equals(selected))sources.getItems().setAll(values);status.setText("目标："+selected+"；"+result.message()+(target.external()?" 在设置中切换到该目录并重启后用于问答。":""));});
        }catch(Exception e){error(e);}});
    }
}
