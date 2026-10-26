package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.RecipePlan;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipePlanTest {
    @Test void shapelessKeepsEverySlotAndAlternativeInsteadOfInventingKeySymbols() {
        var plan=RecipePlan.parse("""
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"a:one"},{"tag":"a:woods"},[{"item":"a:two"},{"item":"b:two"}],{"item":"a:four"},{"item":"a:five"}],"result":{"id":"a:out","count":4}}
                """);
        assertEquals(List.of(0,1,2,3,4),plan.slots().stream().map(RecipePlan.Slot::index).toList());
        assertTrue(plan.slots().get(1).ingredient().alternatives().getFirst().tag());assertEquals(2,plan.slots().get(2).ingredient().alternatives().size());assertEquals(4,plan.output().count());
    }
    @Test void shapedShrinksOnlyOuterWhitespaceAndPreservesInternalEmptySlots() {
        var plan=RecipePlan.parse("""
                {"type":"minecraft:crafting_shaped","pattern":["   ","A A","   "],"key":{"A":{"item":"a:one"}},"result":{"item":"a:out"}}
                """);
        assertEquals(List.of(0,2),plan.slots().stream().map(RecipePlan.Slot::index).toList());assertEquals(3,plan.width());assertEquals(1,plan.height());
        assertThrows(IllegalArgumentException.class,()->RecipePlan.parse("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"AB\"],\"key\":{\"A\":{\"item\":\"a:one\"}},\"result\":\"a:out\"}"));
    }
    @Test void customNamespacesAndMalformedCountsNeverGetVanillaLayout() {
        assertThrows(IllegalArgumentException.class,()->RecipePlan.parse("{\"type\":\"custom:smelting\",\"ingredient\":{\"item\":\"a:one\"},\"result\":\"a:out\"}"));
        for(String count:List.of("0","-1","1.5","2147483648"))assertThrows(IllegalArgumentException.class,()->RecipePlan.parse("{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"a:one\"},\"result\":{\"id\":\"a:out\",\"count\":"+count+"}}"));
    }
    @Test void smithingTrimIsDynamicAndComponentsNeverClaimDefaultState() {
        var trim=RecipePlan.parse("{\"type\":\"minecraft:smithing_trim\",\"template\":{\"tag\":\"a:templates\"},\"base\":{\"tag\":\"a:armor\"},\"addition\":{\"tag\":\"a:materials\"}}");
        assertTrue(trim.output().dynamic());assertNull(trim.output().itemId());assertEquals(3,trim.slots().size());
        var stateful=RecipePlan.parse("{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"a:one\"},\"result\":{\"id\":\"a:out\",\"components\":{\"minecraft:custom_name\":\"special\"}}}");assertTrue(stateful.output().stateful());
    }
    @Test void cookingDefaultsAndLegacyStonecuttingCountHaveDistinctSemantics() {
        var camp=RecipePlan.parse("{\"type\":\"minecraft:campfire_cooking\",\"ingredient\":{\"item\":\"a:one\"},\"result\":\"a:out\",\"experience\":0.35}");
        assertEquals(600,camp.cookingTicks());assertEquals(0.35,camp.experience());
        var cut=RecipePlan.parse("{\"type\":\"minecraft:stonecutting\",\"ingredient\":{\"item\":\"a:one\"},\"result\":\"a:out\",\"count\":2}");assertEquals(2,cut.output().count());
    }
}
