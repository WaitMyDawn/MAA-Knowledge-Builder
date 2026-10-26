package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeIconPackTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    @AfterEach void logging(){LogSetup.shutdown();}
    ObjectNode fixture(Path root)throws Exception {
        Files.createDirectories(root.resolve("icons/demo"));var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);image.setRGB(12,12,0xffff0000);
        Path png=root.resolve("icons/demo/widget.png");ImageIO.write(image,"png",png.toFile());
        var doc=(ObjectNode)json.readTree("""
                {"format":"mkb-export","schemaVersion":1,"origin":"FIXTURE","exporter":{"id":"mkb_export","version":"fixture-1"},
                "environment":{"minecraftVersion":"1.21.1","loader":"neoforge","loaderVersion":"21.1","mods":[{"id":"demo","version":"1.0"}],"resourcePacks":[]},
                "rendering":{"context":"GUI","width":32,"height":32,"background":"transparent"},
                "icons":[{"itemId":"demo:widget","variant":"default","status":"OK","file":"icons/demo/widget.png","sha256":"HASH"},
                {"itemId":"demo:unsupported","variant":"default","status":"UNSUPPORTED","error":"requires runtime context"}]}
                """);
        ((ObjectNode)doc.path("icons").get(0)).put("sha256",ArtifactFingerprint.sha256(png));write(root,doc);return doc;
    }
    void write(Path root,JsonNode doc)throws Exception{json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("manifest.json").toFile(),doc);}
    long count(DatabaseBuilder db,String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    @Test void fileEntryStoresIconsWithoutOcrVectorsOrDocumentsAndRetainsCorpusGeneration()throws Exception {
        Path pack=temp.resolve("renamed-folder");fixture(pack);assertTrue(NativeIconPack.detected(pack));
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));
        var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null){@Override public float[] embed(String s){fail("图标包不能向量化");return null;}}) {
            var vectors=new VectorStore(db,384);db.setBuildGeneration("existing-corpus");
            float[] existing=new float[384];existing[0]=1;
            vectors.storeBatch(List.of(new TextChunker.TextChunk("existing","已有文本向量","demo","Demo","local_document","fixture")),List.of(existing),"existing-profile");
            var documents=new DocumentDirectoryProcessor(config,(bytes)->{fail("图标包不能 OCR");return null;},null);
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(documents));
            var source=BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString());
            var result=builder.build(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),null);
            assertEquals(1,result.nativeIcons);assertEquals(1,result.failedNativeIcons);assertEquals(0,result.addedEmbeddings);
            assertEquals(1,vectors.count());assertEquals("已有文本向量",vectors.search(existing,1).getFirst().chunkText());
            vectors.verifyProfile("existing-profile",false);
            assertEquals(0,count(db,"rag_document"));assertEquals(0,count(db,"rag_content_chunk"));assertEquals("existing-corpus",db.buildGeneration());
            String profile;try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT id FROM rag_item_icon_profile")){assertTrue(r.next());profile=r.getString(1);}
            var icons=new ItemIconStore(db);assertTrue(icons.findDefault(profile,"demo:widget").isPresent());assertTrue(icons.findDefault(profile,"other:widget").isEmpty());assertTrue(icons.findDefault(profile,"demo:unsupported").isEmpty());
            var stored=icons.findDefault(profile,"demo:widget").orElseThrow();assertEquals("FIXTURE",stored.origin());
            assertTrue(new ImageAssetStore(config.getDataDir()).available(stored.assetId(),stored.relativePath()));
            String fingerprint=db.contentFingerprint();var again=builder.build(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),null);
            assertEquals(1,again.reusedIconPacks);assertEquals(fingerprint,db.contentFingerprint());
            var doc=json.readTree(pack.resolve("manifest.json").toFile());((ObjectNode)doc.path("exporter")).put("version","fixture-2");write(pack,doc);
            assertEquals(1,builder.build(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),null).conflicts);assertEquals(fingerprint,db.contentFingerprint());
            Files.delete(pack.resolve("icons/demo/widget.png"));assertTrue(new ImageAssetStore(config.getDataDir()).available(stored.assetId(),stored.relativePath()));
        } finally {db.shutdown();}
    }
    @Test void invalidExportFailsBeforeJarScanningAndRetainsExistingDatabase()throws Exception {
        Path pack=temp.resolve("MKB-Export");var doc=fixture(pack);((ObjectNode)doc.path("icons").get(0)).put("sha256","0".repeat(64));write(pack,doc);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            new VectorStore(db,384);db.setBuildGeneration("before");String fingerprint=db.contentFingerprint();
            var builder=new KnowledgeBuilder(config,db,embedder);
            var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,temp.resolve("does-not-exist").toString()),BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND);
            var error=assertThrows(IllegalArgumentException.class,()->builder.build(request,null));assertTrue(error.getMessage().contains("哈希"));assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }
    @Test void hashesNamespaceStateAndPathsAreValidated()throws Exception {
        Path incomplete=temp.resolve("MKB-Export");Files.createDirectories(incomplete);
        assertThrows(IllegalArgumentException.class,()->NativeIconPack.detected(incomplete));
        Path pack=temp.resolve("pack");var doc=fixture(pack);
        ((ObjectNode)doc.path("icons").get(0)).put("file","../escape.png");write(pack,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(pack));
        doc=fixture(pack);((ObjectNode)doc.path("icons").get(0)).put("variant","dyed");write(pack,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(pack));
        doc=fixture(pack);((ObjectNode)doc.path("icons").get(1)).put("itemId","demo:widget");write(pack,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(pack));
        doc=fixture(pack);((ObjectNode)doc.path("rendering")).put("width",64);write(pack,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(pack));
        doc=fixture(pack);((ObjectNode)doc).put("schemaVersion",2);write(pack,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(pack));
    }
    @Test void compatibleMetadataIsNotAnAssertionOfIdenticalResources()throws Exception {
        Path pack=temp.resolve("pack");var doc=fixture(pack);var read=NativeIconPack.read(pack);
        var mod=new ModEntry();mod.setModId("demo");mod.setVersion("1.0");mod.setLoader("neoforge");mod.setMcVersion("1.21.1");
        assertEquals("DECLARED_MATCH",ItemIconStore.compatibility(read,List.of(mod)));
        mod.setVersion("2.0");assertEquals("CONFLICT",ItemIconStore.compatibility(read,List.of(mod)));
        assertEquals("UNVERIFIED",ItemIconStore.compatibility(read,List.of()));
        String hash=read.manifestHash();doc.put("exportedAt","2030-01-01T00:00:00Z");write(pack,doc);assertEquals(hash,NativeIconPack.read(pack).manifestHash());
        Files.writeString(pack.resolve("manifest.json"),"{\"format\":\"other-format\"}");assertFalse(NativeIconPack.detected(pack));
    }
}
