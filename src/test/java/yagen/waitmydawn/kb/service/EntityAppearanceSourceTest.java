package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EntityAppearanceSourceTest {
 final ObjectMapper json=new ObjectMapper();
 com.fasterxml.jackson.databind.node.ObjectNode row(String origin,String age){var row=json.createObjectNode();var state=row.putObject("state").put("origin",origin);state.putObject("traits").put("age",age);return row;}
 @Test void explicitSourcesDoNotPickOtherRepresentativePictures()throws Exception {var initialized=row("SPAWN_INITIALIZED","adult");var observed=row("OBSERVED_INSTANCE","adult");assertTrue(EntityAppearanceState.matches(initialized,"成年初始化样本外观"));assertFalse(EntityAppearanceState.matches(observed,"成年初始化样本外观"));assertTrue(EntityAppearanceState.matches(observed,"成年实际捕获外观"));assertFalse(EntityAppearanceState.matches(initialized,"成年实际捕获外观"));assertTrue(EntityAppearanceState.matches(row("DEFAULT_INSTANCE","adult"),"默认实例外观"));}
 @Test void sourceAndAgeRequirementsAreBothEnforced()throws Exception {assertFalse(EntityAppearanceState.matches(row("SPAWN_INITIALIZED","adult"),"幼年初始化样本"));assertTrue(EntityAppearanceState.specific("默认实例"));}
}
