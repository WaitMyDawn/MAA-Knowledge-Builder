package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvalPrerequisiteTest {
    @TempDir Path temp;
    @AfterEach void closeLogging(){yagen.waitmydawn.kb.config.LogSetup.shutdown();}
    @Test void legacySuitesDoNotAcquireANewRuntimeRequirement() throws Exception {
        var spec=temp.resolve("legacy.json");Files.writeString(spec,"{\"conversations\":[]}");
        assertDoesNotThrow(()->EvalRunner.verifyDeclaredEnvironment(spec,null));
    }
    @Test void declaredEnvironmentRequiresSnapshotVersionAndDocumentSources() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("base"));
        var db=new DatabaseBuilder(config);db.initDatabase();var spec=temp.resolve("set.json");
        try(var embedding=new EmbeddingService(null)) {
            Files.writeString(spec,"{\"environment\":{\"minecraft\":\"1.21.1\",\"loader\":\"neoforge\"}}");
            assertThrows(IllegalStateException.class,()->EvalRunner.verifyDeclaredEnvironment(spec,db));
            var documents=Files.createDirectory(temp.resolve("documents"));
            var request=RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,documents.toString()))),temp);
            new KnowledgeBuilder(config,db,embedding).build(request,null);
            assertDoesNotThrow(()->EvalRunner.verifyDeclaredEnvironment(spec,db));
            Files.writeString(spec,"{\"environment\":{\"minecraft\":\"1.20.1\"}}");
            assertThrows(IllegalStateException.class,()->EvalRunner.verifyDeclaredEnvironment(spec,db));
            Files.writeString(spec,"{\"environment\":{\"loader\":\"forge\"}}");
            assertThrows(IllegalStateException.class,()->EvalRunner.verifyDeclaredEnvironment(spec,db));
            Files.writeString(spec,"{\"environment\":{\"requiredExternalSources\":[\"https://example.com/missing\"]}}");
            assertThrows(IllegalStateException.class,()->EvalRunner.verifyDeclaredEnvironment(spec,db));
        }finally{db.shutdown();}
    }
}
