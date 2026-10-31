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
    private final VBox metadata=new VBox(8);
    private final Label snapshotStatus=new Label();
    private final Map<TreeItem<String>,String> paths=new IdentityHashMap<>();
    private final TextField search=new TextField();
    private final Label summary=new Label("尚未加载目录");
    private final Map<TreeItem<String>,Item> members=new IdentityHashMap<>();
    public KnowledgeBrowser(DatabaseBuilder db,Executor worker) {
        super(16); setPadding(new Insets(24)); getStyleClass().add("workspace-page");
        var heading=new Label("网页目录"); heading.getStyleClass().add("page-title");
        var help=new Label("按模组与网站原有分类浏览。目录证明名称和归属，具体机制由正文证据确认。"); help.getStyleClass().add("muted");
        search.setPromptText("搜索中文名、英文名或分类路径");
        var refresh=new Button("刷新目录"); refresh.getStyleClass().add("primary-button");
        var row=new HBox(10,search,refresh); row.setAlignment(javafx.geometry.Pos.CENTER_LEFT); HBox.setHgrow(search,Priority.ALWAYS);
        refresh.setTooltip(new Tooltip("重新加载本地目录，不访问网站"));
        detail.setEditable(false); detail.setWrapText(true); detail.getStyleClass().add("document-preview");
        tree.setShowRoot(false);
        snapshotStatus.setWrapText(true); snapshotStatus.getStyleClass().add("muted");
        metadata.getStyleClass().add("card");
        var preview=new VBox(10,metadata,snapshotStatus,detail); VBox.setVgrow(detail,Priority.ALWAYS);
        var split=new WorkspaceLayout().horizontal("knowledge",tree,preview,.45,170,300); VBox.setVgrow(split,Priority.ALWAYS);
        tree.getSelectionModel().selectedItemProperty().addListener((o,b,node)->{
            var item=members.get(node);
            metadata.getChildren().clear();
            if(item==null) {
                snapshotStatus.setText("");
                detail.setText(node==null ? "选择具体条目查看分类、来源和本地正文。" : "当前分类："+node.getValue()+"\n展开分类并选择具体条目查看详情。");
                return;
            }
            var entry=item.entry();
            var name=new Label(entry.label()); name.getStyleClass().add("card-title"); name.setWrapText(true);
            var info=new Label("模组："+item.modId()+"\n分类："+String.join(" → ",entry.path())); info.setWrapText(true);
            metadata.getChildren().addAll(name,info,sourceRow("目录来源",entry.categoryUrl()),sourceRow("详情来源",entry.url()));
            detail.setText(""); snapshotStatus.setText("正在读取本地正文…");
            worker.execute(()->{
                String text, status;
                try {
                    var snapshot=new PublicKnowledgeCache(db).snapshot(entry.url());
                    if(snapshot==null) { text="本地尚无正文，问答时将按需获取。"; status="未缓存正文"; }
                    else {
                        text=snapshot.page().page().content();
                        boolean expired=snapshot.expiredAt(System.currentTimeMillis());
                        status=(expired?"缓存已过期，以下仅供历史浏览；问答时会重新验证。":"已缓存本地正文")+
                                " · 获取时间："+java.time.Instant.ofEpochMilli(snapshot.fetchedAt()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    }
                } catch(Exception e) { text="本地正文读取失败："+e.getMessage(); status="读取失败"; }
                String body=text, state=status;
                Platform.runLater(()->{ if(tree.getSelectionModel().getSelectedItem()==node) { detail.setText(body); snapshotStatus.setText(state); } });
            });
        });
        var searchDelay=new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));
        searchDelay.setOnFinished(event->render());
        search.textProperty().addListener((o,b,a)->searchDelay.playFromStart());
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
    private static HBox sourceRow(String label,String url) {
        var link=SourceLinks.create(url,url); link.setWrapText(true); link.setMaxWidth(Double.MAX_VALUE);
        var copy=new Button("复制");
        copy.setDisable(!yagen.waitmydawn.kb.service.AnswerSourceLinks.safeUrl(url));
        copy.setOnAction(event->{ var content=new javafx.scene.input.ClipboardContent(); content.putString(url); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); });
        var row=new HBox(8,new Label(label+"："),link,copy); row.setAlignment(javafx.geometry.Pos.CENTER_LEFT); HBox.setHgrow(link,Priority.ALWAYS);
        return row;
    }
    private void render() {
        var expanded=new HashSet<String>();
        paths.forEach((node,path)->{ if(node.isExpanded()) expanded.add(path); });
        var selected=members.get(tree.getSelectionModel().getSelectedItem());
        TreeItem<String> restore=null;
        paths.clear();

        var root=new TreeItem<String>("目录"); var nodes=new LinkedHashMap<String,TreeItem<String>>(); members.clear();
        String term=search.getText().strip().toLowerCase(Locale.ROOT); int visible=0;
        for(var item:items) {
            var entry=item.entry();
            if(!(item.modId()+entry.label()+String.join(" ",entry.path())).toLowerCase(Locale.ROOT).contains(term)) continue;
            TreeItem<String> parent=root; String key="";
            var path=new ArrayList<String>(); path.add(item.modId()); path.addAll(entry.path());
            for(String part:path) {
                key+="\u001f"+part;
                var node=nodes.get(key);
                if(node==null) { node=new TreeItem<>(part); parent.getChildren().add(node); nodes.put(key,node); }
                paths.put(node,key);
                if(!term.isEmpty() || expanded.contains(key)) node.setExpanded(true); parent=node;
            }
            var leaf=new TreeItem<>(entry.label()); parent.getChildren().add(leaf); members.put(leaf,item); if(item.equals(selected)) restore=leaf; visible++;
        }
        tree.setRoot(root); summary.setText("目录成员关系 "+items.size()+" 条 · 当前显示 "+visible+" 条");
        detail.setText("选择具体条目查看分类路径、来源和本地正文。浏览目录不会访问网站。\n\n同名生物、物品与技术条目会分别显示，未抓取详情时机制保持未知。");
        metadata.getChildren().clear(); snapshotStatus.setText("");
        if(restore!=null) tree.getSelectionModel().select(restore);
        if(items.isEmpty()) detail.setText("构建知识库后，这里会保留网站的完整分类路径。旧库需要重新构建分类索引。");
    }
}
