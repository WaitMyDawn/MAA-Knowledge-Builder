package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.Path;
import java.util.*;

/** 固定语料上的人工正例标注；未标注块不作为负例或精度分母。 */
public final class RetrievalRelevance {
    public record Positive(String id,String quote) {}
    public record Group(String id,String description,List<Positive> chunks) {}
    public record Scores(int k,int labeledPositiveCount,int retrievedPositiveCount,double labeledRecallAtK,
                         int coveredGroups,int totalGroups,double keyEvidenceCoverageAtK,int firstRelevantRank) {}
    private final Map<String,List<Group>> queries;
    private RetrievalRelevance(Map<String,List<Group>> queries) {this.queries=Map.copyOf(queries);}

    public static RetrievalRelevance load(Path file,DatabaseBuilder db) throws Exception {
        var root=new ObjectMapper().readTree(file.toFile());
        if(!root.path("judgments").asText().equals("partial-positive-labels") || root.path("documents").isEmpty())
            throw new IllegalArgumentException("需要声明语料身份的部分正例标注");
        var documents=new HashSet<String>();
        try(var c=db.getConnection()) {
            for(var document:root.path("documents")) {
                String id=document.path("id").asText();
                try(var p=c.prepareStatement("SELECT content_hash FROM rag_document WHERE id=?")) {
                    p.setString(1,id);try(var r=p.executeQuery()) {
                        if(!r.next() || !r.getString(1).equals(document.path("contentHash").asText()))
                            throw new IllegalArgumentException("标注语料已变化，不能对比召回率："+id);
                    }
                }
                documents.add(id);
            }
            var queries=new LinkedHashMap<String,List<Group>>();
            for(var query:root.path("queries")) {
                var groups=new ArrayList<Group>();var ids=new HashSet<String>();
                for(var group:query.path("groups")) {
                    var positives=new ArrayList<Positive>();
                    for(var chunk:group.path("chunks")) {
                        String id=chunk.path("id").asText(),quote=chunk.path("quote").asText();
                        try(var p=c.prepareStatement("SELECT document_id,content FROM rag_content_chunk WHERE id=?")) {
                            p.setString(1,id);try(var r=p.executeQuery()) {
                                if(!r.next() || !documents.contains(r.getString(1)) || quote.isBlank() || !r.getString(2).contains(quote))
                                    throw new IllegalArgumentException("标注块或引文与语料不符："+id);
                            }
                        }
                        positives.add(new Positive(id,quote));
                    }
                    String id=group.path("id").asText();
                    if(id.isBlank() || !ids.add(id) || positives.isEmpty()) throw new IllegalArgumentException("标注组为空或重复");
                    groups.add(new Group(id,group.path("description").asText(),List.copyOf(positives)));
                }
                String id=query.path("id").asText();
                if(id.isBlank() || groups.isEmpty() || queries.putIfAbsent(id,List.copyOf(groups))!=null) throw new IllegalArgumentException("标注题目为空或重复");
            }
            if(queries.isEmpty()) throw new IllegalArgumentException("标注题集为空");
            return new RetrievalRelevance(queries);
        }
    }

    public Scores score(String queryId,List<String> ranking,int k) {
        var groups=queries.get(queryId);if(groups==null) return null;
        var positiveIds=new HashSet<String>();groups.forEach(g -> g.chunks().forEach(p -> positiveIds.add(p.id())));
        var selected=new LinkedHashSet<>(ranking.subList(0,Math.min(k,ranking.size())));
        int retrieved=(int)selected.stream().filter(positiveIds::contains).count(),covered=0,first=0;
        for(var group:groups) if(group.chunks().stream().anyMatch(p -> selected.contains(p.id()))) covered++;
        for(int i=0;i<Math.min(k,ranking.size());i++) if(positiveIds.contains(ranking.get(i))) {first=i+1;break;}
        return new Scores(k,positiveIds.size(),retrieved,retrieved/(double)positiveIds.size(),covered,groups.size(),covered/(double)groups.size(),first);
    }
}
