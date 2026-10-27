package yagen.waitmydawn.kb.ui;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import yagen.waitmydawn.kb.model.*;
import java.util.*;
import java.util.function.Consumer;

/** 行内容直接关联模组对象，排序后字段编辑不依赖旧的列表索引。 */
public final class ModListCell extends ListCell<ModEntry> {
    private final Consumer<ModEntry> edit;
    public ModListCell(Consumer<ModEntry> edit){this.edit=edit;setPrefWidth(0);getStyleClass().add("mod-list-cell");}
    private Text text(String value,String style,ModEntry mod,boolean editable){var t=new Text(value);t.getStyleClass().add(style);if(editable){t.setStyle("-fx-cursor: hand;");t.setOnMouseClicked(e->{if(e.getClickCount()==1){edit.accept(mod);e.consume();}});}return t;}
    @Override protected void updateItem(ModEntry mod,boolean empty){
        super.updateItem(mod,empty);setText(null);setGraphic(null);setTooltip(null);if(empty||mod==null)return;
        var binding=ModUrlBinding.vanilla(mod)?ModUrlBinding.vanilla():mod.getMcmodBinding();
        var flow=new TextFlow();
        if(mod.getScanStatus()!=null)flow.getChildren().add(text("["+mod.getScanStatus()+"] ","mod-field-muted",mod,false));
        flow.getChildren().add(text(Objects.toString(mod.getModId(),"未知"),"mod-field-id",mod,false));
        String name=mod.getDisplayName();flow.getChildren().add(text((name==null||name.equals(mod.getModId())?"":" — "+name)+" v"+Objects.toString(mod.getVersion(),"?")+" [MC "+Objects.toString(mod.getMcVersion(),"?")+"] ("+Objects.toString(mod.getLoader(),"?")+")  ","mod-field-plain",mod,false));
        flow.getChildren().add(text("{slug="+(ModUrlBinding.vanilla(mod)?"不适用":Objects.toString(mod.getSlug(),"未绑定"))+"}  ","mod-field-slug",mod,true));
        String url=binding==null?"未绑定":binding.url()==null?binding.statusLabel():"class/"+binding.classId()+".html";
        flow.getChildren().add(text("{url="+url+"}","mod-field-url",mod,true));
        var alias=new Label(binding==null||binding.alias().isBlank()?"百科名称未知":binding.alias());alias.setWrapText(true);
        var views=new Label(ModUrlBinding.vanilla(mod)?"原版知识源":binding==null?"未绑定":(binding.views()==null?"浏览量未知":"总浏览："+String.format(Locale.ROOT,"%,d",binding.views()))+" · "+binding.statusLabel());views.getStyleClass().add("muted");views.setWrapText(true);
        var summary=new VBox(3,alias,views);summary.setMinWidth(180);summary.setPrefWidth(300);summary.setMaxWidth(320);
        var row=new HBox(16,flow,summary);HBox.setHgrow(flow,Priority.ALWAYS);flow.setMinWidth(0);row.prefWidthProperty().bind(widthProperty().subtract(22));setGraphic(row);
        setTooltip(new Tooltip("modid："+mod.getModId()+"\nslug："+Objects.toString(mod.getSlug(),"未绑定")+"\n百科："+(binding==null?"未绑定":Objects.toString(binding.url(),binding.statusLabel()))+"\n"+(binding==null?"":Objects.toString(binding.note(),""))+"\n双击条目或点击 slug / url 可编辑"));
    }
}
