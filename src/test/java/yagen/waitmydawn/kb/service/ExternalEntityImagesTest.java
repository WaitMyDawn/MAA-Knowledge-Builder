package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalEntityImagesTest {
 @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
 @AfterEach void logging(){LogSetup.shutdown();}
 Path fixture()throws Exception {
  var fixture=new RuntimeEntityMediaTest();fixture.temp=temp;Path root=fixture.fixture("external",2);var owner=fixture.entity();owner.put("file","entities/mkb_fixture/creature.png").put("stateKey","default").put("pixelSha256","b".repeat(64));
  byte[] png=RuntimeEntityMedia.bytes(owner);owner.remove("pngBase64");Path image=root.resolve(owner.path("file").asText());Files.createDirectories(image.getParent());Files.write(image,png);
  var alias=owner.deepCopy();alias.put("appearance","DECLARED_STATE");var state=alias.putObject("state").put("origin","DECLARED_STATE");state.putObject("traits").put("age","adult");alias.put("stateKey",BuildRequest.fingerprint(NativeIconPack.canonical(state).toString())).put("imageRef","default");
  var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category","entity_icons").put("authoritative",true).put("imageStorage","EXTERNAL_PNG").put("imageReferencesVersion",1);payload.putArray("records").add(owner).add(alias);write(root,payload);return root;
 }
 void write(Path root,ObjectNode payload)throws Exception {
  Path file=root.resolve("data/entity_icons.json");json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(root.resolve("snapshot.json").toFile());descriptor.withObject("categories").putObject("entity_icons").put("status","COMPLETE").put("total",2).put("succeeded",2).put("failed",0).put("unsupported",0).put("transparent",0).put("file","data/entity_icons.json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(root.resolve("snapshot.json").toFile(),descriptor);
 }
 @Test void externalFilesImportAsLocalAssetsAndAliasSelectionSurvivesRemovalOfExportedPng()throws Exception {
  Path root=fixture();var pack=NativeIconPack.read(root);assertTrue(ExportSnapshotPreflight.inspect(pack,null).ready());RuntimeSnapshotData.read(pack);
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("db"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)){
   var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString())));var builder=new KnowledgeBuilder(config,db,embedding);var result=builder.build(request,null);assertEquals(1,result.entityUniqueImages);assertEquals(1,result.entityImageAliases);
   try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT record_data FROM rag_runtime_record WHERE category='entity_icons'")){int owners=0;while(rows.next()){var row=json.readTree(rows.getString(1));assertFalse(row.has("pngBase64"));if(row.has("assetPath"))owners++;}assertEquals(1,owners);}
   String fingerprint=db.contentFingerprint();builder.build(new BuildRequest(request.sources(),BuildRequest.Mode.APPEND),null);assertEquals(fingerprint,db.contentFingerprint());
   byte[] originalPng=Files.readAllBytes(root.resolve("entities/mkb_fixture/creature.png"));Files.delete(root.resolve("entities/mkb_fixture/creature.png"));var answer=new QaPipeline.QaResult();answer.answer="实体说明";new RuntimeEntityMedia(db,config.getDataDir()).attach(answer,"mkb_fixture:creature 成年实体外观");assertEquals(1,AnswerMedia.assess(answer.answer,answer.artifacts,config.getDataDir()).valid());assertTrue(answer.artifacts.pictures().getFirst().caption().contains("成年"));
   try(var c=db.getConnection();var query=c.prepareStatement("SELECT profile_id,record_id,record_data FROM rag_runtime_record WHERE category='entity_icons' AND record_id LIKE '%|default'")){try(var rows=query.executeQuery()){assertTrue(rows.next());var legacy=(ObjectNode)json.readTree(rows.getString(3));legacy.remove("assetPath");legacy.put("pngBase64",Base64.getEncoder().encodeToString(originalPng));try(var update=c.prepareStatement("UPDATE rag_runtime_record SET record_data=? WHERE profile_id=? AND category='entity_icons' AND record_id=?")){update.setString(1,legacy.toString());update.setString(2,rows.getString(1));update.setString(3,rows.getString(2));assertEquals(1,update.executeUpdate());}}}
   var oldDatabaseAnswer=new QaPipeline.QaResult();oldDatabaseAnswer.answer="旧数据库外观";new RuntimeEntityMedia(db,config.getDataDir()).attach(oldDatabaseAnswer,"mkb_fixture:creature 成年实体外观");assertEquals(1,AnswerMedia.assess(oldDatabaseAnswer.answer,oldDatabaseAnswer.artifacts,config.getDataDir()).valid());
   var history=new ChatHistoryService(config.getDataDir());var session=history.newSession("独立图片回放",config.getDataDir());history.addMessage(session,"maa",answer.answer,answer.artifacts);history.save(session);var replay=history.load(session.sessionId).messages.getFirst();assertEquals(1,AnswerMedia.assess(replay.content(),replay.artifacts(),config.getDataDir()).valid());
  }finally{db.shutdown();}
 }
 @Test void missingCorruptAndMixedInlineFilesAreRejected()throws Exception {
  Path root=fixture(),image=root.resolve("entities/mkb_fixture/creature.png");byte[] original=Files.readAllBytes(image);var pack=NativeIconPack.read(root);Files.delete(image);assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.read(pack));Files.write(image,new byte[]{1,2,3});assertThrows(java.io.IOException.class,()->ExportSnapshotPreflight.inspect(pack,null));Files.write(image,original);
  var payload=(ObjectNode)json.readTree(root.resolve("data/entity_icons.json").toFile());((ObjectNode)payload.path("records").get(0)).put("pngBase64",Base64.getEncoder().encodeToString(original));write(root,payload);assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.read(NativeIconPack.read(root)));
 }
 @Test void unknownStorageAndPathsEscapingThePackAreRejected()throws Exception {
  Path root=fixture();var payload=(ObjectNode)json.readTree(root.resolve("data/entity_icons.json").toFile());payload.put("imageStorage","UNKNOWN");write(root,payload);assertThrows(java.io.IOException.class,()->ExportSnapshotPreflight.inspect(NativeIconPack.read(root),null));payload.put("imageStorage","EXTERNAL_PNG");((ObjectNode)payload.path("records").get(0)).put("file","../escape.png");write(root,payload);assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.read(NativeIconPack.read(root)));
 }
}
