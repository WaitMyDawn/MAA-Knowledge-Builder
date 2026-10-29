package yagen.waitmydawn.kb.service;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import java.nio.file.*;import java.util.*;
import yagen.waitmydawn.kb.config.*;import yagen.waitmydawn.kb.model.*;
class RuntimeMediaComparisonTest {
 @TempDir Path temp;
 @AfterEach void shutdown(){LogSetup.shutdown();}
 @Test void multipleStatesAreDistinctAndInvalidStateCannotBorrowDefault()throws Exception {
  var f=new EntityStateProtocolTest();f.temp=temp;var adult=f.row("adult","blue",false,256,true);var baby=f.row("baby","red",false,256,true);Path root=f.fixture(List.of(adult,baby));var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();try(var embedding=new EmbeddingService(null)){
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
   var qa=new QaPipeline.QaResult();qa.answer="实体对照";new RuntimeEntityMedia(db,temp).attach(qa,"比较 mkb_fixture:creature 的成年与幼年实体外观");assertEquals(2,qa.artifacts.pictures().size());assertEquals(2,AnswerMedia.assess(qa.answer,qa.artifacts,temp).valid());assertTrue(qa.artifacts.pictures().stream().anyMatch(p->p.caption().contains("幼年")));assertTrue(qa.artifacts.pictures().stream().anyMatch(p->p.caption().contains("成年")));
   var misplaced=new QaPipeline.QaResult();var media=new RuntimeEntityMedia(db,temp);String question="比较 mkb_fixture:creature 的成年与幼年实体外观";media.prepare(misplaced,question);var young=misplaced.artifacts.pictures().stream().filter(p->p.caption().contains("幼年")).findFirst().orElseThrow();misplaced.answer="成年 mkb_fixture:creature 的外观\n\n![错误位置](kb-image:"+young.id()+")";media.finish(misplaced,question);assertTrue(misplaced.answer.indexOf("kb-image:"+young.id())>misplaced.answer.indexOf("外观对照："));
   var invalid=new QaPipeline.QaResult();invalid.answer="外观";new RuntimeEntityMedia(db,temp).attach(invalid,"展示 mkb_fixture:creature age=ancient 成年实体外观");assertTrue(invalid.artifacts.pictures().isEmpty());assertFalse(invalid.mediaWarnings.isEmpty());
   var history=new ChatHistoryService(temp);var session=history.newSession("对比回放",temp);history.addMessage(session,"maa",qa.answer,qa.artifacts);history.save(session);assertEquals(2,AnswerMedia.assess(history.load(session.sessionId).messages.getFirst().content(),history.load(session.sessionId).messages.getFirst().artifacts(),temp).valid());
  }finally{db.shutdown();}
 }
 @Test void standaloneItemUsesNativeAssetAndDefaultDoesNotPretendToBeAnUnknownState()throws Exception {
  var f=new RuntimeKnowledgeStoreTest();f.temp=temp;Path root=f.fixture("item",2);var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();try(var embedding=new EmbeddingService(null)){
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
   var qa=new QaPipeline.QaResult();qa.answer="物品说明";var media=new RuntimeEntityMedia(db,temp);media.attach(qa,"展示 mkb_fixture:sample_a 的物品外观");assertEquals(1,qa.artifacts.pictures().size());assertEquals("ITEM",qa.artifacts.pictures().getFirst().role());assertNotNull(qa.artifacts.pictures().getFirst().sourceId());assertEquals(1,AnswerMedia.assess(qa.answer,qa.artifacts,temp).valid());
   var invalid=new QaPipeline.QaResult();invalid.answer="物品说明";media.attach(invalid,"展示 mkb_fixture:sample_a color=unknown 物品外观");assertTrue(invalid.artifacts.pictures().isEmpty());
  }finally{db.shutdown();}
 }
 @Test void vectorOnlyConversationDatabaseHasNoRuntimeProfile(){try(var incremental=new IncrementalDB(temp,"rag_data_media",384)){assertNull(RuntimeKnowledgeStore.active(incremental.getDb()));assertDoesNotThrow(()->new EntityResolver().rebuildFromDB(incremental.getDb()));}}
}
