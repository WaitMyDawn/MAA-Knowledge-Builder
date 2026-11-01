package yagen.waitmydawn.kb.config;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class PrewarmPolicyTest {
    @Test void defaultsAndLegacyMigrationDoNotReadSavedSettings()throws Exception {
        assertEquals(0,AppConfig.forIsolatedRun(false).getPrewarmPages());
        var properties=new Properties();assertEquals(0,AppConfig.readPrewarmPages(properties));
        properties.setProperty("prewarmPages","6");assertEquals(0,AppConfig.readPrewarmPages(properties));
        properties.setProperty("prewarmPages","12");assertEquals(12,AppConfig.readPrewarmPages(properties));
        properties.setProperty("prewarmPolicyVersion","2");properties.setProperty("prewarmPages","6");assertEquals(6,AppConfig.readPrewarmPages(properties));
        properties.setProperty("prewarmPages","invalid");assertEquals(0,AppConfig.readPrewarmPages(properties));
        properties.setProperty("prewarmPages","99");assertEquals(30,AppConfig.readPrewarmPages(properties));
    }
}
