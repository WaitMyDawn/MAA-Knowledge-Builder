package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportSnapshotPreflightTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    Path icons()throws Exception {
        Path root=temp.resolve(UUID.randomUUID().toString());Files.createDirectory(root);
        try(var files=Files.walk(Path.of("examples/MKB-Export"))) {
            for(Path file:files.toList()) {
                Path target=root.resolve(Path.of("examples/MKB-Export").relativize(file));
                if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target);
            }
        }
        var manifest=(ObjectNode)json.readTree(root.resolve("manifest.json").toFile());manifest.put("origin","GAME_GUI");json.writeValue(root.resolve("manifest.json").toFile(),manifest);return root;
    }
    ObjectNode descriptor(Path root,boolean complete)throws Exception {
        var doc=json.createObjectNode();doc.put("format","mkb-snapshot").put("schemaVersion",1).put("captureMode","SINGLEPLAYER").put("coverage","ALL")
                .put("clientFingerprint",NativeIconPack.read(root).environmentHash()).put("serverFingerprint","b".repeat(64)).put("serverSession","11111111-1111-1111-1111-111111111111").put("serverGeneration",1);
        var categories=doc.putObject("categories");
        for(String name:ExportSnapshotPreflight.REQUIRED) {
            boolean icon=name.equals("item_icons");var category=categories.putObject(name);
            category.put("status",icon?"COMPLETE_WITH_ERRORS":complete?"COMPLETE":"NOT_IMPLEMENTED").put("total",icon?3:0).put("succeeded",icon?2:0).put("transparent",0).put("unsupported",icon?1:0).put("failed",0);
            if(complete||icon) {
                String path=icon?"manifest.json":"data/"+name+".json";
                if(!icon){Files.createDirectories(root.resolve("data"));var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category",name).put("authoritative",true);payload.putArray("records");json.writeValue(root.resolve(path).toFile(),payload);}
                category.put("file",path).put("sha256",ArtifactFingerprint.sha256(root.resolve(path)));
            }
        }
        return doc;
    }
    void write(Path root,ObjectNode doc)throws Exception {json.writeValue(root.resolve("snapshot.json").toFile(),doc);}
    @Test void legacyIsReadableButNotEligibleForStrictBuild()throws Exception {
        Path root=icons();var pack=NativeIconPack.read(root);var check=ExportSnapshotPreflight.inspect(pack,null);
        assertFalse(check.snapshot());assertThrows(IllegalArgumentException.class,check::requireReady);
    }
    @Test void preparationIsNotCompleteAndTamperedPayloadCannotBeImported()throws Exception {
        Path root=icons();var doc=descriptor(root,false);write(root,doc);
        var pack=NativeIconPack.read(root);var check=ExportSnapshotPreflight.inspect(pack,null);
        assertTrue(check.snapshot());assertFalse(check.ready());assertTrue(check.blockers().contains("recipes：NOT_IMPLEMENTED"));
        var icon=(ObjectNode)doc.path("categories").path("item_icons");icon.put("sha256","c".repeat(64));write(root,doc);
        assertThrows(java.io.IOException.class,()->NativeIconPack.read(root));
    }
    @Test void fullScopeRequiresEveryCoreCategoryAndMatchingEnvironment()throws Exception {
        Path root=icons();var doc=descriptor(root,true);write(root,doc);
        var pack=NativeIconPack.read(root);assertTrue(ExportSnapshotPreflight.inspect(pack,null).ready());
        var expected=new yagen.waitmydawn.kb.model.GameEnvironment(temp.resolve("client.jar").toString(),"forge","1.20.1");
        assertFalse(ExportSnapshotPreflight.inspect(pack,expected).ready());
        doc.put("coverage","SELECTION");write(root,doc);assertFalse(ExportSnapshotPreflight.inspect(NativeIconPack.read(root),null).ready());
        doc.put("coverage","ALL");((ObjectNode)doc.path("categories")).remove("tags");write(root,doc);
        assertFalse(ExportSnapshotPreflight.inspect(NativeIconPack.read(root),null).ready());
    }
    @Test void countersAndPathsAreValidatedAndDescriptorChangesAffectPackIdentity()throws Exception {
        Path root=icons();var doc=descriptor(root,true);write(root,doc);String before=NativeIconPack.read(root).manifestHash();
        doc.put("serverGeneration",2);write(root,doc);assertNotEquals(before,NativeIconPack.read(root).manifestHash());
        var recipes=(ObjectNode)doc.path("categories").path("recipes");recipes.put("file","../outside.json");write(root,doc);
        assertThrows(java.io.IOException.class,()->NativeIconPack.read(root));
        recipes.put("file","data/recipes.json");recipes.put("failed",1);write(root,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(root));
    }
    @Test void strictEntryRequiresOnePackAndChecksScannedModIdentity()throws Exception {
        var noPack=yagen.waitmydawn.kb.model.BuildRequest.jars(temp);
        assertThrows(IllegalArgumentException.class,()->ExportSnapshotPreflight.requireBuildSnapshot(noPack,null,List.of()));
        Path root=icons();write(root,descriptor(root,true));
        var request=new yagen.waitmydawn.kb.model.BuildRequest(List.of(yagen.waitmydawn.kb.model.BuildRequest.Source.of(yagen.waitmydawn.kb.model.BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString())));
        assertDoesNotThrow(()->ExportSnapshotPreflight.requireBuildSnapshot(request,null,List.of()));
        var mod=new yagen.waitmydawn.kb.model.ModEntry();mod.setModId("mkb_fixture");mod.setVersion("different");
        assertThrows(IllegalArgumentException.class,()->ExportSnapshotPreflight.requireBuildSnapshot(request,null,List.of(mod)));
        mod.setModId("not_exported");assertThrows(IllegalArgumentException.class,()->ExportSnapshotPreflight.requireBuildSnapshot(request,null,List.of(mod)));
    }
}
