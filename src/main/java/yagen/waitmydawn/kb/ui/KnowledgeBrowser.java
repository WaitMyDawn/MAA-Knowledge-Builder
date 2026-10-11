package yagen.waitmydawn.kb.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.PublicKnowledgeCache;
import yagen.waitmydawn.kb.service.WebCatalogStore;
import java.util.*;
import java.util.concurrent.Executor;

/** 分类目录浏览只读本地索引与公共快照，选择成员不会触发网站请求。 */
public final class KnowledgeBrowser extends VBox {
    private record Item(String modId, WebCatalogStore.Entry entry) {}
    private final List<Item> items=new ArrayList<>();
    private final TreeView<String> tree=new TreeView<>();
    private final TextArea detail=new TextArea();
    private final TextField search=new TextField();
    private final Label summary=new Label("尚未加载目录");
    private final Map<TreeItem<String>,Item> members=new IdentityHashMap<>();
    public KnowledgeBrowser(DatabaseBuilder db,Executor worker) {
        super(16); setPadding(new Insets(24)); getStyleClass().add("workspace-page");
        var heading=new Label("知识目录"); heading.getStyleClass().add("page-title");
        var help=new Label("按模组与网站原有分类浏览。目录证明名称和归属，具体机制由正文证据确认。"); help.getStyleClass().add("muted");
        search.setPromptText("搜索中文名、英文名或分类路径");
        var refresh=new Button("刷新目录"); refresh.getStyleClass().add("primary-button");
        var row=new HBox(10,search,refresh); HBox.setHgrow(search,Priority.ALWAYS);
        detail.setEditable(false); detail.setWrapText(true); detail.getStyleClass().add("document-preview");
        tree.setShowRoot(false);
        var split=new SplitPane(tree,detail); split.setDividerPositions(.45); VBox.setVgrow(split,Priority.ALWAYS);
        tree.getSelectionModel().selectedItemProperty().addListener((o,b,node)->{
            var item=members.get(node); if(item==null) return;
            var entry=item.entry();
            detail.setText(entry.label()+"\n\n模组："+item.modId()+"\n分类："+String.join(" → ",entry.path())+"\n页面角色："+entry.role()+"\n目录来源："+entry.categoryUrl()+"\n详情来源："+entry.url()+"\n\n正在读取本地正文…");
            worker.execute(()->{
                String text;
                try { var page=new PublicKnowledgeCache(db).find(entry.url()); text=page==null?"本地没有有效正文快照，问答时将按需获取。":page.page().content(); }
                catch(Exception e) { text="本地正文读取失败："+e.getMessage(); }
                String body=text;
                Platform.runLater(()->{ if(tree.getSelectionModel().getSelectedItem()==node) detail.setText(detail.getText().replace("正在读取本地正文…",body)); });
            });
        });
        search.textProperty().addListener((o,b,a)->render());
        refresh.setOnAction(e->{
            refresh.setDisable(true); summary.setText("正在加载目录…");
            worker.execute(()->{
                var loaded=new ArrayList<Item>(); String error=null;
                try { for(var mod:db.findAllModEntries()) for(var category:yagen.waitmydawn.kb.service.WikiScraperService.McmodCategory.values())
                    for(var entry:new WebCatalogStore(db).entries(mod.getModId(),category.getId())) loaded.add(new Item(mod.getModId(),entry)); }
                catch(Exception ex) { error=ex.getMessage(); }
                String failure=error;
                Platform.runLater(()->{ items.clear(); items.addAll(loaded); render(); refresh.setDisable(false); if(failure!=null) summary.setText("目录加载失败："+failure); });
            });
        });
        getChildren().addAll(heading,help,row,summary,split);
        refresh.fire();
    }
    private void render() {
        var root=new TreeItem<String>("目录"); var nodes=new LinkedHashMap<String,TreeItem<String>>(); members.clear();
        String term=search.getText().strip().toLowerCase(Locale.ROOT); int visible=0;
        for(var item:items) {
            var entry=item.entry();
            if(!(item.modId()+entry.label()+String.join(" ",entry.path())).toLowerCase(Locale.ROOT).contains(term)) continue;
            TreeItem<String> parent=root; String key="";
            var path=new ArrayList<String>(); path.add(item.modId()); path.addAll(entry.path());
            for(String part:path) {
                key+="/"+part;
                var node=nodes.get(key);
                if(node==null) { node=new TreeItem<>(part); parent.getChildren().add(node); nodes.put(key,node); }
                if(!term.isEmpty()) node.setExpanded(true); parent=node;
            }
            var leaf=new TreeItem<>(entry.label()); parent.getChildren().add(leaf); members.put(leaf,item); visible++;
        }
        tree.setRoot(root); summary.setText("目录成员关系 "+items.size()+" 条 · 当前显示 "+visible+" 条");
        detail.setText("选择具体条目查看分类路径、来源和本地正文。浏览目录不会访问网站。\n\n同名生物、物品与技术条目会分别显示，未抓取详情时机制保持未知。");
        if(items.isEmpty()) detail.setText("构建知识库后，这里会保留网站的完整分类路径。旧库需要重新构建分类索引。");
    }
}
