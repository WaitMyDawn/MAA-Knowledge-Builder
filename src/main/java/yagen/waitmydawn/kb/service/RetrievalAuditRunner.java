package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** 不加载凭据、不调用回答模型或网络的已有基础库诊断入口。 */
public final class RetrievalAuditRunner {
    private static final ObjectMapper JSON=new ObjectMapper();
    private RetrievalAuditRunner() {}

    public static void main(String[] args) throws Exception {
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {
            if(i+1>=args.length || !Set.of("--data-dir","--questions","--output","--tokenizer-reference","--baseline-sessions","--relevance-labels","--baseline-audit").contains(args[i]))
                throw new IllegalArgumentException("参数：--data-dir <已有库> --questions <golden-set.json> --output <报告.json> [--tokenizer-reference <参考.json>] [--baseline-sessions <目录>] [--relevance-labels <正例.json>] [--baseline-audit <诊断.json>]");
            if(options.put(args[i],args[i+1])!=null) throw new IllegalArgumentException("重复参数："+args[i]);
        }
        for(String key:List.of("--data-dir","--questions","--output")) if(!options.containsKey(key)) throw new IllegalArgumentException("缺少参数："+key);
        Path data=Path.of(options.get("--data-dir")).toRealPath();
        Path vocabulary=data.resolve("models/all-MiniLM-L6-v2/vocab.txt");
        Path model=data.resolve("models/all-MiniLM-L6-v2/model.onnx");
        if(!Files.isRegularFile(data.resolve("rag_data.mv.db")) || !Files.isRegularFile(vocabulary)
                || !Files.isRegularFile(model) || Files.size(vocabulary)<1000 || Files.size(model)<1000)
            throw new IllegalArgumentException("需要已构建的基础库与当前 all-MiniLM-L6-v2 模型文件，诊断不会自动下载或构建");
        var config=AppConfig.forIsolatedRun(false).forBuildTarget(data);
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion",1);report.put("mode","retrieval-only-no-network-no-llm");
        report.put("dataDir",data.toString());report.put("recallStatus","NOT_MEASURED_REQUIRES_RELEVANCE_LABELS");
        try(var lock=DataDirLock.acquire(data);var embedding=new EmbeddingService(config)) {
            if(!embedding.initModel("all-MiniLM-L6-v2").equals("all-MiniLM-L6-v2")) throw new IllegalStateException("原 ONNX 模型不可用，拒绝哈希回退");
            var db=DatabaseBuilder.atReadOnlyPath(data.resolve("rag_data"));
            try {
                VectorStore.verifyProfile(db,embedding.profile(),false);
                report.put("embeddingProfile",embedding.profile());
                report.put("buildGeneration",db.buildGeneration());
                report.put("storage",storage(db,vocabulary));
                var labels=options.containsKey("--relevance-labels")?RetrievalRelevance.load(Path.of(options.get("--relevance-labels")),db):null;
                if(labels!=null) {
                    report.put("recallStatus","PARTIAL_LABELED_EVIDENCE_ONLY");
                    report.put("relevanceLabels",Map.of("path",options.get("--relevance-labels"),"sha256",TokenizerDiagnostics.sha256(Path.of(options.get("--relevance-labels")))));
                }
                var baselineAudit=new HashMap<String,List<String>>();
                if(options.containsKey("--baseline-audit")) {
                    var old=JSON.readTree(Path.of(options.get("--baseline-audit")).toFile());
                    if(!old.path("embeddingProfile").asText().equals(embedding.profile()) || !old.path("buildGeneration").asText().equals(db.buildGeneration()))
                        throw new IllegalArgumentException("基线诊断的模型配置或基础库代次不一致");
                    JsonNode liveManifest=JSON.valueToTree(((Map<?,?>)report.get("storage")).get("documentManifest"));
                    if(!old.path("storage").path("documentManifest").equals(liveManifest))
                        throw new IllegalArgumentException("基线诊断的文档清单或正文哈希不一致");
                    for(var turn:old.path("turns")) {
                        var ids=new ArrayList<String>();turn.path("providedContextChunkIds").forEach(id -> ids.add(id.asText()));
                        baselineAudit.put(turn.path("id").asText(),List.copyOf(ids));
                    }
                    report.put("baselineAudit",options.get("--baseline-audit"));
                }
                if(options.containsKey("--tokenizer-reference")) report.put("tokenizerReference",TokenizerDiagnostics.compare(vocabulary,Path.of(options.get("--tokenizer-reference"))));
                var baseline=new HashMap<String,JsonNode>();
                if(options.containsKey("--baseline-sessions")) {
                    try(var files=Files.list(Path.of(options.get("--baseline-sessions")))) {
                        for(Path path:files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                            var session=JSON.readTree(path.toFile());var messages=session.path("messages");
                            for(int j=1;j<messages.size();j++) if(messages.get(j-1).path("role").asText().equals("user") && messages.get(j).path("role").asText().equals("maa"))
                                baseline.put(messages.get(j-1).path("content").asText(),messages.get(j));
                        }
                    }
                }
                var service=new KnowledgeRetrievalService(db,embedding,data,false);var turns=new ArrayList<Object>();
                var spec=JSON.readTree(Path.of(options.get("--questions")).toFile());
                for(var conversation:spec.path("conversations")) for(var turn:conversation.path("turns")) {
                    String question=turn.path("question").asText();var resolution=service.resolve(question,null,null);
                    var result=service.retrieve(question,resolution,false);
                    var entry=new LinkedHashMap<String,Object>();entry.put("id",turn.path("id").asText());entry.put("question",question);
                    entry.put("scope",result.artifacts().scopeId());entry.put("warning",resolution.warning());entry.put("retrieval",result.diagnostics());
                    entry.put("selectedEvidence",result.artifacts().sources());entry.put("providedContextChunkIds",result.hits().stream().map(VectorStore.SearchResult::chunkId).toList());
                    entry.put("selectedImages",result.artifacts().pictures());
                    entry.put("providedContextBlockIds",KnowledgeRetrievalService.contextIds(result,result.artifacts()));
                    entry.put("answerGeneration","NOT_RUN");
                    if(labels!=null) {
                        var ids=result.hits().stream().map(VectorStore.SearchResult::chunkId).toList();
                        entry.put("relevance",List.of(5,10).stream().map(k -> labels.score(turn.path("id").asText(),ids,k)).filter(Objects::nonNull).toList());
                        var previous=baselineAudit.get(turn.path("id").asText());
                        if(previous!=null) entry.put("baselineRelevance",List.of(5,10).stream().map(k -> labels.score(turn.path("id").asText(),previous,k)).filter(Objects::nonNull).toList());
                    }
                    if(baseline.containsKey(question)) {
                        var old=baseline.get(question);var ids=new LinkedHashSet<String>();old.path("artifacts").path("sources").forEach(s -> ids.add(s.path("id").asText()));
                        var comparison=new LinkedHashMap<String,Object>();comparison.put("historicalSourceIds",ids);
                        comparison.put("historicalCitedSourceIds",AnswerMedia.citedSourceIds(old.path("content").asText()));
                        comparison.put("historicalDecisions","NOT_RECORDED");
                        comparison.put("newSourceIds",result.artifacts().sources().stream().map(AnswerArtifacts.Source::id).toList());
                        entry.put("historicalBaseline",comparison);
                    }
                    turns.add(entry);
                }
                if(turns.isEmpty()) throw new IllegalArgumentException("题集没有可诊断的问题");
                report.put("turns",turns);
            } finally {db.shutdown();}
        }
        Path output=Path.of(options.get("--output")).toAbsolutePath();Files.createDirectories(output.getParent());
        if(Files.exists(output)) throw new IllegalArgumentException("报告文件已存在，拒绝覆盖："+output);
        Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        System.out.println("检索诊断已保存："+output);
    }

