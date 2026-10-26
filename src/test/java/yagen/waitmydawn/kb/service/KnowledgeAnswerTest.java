package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAnswerTest {
    @TempDir Path temp;
    private DatabaseBuilder db() { var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();new VectorStore(db,384);return db; }
    private RagAgentService llm() { return new RagAgentService(null) { @Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;} }; }
    private BuildRequest.Source source(DatabaseBuilder db,String location,String scope,String name) {
        var source=BuildRequest.Source.of(BuildRequest.Kind.URL,location);var content=new KnowledgeContentStore(db);
        content.saveSource(source);content.setSourceScope(source.id(),scope,"MODPACK",name,null);return source;
    }
    private String chunk(DatabaseBuilder db,EmbeddingService embedder,BuildRequest.Source source,String id,String title,String body,Integer page) {
        var content=new KnowledgeContentStore(db);
        content.saveDocument(new KnowledgeContent.Document("d"+id,source.id(),title,source.location()+"/"+id,BuildRequest.fingerprint(body),"text/html"));
        content.linkDocumentSource("d"+id,source.id());
        content.saveChunk(new KnowledgeContent.Chunk(id,"d"+id,body,"组装",page,0));
        content.setChunkMethod(id,"NATIVE");
        new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk(id,body,null,title,"web",source.location()+"/"+id)),embedder);
        return id;
    }
    private AnswerArtifacts.Picture picture(DatabaseBuilder db,String chunk,String role,String caption,String ocr) throws Exception {
        var store=new KnowledgeContentStore(db);var image=new ImageAssetStore(temp).put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));
        store.saveImage(new KnowledgeContent.ImageAsset(image.id(),image.relativePath(),image.mediaType(),image.width(),image.height(),ocr,null));
        store.saveOccurrence(new KnowledgeContent.ImageOccurrence("o"+chunk,"d"+chunk,image.id(),caption,"image.webp",1,0));
        store.setOccurrenceMetadata("o"+chunk,role,"test",null,"OCR_OK");
        store.saveLink(new KnowledgeContent.ChunkImageLink(chunk,"o"+chunk,"same_block"));
        return new AnswerArtifacts.Picture("i"+BuildRequest.fingerprint("o"+chunk).substring(0,20),image.id(),image.relativePath(),caption,
                "s"+BuildRequest.fingerprint(chunk).substring(0,20),role,List.of(chunk),"傀儡核心组装");
    }

    @Test void namedPackBypassesModAgentsAndDoesNotMixAnotherPack() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);var llm=llm();
        var a=source(db,"https://example.com/a","mcmod:modpack:737","龙之冒险：新征程");
        var b=source(db,"https://example.com/b","other","另一个整合包");
        chunk(db,embedder,a,"a","傀儡教程","傀儡核心需要先组装，再安装升级模块。",1);
        chunk(db,embedder,b,"b","其他教程","傀儡核心需要钻石三百个，另一个包独有规则。",1);
        picture(db,"a","ILLUSTRATION","傀儡核心组装",null);
        var entities=new EntityAgent(llm,db,embedder,new VectorStore(db,384)) {
            @Override public EntityResolutionResult resolveEntities(String q,String context){fail("指定整合包资料不应进入模组解析");return null;}
        };
        var urls=new UrlAgent(llm,db,embedder) {
            @Override public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> hits,List<String> topics,List<String> queries){fail("资料问答不应抓取额外机制网页");return null;}
        };
        var answers=new AnswerAgent(llm) {
            @Override public String compose(AnswerContext context) {
                assertTrue(context.knowledgeEvidence().contains("傀儡核心"));
                assertFalse(context.knowledgeEvidence().contains("三百"));
                return "先组装傀儡核心，再安装升级模块。 [[image:i"+BuildRequest.fingerprint("oa").substring(0,20)+"]]";
            }
        };
        try(var manager=new MultiDBManager(temp,db)) {
            var pipeline=new QaPipeline(new ClassifyAgent(llm),entities,urls,answers,llm,new VectorStore(db,384),embedder,db,manager,temp);
            var result=pipeline.process("龙之冒险新征程如何组装并升级傀儡");
            assertEquals("mcmod:modpack:737",result.artifacts.scopeId());
            assertEquals(1,result.vectorResults.size());assertEquals(1,result.artifacts.pictures().size());
            assertTrue(result.answer.contains("kb-image:"));assertFalse(result.answer.contains("三百"));
            assertEquals(0,result.metrics.httpRequestCount);
            var follow=pipeline.process("这个如何升级");assertEquals("mcmod:modpack:737",follow.artifacts.scopeId());
        } finally {db.shutdown();embedder.close();}
    }

    @Test void imageTextSearchFindsRelatedParagraphAndPreviewRequiresExplicitRequest() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);
        var a=source(db,"https://example.com/a","pack","样本资料");
        chunk(db,embedder,a,"a","组装图","步骤示意如下。",1);
        picture(db,"a","ILLUSTRATION","操作示意","GOLEM CORE ASSEMBLY");
        var service=new KnowledgeRetrievalService(db,embedder);
        try {
            var result=service.retrieve("GOLEM CORE ASSEMBLY",service.resolve("GOLEM CORE ASSEMBLY","pack",null));
            assertEquals(1,result.hits().size());assertEquals(1,result.artifacts().pictures().size());
            assertTrue(result.artifacts().pictures().getFirst().context().contains("OCR识别"));
            new KnowledgeContentStore(db).setOccurrenceMetadata("oa","PAGE_PREVIEW","test",null,"NATIVE_TEXT_ONLY");
            assertTrue(service.retrieve("GOLEM CORE ASSEMBLY",service.resolve("GOLEM CORE ASSEMBLY","pack",null)).artifacts().pictures().isEmpty());
            assertEquals(1,service.retrieve("GOLEM CORE ASSEMBLY整页预览",service.resolve("GOLEM CORE ASSEMBLY整页预览","pack",null)).artifacts().pictures().size());
        } finally {db.shutdown();embedder.close();}
    }

    @Test void bilingualAliasDiscoveryCannotBorrowMappingsFromAnotherScope() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);
        var a=source(db,"https://example.com/chosen","chosen","资料甲");
        var b=source(db,"https://example.com/other","other","资料乙");
        chunk(db,embedder,a,"a","入门","星河术法通过祭坛制作法杖，需要先收集材料。",null);
        chunk(db,embedder,b,"b","别名","星河术法 (NovaCraft) 使用另一份资料的独立配方。",null);
        try {
            var service=new KnowledgeRetrievalService(db,embedder);
            var result=service.retrieve("NovaCraft怎么玩",service.resolve("NovaCraft怎么玩","chosen",null),false);
            assertTrue(result.diagnostics().aliases().isEmpty());
            assertTrue(result.artifacts().sources().stream().noneMatch(s -> s.location().contains("/other/")));
            chunk(db,embedder,a,"a2","名称","星河术法 (NovaCraft) 是该资料中的模组名称。",null);
            result=service.retrieve("NovaCraft怎么玩",service.resolve("NovaCraft怎么玩","chosen",null),false);
            assertTrue(result.diagnostics().aliases().stream().anyMatch(alias -> alias.equivalent().equals("星河术法") && alias.documentId().equals("da2")));
            assertTrue(result.evidence().contains("NovaCraft → 星河术法"));
        } finally {db.shutdown();embedder.close();}
    }

    @Test void fileAndPageScopeAndRemovedScopeAreExplicit() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);
        var a=source(db,"https://example.com/a","pack","样本资料");
        chunk(db,embedder,a,"a","组装说明.pdf","傀儡核心组装方法。",1);
        var content=new KnowledgeContentStore(db);
        content.saveChunk(new KnowledgeContent.Chunk("a2","da","傀儡升级模块的安装方法。","升级",2,1));
        new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk("a2","傀儡升级模块的安装方法。",null,"组装说明.pdf","local_document",a.location()+"/a")),embedder);
        var service=new KnowledgeRetrievalService(db,embedder);
        try {
            var resolution=service.resolve("组装说明.pdf第2页如何升级",null,null);
            assertEquals("da",resolution.documentId());
            var result=service.retrieve("组装说明.pdf第2页如何升级",resolution);
            assertEquals(List.of("a2"),result.hits().stream().map(VectorStore.SearchResult::chunkId).toList());
            assertEquals("document:da",result.artifacts().scopeId());
            assertNotNull(service.resolve("如何组装","removed",null).warning());
        } finally {db.shutdown();embedder.close();}
    }

    @Test void controlledReferencesRejectInventedPathsAndHistorySurvivesRebuild() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);
        var a=source(db,"https://example.com/a","pack","样本资料");
        chunk(db,embedder,a,"a","组装说明.pdf","傀儡核心组装方法。",2);
        var image=picture(db,"a","ILLUSTRATION","傀儡核心组装",null);
        var ref=new AnswerArtifacts.Source(image.sourceId(),"da","组装说明.pdf",temp.resolve("missing.pdf").toString(),"组装",2,"OCR","傀儡核心组装方法。");
        var artifacts=new AnswerArtifacts("pack","样本资料",List.of(ref),List.of(image));
        String text=AnswerMedia.bind("傀儡核心组装。 [[image:"+image.id()+"]] [[image:invented]] ![fake](C:/secret.png) [猜测](https://unverified.example) [[source:"+ref.id()+"]]",artifacts);
        assertFalse(text.contains("C:/secret.png"));assertFalse(text.contains("invented"));assertFalse(text.contains("unverified.example"));
        assertTrue(text.contains("kb-source:"));assertTrue(text.contains("kb-image:"));
        try {
            var history=new ChatHistoryService(temp);var session=history.newSession("图文测试",temp);
            history.addMessage(session,"user","如何组装傀儡");history.addMessage(session,"maa",text,artifacts);history.save(session);
            db.clearBuildData();
            var loaded=history.load(session.sessionId).messages.getLast();
            assertEquals(artifacts,loaded.artifacts());
            assertTrue(Files.isRegularFile(AnswerMedia.resolveImage(temp,loaded.artifacts().pictures().getFirst())));
            assertEquals("傀儡核心组装方法。",loaded.artifacts().sources().getFirst().evidence());
            var escaped=new AnswerArtifacts.Picture(image.id(),image.assetId(),"../../escape.png",image.caption(),image.sourceId(),image.role(),List.of(),"");
            assertThrows(java.io.IOException.class,()->AnswerMedia.resolveImage(temp,escaped));
        } finally {db.shutdown();embedder.close();}
    }

    @Test void legacyMessagesStillLoadAndMarkersInCodeAreNotActivated() throws Exception {
        var history=new ChatHistoryService(temp);Files.createDirectories(temp.resolve("sessions"));
        Files.writeString(temp.resolve("sessions/legacy.json"),"{\"sessionId\":\"legacy\",\"messages\":[{\"role\":\"maa\",\"content\":\"old\",\"timestamp\":\"00:00\"}]}");
        assertNull(history.load("legacy").messages.getFirst().artifacts());
        var image=new AnswerArtifacts.Picture("i1","a".repeat(64),"aa/"+"a".repeat(64)+".png","图",null,"ILLUSTRATION",List.of(),"");
        String text=AnswerMedia.bind("`[[image:i1]]`",new AnswerArtifacts(null,null,List.of(),List.of(image)));
        assertFalse(text.contains("kb-image:"));
    }

    @Test void tierLabelsAreDiagnosticRatherThanHardFilters() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);
        var source=source(db,"https://example.com/a","pack","龙之冒险：新征程");
        chunk(db,embedder,source,"a","装备推荐","T1.1 武器强度梯度：寒冰剑，T3流程阶段也可选择该武器。",null);
        chunk(db,embedder,source,"b","装备推荐","T3 装备推荐：武器与饰品需要按教程搭配。",null);
        chunk(db,embedder,source,"c","装备推荐","T2 武器强度梯度：寒冰剑适合装备过渡。",null);
        try {
            var service=new KnowledgeRetrievalService(db,embedder);
            var result=service.retrieve("龙之冒险新征程的T3装备武器饰品推荐",service.resolve("龙之冒险新征程的T3装备武器饰品推荐",null,null));
            assertEquals(Set.of("a","b","c"),new HashSet<>(result.hits().stream().map(VectorStore.SearchResult::chunkId).toList()));
            assertTrue(result.diagnostics().candidates().stream().anyMatch(d -> d.chunkId().equals("c") && d.tierLabelDifference() && d.decision().equals("SELECTED")));
            assertEquals(3,result.diagnostics().scanned());
        } finally {db.shutdown();embedder.close();}
    }

    @Test void imageAutoPlacementFollowsItsExplanationInsteadOfTheAnswerTail() {
        var image=new AnswerArtifacts.Picture("i1","a".repeat(64),"aa/"+"a".repeat(64)+".png","傀儡核心组装",null,"ILLUSTRATION",List.of(),"先组装傀儡核心，再升级模块");
        String answer=AnswerMedia.bind("先组装傀儡核心，再升级模块。\n\n女仆还有其他用途。",new AnswerArtifacts(null,null,List.of(),List.of(image)));
        assertTrue(answer.indexOf("kb-image:")<answer.indexOf("女仆"));
    }

    @Test void recipeSnapshotSurvivesGeneratedFileRemovalAndMissingRecipeDoesNotLoseText() throws Exception {
        Path generated=Files.createDirectories(temp.resolve("generated"));
        Path recipe=generated.resolve("core.png");
        var original=new ImageAssetStore(temp).put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));
        Files.copy(temp.resolve("assets/images").resolve(original.relativePath()),recipe);
        var result=new QaPipeline.QaResult();result.answer="核心需要在工作台制作。";
        AnswerMedia.attachRecipes(result,Map.of("核心",recipe.toString()),temp);
        assertEquals(1,result.artifacts.pictures().size());
        assertTrue(result.answer.contains("kb-image:"));
        Files.delete(recipe);
        assertTrue(Files.isRegularFile(AnswerMedia.resolveImage(temp,result.artifacts.pictures().getFirst())));
        var missing=new QaPipeline.QaResult();missing.answer="核心需要在工作台制作。";
        AnswerMedia.attachRecipes(missing,Map.of("核心",recipe.toString()),temp);
        assertEquals("核心需要在工作台制作。",missing.answer);
        assertTrue(missing.artifacts.pictures().isEmpty());
    }

    @Test void knownModScopeRetainsBoundJarRecipes() throws Exception {
        var db=db();var embedder=new EmbeddingService(null);var llm=llm();
        var source=source(db,"https://example.com/mod","mcmod:class:1","测试模组");
        new KnowledgeContentStore(db).setSourceScope(source.id(),"mcmod:class:1","MOD","测试模组","sample");
        chunk(db,embedder,source,"a","配方教程","核心的制作需要工作台。",null);
        var mod=new ModEntry();mod.setModId("sample");db.saveModEntry(mod);
        try(var c=db.getConnection();var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO rag_recipe(recipe_type,output_item,recipe_data,source_mod) VALUES('crafting','sample:core','{\"type\":\"minecraft:crafting_shaped\",\"result\":{\"id\":\"sample:core\"}}','sample')");
        }
        var entities=new EntityAgent(llm,db,embedder,new VectorStore(db,384)) {
            @Override public EntityResolutionResult resolveEntities(String q,String context) {
                return new EntityResolutionResult(List.of(),List.of(),List.of(new ResolvedEntity("sample:core","sample",1,"test")),List.of(q),null,null);
            }
        };
        var answer=new AnswerAgent(llm) {
            @Override public String compose(AnswerContext context) {assertEquals(1,context.recipeMatches().size());return "核心需要工作台。";}
        };
        try(var manager=new MultiDBManager(temp,db)) {
            var pipeline=new QaPipeline(new ClassifyAgent(llm),entities,new UrlAgent(llm,db,embedder),answer,llm,new VectorStore(db,384),embedder,db,manager,temp);
            var result=pipeline.process("测试模组的核心怎么制作");
            assertEquals(1,result.recipeResults.size());assertEquals(1,result.recipesToRender.size());
        } finally {db.shutdown();embedder.close();}
    }
}
