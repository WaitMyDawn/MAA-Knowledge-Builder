package yagen.waitmydawn.kb.service;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import yagen.waitmydawn.kb.model.*;
class BuildPublicationDeltaTest{
 @TempDir Path temp;
 static long vectorId(DatabaseBuilder db)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT id FROM rag_vectors WHERE chunk_id='retained'")){r.next();return r.getLong(1);}}
 @Test void appendKeepsExistingVectorIdentityAndPublicationRollsBackOnFailure()throws Exception{
  var target=DatabaseBuilder.atPath(temp.resolve("target"));var stage=DatabaseBuilder.atPath(temp.resolve("stage"));target.initDatabase();stage.initDatabase();new VectorStore(target,384);new VectorStore(stage,384);
  try{try(var c=target.getConnection();var p=c.prepareStatement("INSERT INTO rag_vectors(chunk_id,chunk_text,embedding) VALUES('retained','unchanged',?)")){p.setBytes(1,new byte[2*1024*1024]);p.executeUpdate();}long id=vectorId(target);stage.replaceBuildDataFrom(target);try(var c=stage.getConnection();var s=c.createStatement()){s.execute("INSERT INTO rag_vectors(chunk_id,chunk_text) VALUES('new','added')");}target.replaceBuildDataFrom(stage);assertEquals(id,vectorId(target));
   String before=target.contentFingerprint();try(var c=target.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE rag_build_metadata ADD CONSTRAINT reject_bad CHECK(meta_value <> 'bad')");}try(var c=stage.getConnection();var s=c.createStatement()){s.execute("UPDATE rag_vectors SET chunk_text='changed' WHERE chunk_id='retained'");s.execute("INSERT INTO rag_build_metadata VALUES('invalid','bad')");}assertThrows(IllegalStateException.class,()->target.replaceBuildDataFrom(stage));assertEquals(before,target.contentFingerprint());assertEquals(id,vectorId(target));
  }finally{stage.shutdown();target.shutdown();}
 }
}
