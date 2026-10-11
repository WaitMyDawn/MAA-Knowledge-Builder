package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.dto.RetrievalPlan;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogEnumerationScopeTest {
    @TempDir Path temp;
    @Test void familyEnumerationKeepsAllDragonVariantsAndBiologicalExamplesExcludeVehicles() {
        var db=DatabaseBuilder.atPath(temp.resolve("catalog"));db.initDatabase();
        try {
            var nodes=List.of(new WebCatalog.Node("dragon",null,"iceandfire","catalog",4,"飞龙",List.of("生物/实体","飞龙")),
                    new WebCatalog.Node("other",null,"iceandfire","catalog",4,"生物",List.of("生物/实体","其他生物")),
                    new WebCatalog.Node("vehicle",null,"iceandfire","catalog",4,"交通工具",List.of("生物/实体","技术性实体","交通工具")));
            var members=new ArrayList<WebCatalog.Member>();
            for(String name:List.of("冰龙","火龙","电龙")) members.add(new WebCatalog.Member("dragon",name,name,name,"Dragon","entry"));
            members.add(new WebCatalog.Member("other","pixie","小精灵","小精灵","Pixie","entry"));
            members.add(new WebCatalog.Member("vehicle","boat","潜水艇","潜水艇","Submarine","entry"));
            new WebCatalogStore(db).save(new WebCatalog(nodes,members));
            var selection=new CatalogEnumeration(db,null).select(new RetrievalPlan("龙有哪几种？","iceandfire",true,"ENTITY",List.of(),3,false),List.of("龙"));
            assertEquals(Set.of("冰龙","火龙","电龙"),selection.hits().stream().map(h->h.label()).collect(java.util.stream.Collectors.toSet()));
            selection=new CatalogEnumeration(db,null).select(new RetrievalPlan("有哪些生物？","iceandfire",true,"ENTITY",List.of(),3,false),List.of("生物"));
            assertEquals(4,selection.hits().size());assertFalse(selection.evidence().contains("潜水艇"));
        } finally {db.shutdown();}
    }
}
