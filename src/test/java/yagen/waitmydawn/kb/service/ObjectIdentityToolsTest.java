package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.config.LogSetup;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectIdentityToolsTest {
    @TempDir Path temp;
    @AfterEach void cleanup(){LogSetup.shutdown();}
    @Test void duplicateRecordsMergeButCrossNamespaceAndCrossKindNamesRemainAmbiguous()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);var db=new DatabaseBuilder(config);db.initDatabase();
        try{
            var json=new ObjectMapper();String profile="a".repeat(64);
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){
                String[][] objects={{"a","first:ghost","entity_type"},{"b","first:ghost","entity"},{"c","second:ghost","entity_type"},{"d","first:ghost","item"}};
                for(var object:objects){var node=json.createObjectNode().put("id",object[1]).put("displayName","幽灵").put("registry",object[2]);p.setString(1,profile);p.setString(2,"registries");p.setString(3,object[0]);p.setString(4,node.toString());p.addBatch();}p.executeBatch();
            }
            var index=new RuntimeNameIndex(db,profile);
            assertEquals("UNIQUE",index.resolve("幽灵","first","ENTITY").status());
            assertEquals(2,index.resolve("幽灵",null,"ENTITY").candidates().size());
            assertEquals("AMBIGUOUS",index.resolve("幽灵",null,"ENTITY").status());
            assertEquals(3,index.resolve("幽灵",null,null).candidates().size());
            assertEquals(2,index.list(null,"ENTITY",null,0,20).size());
            assertEquals("幽灵",index.list("first","ENTITY",null,0,20).getFirst().name());
        }finally{db.shutdown();}
    }
}
