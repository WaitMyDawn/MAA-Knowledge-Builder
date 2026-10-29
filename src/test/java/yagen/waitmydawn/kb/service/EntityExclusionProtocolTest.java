package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EntityExclusionProtocolTest {
 @Test void diagnosticReasonsAreExplicitAndUnknownReasonsAreRejected()throws Exception {var json=new ObjectMapper();var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category","entity_icons").put("authoritative",true);payload.putArray("records");var reason=payload.putArray("exclusions").addObject().put("id","minecraft:player");var declaration=json.createObjectNode().put("total",0).put("succeeded",0).put("failed",0).put("transparent",0);for(String value:java.util.List.of("REQUIRES_CREATION_CONTEXT","DEBUG_ENTITY_REQUIRES_SPELL","NO_ENTITY_GEOMETRY_EFFECT_OR_LOGIC","EXCLUDED_NON_LIVING")){reason.put("reason",value);RuntimeSnapshotData.validate("entity_icons",payload,declaration);}reason.put("reason","UNKNOWN_SKIP");assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.validate("entity_icons",payload,declaration));}
}
