package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttributeCaptureMetadataTest {
    @Test void legitimateUnitValuesDoNotProveDefaultOnlyCapture() throws Exception {
        var data=new ObjectMapper().readTree("""
                {"id":"example:boss","attributes":[
                  {"attribute":"example:power","base":1,"effective":1,"modifiers":[]},
                  {"attribute":"example:resist","base":1,"effective":1,"modifiers":[]},
                  {"attribute":"example:fire","base":1,"effective":1,"modifiers":[]}
                ]}
                """);
        assertFalse(QaAgentTurn.defaultsOnly(data));
    }

    @Test void explicitCaptureLimitationIsRetained() throws Exception {
        var data=new ObjectMapper().readTree("{\"defaultsOnly\":true}");
        assertTrue(QaAgentTurn.defaultsOnly(data));
    }
}
