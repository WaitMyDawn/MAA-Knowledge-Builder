package yagen.waitmydawn.kb.ui;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import yagen.waitmydawn.kb.model.GameEnvironment;
import yagen.waitmydawn.kb.service.GameEnvironmentDetector;
import java.nio.file.*;
import java.util.*;

/** 确认之前只读取游戏文件，不发起模组项目查询。 */
public final class ScanEnvironmentDialog extends Dialog<GameEnvironment> {
    public ScanEnvironmentDialog(Window owner,List<GameEnvironmentDetector.Candidate> candidates){
        if(candidates.isEmpty())throw new IllegalArgumentException("缺少 Minecraft 本体候选");
        if(owner!=null)initOwner(owner);setTitle("确认游戏环境");setHeaderText("确认本体 JAR、加载器和 Minecraft 版本后开始 Scan");
        var detector=new GameEnvironmentDetector();var selected=new java.util.concurrent.atomic.AtomicReference<GameEnvironmentDetector.Candidate>();
        var file=new TextField();file.setEditable(false);file.setId("scan-game-file");file.setPrefColumnCount(48);
        var loader=new ComboBox<String>();loader.getItems().setAll("NeoForge","Forge","Fabric");loader.setId("scan-game-loader");
        var version=new TextField();version.setId("scan-game-version");var summary=new Label();summary.setId("scan-game-summary");summary.setWrapText(true);
        var info=new Label(candidates.size()>1?"找到多个本体 JAR，当前显示第一个；可通过浏览选择实际实例。":"后续 Modrinth 搜索统一使用这里确认的加载器和版本。");info.setWrapText(true);
        Runnable update=()->{var c=selected.get();if(c!=null)summary.setText(c.jar().getFileName()+" + "+Objects.toString(loader.getValue(),"请选择加载器")+" + "+version.getText());};
        java.util.function.Consumer<GameEnvironmentDetector.Candidate> populate=c->{selected.set(c);file.setText(c.jar().toString());version.setText(c.version());loader.setValue(c.loader()==null?null:switch(c.loader()){case "neoforge"->"NeoForge";case "forge"->"Forge";default->"Fabric";});update.run();};
        loader.valueProperty().addListener((o,a,b)->update.run());version.textProperty().addListener((o,a,b)->update.run());populate.accept(candidates.getFirst());
        var browse=new Button("浏览…");browse.setOnAction(e->{var chooser=new FileChooser();chooser.setTitle("选择 Minecraft 本体 JAR");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Minecraft JAR","*.jar"));var chosen=chooser.showOpenDialog(getDialogPane().getScene().getWindow());if(chosen==null)return;var candidate=detector.inspect(chosen.toPath());if(candidate.isEmpty()){info.setText("所选文件不是可识别的 Minecraft 客户端本体 JAR，请重新选择。");return;}populate.accept(candidate.get());info.setText("已更新文件信息；请核对加载器和版本。");});
        file.setMinWidth(0);version.setMinWidth(0);browse.setMinWidth(80);
        var fieldColumn=new ColumnConstraints();fieldColumn.setHgrow(Priority.ALWAYS);fieldColumn.setMinWidth(0);
        var grid=new GridPane();grid.getColumnConstraints().setAll(new ColumnConstraints(105),fieldColumn,new ColumnConstraints(85));grid.setHgap(10);grid.setVgap(12);grid.addRow(0,new Label("本体 JAR"),file,browse);grid.addRow(1,new Label("加载器"),loader);grid.addRow(2,new Label("游戏版本"),version);grid.addRow(3,new Label("当前环境"),summary);GridPane.setHgrow(file,Priority.ALWAYS);
        getDialogPane().setContent(new VBox(14,grid,info));getDialogPane().setPrefWidth(810);
        var confirm=new ButtonType("确认并开始 Scan",ButtonBar.ButtonData.OK_DONE);getDialogPane().getButtonTypes().setAll(confirm,ButtonType.CANCEL);
        getDialogPane().lookupButton(confirm).addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{new GameEnvironment(selected.get().jar().toString(),loader.getValue(),version.getText());if(!Files.isRegularFile(selected.get().jar()))throw new IllegalArgumentException("本体 JAR 已不存在，请重新浏览选择");}catch(Exception failure){info.setText(failure.getMessage());e.consume();}});
        setResultConverter(button->button==confirm?new GameEnvironment(selected.get().jar().toString(),loader.getValue(),version.getText()):null);
    }
}
