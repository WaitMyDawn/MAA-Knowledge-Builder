package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.config.DataDirLock;
import yagen.waitmydawn.kb.eval.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;

/** 固定题集的只读检索、选图、历史复核与冷热索引测量，不加载凭据或访问网络。 */
public final class MultimodalAuditRunner {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> PREVIEWS=Set.of("MM-03","MM-04","MM-05","MM-11","MM-MP-02","MM-DOC-01");
    private MultimodalAuditRunner() {}

    public static void main(String[] args)throws Exception {
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {
            if(i+1>=args.length||!Set.of("--data-dir","--output","--questions").contains(args[i])||options.put(args[i],args[i+1])!=null)
                throw new IllegalArgumentException("参数：--data-dir <已有库> --output <新报告目录> [--questions <golden-set-media.json>]");
        }
        if(!options.containsKey("--data-dir")||!options.containsKey("--output"))throw new IllegalArgumentException("缺少数据目录或报告目录");
        run(Path.of(options.get("--data-dir")),Path.of(options.get("--output")),Path.of(options.getOrDefault("--questions","golden-set-media.json")));
    }

    public static void run(Path data,Path output,Path questions)throws Exception {
        data=data.toRealPath();output=output.toAbsolutePath().normalize();
        if(output.startsWith(data)||Files.exists(output))throw new IllegalArgumentException("报告必须使用基础库以外的新目录");
        for(String file:List.of("rag_data.mv.db","models/all-MiniLM-L6-v2/model.onnx","models/all-MiniLM-L6-v2/vocab.txt"))
            if(!Files.isRegularFile(data.resolve(file))||Files.size(data.resolve(file))<1000)throw new IllegalArgumentException("缺少完整基础库或本地模型："+file);
        var spec=GoldenSet.load(questions);var sourceConfig=AppConfig.forIsolatedRun(false).forBuildTarget(data);
        Files.createDirectories(output);var outputConfig=AppConfig.forIsolatedRun(false).forBuildTarget(output);
        Files.copy(questions,output.resolve("golden-set.json"));
        var history=new HashMap<String,ChatHistoryService.ChatMessage>();
        for(var session:new ChatHistoryService(data).listSessions())if(Objects.toString(session.title,"").startsWith("P1P2真实问答 | "))
            for(int i=1;i<session.messages.size();i++)if(session.messages.get(i-1).role().equals("user"))history.putIfAbsent(session.messages.get(i-1).content(),session.messages.get(i));
        var turns=new ArrayList<Map<String,Object>>();var cold=new ArrayList<Long>();var warm=new ArrayList<Long>();
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("mode","READ_ONLY_RETRIEVAL_SELECTION_AND_HISTORY");
        report.put("answerGeneration","NOT_RUN");report.put("modelCalls",0);report.put("networkRequests",0);report.put("dataDir",data.toString());
        report.put("manualReview","文本事实充分性、图片像素语义和步骤适用性必须人工复核；元数据规则通过不代表端到端问答通过。");
        try(var lock=DataDirLock.acquire(data);var embedding=new EmbeddingService(sourceConfig)) {
            if(!embedding.initModel("all-MiniLM-L6-v2").equals("all-MiniLM-L6-v2"))throw new IllegalStateException("需要原 ONNX 模型，不允许回退后混用向量");
            var db=DatabaseBuilder.atReadOnlyPath(data.resolve("rag_data"));
            try {
                VectorStore.verifyProfile(db,embedding.profile(),false);report.put("buildGeneration",db.buildGeneration());report.put("embeddingProfile",embedding.profile());
                var retrieval=new KnowledgeRetrievalService(db,embedding,data,false);var media=new RuntimeEntityMedia(db,data,true);
                var replay=new ChatHistoryService(output);String profile=RuntimeKnowledgeStore.active(db);
                for(var conversation:spec.conversations())for(var turn:conversation.turns()) {
                    System.out.println("P5 "+turn.id()+" / "+spec.conversations().size());
                    var entry=new LinkedHashMap<String,Object>();entry.put("id",turn.id());entry.put("question",turn.question());
                    entry.put("answerGeneration","NOT_RUN");entry.put("manualRubric",turn.judgeRubric());
                    try {
                        var result=new QaPipeline.QaResult();
                        if(turn.id().equals("MM-03")) {
                            var matches=new ArrayList<QaPipeline.RecipeMatch>();
                            try(var c=db.getConnection();var p=c.prepareStatement("SELECT recipe_data,output_item,source_mod,source_type FROM rag_runtime_recipe WHERE profile_id=? AND output_item='minecraft:diamond_sword' ORDER BY recipe_id")) {
                                p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next())matches.add(new QaPipeline.RecipeMatch(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4)));}
                            }
                            var batch=new RendererService(outputConfig,db,data).renderDetailed(matches);AnswerMedia.attachRecipes(result,batch,output);
                            entry.put("recipeDiagnostics",batch.diagnostics());entry.put("recipes",batch.cards().stream().map(RendererService.RenderedRecipe::summary).toList());
                        } else if(turn.category().equals("runtime")||turn.category().equals("missing-state")) {
                            var baseline=new QaPipeline.QaResult();long start=System.nanoTime();new RuntimeEntityMedia(db,data,true).prepare(baseline,turn.question());cold.add(System.nanoTime()-start);
                            media.prepare(result,turn.question());var samples=new ArrayList<Object>();
                            for(int repeat=0;repeat<3;repeat++) {
                                var repeated=new QaPipeline.QaResult();start=System.nanoTime();media.prepare(repeated,turn.question());warm.add(System.nanoTime()-start);
                                if(!baseline.artifacts.equals(repeated.artifacts))throw new IllegalStateException("冷热索引选图结果不一致");
                                samples.add(repeated.mediaPerformance);
                            }
                            entry.put("performance",result.mediaPerformance);entry.put("warmSamples",samples);entry.put("entityAudit",result.entityImageAudit);entry.put("warnings",result.mediaWarnings);
                            archive(result.artifacts,data,output);
                        } else {
                            var resolution=retrieval.resolve(turn.question(),turn.id().equals("MM-DOC-01")?"mcmod:class:5214":turn.id().startsWith("MM-MP-")?"mcmod:modpack:737":null,null);
                            long start=System.nanoTime();var found=retrieval.retrieve(turn.question(),resolution,false);
                            result.artifacts=found.artifacts();entry.put("retrievalTimeMs",(System.nanoTime()-start)/1_000_000.0);
                            entry.put("retrieval",found.diagnostics());entry.put("evidence",found.evidence());entry.put("warning",Objects.toString(resolution.warning(),""));
                            entry.put("prerequisite",turn.category().equals("local-document")&&found.artifacts().scopeId()==null?"LOCAL_FIXTURE_NOT_IMPORTED":"AVAILABLE");
                            archive(result.artifacts,data,output);
                        }
                        String markers=markers(result.artifacts);entry.put("selectedArtifacts",result.artifacts);
                        var selectionRules=turn.assertions().stream().filter(a->Set.of("answer_media_identity_all","answer_media_identity_only","answer_images_absent","answer_media_refs_valid","answer_media_sources_bound","answer_image_grounding_valid","answer_image_facets_all","answer_image_page_any","knowledge_scope").contains(a.kind())).toList();
                        var selectionTurn=new GoldenSet.Turn(turn.id(),turn.question(),"",List.of(),turn.type(),turn.category(),1,List.of(),List.of(),selectionRules);
                        entry.put("selectionRules",RuleJudge.judge(selectionTurn,observation(markers,result.artifacts,output)));
                        if(history.containsKey(turn.question())) {
                            var previous=history.get(turn.question());boolean legacy=previous.artifacts()!=null&&previous.artifacts().pictures().stream().filter(p->Set.of("ENTITY","ITEM","RECIPE").contains(p.role())).anyMatch(p->p.runtimeIdentity()==null);
                            var reviewTurn=legacy?new GoldenSet.Turn(turn.id(),turn.question(),turn.referenceAnswer(),turn.referenceSources(),turn.type(),turn.category(),1,turn.answerPoints(),turn.judgeRubric(),turn.assertions().stream().filter(a->!Set.of("answer_media_identity_all","answer_media_identity_only").contains(a.kind())).toList()):turn;
                            entry.put("historicalRules",RuleJudge.judge(reviewTurn,observation(previous.content(),previous.artifacts(),data)));
                            boolean hasRuntime=previous.artifacts()!=null&&previous.artifacts().pictures().stream().anyMatch(p->Set.of("ENTITY","ITEM","RECIPE").contains(p.role()));
                            entry.put("historicalStateVerification",!hasRuntime?"NO_RUNTIME_IMAGES":legacy?"NOT_RECORDED_IN_LEGACY_SESSION":"STRUCTURED");
                            entry.put("historicalReviewStatus","MANUAL_REVIEW_REQUIRED");
                        } else entry.put("historicalReviewStatus","NO_MATCHING_SAVED_ANSWER");
                        if(PREVIEWS.contains(turn.id())) {
                            var session=replay.newSession("P5离线检索回放 | "+turn.id()+" "+conversation.name(),output);
                            replay.addMessage(session,"user",turn.question());
                            replay.addMessage(session,"maa","离线检索／选图验收，未调用回答模型；以下是候选证据与图片，不是新生成的问答答案。\n\n"+String.join("\n\n",result.mediaWarnings)+"\n\n"+markers,result.artifacts);replay.save(session);
                        }
                    } catch(Exception failure) {entry.put("error",failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),""));}
                    entry.put("selectionStatus",entry.containsKey("error")?"ERROR":"LOCAL_FIXTURE_NOT_IMPORTED".equals(entry.get("prerequisite"))?"BLOCKED_PREREQUISITE":entry.get("selectionRules") instanceof RuleJudge.Verdict v&&v.passed()?"PASS_METADATA_ONLY":"FAILED");
                    turns.add(entry);
                }
            } finally {db.shutdown();}
        }
        report.put("turns",turns);report.put("performance",Map.of("coldIndexSelection",latency(cold),"warmIndexSelection",latency(warm),"scope","仅配图准备；冷样本仍可能命中操作系统文件缓存，不是整轮问答加速比"));
        report.put("summary",Map.of("questions",turns.size(),"selectionPassed",turns.stream().filter(t->t.get("selectionStatus").equals("PASS_METADATA_ONLY")).count(),"selectionFailed",turns.stream().filter(t->t.get("selectionStatus").equals("FAILED")).count(),"prerequisitesMissing",turns.stream().filter(t->t.get("selectionStatus").equals("BLOCKED_PREREQUISITE")).count(),"errors",turns.stream().filter(t->t.containsKey("error")).count(),"newAnswersGenerated",0));
        Files.writeString(output.resolve("report.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        Files.writeString(output.resolve("README.md"),"# P5 离线图文验收\n\n未调用模型或网络，未修改原基础库。report.json 分别记录当前检索与选图、历史回答规则复核和性能。selectionPassed 仅表示当前候选图的元数据／引用检查，不表示新答案通过。旧历史缺少结构化状态时明确 NOT_RECORDED。\n\n本地 PDF／教程两题需要先导入固定资料；缺少资料不计作通过。6 条代表性离线回放可从问答页“导入评测会话…”选择本目录导入，点击来源可查看已保存证据。\n");
        var markdown=new StringBuilder("# P5 固定图文评测\n\n模式：只读检索、选图与历史复核；无新回答生成、无模型调用、无网络访问。\n\n| 题号 | 当前选图 | 已有回答 |\n| --- | --- | --- |\n");
        for(var turn:turns)markdown.append("| ").append(turn.get("id")).append(" | ").append(turn.get("selectionStatus")).append(" | ").append(turn.getOrDefault("historicalReviewStatus","未复核")).append(" |\n");
        markdown.append("\nPASS_METADATA_ONLY 仅指元数据与引用检查通过，不代表文字事实充分或图片像素语义已经确认。BLOCKED_PREREQUISITE 表示缺少本地固定资料，不计为通过。\n\n");
        markdown.append("冷索引配图准备：").append(latency(cold)).append("\n\n热索引配图准备：").append(latency(warm)).append("\n\n这些数据不包含回答模型，不是整轮问答加速比。\n");
        Files.writeString(output.resolve("report.md"),markdown);
        var bad=new StringBuilder("# 未通过与待复核\n\n");
        for(var turn:turns)if(!turn.get("selectionStatus").equals("PASS_METADATA_ONLY")) {
            bad.append("## ").append(turn.get("id")).append("\n\n").append(turn.get("question")).append("\n\n状态：").append(turn.get("selectionStatus")).append("\n\n");
            if(turn.get("selectionRules") instanceof RuleJudge.Verdict verdict)for(var failed:verdict.failedOutcomes())bad.append("- ").append(failed.kind()).append("：").append(failed.detail()).append('\n');
            if(turn.containsKey("error"))bad.append(turn.get("error")).append('\n');bad.append('\n');
        }
        Files.writeString(output.resolve("bad-cases.md"),bad);
        System.out.println("P5 报告："+output.resolve("report.json"));
    }

    private static String markers(AnswerArtifacts artifacts) {return artifacts.pictures().stream().map(p->"![参考](kb-image:"+p.id()+")").collect(java.util.stream.Collectors.joining("\n\n"));}
    private static void archive(AnswerArtifacts artifacts,Path source,Path output)throws Exception {
        var seen=new HashSet<String>();for(var picture:artifacts.pictures())if(seen.add(picture.assetId()))new ImageAssetStore(output).restore(picture.assetId(),Files.readAllBytes(AnswerMedia.resolveImage(source,picture)));
    }
    static Map<String,Object> latency(List<Long> nanos) {
        var sorted=nanos.stream().sorted().toList();return Map.of("samples",sorted.size(),"p50Ms",sorted.isEmpty()?0:sorted.get((int)Math.ceil(sorted.size()*.5)-1)/1_000_000.0,"p95Ms",sorted.isEmpty()?0:sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1_000_000.0);
    }
    static RuleJudge.Observation observation(String answer,AnswerArtifacts artifacts,Path data) {
        return new RuleJudge.Observation(answer,false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false,Map.of(),artifacts,AnswerMedia.assess(answer,artifacts,data));
    }
}
