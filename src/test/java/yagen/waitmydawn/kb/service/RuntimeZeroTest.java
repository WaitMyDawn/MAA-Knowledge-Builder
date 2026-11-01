package yagen.waitmydawn.kb.service;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import java.nio.file.*;import java.util.*;import yagen.waitmydawn.kb.config.*;import yagen.waitmydawn.kb.model.*;import static org.junit.jupiter.api.Assertions.*;
class RuntimeZeroTest {
 @TempDir Path temp;private final ObjectMapper json=new ObjectMapper();
 @AfterEach void logging(){LogSetup.shutdown();}
 @Test void zeroVectorsKeepNamesRulesAndMigrationPreservesWebEvidence()throws Exception {
  var root=RuntimeBuildFixtures.create(temp.resolve("export"),List.of(),"neoforge","1.21.1");var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedder=new EmbeddingService(null)){
   var builder=new KnowledgeBuilder(config,db,embedder);builder.build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
   assertEquals(0,new SourceLifecycleStore(db).strings("SELECT chunk_id FROM rag_vectors").size());
   var store=new VectorStore(db,384);var web=new TextChunker.TextChunk("web-test","valid web knowledge","test","Test","web","https://example.com");var old=new TextChunker.TextChunk("old-runtime","old noisy rule","test","Test","runtime_recipe","mkb-runtime:old/recipe/test:item");store.storeBatch(List.of(web,old),embedder);
   String profile=RuntimeKnowledgeStore.active(db);var query=new RuntimeStructuredQuery(db,profile);int before=query.page("registries",null,RuntimeStructuredQuery.State.any(),0,100).total();RuntimeKnowledgeStore.migrateRuntimeText(db);
   assertEquals(List.of("web-test"),new SourceLifecycleStore(db).strings("SELECT chunk_id FROM rag_vectors"));assertEquals(before,query.page("registries",null,RuntimeStructuredQuery.State.any(),0,100).total());
   var index=new RuntimeNameIndex(db,profile);assertFalse(index.resolve("minecraft:stone",null,"ITEM").candidates().isEmpty());
  }finally{db.shutdown();}
 }
 @Test void duplicateChineseNamesRemainAmbiguousAndFactsAndJsonPagesAreComplete()throws Exception {
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);var db=new DatabaseBuilder(config);db.initDatabase();String profile="a".repeat(64);
  try {
   var descriptor=json.createObjectNode();for(String category:List.of("registries","language","trades","potions"))((ObjectNode)descriptor.with("categories")).putObject(category).put("status","COMPLETE");
   try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_snapshot VALUES('source',?,?)")){p.setString(1,profile);p.setString(2,descriptor.toString());p.executeUpdate();}
   try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){
    for(String mod:List.of("a","b")){var row=json.createObjectNode().put("id",mod+":ghost").put("registry","entity_type").put("descriptionId","entity."+mod+".ghost");p.setString(1,profile);p.setString(2,"registries");p.setString(3,"entity_type|"+mod+":ghost");p.setString(4,row.toString());p.addBatch();}
    var language=json.createObjectNode().put("id","zh_cn");language.putObject("translations").put("entity.a.ghost","幽灵").put("entity.b.ghost","幽灵").put("item.minecraft.potion.effect.strength","力量药水");p.setString(2,"language");p.setString(3,"zh_cn");p.setString(4,language.toString());p.addBatch();
    var potion=json.createObjectNode().put("id","minecraft:strong_strength");potion.putArray("effects").addObject().put("effect","minecraft:strength").put("amplifier",1).put("durationTicks",1800);p.setString(2,"potions");p.setString(3,"minecraft:strong_strength");p.setString(4,potion.toString());p.addBatch();var normal=potion.deepCopy();normal.put("id","minecraft:strength");((com.fasterxml.jackson.databind.node.ObjectNode)normal.path("effects").get(0)).put("amplifier",0);p.setString(3,"minecraft:strength");p.setString(4,normal.toString());p.addBatch();
    var trade=json.createObjectNode().put("id","test:trade").put("entity","minecraft:villager").put("profession","minecraft:librarian").put("level",3);var entries=trade.putArray("entries");for(int i=0;i<300;i++)entries.addObject().put("index",i).put("description","long verified rule ".repeat(20));p.setString(2,"trades");p.setString(3,"test:trade");p.setString(4,trade.toString());p.addBatch();p.executeBatch();
   }
   var names=new RuntimeNameIndex(db,profile);assertEquals("AMBIGUOUS",names.resolve("幽灵",null,"ENTITY").status());assertEquals(2,names.resolve("幽灵",null,"ENTITY").candidates().size());assertEquals("UNIQUE",names.resolve("幽灵","a","ENTITY").status());assertFalse(names.mentions("a:ghost怎么打","ENTITY").isEmpty());assertEquals("UNIQUE",names.resolve("力量药水Ⅱ",null,"ITEM").status());assertEquals("minecraft:strong_strength",names.resolve("力量药水 II",null,"ITEM").candidates().getFirst().registry());
   assertEquals("AMBIGUOUS",names.resolve("力量药水",null,"ITEM").status());
   try(var embedding=new EmbeddingService(null)) {
       var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};var vectors=new VectorStore(db,384);var knowledge=new KnowledgeRetrievalService(db,embedding);var urls=new yagen.waitmydawn.kb.agent.UrlAgent(llm,db,embedding);
       var backend=new QaAgentTurn(db,vectors,knowledge,urls,new QaPipeline.QaResult(),"力量药水II怎么酿造",null,null,()->{});
       var resolved=json.readTree(backend.execute("resolve_objects",json.readTree("{\"name\":\"力量药水\",\"kind\":\"ITEM\"}")));assertEquals("UNIQUE",resolved.path("resolution").path("status").asText());assertTrue(resolved.toString().contains("minecraft:strong_strength"));
       var registry=json.readTree(backend.execute("query_runtime",json.readTree("{\"category\":\"registries\",\"id\":\"a:ghost\"}")));assertEquals("FOUND",registry.path("status").asText());assertTrue(registry.toString().contains("entity_type|a:ghost"));
       var uncertain=json.readTree(backend.execute("resolve_objects",json.readTree("{\"name\":\"幽灵s\",\"kind\":\"ENTITY\"}")));assertEquals("NOT_FOUND",uncertain.path("resolution").path("status").asText());assertFalse(uncertain.path("suggestions").isEmpty());
   }

   var query=new RuntimeStructuredQuery(db,profile);var trades=query.query("trades",Map.of("profession","minecraft:librarian","level","3"),0,10);assertEquals(1,trades.total());assertTrue(query.query("trades",Map.of("level","4"),0,10).rows().isEmpty());
   var pages=query.evidencePages(trades.rows().getFirst());assertTrue(pages.size()>1);for(var page:pages){String raw=page.chunkText().substring(page.chunkText().indexOf("JSON section "));raw=raw.substring(raw.indexOf('\n')+1);assertNotNull(json.readTree(raw));}assertTrue(pages.stream().anyMatch(page->page.chunkText().contains("299")));
  }finally{db.shutdown();}
 }
}
