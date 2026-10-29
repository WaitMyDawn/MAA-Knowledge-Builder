package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLootTest extends RuntimeKnowledgeStoreTest {
    void loot(Path root,String category,String id,boolean failed)throws Exception {
        var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category",category).put("authoritative",true);var row=payload.putArray("records").addObject().put("id",id);
        if(failed)row.put("error","unsupported codec");else {
            row.put("runtimeLoaded",true);if(category.equals("loot_modifiers"))row.put("identity","CAPTURE_ORDER").put("order",0);
            row.set("definition",json.readTree("{\"pools\":[{\"rolls\":1,\"conditions\":[{\"condition\":\"minecraft:killed_by_player\"}],\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"mkb_fixture:sample_a\",\"weight\":2}]}]}"));
        }
        Path file=root.resolve("data/"+category+".json");json.writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(root.resolve("snapshot.json").toFile());
        descriptor.withObject("categories").putObject(category).put("status",failed?"COMPLETE_WITH_ERRORS":"COMPLETE").put("total",1).put("succeeded",failed?0:1).put("failed",failed?1:0).put("transparent",0).put("unsupported",0).put("file","data/"+category+".json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(root.resolve("snapshot.json").toFile(),descriptor);
    }
    @Test void lootRulesAreIndexedWithNamesAndFailuresNeverBecomeDropFacts()throws Exception {
        Path root=fixture("MKB-Export-loot",2);loot(root,"loot_tables","mkb_fixture:entities/example",false);loot(root,"loot_modifiers","mkb_export:global_modifier/0",true);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("loot-db"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedding);builder.build(request(root,BuildRequest.Mode.REBUILD),null);
            var store=new SourceLifecycleStore(db);assertEquals(2,store.strings("SELECT record_id FROM rag_runtime_record WHERE category LIKE 'loot_%'").size());
            var text=String.join("\n",store.strings("SELECT chunk_text FROM rag_vectors WHERE chunk_type='runtime_loot_tables'"));assertTrue(text.contains("实例中的名称"));assertTrue(text.contains("killed_by_player"));assertTrue(text.contains("不保证必掉"));
            assertTrue(store.strings("SELECT chunk_id FROM rag_vectors WHERE chunk_type='runtime_loot_modifiers'").isEmpty());assertTrue(RuntimeLootSummary.capability(db,"掉落什么").contains("未完整"));
            String before=db.contentFingerprint();builder.build(request(root,BuildRequest.Mode.APPEND),null);assertEquals(before,db.contentFingerprint());
            Files.writeString(root.resolve("data/loot_tables.json"),"{}");assertThrows(IllegalArgumentException.class,()->builder.build(request(root,BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
        }finally{db.shutdown();}
    }
}
