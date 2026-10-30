package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShortModScopeTest {
    @TempDir Path temp;
    @Test void explicitModCueResolvesShortNamesButOrdinaryWordsDoNot() {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try {
            var store=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://www.mcmod.cn/class/5214.html");store.saveSource(source);
            store.setSourceScope(source.id(),"mcmod:class:5214","MOD","灾变",null);
            store.saveDocument(new KnowledgeContent.Document("doc",source.id(),"灾变",source.location(),"hash","text/html"));
            var service=new KnowledgeRetrievalService(db,null,temp,false);
            assertEquals("mcmod:class:5214",service.resolve("灾变模组有哪些主要Boss",null,null).scope().id());
            assertEquals("mcmod:class:5214",service.resolve("灾变这个模组怎么玩",null,null).scope().id());
            assertNull(service.resolve("世界发生灾变后有什么变化",null,null).scope());
            var other=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/another");store.saveSource(other);store.setSourceScope(other.id(),"another","MOD","灾变",null);
            store.saveDocument(new KnowledgeContent.Document("other",other.id(),"灾变",other.location(),"hash2","text/html"));
            assertNotNull(service.resolve("灾变模组有哪些Boss",null,null).warning());
        } finally {db.shutdown();}
    }
}
