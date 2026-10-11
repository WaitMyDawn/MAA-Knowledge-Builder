package yagen.waitmydawn.kb.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityNameMatchTest {
    @Test void familyNamesAllowInsertedVariantWordsButRejectDifferentObjects() {
        assertTrue(EntityAgent.matchesExpandedName("龙钢锭","龙炎钢锭(Fire Dragonsteel Ingot)"));
        assertTrue(EntityAgent.matchesExpandedName("龙钢锭","龙霜钢锭(Ice Dragonsteel Ingot)"));
        assertFalse(EntityAgent.matchesExpandedName("龙钢锭","龙钢锻炉(Dragon Forge)"));
        assertFalse(EntityAgent.matchesExpandedName("龙","末影龙(Ender Dragon)"));
    }
}
