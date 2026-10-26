package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalRelevanceTest {
    @TempDir Path temp;
    @Test void partialLabelsScoreKnownPositivesAndRefuseChangedCorpusOrWrongQuotes() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try {
            var content=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("d1",source.id(),"指南",source.location(),"hash1","text/html"));
            content.saveChunk(new KnowledgeContent.Chunk("c1","d1","先安装核心。","操作",null,0));
            content.saveChunk(new KnowledgeContent.Chunk("c2","d1","再接入能源。","运行",null,1));
            var spec=new LinkedHashMap<String,Object>();spec.put("judgments","partial-positive-labels");
            spec.put("documents",List.of(Map.of("id","d1","contentHash","hash1")));
            spec.put("queries",List.of(Map.of("id","q1","groups",List.of(
                    Map.of("id","core","description","安装","chunks",List.of(Map.of("id","c1","quote","安装核心"))),
                    Map.of("id","power","description","运行","chunks",List.of(Map.of("id","c2","quote","接入能源")))))));
            Path file=temp.resolve("labels.json");var json=new ObjectMapper();json.writeValue(file.toFile(),spec);
            var labels=RetrievalRelevance.load(file,db);var result=labels.score("q1",List.of("unjudged","c1"),10);
            assertEquals(0.5,result.labeledRecallAtK());assertEquals(0.5,result.keyEvidenceCoverageAtK());
            assertEquals(2,result.firstRelevantRank());assertEquals(2,result.labeledPositiveCount());
            assertNull(labels.score("unlabeled",List.of("c1"),10));
            assertEquals(0,labels.score("q1",List.of("unjudged","c1"),1).retrievedPositiveCount());
            content.saveChunk(new KnowledgeContent.Chunk("c1","d1","无关内容。","操作",null,0));
            assertThrows(IllegalArgumentException.class,()->RetrievalRelevance.load(file,db));
            spec.put("documents",List.of(Map.of("id","d1","contentHash","different")));json.writeValue(file.toFile(),spec);
            assertThrows(IllegalArgumentException.class,()->RetrievalRelevance.load(file,db));
        } finally {db.shutdown();}
    }
}
