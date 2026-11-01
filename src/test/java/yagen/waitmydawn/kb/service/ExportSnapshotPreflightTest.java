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

    @Test void exporterUpgradeDoesNotRequireMatchingScannedToolJar()throws Exception {
        var exportedTool=new yagen.waitmydawn.kb.model.ModEntry();exportedTool.setModId("mkb_export");exportedTool.setVersion("0.16.0");Path exportedJar=temp.resolve("new-tool.jar");Files.writeString(exportedJar,"new exporter");exportedTool.setJarPath(exportedJar.toString());
        Path root=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(exportedTool),"neoforge","1.21.1");
        var scannedTool=new yagen.waitmydawn.kb.model.ModEntry();scannedTool.setModId("mkb_export");scannedTool.setVersion("0.11.1");Path oldJar=temp.resolve("old-tool.jar");Files.writeString(oldJar,"old exporter");scannedTool.setJarPath(oldJar.toString());
        var check=ExportSnapshotPreflight.inspect(NativeIconPack.read(root),null,List.of(scannedTool));assertTrue(check.ready());assertTrue(check.warnings().stream().anyMatch(text->text.contains("0.11.1")&&text.contains("0.16.0")));
        scannedTool.setVersion("0.16.0");assertTrue(ExportSnapshotPreflight.inspect(NativeIconPack.read(root),null,List.of(scannedTool)).ready(),"工具同版本重新构建的不同哈希也不属于内容模组冲突");
    }
    @Test void exporterExemptionKeepsContentAndPayloadValidationStrict()throws Exception {
        var content=new yagen.waitmydawn.kb.model.ModEntry();content.setModId("example");content.setVersion("1.0");Path jar=temp.resolve("content.jar");Files.writeString(jar,"content version one");content.setJarPath(jar.toString());
        Path root=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(content),"neoforge","1.21.1");var pack=NativeIconPack.read(root);
        var tool=new yagen.waitmydawn.kb.model.ModEntry();tool.setModId("mkb_export");tool.setVersion("0.11.1");tool.setJarPath(temp.resolve("not-required.jar").toString());
        assertTrue(ExportSnapshotPreflight.inspect(pack,null,List.of(tool,content)).ready());content.setVersion("2.0");var mismatched=ExportSnapshotPreflight.inspect(pack,null,List.of(tool,content));assertFalse(mismatched.ready());assertTrue(mismatched.blockers().stream().anyMatch(text->text.contains("example")&&text.contains("2.0")&&text.contains("1.0")));
        content.setVersion("1.0");Files.writeString(jar,"modified content");assertTrue(ExportSnapshotPreflight.inspect(pack,null,List.of(tool,content)).blockers().contains("模组文件内容不一致：example"));
        Files.writeString(root.resolve("data/recipes.json"),"{}");assertThrows(java.io.IOException.class,()->ExportSnapshotPreflight.inspect(pack,null,List.of(tool)));
    }
}