    private static Map<String,Object> storage(DatabaseBuilder db,Path vocabulary) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        var tokenizer=new WordPieceTokenizer(vocabulary);long unknownId=Files.readAllLines(vocabulary).indexOf("[UNK]");
        long tokens=0,unknown=0,han=0,unknownHan=0;int truncated=0,invalid=0,zero=0;
        var lengths=new IntSummaryStatistics();var bodies=new IntSummaryStatistics();int shortBodies=0;
        try(var c=db.getConnection();var s=c.createStatement()) {
            for(String table:List.of("rag_document","rag_content_chunk","rag_vectors")) {
                try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table)) {r.next();result.put(table,r.getLong(1));}
            }
            try(var tables=c.getMetaData().getTables(null,null,"rag_content_block",null)) {
                if(tables.next()) {
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM rag_content_block")) {r.next();result.put("rawBlocks",r.getLong(1));}
                    try(var r=s.executeQuery("SELECT COUNT(DISTINCT chunk_id) FROM rag_chunk_block")) {r.next();result.put("structuredChunks",r.getLong(1));}
                }
            }
            try(var r=s.executeQuery("SELECT COUNT(*) FROM rag_content_chunk c LEFT JOIN rag_vectors v ON c.id=v.chunk_id WHERE v.chunk_id IS NULL")) {r.next();result.put("missingVectors",r.getLong(1));}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM rag_vectors v LEFT JOIN rag_content_chunk c ON c.id=v.chunk_id WHERE c.id IS NULL")) {r.next();result.put("orphanVectors",r.getLong(1));}
            var manifest=new ArrayList<Object>();
            try(var r=s.executeQuery("SELECT d.id,d.title,d.location,d.content_hash,COUNT(c.id) FROM rag_document d LEFT JOIN rag_content_chunk c ON c.document_id=d.id GROUP BY d.id,d.title,d.location,d.content_hash ORDER BY d.location")) {
                while(r.next()) manifest.add(Map.of("documentId",r.getString(1),"title",r.getString(2),"location",r.getString(3),"contentHash",r.getString(4),"chunks",r.getInt(5)));
            }
            result.put("documentManifest",manifest);
            try(var r=s.executeQuery("SELECT content FROM rag_content_chunk")) {
                while(r.next()) {
                    String text=r.getString(1);lengths.accept(text.length());String body=KnowledgeHybridRanker.body(text);
                    bodies.accept(body.length());if(body.length()<50) shortBodies++;
                    var stats=TokenizerDiagnostics.inspect(text,tokenizer,unknownId,256);
                    tokens+=stats.tokens();unknown+=stats.unknownTokens();han+=stats.hanCharacters();unknownHan+=stats.unknownHanCharacters();if(stats.truncated()) truncated++;
                }
            }
            try(var r=s.executeQuery("SELECT embedding FROM rag_vectors")) {
                while(r.next()) {
                    byte[] bytes=r.getBytes(1);if(bytes==null || bytes.length!=384*4) {invalid++;continue;}
                    var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);double norm=0;boolean finite=true;
                    while(buffer.hasRemaining()) {float f=buffer.getFloat();finite&=Float.isFinite(f);norm+=f*f;}
                    if(!finite) invalid++;if(norm==0) zero++;
                }
            }
        }
        result.put("vocabularySha256",TokenizerDiagnostics.sha256(vocabulary));result.put("averageChunkCharacters",lengths.getAverage());
        result.put("averageBodyCharacters",bodies.getAverage());result.put("bodyUnder50Characters",shortBodies);
        result.put("unknownTokenFraction",tokens==0?0:(double)unknown/tokens);result.put("unknownHanOccurrenceFraction",han==0?0:(double)unknownHan/han);
        result.put("chunksExceeding256Tokens",truncated);result.put("invalidVectors",invalid);result.put("zeroVectors",zero);
        return result;
    }
}
