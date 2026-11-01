package yagen.waitmydawn.kb.service;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import yagen.waitmydawn.kb.config.*;import static org.junit.jupiter.api.Assertions.*;
class EmbeddingReuseTest {
 @TempDir Path temp;
 @AfterEach void cleanup(){RetrievalTelemetry.end();LogSetup.shutdown();}
 @Test void exactTextReuseSurvivesRestartAndSeparatesModelProfiles()throws Exception {
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);float[] expected;
  var trace=RetrievalTelemetry.begin();
  try(var model=new EmbeddingService(config)){var vectors=model.embedBatch(List.of("alpha","alpha","beta"));assertEquals(2,trace.documentEmbeddings);assertEquals(1,trace.reusedEmbeddings);expected=vectors.get(0).clone();vectors.get(0)[0]=99;assertArrayEquals(expected,model.embedBatch(List.of("alpha")).getFirst());}
  trace=RetrievalTelemetry.begin();try(var model=new EmbeddingService(config)){assertArrayEquals(expected,model.embedBatch(List.of("alpha")).getFirst());assertEquals(0,trace.documentEmbeddings);assertEquals(1,trace.reusedEmbeddings);}
  trace=RetrievalTelemetry.begin();try(var model=new EmbeddingService(config){@Override public String profile(){return "other-test-profile";}}){model.embedBatch(List.of("alpha"));assertEquals(1,trace.documentEmbeddings);}
 }
 @Test void readReuseDoesNotCrossVerificationScopes()throws Exception {
  Path file=temp.resolve("data.json");Files.writeString(file,"{\"value\":1}");Object first;
  try(var scope=SnapshotReadCache.begin()){first=SnapshotReadCache.read(file,1024);assertSame(first,SnapshotReadCache.read(file,1024));}
  Files.writeString(file,"{\"value\":2}");try(var scope=SnapshotReadCache.begin()){var second=SnapshotReadCache.read(file,1024);assertNotSame(first,second);assertEquals(2,second.path("value").asInt());}
 }
}
