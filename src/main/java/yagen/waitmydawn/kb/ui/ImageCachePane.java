package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.*;

/** 耗时的缓存检查、修复与清理交由应用串行工作线程执行。 */
public final class ImageCachePane extends VBox {
    private final TextArea status=new TextArea("点击“检查图片缓存”查看当前知识库与历史会话的资源状态。");
    private final ImageCacheMaintenance maintenance;
    private final BooleanSupplier busy;
    private final Consumer<Runnable> executor;
    private final List<Button> buttons;

    public ImageCachePane(DatabaseBuilder db,Path directory,BooleanSupplier busy,Consumer<Runnable> executor) {
        super(8);this.maintenance=new ImageCacheMaintenance(db,directory);this.busy=busy;this.executor=executor;
        var inspect=new Button("检查图片缓存");
        var repair=new Button("修复缺失或损坏的网页图片");
        var clean=new Button("清理7天前的未引用图片");
        buttons=List.of(inspect,repair,clean);
        inspect.setOnAction(event -> run(()->format(maintenance.inspect())));
        repair.setOnAction(event -> run(()->{
            var result=maintenance.repairMissing(new WikiScraperService(),4);
            return "尝试获取："+result.attempted()+"，已恢复："+result.restored()+"，仍不可用："+result.unavailable()
                    +"（含没有原图网址或本次预算未处理的图片）\n"+String.join("\n",result.messages())+"\n"+format(result.after());
        }));
        clean.setOnAction(event -> run(()->{
            var result=maintenance.cleanUnreferenced();
            return "已清理："+result.removed()+" 张，释放 "+mib(result.bytes())+" MiB\n"+format(result.after());
        }));
        status.setEditable(false);status.setWrapText(true);status.setPrefRowCount(7);
        var note=new Label("检查范围包括基础库、聊天历史和评测记录。清理只删除文件已保留7天且没有引用的图片；历史记录无法读取时停止清理。修复仅按已记录网址恢复原图，每次最多尝试4次，遵循现有限流与缓存；本地资料缺图请重新构建。");
        note.setWrapText(true);note.getStyleClass().add("muted");
        getChildren().addAll(new FlowPane(8,6,inspect,repair,clean),note,status);
    }

    private void run(Operation operation) {
        if(busy.getAsBoolean()) return;
        buttons.forEach(button -> button.setDisable(true));status.setText("正在检查图片引用与缓存…");
        executor.accept(()->{
            String result;
            try {result=operation.run();}
            catch(java.util.concurrent.CancellationException e) {result="维护已取消；已经完成的修复或清理保留。";}
            catch(Exception e) {result="维护未完成："+e.getMessage();}
            String text=result;
            Platform.runLater(()->{status.setText(text);buttons.forEach(button -> button.setDisable(false));});
        });
    }
    @FunctionalInterface private interface Operation {String run() throws Exception;}
    private static String mib(long bytes) {return String.format(java.util.Locale.ROOT,"%.2f",bytes/(1024.0*1024));}
    private static String format(ImageCacheMaintenance.Report report) {
        var text=new StringBuilder("图片文件："+report.files()+"，占用 "+mib(report.bytes())+" MiB；受引用保护："+report.references()+"\n"
                +"缺失："+report.missing()+"，损坏："+report.corrupt()+"；未引用："+report.orphans()+"，可清理："+report.removable()+"（"+mib(report.removableBytes())+" MiB）\n"
                +"网页原始缓存："+report.rawResources()+" 项（"+mib(report.rawBytes())+" MiB）；Build时未缓存的图片引用："+report.buildPending()+"\n"
                +"清理检查："+(report.cleanupSafe()?"引用扫描完整":"引用扫描不完整，禁止清理"));
        for(var problem:report.problems().stream().limit(8).toList()) text.append("\n").append(problem.status()).append("：").append(problem.assetId(),0,12);
        report.warnings().stream().limit(8).forEach(warning -> text.append("\n").append(warning));
        return text.toString();
    }
}
