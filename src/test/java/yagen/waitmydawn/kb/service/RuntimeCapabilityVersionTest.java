package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCapabilityVersionTest {
    private final ObjectMapper json=new ObjectMapper();

    private ObjectNode payload(String category,int version) {
        var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1)
                .put("category",category).put("authoritative",true).put("capabilityVersion",version);
        payload.putArray("records");
        return payload;
    }

    private ObjectNode declaration() {
        return json.createObjectNode().put("total",0).put("succeeded",0).put("failed",0);
    }

    @Test void knownEntityVersionsAreAcceptedEvenForEmptyPayloads() {
        for(int version:List.of(1,2,3))
            assertDoesNotThrow(()->RuntimeSnapshotData.validate("entity_attributes",payload("entity_attributes",version),declaration()));
    }

    @Test void futureAndMalformedEntityVersionsAreRejected() {
        for(int version:List.of(-1,0,4,Integer.MAX_VALUE)) {
            var error=assertThrows(IOException.class,()->RuntimeSnapshotData.validate("entity_attributes",payload("entity_attributes",version),declaration()));
            assertTrue(error.getMessage().contains("entity_attributes"));
        }
        var stringVersion=payload("entity_attributes",1).put("capabilityVersion","3");
        assertThrows(IOException.class,()->RuntimeSnapshotData.validate("entity_attributes",stringVersion,declaration()));
        var fraction=payload("entity_attributes",1).put("capabilityVersion",3.5);
        assertThrows(IOException.class,()->RuntimeSnapshotData.validate("entity_attributes",fraction,declaration()));
        var missing=payload("entity_attributes",1);missing.remove("capabilityVersion");
        assertThrows(IOException.class,()->RuntimeSnapshotData.validate("entity_attributes",missing,declaration()));
    }

    @Test void entityVersionSupportDoesNotEnableUnknownVersionsOfOtherCategories() {
        for(String category:List.of("item_attributes","entity_state_attributes","trades","potions","spawn_rules","structures"))
            for(int version:List.of(2,3))
                assertThrows(IOException.class,()->RuntimeSnapshotData.validate(category,payload(category,version),declaration()));
    }

    @Test void supportedVersionStillValidatesAttributeNumbers() {
        var payload=payload("entity_attributes",3);
        var row=payload.withArray("records").addObject().put("id","example:boss").put("schemaVersion",1)
                .put("context","SERVER_INITIALIZED_TICK_SAMPLE");
        row.putArray("attributes").addObject().put("attribute","example:power").put("base",1).put("effective","invalid");
        var declaration=declaration().put("total",1).put("succeeded",1);
        assertThrows(IOException.class,()->RuntimeSnapshotData.validate("entity_attributes",payload,declaration));
    }
}
