package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEntityMediaTest extends RuntimeKnowledgeStoreTest {
    ObjectNode entity()throws Exception {
        var image=new java.awt.image.BufferedImage(128,128,2);image.setRGB(60,60,0xff778899);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);var data=bytes.toByteArray();
        return json.createObjectNode().put("id","mkb_fixture:creature").put("appearance","DEFAULT_INSTANCE").put("status","OK").put("width",128).put("height",128).put("pngBase64",Base64.getEncoder().encodeToString(data)).put("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)));
    }
    @Test void entityImagesAreValidatedArchivedAndAssociatedOnlyByExactEntityIdentity()throws Exception {
        Path root=fixture("MKB-Export-entities",2);var row=entity();RuntimeEntityMedia.validate(row);
        var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category","entity_icons").put("authoritative",true);payload.putArray("records").add(row);
        Path file=root.resolve("data/entity_icons.json");json.writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(root.resolve("snapshot.json").toFile());descriptor.withObject("categories").putObject("entity_icons").put("status","COMPLETE").put("total",1).put("succeeded",1).put("failed",0).put("unsupported",0).put("transparent",0).put("file","data/entity_icons.json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(root.resolve("snapshot.json").toFile(),descriptor);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("entities-db"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedding);builder.build(request(root,BuildRequest.Mode.REBUILD),null);var media=new RuntimeEntityMedia(db,config.getDataDir());
            var answer=new QaPipeline.QaResult();answer.answer="实体说明";media.attach(answer,"mkb_fixture:creature 长什么样");assertEquals(1,answer.artifacts.pictures().size());assertEquals(1,AnswerMedia.assess(answer.answer,answer.artifacts,config.getDataDir()).valid());
            var unrelated=new QaPipeline.QaResult();unrelated.answer="其他回答";media.attach(unrelated,"other:creature");assertTrue(unrelated.artifacts.pictures().isEmpty());
            String before=db.contentFingerprint();builder.build(request(root,BuildRequest.Mode.APPEND),null);assertEquals(before,db.contentFingerprint());
            var bad=row.deepCopy().put("sha256","0".repeat(64));assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(bad));
            var history=new ChatHistoryService(config.getDataDir());var session=history.newSession("实体图回放",config.getDataDir());history.addMessage(session,"maa",answer.answer,answer.artifacts);history.save(session);
            assertEquals(1,AnswerMedia.assess(history.load(session.sessionId).messages.getFirst().content(),history.load(session.sessionId).messages.getFirst().artifacts(),config.getDataDir()).valid());
        }finally{db.shutdown();}
    }
    @Test void externalPngMustExistAndMatchEmbeddedImageWhileLegacyInlineRecordsRemainSupported()throws Exception {
        var row=entity();RuntimeEntityMedia.validate(row);RuntimeEntityMedia.validateExternal(temp,row);
        row.put("file","entities/mkb_fixture/creature.png");RuntimeEntityMedia.validate(row);
        assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validateExternal(temp,row));
        Path image=temp.resolve(row.path("file").asText());Files.createDirectories(image.getParent());Files.write(image,RuntimeEntityMedia.bytes(row));RuntimeEntityMedia.validateExternal(temp,row);
        Files.write(image,new byte[]{1,2,3});assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validateExternal(temp,row));
        row.put("file","../escape.png");assertThrows(java.io.IOException.class,()->RuntimeEntityMedia.validate(row));
    }
}
