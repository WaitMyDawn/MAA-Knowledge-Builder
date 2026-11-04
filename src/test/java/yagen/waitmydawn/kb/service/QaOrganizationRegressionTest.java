package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.config.LogSetup;

class QaOrganizationRegressionTest {
    @TempDir Path temp;
    @AfterEach void cleanup(){LogSetup.shutdown();}
    @Test void emptySourceColumnIsRemovedWithoutDroppingUsefulColumns(){
        String input="| 物品 | 获取方式 | 来源 |\n|---|---|---|\n| 晶石 | 开采 | — |\n| 木棒 | 合成 | 无 |";
        String result=AnswerPostProcess.apply(input,"获取方式");
        assertFalse(result.contains("来源"));assertTrue(result.contains("|晶石|开采|"));assertTrue(result.contains("|木棒|合成|"));
        assertTrue(AnswerPostProcess.apply(input.replace("| 无 |","| 条目页 |"),"获取方式").contains("来源"));
        String empty="| 项目 | 数据 |\n|---|---|\n| — | — |";assertEquals(empty,AnswerPostProcess.apply(empty,"查看表格"));
    }
    @Test void genericInvitationIsRemovedButConcreteAmbiguityIsPreserved(){
        String input="该状态未导出。\n\n如需更详细的信息，请尝试更具体地描述你的问题，例如指定模组名称。";
        assertEquals("该状态未导出。",AnswerPostProcess.apply(input,"展示指定状态").strip());
        String targeted="两个模组都有同名水晶，请选择 example_a 或 example_b。";assertEquals(targeted,AnswerPostProcess.apply(targeted,"水晶"));
        String code="```text\n如需更详细的信息，请尝试更具体地描述你的问题。\n```";assertEquals(code,AnswerPostProcess.apply(code,"模板示例"));
    }
    @Test void unrelatedCandidatesNeverBecomeFallbackSources(){
        var irrelevant=new AnswerArtifacts.Source("bad","b","深海守卫","https://example.test/b","正文",null,"HTML","深海守卫会掉落海洋碎片。");
        var relevant=new AnswerArtifacts.Source("good","g","晶石","https://example.test/g","获取",null,"HTML","晶石可以在地下矿脉开采。");
        var artifacts=new AnswerArtifacts(null,null,List.of(irrelevant,relevant),List.of());
        String text=AnswerMedia.bind("晶石可以在地下矿脉开采。",artifacts);
        assertTrue(text.contains("kb-source:good"));assertFalse(text.contains("kb-source:bad"));
        assertFalse(AnswerMedia.bind("本轮未找到晶石的获取方式。",artifacts).contains("资料来源："));
    }
    @Test void expandedRegistryDoesNotReinterpretFollowupAsASourceSelection()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("db"));db.initDatabase();
        try(var embedder=new EmbeddingService(null)){
            var content=new KnowledgeContentStore(db);for(int i=0;i<2;i++){var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.test/"+i);content.saveSource(source);content.setSourceScope(source.id(),"scope"+i,"MOD","minecraft","minecraft");content.saveDocument(new KnowledgeContent.Document("doc"+i,source.id(),"资料"+i,source.location(),"hash","text/html"));}
            var knowledge=new KnowledgeRetrievalService(db,embedder);assertNotNull(knowledge.resolve("minecraft:cow 再展示它的幼年形态",null,null).warning());
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};
            var turn=new QaAgentTurn(db,new VectorStore(db,384),knowledge,new UrlAgent(llm,db,embedder),new QaPipeline.QaResult(),"minecraft:cow 再展示它的幼年形态",null,null,()->{},"再展示它的幼年形态");
            assertNull(turn.stopReason());assertNull(turn.clarification);
        }finally{db.shutdown();}
    }
}
