package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.config.LogSetup;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P5EvidenceWorkflowTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final String profile="e".repeat(64);
    @AfterEach void cleanup(){LogSetup.shutdown();}
    DatabaseBuilder db(){var db=DatabaseBuilder.atPath(temp.resolve(UUID.randomUUID().toString()));db.initDatabase();return db;}
    void snapshot(DatabaseBuilder db)throws Exception{
        var descriptor=json.createObjectNode();for(String category:List.of("recipes","registries","acquisition_index","tags"))descriptor.withObject("categories").putObject(category).put("status","COMPLETE");
        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_snapshot VALUES('runtime',?,?)")){p.setString(1,profile);p.setString(2,descriptor.toString());p.executeUpdate();}
    }
    String scope(DatabaseBuilder db,String id,String label,String namespace){
        var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://www.mcmod.cn/class/"+(id.equals("a")?1001:1002)+".html");
        var store=new KnowledgeContentStore(db);store.saveSource(source);store.setSourceScope(source.id(),id,"MOD",label,namespace);store.saveDocument(new KnowledgeContent.Document("doc"+id,source.id(),label,source.location(),"hash","text/html"));return id;
    }
    void member(DatabaseBuilder db,String owner,String id,String url){new WebCatalogStore(db).save(new WebCatalog(List.of(new WebCatalog.Node(id,null,owner,"https://www.mcmod.cn/item/list/1001-1.html",1,"器物",List.of("器物"))),List.of(new WebCatalog.Member(id,url,"星棱","星棱","Aster","entry"))));}
    QaAgentTurn turn(DatabaseBuilder db,EmbeddingService embedding,QaPipeline.QaResult result,String question,String selected){
        var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};
        return new QaAgentTurn(db,new VectorStore(db,384),new KnowledgeRetrievalService(db,embedding),new UrlAgent(llm,db,embedding),result,question,selected,null,()->{});
    }
    void recipe(DatabaseBuilder db,String output,String recipeId,String input)throws Exception{
        var row=json.readTree("{\"id\":\""+recipeId+"\",\"definition\":{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\""+input+"\"}],\"result\":{\"id\":\""+output+"\"}}}");
        var index=json.createObjectNode().put("id",output);index.putArray("sources").addObject().put("category","recipes").put("recordId",recipeId);
        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){
            for(var record:List.of(new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("recipes",recipeId),row),new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("acquisition_index",output),index))){p.setString(1,profile);p.setString(2,record.key().category());p.setString(3,record.key().id());p.setString(4,record.data().toString());p.addBatch();}p.executeBatch();
        }
        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_recipe VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,BuildRequest.fingerprint(recipeId));p.setString(2,profile);p.setString(3,recipeId);p.setString(4,row.toString());p.setString(5,output);p.setString(6,RuntimeRules.namespace(output));p.setString(7,"RUNTIME_SNAPSHOT");p.setString(8,"SUPPORTED");p.executeUpdate();}
    }
    @Test void catalogOwnershipCorrectsAliasMetadataAndRejectsForeignPage(){
        var db=db();try{
            scope(db,"a","北辰","alpha");scope(db,"b","暮光","beta");String first="https://www.mcmod.cn/item/100.html",other="https://www.mcmod.cn/item/200.html";member(db,"alpha","catA",first);member(db,"beta","catB",other);
            var identity=new WebSourceIdentity(db);var resolution=new KnowledgeRetrievalService.Resolution(new KnowledgeRetrievalService.Scope("a","北辰","MOD",List.of(),""),null,null,true);
            var hit=identity.canonicalize(new VectorStore.SearchResult("c","星棱的正文","beta","错误模组","web",1,first));
            assertEquals("alpha",hit.modId());assertEquals("北辰",hit.modName());assertTrue(identity.permits(hit,resolution,"alpha"));
            assertFalse(identity.permits(new VectorStore.SearchResult("x","同名正文","alpha","北辰","web",1,other),resolution,"alpha"));
        }finally{db.shutdown();}
    }
    @Test void unboundExternalSourceCannotBorrowAnInstalledNamesake()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            snapshot(db);scope(db,"a","北辰",null);var turn=turn(db,embedding,new QaPipeline.QaResult(),"北辰模组的星棱如何获取", "a");
            assertTrue(turn.execute("resolve_objects",json.readTree("{\"name\":\"星棱\"}")).contains("EXTERNAL_SOURCE_OBJECT"));
            assertThrows(IllegalArgumentException.class,()->turn.execute("query_recipes",json.readTree("{\"id\":\"beta:aster\"}")));
        }finally{db.shutdown();}
    }
    @Test void fullRegistryIdCannotBypassModFilter()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            snapshot(db);var turn=turn(db,embedding,new QaPipeline.QaResult(),"查找器物",null);
            assertThrows(IllegalArgumentException.class,()->turn.execute("resolve_objects",json.readTree("{\"name\":\"beta:aster\",\"mod\":\"alpha\"}")));
        }finally{db.shutdown();}
    }
    @Test void explicitRuntimeObjectDoesNotOpenUnrelatedRecordsOrUnfilteredCategories()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            snapshot(db);scope(db,"a","北辰",null);var turn=turn(db,embedding,new QaPipeline.QaResult(),"北辰资料与 alpha:aster 对比", "a");
            assertThrows(IllegalArgumentException.class,()->turn.execute("query_runtime",json.readTree("{\"category\":\"recipes\",\"id\":\"beta:unrelated\"}")));
            assertThrows(IllegalArgumentException.class,()->turn.execute("query_runtime",json.readTree("{\"category\":\"recipes\"}")));
            assertDoesNotThrow(()->turn.execute("query_runtime",json.readTree("{\"category\":\"registries\",\"id\":\"alpha:aster\"}")));
        }finally{db.shutdown();}
    }
    @Test void declaredRecipeInputAllowsLegitimateCrossModAcquisition()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            snapshot(db);scope(db,"a","北辰","alpha");recipe(db,"alpha:result","alpha:build","beta:component");var turn=turn(db,embedding,new QaPipeline.QaResult(),"北辰模组器物怎么制作", "a");
            assertThrows(IllegalArgumentException.class,()->turn.execute("query_acquisition",json.readTree("{\"id\":\"beta:component\"}")));
            turn.execute("query_recipes",json.readTree("{\"id\":\"alpha:result\"}"));
            assertDoesNotThrow(()->turn.execute("query_acquisition",json.readTree("{\"id\":\"beta:component\"}")));
        }finally{db.shutdown();}
    }
    @Test void oneMaterialCannotCompleteAnotherMaterial()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            snapshot(db);recipe(db,"alpha:left","alpha:left_recipe","alpha:ore");recipe(db,"alpha:right","alpha:right_recipe","alpha:wood");
            var result=new QaPipeline.QaResult();result.acquisitionTargets=List.of(new RecipeDependencies.Group("left","ITEM",List.of("alpha:left"),"build"),new RecipeDependencies.Group("right","ITEM",List.of("alpha:right"),"build"));
            var turn=turn(db,embedding,result,"只解释刚才所需材料的获取方式",null);
            turn.execute("query_acquisition",json.readTree("{\"id\":\"alpha:left\"}"));assertTrue(turn.readinessGap().contains("right"));
            turn.execute("query_acquisition",json.readTree("{\"id\":\"alpha:right\"}"));assertNull(turn.readinessGap());
        }finally{db.shutdown();}
    }
    @Test void readExcerptAndSupportedSubjectHaveDistinctStates(){
        var ledger=new TaskEvidenceLedger();for(String id:List.of("a","b"))ledger.require(id,"交互","右键");ledger.alias("a","星角兽");ledger.alias("b","暮影兽");
        var source=new VectorStore.SearchResult("c","名称：星角兽。空手右键星角兽即可进行采集。","alpha","北辰","web",1,"https://example.test/page");
        ledger.read("a","交互","c");assertEquals("READ_UNCONFIRMED",ledger.entries().getFirst().state());assertEquals(2,ledger.missing().size());
        assertThrows(IllegalArgumentException.class,()->ledger.document("b","交互",source,"空手右键星角兽即可进行采集。"));
        assertThrows(IllegalArgumentException.class,()->ledger.document("a","交互",source,"右键后会掉落十个材料和经验。"));
        ledger.document("a","交互",source,"空手右键星角兽即可进行采集。");assertEquals(1,ledger.missing().size());
    }
    @Test void itemReadDoesNotCompleteFluidProduction(){
        var ledger=new TaskEvidenceLedger();ledger.require("item","获取途径","装瓶");ledger.require("fluid:alpha:essence","流体来源","合成");ledger.rule("item","获取途径","alpha:bottling");
        assertEquals(List.of("fluid:alpha:essence / 流体来源"),ledger.missing());
    }
    @Test void prerequisiteStateSurvivesHistoryAndOldHistoryRemainsReadable()throws Exception{
        var old=json.readValue("{\"profile\":\"p\",\"objects\":[]}",AnswerArtifacts.TurnState.class);assertTrue(old.prerequisites().isEmpty());
        var state=new AnswerArtifacts.TurnState("p",null,List.of(),List.of(new AnswerArtifacts.AcquisitionRequirement("#alpha:parts","ITEM",List.of("beta:part"),"alpha:recipe")));
        assertEquals(state,json.readValue(json.writeValueAsString(state),AnswerArtifacts.TurnState.class));
    }
    @Test void pureMediaCannotInventStatsOrReferToUnavailablePictures(){
        var result=new QaPipeline.QaResult();var subject=new MediaRequestPlanner.Subject("alpha:beast","ENTITY",List.of("星角兽"));result.mediaPlan=new MediaRequestPlanner.Plan(List.of(new MediaRequestPlanner.Request(subject,"ENTITY","星角兽 sex=unknown","星角兽 · 性别未知")),false,false,List.of());
        assertTrue(PlayerMediaPresentation.pure("展示星角兽 sex=unknown 的实体图片"));String rendered=PlayerMediaPresentation.render(result);
        assertEquals("本次快照没有与“星角兽 · 性别未知”匹配的可用图片。",rendered);assertFalse(rendered.contains("其他状态"));assertFalse(rendered.contains("上面"));
        assertFalse(PlayerMediaPresentation.pure("展示星角兽图片并介绍生命值和属性"));
    }
    @Test void playerCaptionPreservesIdentityButKeepsDiagnosticsInEvidence(){
        var result=new QaPipeline.QaResult();var subject=new MediaRequestPlanner.Subject("alpha:beast","ENTITY",List.of("星角兽"));result.mediaPlan=new MediaRequestPlanner.Plan(List.of(new MediaRequestPlanner.Request(subject,"ENTITY","幼年星角兽","星角兽")),false,false,List.of());
        var identity=new AnswerArtifacts.RuntimeIdentity("alpha:beast","state",Map.of("age","baby"));var picture=new AnswerArtifacts.Picture("picture","asset","path","星角兽；DECLARED_STATE；0部件",null,"ENTITY",List.of(),"原始采样状态保留",null,null,null,identity);
        result.artifacts=new AnswerArtifacts(null,null,List.of(),List.of(picture));PlayerMediaPresentation.captions(result);
        var display=result.artifacts.pictures().getFirst();assertEquals("星角兽（幼年）",display.caption());assertEquals(identity,display.runtimeIdentity());assertEquals(picture.context(),display.context());
    }
    @Test void unavailableEquipmentIsPrunedBeforeAnswerCompositionAndIgnoresFriendlyCaption(){
        var result=new QaPipeline.QaResult();
        var picture=new AnswerArtifacts.Picture("picture","asset","path","星角兽",null,"ENTITY",List.of(),"",null,null,null,new AnswerArtifacts.RuntimeIdentity("alpha:beast","state",Map.of("equipped","false")));
        result.artifacts=new AnswerArtifacts(null,null,List.of(),List.of(picture));
        RuntimeEntityMedia.pruneUnavailable(result,"介绍穿着护甲的星角兽并配图");
        assertTrue(result.artifacts.pictures().isEmpty());
    }
    @Test void mcmodRecipeTablesOutsideItemContentArePreserved() {
        var doc = Jsoup.parse("<div class='item-content common-text'><p>来源：合成获取。</p></div>"
                + "<div class='item-table-frame'><table class='item-table-block'><tr><td>工作台</td><td>示例材料 ×1</td><td>示例产物 ×1</td></tr></table></div>",
                "https://www.mcmod.cn/item/1.html");
        var blocks = WebDocumentParser.parse(doc, "https://www.mcmod.cn/item/1.html").blocks();
        assertTrue(blocks.stream().anyMatch(block -> block.text().contains("示例材料")), blocks.toString());
        assertTrue(blocks.stream().anyMatch(block -> block.kind().equals("TABLE_ROW")), blocks.toString());
    }

    @Test void mcmodTableBlockKeepsInventoryGridPositionsAndShapelessMarker() {
        String item = "<div class=\"item-table-hover\" item-id=\"%s\" style=\"margin:%dpx 0 0 %dpx\"><img alt=\"%s\"></div>";
        String html = "<div class='item-content common-text'><p>来源：合成获取。</p></div>"
                + "<div class='item-table-frame'><table class='item-table-block'><tr><td><div class='TableBlock'>"
                + item.formatted("1", 34, 46, "甲")
                + item.formatted("2", 34, 82, "乙")
                + item.formatted("3", 70, 46, "丙")
                + item.formatted("4", 70, 234, "丁")
                + "<div class='orderly'></div></div></td></tr></table></div>";
        var blocks = WebDocumentParser.parse(Jsoup.parse(html), "https://www.mcmod.cn/item/1.html").blocks();
        var grid = blocks.stream().filter(block -> block.kind().equals("RECIPE_GRID")).findFirst().orElseThrow();
        assertTrue(grid.text().contains("无序合成"), grid.text());
        assertTrue(grid.text().contains("[1,1] 甲"), grid.text());
        assertTrue(grid.text().contains("[1,2] 乙"), grid.text());
        assertTrue(grid.text().contains("[2,1] 丙"), grid.text());
        assertTrue(grid.text().contains("[2,2] —"), grid.text());
        assertTrue(grid.text().contains("输出：丁"), grid.text());
    }

    @Test void bothExtractionPathsUseDomMarkersWithoutDeletingUnmarkedMechanics(){
        var doc=Jsoup.parse("<div class='item-content common-text'><p>空手右键采集材料。<span class='uknowtoomuch'>隐藏吐槽</span><span style='color:red; display: none !important'>隐藏样式</span></p><p>把材料均分后可以合成，这是未标记的原文。</p></div>");
        String structured=WebDocumentParser.parse(doc,"https://www.mcmod.cn/item/100.html").blocks().toString(),plain=WikiBodyExtractor.extract(doc.body());
        for(String body:List.of(structured,plain)){assertTrue(body.contains("空手右键"));assertTrue(body.contains("均分"));assertFalse(body.contains("隐藏吐槽"));assertFalse(body.contains("隐藏样式"));}
        assertTrue(doc.text().contains("隐藏吐槽"));
    }
    @Test void externalSourceVersionCannotBecomeCurrentEnvironmentButItsOwnVersionIsPreserved(){
        var snapshot=new RuntimeEvidencePolicy.Snapshot("profile","1.21.1","neoforge",Map.of("alpha","1.0"));
        String answer="当前快照（Minecraft 1.21.1 / 北辰 2.0）。网页北辰 2.0 的原文可独立讨论。北辰已经安装。";
        String fixed=SourceEnvironmentBoundary.constrain(answer,"北辰",null,snapshot);
        assertFalse(fixed.contains("当前快照（Minecraft 1.21.1 / 北辰"));assertTrue(fixed.contains("网页北辰 2.0"));assertFalse(fixed.contains("北辰已经安装"));
        assertEquals(answer,SourceEnvironmentBoundary.constrain(answer,"北辰","alpha",snapshot));
    }
    @Test void scopedEnvironmentCorrectionPreservesCodeAndEscapesArbitraryLabels(){
        var snapshot=new RuntimeEvidencePolicy.Snapshot("p","1.21.1","neoforge",Map.of());String code="```text\n当前快照（星野+ 2.0）\n```";
        assertEquals(code,SourceEnvironmentBoundary.constrain(code,"星野+",null,snapshot));
        assertFalse(SourceEnvironmentBoundary.constrain("当前快照（星野+ 2.0）","星野+",null,snapshot).contains("星野+"));
    }
    @Test void bodyLinksPreserveIrregularNamesAndConditionsWithoutTreatingNavigationAsEvidence(){
        String url="https://www.mcmod.cn/item/400.html";
        var document=Jsoup.parse("<nav><a href='/item/1.html'>导航</a></nav><div class='item-content common-text'><p>需先对<a href='/item/73.html'>暮轮</a>操作，再使用<a href='/item/96.html'>七叶</a>。</p><span hidden><a href='/item/8.html'>隐藏</a></span></div>",url);
        var links=WebBodyLinks.extract(document,url);assertEquals(2,links.size());assertEquals("暮轮",links.getFirst().label());assertTrue(links.getFirst().context().contains("需先"));assertTrue(links.getFirst().url().endsWith("/73.html"));
        var ledger=new TaskEvidenceLedger();ledger.require("task","用途",TaskEvidence.requirements("有什么用途").get("用途或机制"));
        assertDoesNotThrow(()->ledger.document("task","用途",new VectorStore.SearchResult("c","这种材料可合成任意形状的结构。","a","A","web",1,url),"这种材料可合成任意形状的结构。"));
    }
    @Test void relatedPageLinksArePagedAndOnlyAvailableAfterReadingThePage()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            String url="https://www.mcmod.cn/item/400.html";var result=new QaPipeline.QaResult();var turn=turn(db,embedding,result,"查询器物操作",null);
            var args=json.createObjectNode().put("url",url).put("limit",1);
            assertThrows(IllegalArgumentException.class,()->turn.execute("query_page_links",args));
            String html="<div class='item-content common-text'><p><a href='/item/73.html'>暮轮</a><a href='/item/96.html'>七叶</a></p></div>";
            new WebResourceCache(db,new WikiScraperService()).put(url,new WikiScraperService.Resource(200,html.getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",url,null,null));
            result.vectorResults=List.of(new VectorStore.SearchResult("c","已读取正文",null,null,"web",1,url));
            var response=json.readTree(turn.execute("query_page_links",args));assertEquals(2,response.path("total").asInt());assertEquals(1,response.path("links").size());assertEquals(1,response.path("nextOffset").asInt());assertFalse(response.path("candidateIsEvidence").asBoolean());
        }finally{db.shutdown();}
    }
    @Test void imageToolUsesRegisteredItemKindUnlessTheQuestionRequestsAnEntity()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            var result=new QaPipeline.QaResult();result.resolvedEntities=List.of(new EntityAgent.ResolvedEntity("alpha:aster","alpha",1,"fixture"));
            var turn=turn(db,embedding,result,"器物怎么制作，请展示配方图",null);
            turn.execute("query_images",json.readTree("{\"id\":\"alpha:aster\",\"need\":\"物品外观\"}"));
            var plan=MediaRequestPlanner.plan(result.mediaQuestion,List.of(new MediaRequestPlanner.Subject("alpha:aster","ITEM",List.of("器物"))),Set.of("alpha:aster"));
            assertEquals("ITEM",plan.requests().getFirst().kind());
        }finally{db.shutdown();}
    }
    @Test void unselectedExternalSourceCannotBecomeAnInstalledModClaim()throws Exception{
        var db=db();try(var embedding=new EmbeddingService(null)){
            scope(db,"a","北辰",null);var turn=turn(db,embedding,new QaPipeline.QaResult(),"介绍一种器物",null);
            String corrected=turn.constrainEnvironment("当前快照安装了北辰。",new RuntimeEvidencePolicy.Snapshot("p","1.21.1","neoforge",Map.of()));
            assertFalse(corrected.contains("安装了北辰"));assertTrue(corrected.contains("尚未核实"));
        }finally{db.shutdown();}
    }
    @Test void repeatedActualStateIsDeduplicatedWhileDifferentStatesWithSharedPixelsRemain(){
        var result=new QaPipeline.QaResult();var pictures=new ArrayList<AnswerArtifacts.Picture>();
        for(int i=0;i<3;i++)pictures.add(new AnswerArtifacts.Picture("i"+i,"sameAsset","samePath","器物",null,"ENTITY",List.of(),"",null,null,null,new AnswerArtifacts.RuntimeIdentity("alpha:beast",i<2?"first":"second",Map.of())));
        result.artifacts=new AnswerArtifacts(null,null,List.of(),pictures);PlayerMediaPresentation.captions(result);
        assertEquals(List.of("i0","i2"),result.artifacts.pictures().stream().map(AnswerArtifacts.Picture::id).toList());
    }
}
