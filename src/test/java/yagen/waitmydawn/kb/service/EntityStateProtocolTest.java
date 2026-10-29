package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityStateProtocolTest {
 @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
 @AfterEach void logging(){LogSetup.shutdown();}
 ObjectNode row(String age,String color,boolean equipped,int size,boolean visible)throws Exception {
  var row=json.createObjectNode().put("id",visible?"mkb_fixture:creature":"mkb_fixture:empty").put("appearance",age==null?"DEFAULT_INSTANCE":"DECLARED_STATE").put("width",size).put("height",size).put("status",visible?"OK":"TRANSPARENT").put("framing","FITTED");
  var state=row.putObject("state").put("origin",age==null?"DEFAULT_INSTANCE":"DECLARED_STATE");var traits=state.putObject("traits");if(age!=null){traits.put("age",age).put("color",color);var equipment=traits.putObject("equipment");if(equipped)equipment.put("head","minecraft:iron_helmet");}
  row.put("stateKey",age==null?"default":BuildRequest.fingerprint(NativeIconPack.canonical(state).toString()));row.put("file",EntityAppearanceState.path(row));
  var image=new java.awt.image.BufferedImage(size,size,2);if(visible)image.setRGB(size/2,size/2,equipped?0xff2266ff:age==null?0xff778899:0xffff2222);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);row.put("pngBase64",Base64.getEncoder().encodeToString(bytes.toByteArray())).put("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));return row;
 }
 Path fixture(List<ObjectNode> rows)throws Exception {
  var fixture=new RuntimeKnowledgeStoreTest();fixture.temp=temp;Path root=fixture.fixture("MKB-Export-state",2);var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category","entity_icons").put("authoritative",true);var records=payload.putArray("records");
  for(var row:rows){records.add(row);Path png=root.resolve(row.path("file").asText());Files.createDirectories(png.getParent());Files.write(png,RuntimeEntityMedia.bytes(row));}
  payload.putArray("exclusions").addObject().put("id","mkb_fixture:arrow").put("reason","EXCLUDED_NON_LIVING");Path file=root.resolve("data/entity_icons.json");json.writeValue(file.toFile(),payload);
  var descriptor=(ObjectNode)json.readTree(root.resolve("snapshot.json").toFile());int transparent=(int)rows.stream().filter(r->r.path("status").asText().equals("TRANSPARENT")).count();descriptor.withObject("categories").putObject("entity_icons").put("status","COMPLETE").put("total",rows.size()).put("succeeded",rows.size()-transparent).put("transparent",transparent).put("failed",0).put("unsupported",0).put("file","data/entity_icons.json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(root.resolve("snapshot.json").toFile(),descriptor);return root;
 }
 @Test void variantsCoexistAndSelectionDoesNotSubstituteDefaultForMissingState()throws Exception {
  var defaultRow=row(null,null,false,256,true);var baby=row("baby","red",false,256,true);var adult=row("adult","blue",true,512,true);var empty=row(null,null,false,256,false);Path root=fixture(List.of(defaultRow,baby,adult,empty));
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)) {
   var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString())));var builder=new KnowledgeBuilder(config,db,embedding);var result=builder.build(request,null);assertEquals(3,result.entityVisible);assertEquals(1,result.entityTransparent);assertEquals(1,result.entityExcluded);var store=new SourceLifecycleStore(db);assertEquals(4,store.strings("SELECT record_id FROM rag_runtime_record WHERE category='entity_icons'").size());assertEquals(1,store.strings("SELECT record_id FROM rag_runtime_record WHERE category='entity_exclusions'").size());
   var media=new RuntimeEntityMedia(db,config.getDataDir());
   for(var test:Map.of("mkb_fixture:creature 外观",defaultRow,"mkb_fixture:creature 红色幼年外观",baby,"mkb_fixture:creature 穿戴装备的成年蓝色外观",adult).entrySet()) {
    var answer=new QaPipeline.QaResult();answer.answer="状态选择验收";media.attach(answer,test.getKey());assertEquals(1,answer.artifacts.pictures().size());assertEquals(test.getValue().path("sha256").asText(),answer.artifacts.pictures().getFirst().assetId());assertEquals(1,AnswerMedia.assess(answer.answer,answer.artifacts,config.getDataDir()).valid());
   }
   var missing=new QaPipeline.QaResult();missing.answer="状态选择验收";media.attach(missing,"mkb_fixture:creature 蓝色幼年外观");assertTrue(missing.artifacts.pictures().isEmpty());assertTrue(missing.answer.contains("未用默认外观代替"));
   var transparent=new QaPipeline.QaResult();transparent.answer="透明结果";media.attach(transparent,"mkb_fixture:empty");assertTrue(transparent.artifacts.pictures().isEmpty());
   String before=db.contentFingerprint();builder.build(new BuildRequest(request.sources(),BuildRequest.Mode.APPEND),null);assertEquals(before,db.contentFingerprint());
   Files.delete(root.resolve(adult.path("file").asText()));assertThrows(IllegalArgumentException.class,()->builder.build(new BuildRequest(request.sources(),BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
  }finally{db.shutdown();}
 }
 @Test void stateKeysAndPathsAreVerifiedAndEnglishIdsDoNotImplyColor()throws Exception {
  var row=row("baby","red",false,512,true);RuntimeEntityMedia.validate(row);assertFalse(EntityAppearanceState.specific("demo:red_dragon 怎么养"));assertFalse(EntityAppearanceState.specific("blacksmith 的介绍"));assertFalse(EntityAppearanceState.specific("demo:blue 的介绍"));
  var bad=row.deepCopy().put("stateKey","0".repeat(64));assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(bad));
  var badPath=row.deepCopy().put("file","../escape.png");assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(badPath));
 }
 @Test void newEntityPayloadCannotUseLegacyIncorrectTransparencyCounters()throws Exception {
  Path root=fixture(List.of(row(null,null,false,256,true),row(null,null,false,512,false)));
  var payload=json.readTree(root.resolve("data/entity_icons.json").toFile());var descriptor=(ObjectNode)json.readTree(root.resolve("snapshot.json").toFile());var declaration=(ObjectNode)descriptor.path("categories").path("entity_icons");declaration.put("succeeded",2).put("transparent",0);
  assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.validate("entity_icons",payload,declaration));
 }
 @Test void sharedStateIdentitySupportsUnicodeNumericNormalizationAndExplicitRepresentative()throws Exception {
  var state=json.readTree("{\"origin\":\"DECLARED_STATE\",\"traits\":{\"scale\":1.00,\"variant\":\"雪地\",\"color\":\"white\",\"age\":\"adult\"}}");
  var row=json.createObjectNode().put("stateKey","4ce2de9e4a237f02a6c338605c145f4d4bced77252da049dea2ba8c9e117515b");row.set("state",state);assertEquals(row.path("stateKey").asText(),EntityAppearanceState.key(row));assertTrue(EntityAppearanceState.matches(row,"雪地变种"));assertFalse(EntityAppearanceState.matches(row,"沙漠变种"));
  row.put("representative",true);assertTrue(EntityAppearanceState.matches(row,"介绍外观"));assertTrue(EntityAppearanceState.preference(row)<EntityAppearanceState.preference(row(null,null,false,256,true)));
 }
 @Test void itemQuestionsDoNotReceiveNonLivingEntityPicturesEvenWhenExported()throws Exception {
  var image=row(null,null,false,256,true);image.put("id","mkb_fixture:sample_a").put("classification","NON_LIVING");image.put("file",EntityAppearanceState.path(image));Path root=fixture(List.of(image));
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("item-entity"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)) {
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);var media=new RuntimeEntityMedia(db,config.getDataDir());
   var item=new QaPipeline.QaResult();item.answer="物品配方";media.attach(item,"mkb_fixture:sample_a 怎么合成");assertTrue(item.artifacts.pictures().isEmpty());
   var entity=new QaPipeline.QaResult();entity.answer="实体视图";media.attach(entity,"mkb_fixture:sample_a 实体外观");assertEquals(1,entity.artifacts.pictures().size());
  }finally{db.shutdown();}
 }
 @Test void observedRepresentativeWinsAndEquipmentCaptionDoesNotDumpComponentPayloads()throws Exception {
  var initialized=row("adult","blue",true,256,true);initialized.withObject("state").put("origin","SPAWN_INITIALIZED");initialized.put("appearance","SPAWN_INITIALIZED").put("representative",true);
  var observed=initialized.deepCopy();observed.withObject("state").put("origin","OBSERVED_INSTANCE");observed.put("appearance","OBSERVED_INSTANCE");observed.withObject("state").withObject("traits").put("variant","钻石头盔样本");
  assertTrue(EntityAppearanceState.preference(observed)<EntityAppearanceState.preference(initialized));assertTrue(EntityAppearanceState.label(observed).contains("实际实体捕获"));assertTrue(EntityAppearanceState.label(observed).contains("成年"));assertTrue(EntityAppearanceState.label(observed).contains("含装备快照"));assertFalse(EntityAppearanceState.label(observed).contains("minecraft:iron_helmet"));assertTrue(EntityAppearanceState.matches(observed,"钻石头盔样本变种"));
 }
 @Test void equipmentFilteringChecksExactItemsAndEmptyHandsAllowArmor()throws Exception {
  var armored=row("adult","blue",true,256,true);assertTrue(EntityAppearanceState.equipmentMatches(armored,Set.of("minecraft:iron_helmet")));assertFalse(EntityAppearanceState.equipmentMatches(armored,Set.of("minecraft:diamond_helmet")));assertTrue(EntityAppearanceState.matches(armored,"成年空手外观"));assertFalse(EntityAppearanceState.matches(armored,"成年无装备外观"));
 }
 @Test void compoundDyeColorsDoNotFallBackToTheirBaseColors()throws Exception {
  for(var pair:Map.of("浅蓝色","light_blue","浅灰色","light_gray","黄绿色","lime","品红色","magenta").entrySet()) {
   var state=row("baby",pair.getValue(),false,256,true);assertTrue(EntityAppearanceState.matches(state,pair.getKey()+"幼年外观"));assertFalse(EntityAppearanceState.matches(row("baby","red",false,256,true),pair.getKey()+"幼年外观"));
  }
 }
 @Test void itemIdsAndLongEquipmentNamesDoNotAccidentallyRequireTheMaterialItem()throws Exception {
  Path root=fixture(List.of(row("adult","blue",true,256,true)));var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("equipment-names"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)) {
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
   try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_name VALUES(?,?,?,?)")) {
    for(var name:Map.of("minecraft:iron_helmet","Iron Helmet","minecraft:iron_ingot","Iron","minecraft:diamond_helmet","Diamond Helmet","minecraft:diamond","Diamond").entrySet()){p.setString(1,RuntimeKnowledgeStore.active(db));p.setString(2,name.getKey());p.setString(3,"en_us");p.setString(4,name.getValue());p.addBatch();}p.executeBatch();
   }
   var media=new RuntimeEntityMedia(db,config.getDataDir());
   for(String query:List.of("穿戴 minecraft:iron_helmet 的 mkb_fixture:creature 成年实体外观","穿戴 Iron Helmet 的 mkb_fixture:creature 成年实体外观")){var qa=new QaPipeline.QaResult();qa.answer="装备验收";media.attach(qa,query);assertEquals(1,qa.artifacts.pictures().size(),query);}
   var missing=new QaPipeline.QaResult();missing.answer="装备验收";media.attach(missing,"穿戴 Diamond Helmet 的 mkb_fixture:creature 成年实体外观");assertTrue(missing.artifacts.pictures().isEmpty());
  }finally{db.shutdown();}
 }
 @Test void readableStateFilesBuildWithoutChangingIdentityAndOldFilesRemainValid()throws Exception {
  var state=json.readTree("{\"origin\":\"DECLARED_STATE\",\"traits\":{\"age\":\"adult\",\"color\":\"white\",\"variant\":\"雪地\",\"scale\":1}}");var probe=json.createObjectNode().put("id","demo:mob").put("stateKey","4ce2de9e4a237f02a6c338605c145f4d4bced77252da049dea2ba8c9e117515b");probe.set("state",state);assertEquals("entities/demo/mob/states/declared_adult_white_4ce2de9e4a237f02.png",EntityAppearanceState.readablePath(probe));
  var image=row("baby","red",false,256,true);String key=image.path("stateKey").asText();RuntimeEntityMedia.validate(image);image.put("file",EntityAppearanceState.readablePath(image));RuntimeEntityMedia.validate(image);assertEquals(key,EntityAppearanceState.key(image));Path root=fixture(List.of(image));
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("readable-state"));var db=new DatabaseBuilder(config);db.initDatabase();try(var embedding=new EmbeddingService(null)){new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);var qa=new QaPipeline.QaResult();qa.answer="可读路径验收";new RuntimeEntityMedia(db,config.getDataDir()).attach(qa,"mkb_fixture:creature 红色幼年实体外观");assertEquals(1,AnswerMedia.assess(qa.answer,qa.artifacts,config.getDataDir()).valid());}finally{db.shutdown();}
  image.put("file",image.path("file").asText().replace(key.substring(0,16),"0".repeat(16)));assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(image));
 }
 @Test void finalPixelMetricsMustBeTruthfulAndFittedRequiresCenteredOccupancy()throws Exception {
  var row=row(null,null,false,256,true);var image=new java.awt.image.BufferedImage(256,256,2);for(int y=27;y<152;y++)for(int x=96;x<160;x++)image.setRGB(x,y,0xff778899);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);row.put("pngBase64",Base64.getEncoder().encodeToString(bytes.toByteArray())).put("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));row.putArray("framingBounds").add(96).add(27).add(160).add(152);row.put("framingOccupancy",125.0/256).put("framingOffsetX",0).put("framingOffsetY",89.5/256-.5);
  assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(row));row.put("framing","UNSTABLE_FRAMING");RuntimeEntityMedia.validate(row);row.put("framingOccupancy",.8);assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(row));
 }
 @Test void flatFilesNumberCollidingDescriptionsAndRejectIncorrectAssignments()throws Exception {
  var a=row("adult","blue",true,256,true);var b=a.deepCopy();b.withObject("state").withObject("traits").put("variant","second");b.put("stateKey",BuildRequest.fingerprint(NativeIconPack.canonical(b.path("state")).toString()));var rows=json.createArrayNode().add(a).add(b);var paths=EntityAppearanceState.flatPaths(rows);assertEquals(2,new HashSet<>(paths.values()).size());
  for(var r:List.of(a,b)){r.put("file",paths.get(r.path("id").asText()+"|"+r.path("stateKey").asText()));assertFalse(r.path("file").asText().contains("/states/"));RuntimeEntityMedia.validate(r);}
  Path root=fixture(List.of(a,b));RuntimeSnapshotData.read(NativeIconPack.read(root));b.put("file",EntityAppearanceState.flatBase(b)+"_99.png");var payload=json.readTree(root.resolve("data/entity_icons.json").toFile());((ArrayNode)payload.path("records")).set(1,b);var descriptor=json.readTree(root.resolve("snapshot.json").toFile());assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.validate("entity_icons",payload,descriptor.path("categories").path("entity_icons")));
 }
}
