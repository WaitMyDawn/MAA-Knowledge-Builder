package yagen.waitmydawn.kb.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.Comparator;

/** 浏览当前数据目录中的评测产物；人工结论由独立复核文档记录。 */
public final class EvaluationBrowser extends VBox {
    public EvaluationBrowser(Path dataDir) {
        super(16); setPadding(new Insets(24)); getStyleClass().add("workspace-page");
        var title=new Label("评测与复核"); title.getStyleClass().add("page-title");
        var help=new Label("自动规则、模型裁判与人工判定分别保留。partial 和评分争议可在人工复核文档逐项处理。"); help.setWrapText(true); help.getStyleClass().add("muted");
        var runs=new ComboBox<Path>(); runs.setMaxWidth(Double.MAX_VALUE);
        runs.setConverter(new javafx.util.StringConverter<>() {
            public String toString(Path p) { return p==null?"选择测试运行":p.getFileName().toString(); }
            public Path fromString(String s) { return null; }
        });
        var kind=new ComboBox<String>(); kind.getItems().addAll("report.md","bad-cases.md","disagreements.md","human-review.md"); kind.setValue("report.md");
        var preview=new TextArea(); preview.setEditable(false); preview.setWrapText(true); preview.getStyleClass().add("document-preview");
        var refresh=new Button("刷新记录");
        var external=new Button("打开外部评测目录…");
        external.setOnAction(event->{
            var chooser=new javafx.stage.DirectoryChooser();chooser.setTitle("选择评测报告目录");
            var selected=chooser.showDialog(getScene()==null?null:getScene().getWindow());
            if(selected!=null){Path path=selected.toPath();if(!runs.getItems().contains(path))runs.getItems().add(path);runs.setValue(path);}
        });
        Runnable show=()->{
            Path run=runs.getValue(); if(run==null) { preview.setText("当前数据目录尚无评测记录。使用完整在线测试集生成报告后，可在这里浏览。\n\n报告目录："+dataDir.resolve("eval-runs")); return; }
            try { Path file=run.resolve(kind.getValue()); preview.setText(Files.exists(file)?Files.readString(file):"该运行没有生成此文件。"); }
            catch(IOException e) { preview.setText("报告读取失败："+e.getMessage()); }
        };
        runs.valueProperty().addListener((o,b,a)->show.run()); kind.valueProperty().addListener((o,b,a)->show.run());
        refresh.setOnAction(e->{
            Path base=dataDir.resolve("eval-runs"); runs.getItems().clear();
            if(Files.isDirectory(base)) try(var paths=Files.list(base)) { runs.getItems().addAll(paths.filter(Files::isDirectory).filter(p->p.getFileName().toString().startsWith("run-")).sorted(Comparator.reverseOrder()).limit(30).toList()); }
            catch(IOException ex) { preview.setText("运行目录读取失败："+ex.getMessage()); }
            if(!runs.getItems().isEmpty()) runs.setValue(runs.getItems().getFirst()); else show.run();
        });
        var controls=new HBox(10,runs,kind,refresh,external); HBox.setHgrow(runs,Priority.ALWAYS);
        getChildren().addAll(title,help,controls,preview); VBox.setVgrow(preview,Priority.ALWAYS); refresh.fire();
    }
}
