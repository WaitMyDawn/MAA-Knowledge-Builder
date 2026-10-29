package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EntityImageReferencesTest {
 ObjectNode owner(){return new ObjectMapper().createObjectNode().put("id","minecraft:blaze").put("stateKey","default").put("sha256","a".repeat(64)).put("pixelSha256","b".repeat(64)).put("width",256).put("height",256).put("status","OK").put("file","entities/minecraft/blaze.png").put("pngBase64","AA==");}
 @Test void aliasRetainsItsOwnStateAndResolvesCanonicalBytes()throws Exception{var owner=owner();var alias=owner.deepCopy();alias.put("stateKey","c".repeat(64)).put("imageRef","default").put("ageMeaning","adult");alias.remove("pngBase64");var resolved=EntityImageReferences.resolve(alias,Map.of("minecraft:blaze|default",owner));assertEquals("adult",resolved.path("ageMeaning").asText());assertEquals("AA==",resolved.path("pngBase64").asText());assertFalse(alias.has("pngBase64"));}
 @Test void missingCrossEntityAndChainedReferencesAreRejected(){var owner=owner();var alias=owner.deepCopy().put("imageRef","default");alias.remove("pngBase64");assertThrows(java.io.IOException.class,()->EntityImageReferences.resolve(alias,Map.of()));assertThrows(java.io.IOException.class,()->EntityImageReferences.resolve(alias,Map.of("minecraft:cow|default",owner)));owner.put("imageRef","other");assertThrows(java.io.IOException.class,()->EntityImageReferences.resolve(alias,Map.of("minecraft:blaze|default",owner)));}
 @Test void conflictingImagePropertiesAreRejected(){var owner=owner();var alias=owner.deepCopy().put("imageRef","default").put("width",512);alias.remove("pngBase64");assertThrows(java.io.IOException.class,()->EntityImageReferences.resolve(alias,Map.of("minecraft:blaze|default",owner)));}
}
