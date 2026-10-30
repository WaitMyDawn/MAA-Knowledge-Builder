package yagen.waitmydawn.kb.ui;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import java.nio.file.*;
import java.util.Properties;
import org.slf4j.LoggerFactory;

/** 布局偏好独立于含凭据的应用设置，仅在用户结束拖动或重置时保存。 */
public final class WorkspaceLayout {
    private final Path file;
    public WorkspaceLayout() {
        this(Path.of(System.getProperty("maa.layout.file",Path.of(System.getProperty("user.home"),".maa_kb","layout.properties").toString())));
    }
    public WorkspaceLayout(Path file) {this.file=file.toAbsolutePath().normalize();}

    public SplitPane horizontal(String key,Region first,Region second,double initial,double firstMinimum,double secondMinimum) {
        first.setMinWidth(firstMinimum);second.setMinWidth(secondMinimum);
        return split(key,first,second,Orientation.HORIZONTAL,initial);
    }
    public SplitPane vertical(String key,Region first,Region second,double initial,double firstMinimum,double secondMinimum) {
        first.setMinHeight(firstMinimum);second.setMinHeight(secondMinimum);
        return split(key,first,second,Orientation.VERTICAL,initial);
    }
    private SplitPane split(String key,Region first,Region second,Orientation orientation,double initial) {
        var pane=new SplitPane(first,second);pane.setOrientation(orientation);pane.setId("layout-"+key);
        pane.setMinSize(0,0);pane.getStyleClass().addAll("workspace-split",orientation==Orientation.HORIZONTAL?"workspace-split-horizontal":"workspace-split-vertical");
        double position=initial;
        try {double saved=Double.parseDouble(load().getProperty(key,Double.toString(initial)));if(Double.isFinite(saved)&&saved>=.05&&saved<=.95)position=saved;}
        catch(NumberFormatException ignored) {}
        pane.setDividerPositions(position);
        // Divider 的皮肤会消费鼠标事件，捕获阶段才能可靠保存拖动结果。
        pane.addEventFilter(MouseEvent.MOUSE_RELEASED,event->{if(dividerOwner(event.getTarget())==pane)save(key,pane.getDividerPositions()[0]);});
        pane.addEventFilter(MouseEvent.MOUSE_CLICKED,event->{
            if(event.getClickCount()==2&&dividerOwner(event.getTarget())==pane){pane.setDividerPositions(initial);save(key,initial);event.consume();}
        });
        return pane;
    }
    private static SplitPane dividerOwner(Object target) {
        boolean divider=false;
        for(Node node=target instanceof Node value?value:null;node!=null;node=node.getParent()) {
            if(node.getStyleClass().contains("split-pane-divider"))divider=true;
            if(node instanceof SplitPane pane)return divider?pane:null;
        }
        return null;
    }
    private Properties load() {
        var values=new Properties();
        try {if(Files.isRegularFile(file)&&Files.size(file)<=65536)try(var input=Files.newInputStream(file)){values.load(input);}}
        catch(java.io.IOException failure){LoggerFactory.getLogger(WorkspaceLayout.class).warn("Layout preferences unavailable: {}",file);}
        return values;
    }
    private void save(String key,double position) {
        // 多个页面可持有不同实例，写入前合并磁盘偏好以保留其他页面的分隔位置。
        var values=load();values.setProperty(key,Double.toString(position));
        try {
            Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"layout-",".tmp");
            try {
                try(var output=Files.newOutputStream(temporary)){values.store(output,"Workspace divider positions");}
                try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
            } finally {Files.deleteIfExists(temporary);}
        } catch(java.io.IOException failure){LoggerFactory.getLogger(WorkspaceLayout.class).warn("Layout preferences could not be saved: {}",file);}
    }
}
