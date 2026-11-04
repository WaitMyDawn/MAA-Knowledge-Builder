package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.agent.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FamilyDiscoveryTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private DatabaseBuilder database(){var db=DatabaseBuilder.atPath(temp.resolve("db"));db.initDatabase();return db;}
    private WebCatalog.Node category(String id,String parent,String mod,int top,String name,String... path){return new WebCatalog.Node(id,parent,mod,"https://www.mcmod.cn/item/list/100-"+top+".html",top,name,List.of(path));}
    private WebCatalog.Member member(String category,int number,String name){return new WebCatalog.Member(category,"https://www.mcmod.cn/item/"+number+".html",name,name,"Name "+number,"entry");}
    private JsonNode find(DatabaseBuilder db,String mod,String kind,String query,List<String> selected,String predicate,String pool,int offset,int limit)throws Exception{return new FamilyDiscovery(db,null).find(mod,kind,query,selected,predicate,pool,offset,limit);}

    @Test void catalogMembershipWorksWhenMemberNamesHaveNoCommonWords()throws Exception{
        var db=database();try{
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("chosen",null,"sample",1,"星野器具","器物","星野器具"),category("other",null,"sample",1,"其他","器物","其他")),List.of(member("chosen",1,"月阶"),member("chosen",2,"无声之池"),member("chosen",3,"白隼"),member("other",4,"星野器具复制品"))));
            var found=find(db,"sample","ITEM","星野器具",List.of(),"","",0,20);
            assertEquals("CATALOG_MEMBERS",found.path("status").asText());assertEquals(3,found.path("total").asInt());assertTrue(found.path("allStoredMembersReturned").asBoolean());
            assertEquals(Set.of("月阶","无声之池","白隼"),names(found));assertFalse(found.toString().contains("星野器具复制品"));assertFalse(found.path("mechanicsVerified").asBoolean());
        }finally{db.shutdown();}
    }
    @Test void categoryTreeUnionDeduplicatesMembershipAndPaginatesWithoutLoss()throws Exception{
        var db=database();try{
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("root",null,"sample",1,"器具","器具"),category("child","root","sample",1,"古物","器具","古物")),List.of(member("root",1,"甲"),member("child",1,"甲"),member("child",2,"乙"),member("child",3,"丙"))));
            var first=find(db,"sample","ITEM","器具",List.of("root","child"),"","",0,2);var next=find(db,"sample","ITEM","器具",List.of("root"),"","",2,2);
            assertEquals(3,first.path("total").asInt());assertEquals(2,first.path("nextOffset").asInt());assertEquals(2,first.path("members").get(0).path("membershipCount").asInt());
            var all=new HashSet<>(names(first));all.addAll(names(next));assertEquals(Set.of("甲","乙","丙"),all);assertEquals(-1,next.path("nextOffset").asInt());
        }finally{db.shutdown();}
    }
    @Test void propertyNeedsExplicitCategoryChoiceAndMemberVerification()throws Exception{
        var db=database();try{
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("animals",null,"sample",4,"生态群","生物","生态群"),category("water",null,"sample",4,"水域","生物","水域")),List.of(member("animals",8,"回响"),member("animals",9,"银线"),member("water",10,"苔纹"))));
            var choices=find(db,"sample","ENTITY","陆地生物",List.of(),"主要在陆地生活","",0,20);assertEquals("CATEGORY_SELECTION_REQUIRED",choices.path("status").asText());assertFalse(choices.has("members"));assertEquals(2,choices.path("categoryTotal").asInt());
            var selected=find(db,"sample","ENTITY","陆地生物",List.of("animals"),"主要在陆地生活","",0,20);assertEquals(2,selected.path("total").asInt());assertFalse(selected.path("additionalPredicateVerified").asBoolean());
            for(var member:selected.path("members"))assertEquals("PENDING_REQUESTED_PREDICATE",member.path("verification").asText());
            var mixed=find(db,"sample","ENTITY","水域",List.of("water","animals"),"","",0,20);
            assertFalse(mixed.path("additionalPredicateVerified").asBoolean());
        }finally{db.shutdown();}
    }
    @Test void sameCategoryNamesAcrossPathsRequireSelectionAndCannotCrossScope()throws Exception{
        var db=database();try{
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("a",null,"sample",1,"工具","古代","工具"),category("b",null,"sample",1,"工具","现代","工具"),category("foreign",null,"other",1,"工具","工具")),List.of(member("a",1,"甲"),member("b",2,"乙"),member("foreign",3,"丙"))));
            assertEquals("CATEGORY_SELECTION_REQUIRED",find(db,"sample","ITEM","工具",List.of(),"","",0,20).path("status").asText());
            assertThrows(IllegalArgumentException.class,()->find(db,"sample","ITEM","工具",List.of("foreign"),"","",0,20));
            assertEquals(Set.of("乙"),names(find(db,"sample","ITEM","工具",List.of("b"),"","",0,20)));
        }finally{db.shutdown();}
    }
    @Test void absentCatalogOffersLiteralStructuredCategoriesWithoutInventingLandMembership()throws Exception{
        var db=database();String profile="e".repeat(64);try{
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){
                for(var row:List.of("{\"id\":\"alpha_mod:q7\",\"biomeSpawns\":[{\"category\":\"custom_pool\"},{\"category\":\"custom_pool\"}]}","{\"id\":\"alphaxmod:x9\",\"biomeSpawns\":[{\"category\":\"custom_pool\"}]}")){var data=json.readTree(row);p.setString(1,profile);p.setString(2,"spawn_rules");p.setString(3,data.path("id").asText());p.setString(4,row);p.addBatch();}p.executeBatch();
            }
            var service=new FamilyDiscovery(db,profile);var choices=service.find("alpha_mod","ENTITY","陆地生物",List.of(),"","",0,20);assertEquals("CATALOG_MISSING",choices.path("status").asText());assertEquals(1,choices.path("runtimeCategoryChoices").get(0).path("count").asInt());
            var fallback=service.find("alpha_mod","ENTITY","陆地生物",List.of(),"主要在陆地生活","custom_pool",0,20);assertEquals("STRUCTURED_CANDIDATES",fallback.path("status").asText());assertEquals("alpha_mod:q7",fallback.path("members").get(0).path("id").asText());assertFalse(fallback.path("additionalPredicateVerified").asBoolean());
            assertThrows(IllegalArgumentException.class,()->service.find("alpha_mod","ENTITY","陆地生物",List.of(),"","land",0,20));
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("known",null,"alpha_mod",4,"陆地生物","陆地生物")),List.of(member("known",10,"其他候选"))));
            assertEquals("STRUCTURED_CANDIDATES",service.find("alpha_mod","ENTITY","陆地生物",List.of(),"","custom_pool",0,20).path("status").asText());
            assertThrows(IllegalArgumentException.class,()->service.find("alpha_mod","ENTITY","陆地生物",List.of("known"),"","custom_pool",0,20));
        }finally{db.shutdown();}
    }
    @Test void toolIsDiscoverableAndReturnsCatalogEvidenceWithoutModelOrPageFetch()throws Exception{
        var db=database();try(var embedding=new EmbeddingService(null)){
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("chosen",null,"sample",1,"器具","器具")),List.of(member("chosen",1,"回响"))));
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};var result=new QaPipeline.QaResult();
            var turn=new QaAgentTurn(db,new VectorStore(db,384),new KnowledgeRetrievalService(db,embedding),new UrlAgent(llm,db,embedding),result,"有哪些器具",null,null,()->{});
            assertTrue(AgentToolLoop.specifications().stream().anyMatch(tool->tool.name().equals("find_family")));
            var schema=AgentToolLoop.specifications().stream().filter(tool->tool.name().equals("find_family")).findFirst().orElseThrow().parameters().toString();
            assertTrue(schema.contains("1..40"));assertTrue(schema.contains("preserving the original semantic qualifiers"));
            var answer=json.readTree(turn.execute("find_family",json.readTree("{\"mod\":\"sample\",\"kind\":\"ITEM\",\"query\":\"器具\"}")));assertEquals(1,answer.path("total").asInt());assertEquals(2,result.vectorResults.size());assertNull(turn.readinessGap());
            assertTrue(result.vectorResults.getFirst().sourcePath().endsWith("100-1.html"));
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var outcome=new AgentToolLoop().run(request->{var message=calls.getAndIncrement()==0?dev.langchain4j.data.message.AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("family").name("find_family").arguments("{\"mod\":\"sample\",\"query\":\"器具\"}").build()):dev.langchain4j.data.message.AiMessage.from("READY");return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(message).build();},turn,"有哪些器具","");
            assertEquals("READY",outcome.reason());assertEquals(2,calls.get());assertEquals("find_family",outcome.events().getFirst().tool());
        }finally{db.shutdown();}
    }
    @Test void excludedSubtreeRemovesMembersBeforePagination()throws Exception{
        var db=database();try{
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("root",null,"sample",1,"器具","器具"),category("child","root","sample",1,"水生动物","器具","水生动物")),List.of(member("root",1,"甲"),member("child",2,"乙"))));
            var found=new FamilyDiscovery(db,null).find("sample","ITEM","器具",List.of("root"),List.of("child"),"","",0,20);
            assertEquals(Set.of("甲"),names(found));
        }finally{db.shutdown();}
    }

    @Test void requestedPredicateDoesNotBecomeReadyUntilMemberEvidenceIsAvailable()throws Exception{
        var db=database();try(var embedding=new EmbeddingService(null)){
            new WebCatalogStore(db).save(new WebCatalog(List.of(category("group",null,"sample",4,"生态群","生物","生态群")),List.of(member("group",21,"无规律名字"))));
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};var result=new QaPipeline.QaResult();
            var turn=new QaAgentTurn(db,new VectorStore(db,384),new KnowledgeRetrievalService(db,embedding),new UrlAgent(llm,db,embedding),result,"有哪些陆地生物",null,null,()->{});
            turn.execute("find_family",json.readTree("{\"mod\":\"sample\",\"kind\":\"ENTITY\",\"query\":\"陆地生物\"}"));assertTrue(turn.readinessGap().contains("category choices"));
            turn.execute("find_family",json.readTree("{\"mod\":\"sample\",\"kind\":\"ENTITY\",\"query\":\"陆地生物\",\"category_ids_json\":\"[\\\"group\\\"]\",\"predicate\":\"主要在陆地生活\"}"));
            turn.execute("find_mobs_by_spawn",json.readTree("{\"mod\":\"sample\"}"));
            assertTrue(turn.readinessGap().contains("item/21.html"));
            var hits=new ArrayList<>(result.vectorResults);hits.add(new VectorStore.SearchResult("page21","条目正文描述该生物的栖息环境。","sample","样本","web",1,"https://www.mcmod.cn/item/21.html"));result.vectorResults=hits;
            assertNull(turn.readinessGap());
        }finally{db.shutdown();}
    }
    @Test void answerReceivesAllReturnedMembersBeyondGenericSnippetAndHitLimits()throws Exception{
        var db=database();try(var embedding=new EmbeddingService(null)){
            String firstUrl="https://www.mcmod.cn/item/list/100-1.html",secondUrl="https://www.mcmod.cn/item/list/101-1.html",lastName="末尾无规律条目";
            var nodes=List.of(category("root",null,"sample",1,"器具","器具"),new WebCatalog.Node("child","root","sample",secondUrl,1,"古物",List.of("器具","古物")));
            var members=new ArrayList<WebCatalog.Member>();for(int i=0;i<24;i++)members.add(member(i<12?"root":"child",100+i,i==23?lastName:"名称"+i+"·"+"无规律".repeat(20)));
            new WebCatalogStore(db).save(new WebCatalog(nodes,members));
            var captured=new java.util.concurrent.atomic.AtomicReference<dev.langchain4j.model.chat.request.ChatRequest>();
            var model=new dev.langchain4j.model.chat.ChatModel(){@Override public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request){captured.set(request);return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("仅依据目录列出成员。")).build();}};
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return model;}};var result=new QaPipeline.QaResult();
            var irrelevant=new ArrayList<VectorStore.SearchResult>();for(int i=0;i<10;i++)irrelevant.add(new VectorStore.SearchResult("other"+i,"其他片段","sample","样本","mcmod",1,"https://example.test/"+i));result.vectorResults=irrelevant;
            var turn=new QaAgentTurn(db,new VectorStore(db,384),new KnowledgeRetrievalService(db,embedding),new UrlAgent(llm,db,embedding),result,"有哪些器具",null,null,()->{});
            var found=json.readTree(turn.execute("find_family",json.readTree("{\"mod\":\"sample\",\"kind\":\"ITEM\",\"query\":\"器具\",\"limit\":40}")));assertEquals(24,found.path("returned").asInt());
            assertFalse(result.vectorResults.stream().filter(hit->firstUrl.equals(hit.sourcePath())).findFirst().orElseThrow().chunkText().contains(lastName));
            assertTrue(result.vectorResults.stream().filter(hit->secondUrl.equals(hit.sourcePath())).findFirst().orElseThrow().chunkText().contains(lastName));
            var agent=new AnswerAgent(llm);
            for(var subqueries:List.of(List.<UrlAgent.SubQueryResult>of(),List.of(new UrlAgent.SubQueryResult("器具",result.vectorResults,List.of())))){
                agent.composeSinglePass(new AnswerAgent.AnswerContext("列出目录成员",yagen.waitmydawn.kb.dto.QuestionType.GENERAL,List.of(),List.of(),result.vectorResults,null,subqueries,null,null));
                String prompt=captured.get().messages().stream().filter(message->message instanceof dev.langchain4j.data.message.UserMessage).map(message->((dev.langchain4j.data.message.UserMessage)message).singleText()).reduce("",String::concat);
                assertTrue(prompt.contains(lastName));assertTrue(prompt.contains(secondUrl));
                for(var member:members)assertTrue(prompt.contains(member.url()),member.url());
                assertEquals("MODEL",agent.lastGenerationMode());
            }
        }finally{db.shutdown();}
    }
    private Set<String> names(JsonNode result){var names=new HashSet<String>();for(var row:result.path("members"))names.add(row.path("name").asText());return names;}
}
