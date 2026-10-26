package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationSessionImporterTest {
    @TempDir Path temp;
    ChatHistoryService.ChatSession fixture(Path run)throws Exception {
        Files.createDirectories(run);var asset=new ImageAssetStore(run).put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));
        var source=new AnswerArtifacts.Source("s1","d1","协议样本","https://example.com/fixture","验证",null,"NATIVE","模拟导出包图标，仅供协议验收。");
        var picture=new AnswerArtifacts.Picture("i1",asset.id(),asset.relativePath(),"模拟图标","s1","ILLUSTRATION",List.of(),source.evidence());
        var artifacts=new AnswerArtifacts("fixture","验收样本",List.of(source),List.of(picture));
        var history=new ChatHistoryService(run);var session=history.newSession("Eval: 协议验收",run);
        history.addMessage(session,"user","验证导入");history.addMessage(session,"maa",AnswerMedia.bind("模拟导出图标。[[source:s1]]\n\n[[image:i1]]",artifacts),artifacts);history.save(session);return session;
    }
    @Test void importedSessionsAreVisibleIdempotentAndKeepTheirOwnAssets()throws Exception {
        Path run=temp.resolve("run"),target=temp.resolve("target");Files.createDirectories(target);fixture(run);
        Files.writeString(target.resolve("rag_data.mv.db"),"untouched database");
        var history=new ChatHistoryService(target);var user=history.newSession("用户原有对话",target);history.addMessage(user,"user","保留");history.save(user);
        var importer=new EvaluationSessionImporter();var result=importer.importRun(run,target,"R0验收");
        assertEquals(1,result.sessions().size());assertEquals(1,result.imageAssets());assertEquals(2,history.listSessions().size());
        var imported=history.load(result.sessions().getFirst().id());assertTrue(imported.title.startsWith("R0验收｜"));assertEquals(target.toRealPath().toString(),imported.dataDir);
        assertTrue(importer.importRun(run,target,"R0验收").sessions().getFirst().reused());assertEquals(2,history.listSessions().size());
        var answer=imported.messages.getLast();var image=answer.artifacts().pictures().getFirst();Files.delete(AnswerMedia.resolveImage(run,image));
        assertEquals(1,AnswerMedia.assess(answer.content(),answer.artifacts(),target).valid());
        assertEquals("untouched database",Files.readString(target.resolve("rag_data.mv.db")));assertEquals("保留",history.load(user.sessionId).messages.getFirst().content());
    }
    @Test void missingAssetsAndMalformedSessionsFailBeforePublishing()throws Exception {
        Path run=temp.resolve("run"),target=temp.resolve("target");Files.createDirectories(target);var fixture=fixture(run);
        Files.writeString(run.resolve("sessions/broken.json"),"{");
        assertThrows(java.io.IOException.class,()->new EvaluationSessionImporter().importRun(run,target,"验收"));assertFalse(Files.exists(target.resolve("sessions")));
        Files.delete(run.resolve("sessions/broken.json"));Files.delete(AnswerMedia.resolveImage(run,fixture.messages.getLast().artifacts().pictures().getFirst()));
        assertThrows(java.io.IOException.class,()->new EvaluationSessionImporter().importRun(run,target,"验收"));assertFalse(Files.exists(target.resolve("sessions")));
    }
    @Test void existingSessionIdentityCannotBeOverwritten()throws Exception {
        Path run=temp.resolve("run"),target=temp.resolve("target");Files.createDirectories(target);fixture(run);
        var importer=new EvaluationSessionImporter();var imported=importer.importRun(run,target,"验收");var history=new ChatHistoryService(target);
        var modified=history.load(imported.sessions().getFirst().id());history.addMessage(modified,"user","用户添加的追问");history.save(modified);
        assertThrows(java.io.IOException.class,()->importer.importRun(run,target,"验收"));assertEquals(3,history.load(modified.sessionId).messages.size());
    }
}
