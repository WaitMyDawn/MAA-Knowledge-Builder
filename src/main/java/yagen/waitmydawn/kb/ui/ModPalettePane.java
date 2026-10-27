package yagen.waitmydawn.kb.ui;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import yagen.waitmydawn.kb.config.AppConfig;
import java.util.*;

/** 深浅配色独立保存，预览覆盖普通与选中背景。 */
public final class ModPalettePane extends VBox {
    public ModPalettePane(AppConfig config,Runnable refresh){
        super(10);var mode=new ComboBox<String>();mode.getItems().setAll("深色主题","浅色主题");mode.setValue("深色主题");
        var pickers=new LinkedHashMap<String,ColorPicker>();for(String role:List.of("modid","slug","url"))pickers.put(role,new ColorPicker());
        var preview=new VBox(7);var status=new Label();status.setWrapText(true);
        Runnable render=()->{boolean dark=mode.getSelectionModel().getSelectedIndex()==0;preview.getChildren().clear();for(boolean selected:List.of(false,true)){var flow=new TextFlow();for(var entry:pickers.entrySet()){var t=new Text(entry.getKey().equals("modid")?"examplemod  ":"{"+entry.getKey()+"=example}  ");t.setFill(entry.getValue().getValue());flow.getChildren().add(t);}flow.setStyle("-fx-background-color:"+(selected?(dark?"#303653":"#e5e8ff"):(dark?"#1d2532":"#ffffff"))+";-fx-padding:12;");preview.getChildren().add(new VBox(3,new Label(selected?"选中状态":"普通状态"),flow));}};
        Runnable load=()->{boolean dark=mode.getSelectionModel().getSelectedIndex()==0;pickers.forEach((role,picker)->picker.setValue(Color.web(config.getModFieldColor(dark,role))));render.run();};
        mode.valueProperty().addListener((o,a,b)->load.run());pickers.values().forEach(p->p.valueProperty().addListener((o,a,b)->{if(pickers.values().stream().allMatch(v->v.getValue()!=null))render.run();}));
        var progressPickers=new LinkedHashMap<String,ColorPicker>();var defaults=new LinkedHashMap<String,CheckBox>();var progressFields=new FlowPane(12,10);var progressPreview=new HBox(18);
        for(String role:List.of("scan","bind","build")){var picker=new ColorPicker();var follow=new CheckBox("使用默认");progressPickers.put(role,picker);defaults.put(role,follow);progressFields.getChildren().add(new VBox(4,new Label(role.equals("scan")?"Scan 进度":role.equals("bind")?"Bind Url 进度":"Build 进度"),picker,follow));}
        Runnable updateProgress=()->{boolean dark=mode.getSelectionModel().getSelectedIndex()==0;progressPreview.getChildren().clear();for(String role:progressPickers.keySet()){var picker=progressPickers.get(role);if(defaults.get(role).isSelected()){Color value=switch(role){case "scan"->midpoint(pickers.get("modid").getValue(),pickers.get("slug").getValue());case "bind"->pickers.get("url").getValue();default->Color.web(dark?"#c4b5fd":"#6d28d9");};picker.setValue(value);}picker.setDisable(defaults.get(role).isSelected());var text=new Text((role.equals("bind")?"Bind Url":role.equals("scan")?"Scan":"Build")+" 12/248");text.setFill(picker.getValue());progressPreview.getChildren().add(text);}};
        Runnable loadProgress=()->{boolean dark=mode.getSelectionModel().getSelectedIndex()==0;progressPickers.forEach((role,picker)->{picker.setValue(Color.web(config.getProgressColor(dark,role)));defaults.get(role).setSelected(!config.hasProgressOverride(dark,role));});updateProgress.run();};
        mode.valueProperty().addListener((o,a,b)->loadProgress.run());
        defaults.values().forEach(c->c.selectedProperty().addListener((o,a,b)->updateProgress.run()));
        pickers.values().forEach(c->c.valueProperty().addListener((o,a,b)->updateProgress.run()));
        progressPickers.values().forEach(c->c.setOnAction(e->updateProgress.run()));
        var fields=new HBox(12);pickers.forEach((role,picker)->fields.getChildren().add(new VBox(4,new Label(role),picker)));
        var save=new Button("保存配色");save.setOnAction(e->{boolean dark=mode.getSelectionModel().getSelectedIndex()==0;var colors=new HashMap<String,String>();pickers.forEach((role,p)->{var c=p.getValue();colors.put(role,String.format(Locale.ROOT,"#%02x%02x%02x",Math.round(c.getRed()*255),Math.round(c.getGreen()*255),Math.round(c.getBlue()*255)));});config.setModFieldColors(dark,colors);var progress=new HashMap<String,String>();progressPickers.forEach((role,p)->{if(!defaults.get(role).isSelected()){var c=p.getValue();progress.put(role,String.format(Locale.ROOT,"#%02x%02x%02x",Math.round(c.getRed()*255),Math.round(c.getGreen()*255),Math.round(c.getBlue()*255)));}});config.setProgressColors(dark,progress);refresh.run();status.setText("已保存 "+mode.getValue()+" 的字段配色");});
        var reset=new Button("恢复默认");reset.setOnAction(e->{config.resetModFieldColors(mode.getSelectionModel().getSelectedIndex()==0);config.setProgressColors(mode.getSelectionModel().getSelectedIndex()==0,Map.of());load.run();loadProgress.run();refresh.run();status.setText("已恢复默认配色");});
        getChildren().setAll(mode,fields,preview,new Label("构建页进度配色（默认 Scan 为 modid/slug 的1:1插值，Bind Url 跟随 url）"),progressFields,progressPreview,new HBox(10,save,reset),status);load.run();loadProgress.run();
    }
    private static Color midpoint(Color a,Color b){return Color.rgb((int)(Math.round(a.getRed()*255)+Math.round(b.getRed()*255)+1)/2,(int)(Math.round(a.getGreen()*255)+Math.round(b.getGreen()*255)+1)/2,(int)(Math.round(a.getBlue()*255)+Math.round(b.getBlue()*255)+1)/2);}
}
