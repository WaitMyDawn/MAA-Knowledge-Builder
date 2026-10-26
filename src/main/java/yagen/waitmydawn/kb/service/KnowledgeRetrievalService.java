package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.sql.*;
import java.nio.*;
import java.util.*;

/** 已发布图文资料的范围解析与混合检索，可按预算补缓存已记录的插图。 */
public final class KnowledgeRetrievalService {
    public record Scope(String id, String label, String kind, List<String> sourceIds, String location) {
        @Override public String toString() { return location==null ? label : label + "（" + location + "）"; }
    }
    public record Resolution(Scope scope, String documentId, String warning, boolean explicit) {}
    public record RankingSignals(double keyword,double sectionKeyword,double fusion,double quality,double heading,double section,double phase,double intent,
                                 int keywordRank,int semanticRank,int sectionRank,List<String> matchedTerms) {}
    public record AliasExpansion(String original,String equivalent,String documentId,String chunkId) {}
    public record ContextDiagnostics(String layoutVersion,List<String> blockIds,Map<String,String> blockSources,
                                     Map<String,String> chunkSources,int characters,int budget,boolean truncated) {}
    public record CandidateDecision(String chunkId, String documentId, String location, String heading,
                                    int textLength, Double semantic, double lexical, Double score,
                                    int rank, String decision, boolean tierLabelDifference,RankingSignals signals) {
        public CandidateDecision(String chunkId,String documentId,String location,String heading,int textLength,Double semantic,
                                 double lexical,Double score,int rank,String decision,boolean tierLabelDifference) {
            this(chunkId,documentId,location,heading,textLength,semantic,lexical,score,rank,decision,tierLabelDifference,null);
        }
    }
    /** 分数是检索排序信号；未标注相关证据时不能据此计算真实召回率。 */
    public record Diagnostics(String query, String embeddingProfile, int scanned, int selected,
                              Map<String,Integer> decisions, List<CandidateDecision> candidates, boolean truncated,
                              String strategy,List<AliasExpansion> aliases,List<String> queryTerms,long rankingTimeMs,ContextDiagnostics context,
                              KnowledgeCoverage.Audit coverage,KnowledgeImageSearch.Audit images) {
        public Diagnostics(String query,String profile,int scanned,int selected,Map<String,Integer> decisions,List<CandidateDecision> candidates,
                           boolean truncated,String strategy,List<AliasExpansion> aliases,List<String> terms,long time,ContextDiagnostics context) {
            this(query,profile,scanned,selected,decisions,candidates,truncated,strategy,aliases,terms,time,context,null,null);
        }
        public Diagnostics(String query,String embeddingProfile,int scanned,int selected,Map<String,Integer> decisions,
                           List<CandidateDecision> candidates,boolean truncated) {
            this(query,embeddingProfile,scanned,selected,decisions,candidates,truncated,null,List.of(),List.of(),0,null);
        }
    }
    public record Retrieval(List<VectorStore.SearchResult> hits, AnswerArtifacts artifacts, String evidence,
                            double bestLexical, Diagnostics diagnostics) {
        public Retrieval(List<VectorStore.SearchResult> hits,AnswerArtifacts artifacts,String evidence,double bestLexical) {
            this(hits,artifacts,evidence,bestLexical,null);
        }
    }
    private final DatabaseBuilder db;
    private final EmbeddingService embedder;
    private final KnowledgeImageHydrator hydrator;
    private final KnowledgeImageSearch imageSearch;
    private boolean imageFetching=true;
    public KnowledgeRetrievalService(DatabaseBuilder db, EmbeddingService embedder) { this(db,embedder,null); }
    public KnowledgeRetrievalService(DatabaseBuilder db,EmbeddingService embedder,java.nio.file.Path dataDir) {
        this(db,embedder,dataDir,true);
    }
    KnowledgeRetrievalService(DatabaseBuilder db,EmbeddingService embedder,java.nio.file.Path dataDir,boolean allowImageFetch) {
        this.db=db;this.embedder=embedder;
        imageSearch=new KnowledgeImageSearch(db,dataDir);
        hydrator=dataDir==null || !allowImageFetch?null:new KnowledgeImageHydrator(db,dataDir,new WikiScraperService());
    }
    public void setImageFetching(boolean enabled) {imageFetching=enabled;}

    public List<Scope> scopes() {
        var scopes = new LinkedHashMap<String, Scope>();
        String sql = "SELECT s.id,s.scope_id,s.display_name,s.scope_kind,s.location FROM rag_knowledge_source s WHERE EXISTS(SELECT 1 FROM rag_document d WHERE d.source_id=s.id) OR EXISTS(SELECT 1 FROM rag_document_source l WHERE l.source_id=s.id) ORDER BY s.display_name,s.id";
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            while (r.next()) {
                String key = r.getString(2) == null ? r.getString(1) : r.getString(2);
                String label = r.getString(3) == null ? r.getString(1) : r.getString(3);
                var previous = scopes.get(key); var ids = new ArrayList<String>();
                if (previous != null) ids.addAll(previous.sourceIds()); ids.add(r.getString(1));
                scopes.put(key, new Scope(key, label, r.getString(4), List.copyOf(ids), r.getString(5)));
            }
        } catch (SQLException e) { throw new IllegalStateException("资料来源读取失败", e); }
        return List.copyOf(scopes.values());
    }

    public Resolution resolve(String question, String selectedScope, String previousScope) {
        var scopes = scopes();
        if (selectedScope != null && !selectedScope.isBlank()) {
            var scope = scopes.stream().filter(s -> s.id().equals(selectedScope)).findFirst();
            return scope.map(s -> new Resolution(s, null, null, true)).orElseGet(() -> new Resolution(null, null, "所选资料来源已不在当前知识库中，请重新选择范围。", true));
        }
        String normalized = KnowledgeSearchText.normalize(question);
        var matches = scopes.stream().filter(s -> {
            String name = KnowledgeSearchText.normalize(s.label());
            return name.length() >= 3 && normalized.contains(name);
        }).sorted(Comparator.comparingInt((Scope s) -> KnowledgeSearchText.normalize(s.label()).length()).reversed()).toList();
        if (!matches.isEmpty()) {
            int length = KnowledgeSearchText.normalize(matches.getFirst().label()).length();
            var best = matches.stream().filter(s -> KnowledgeSearchText.normalize(s.label()).length() == length).toList();
            if (best.size() > 1) return new Resolution(null, null, "存在多个同名资料来源，请在资料范围中选择。", true);
            return new Resolution(best.getFirst(), null, null, true);
        }
        var documents = new LinkedHashSet<String>();
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT id,title FROM rag_document")) {
            while (r.next()) {
                String title = KnowledgeSearchText.normalize(r.getString(2));
                if (title.length() >= 4 && normalized.contains(title)) documents.add(r.getString(1));
            }
        } catch (SQLException e) { throw new IllegalStateException("资料名称解析失败", e); }
        if (documents.size() > 1) return new Resolution(null, null, "问题匹配到多个文件，请指定文件或资料范围。", true);
        if (documents.size() == 1) return new Resolution(null, documents.iterator().next(), null, true);
        if (previousScope != null && question.matches("(?s).*(它|这个|那个|上面|刚才|继续|还有|第[0-9]+页).*")) {
            if (previousScope.startsWith("document:")) {
                String id = previousScope.substring(9);
                try (var c=db.getConnection();var p=c.prepareStatement("SELECT 1 FROM rag_document WHERE id=?")) {
                    p.setString(1,id);try(var r=p.executeQuery()) {
                        return r.next() ? new Resolution(null,id,null,true) : new Resolution(null,null,"上一轮资料已不在当前知识库中，请重新选择来源。",true);
                    }
                } catch(SQLException e) { throw new IllegalStateException("会话资料范围读取失败",e); }
            }
            for (var scope : scopes) if (scope.id().equals(previousScope)) return new Resolution(scope, null, null, true);
            return new Resolution(null,null,"上一轮资料来源已不在当前知识库中，请重新选择来源。",true);
        }
        return new Resolution(null, null, null, false);
    }

    public String boundModId(Scope scope) {
        if(scope==null || !"MOD".equals(scope.kind())) return null;
        String marks=String.join(",",Collections.nCopies(scope.sourceIds().size(),"?"));
        var ids=new LinkedHashSet<String>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT DISTINCT mod_id FROM rag_knowledge_source WHERE id IN ("+marks+") AND mod_id IS NOT NULL")) {
            for(int i=0;i<scope.sourceIds().size();i++) p.setString(i+1,scope.sourceIds().get(i));
            try(var r=p.executeQuery()) {while(r.next()) ids.add(r.getString(1));}
        } catch(SQLException e) {throw new IllegalStateException("模组来源绑定读取失败",e);}
        return ids.size()==1?ids.iterator().next():null;
    }

    public Retrieval retrieve(String question, Resolution resolution) {
        return retrieve(question,resolution,true);
    }
    public Retrieval retrieve(String question, Resolution resolution,boolean fetchImages) {
        var initial=retrieveOnce(question,resolution);
        if(resolution.warning()!=null) return initial;
        var supplemented=KnowledgeCoverage.run(initial.diagnostics().query(),initial,q -> retrieveOnce(q,resolution),
                (results,q) -> merge(results,q));
        var result=supplemented.retrieval();var d=result.diagnostics();
        var diagnostics=new Diagnostics(d.query(),d.embeddingProfile(),d.scanned(),d.selected(),d.decisions(),d.candidates(),d.truncated(),
                d.strategy(),d.aliases(),d.queryTerms(),d.rankingTimeMs(),d.context(),supplemented.audit(),d.images());
        result=new Retrieval(result.hits(),result.artifacts(),evidence(result.artifacts(),diagnostics),result.bestLexical(),diagnostics);
        return fetchImages?hydrate(result,question):result;
    }

    private Retrieval retrieveOnce(String question,Resolution resolution) {
        if (resolution.warning() != null) return new Retrieval(List.of(), AnswerArtifacts.empty(), "", 0);
        var scope = resolution.scope();
        String query = question;
        if (scope != null) {
            query = stripName(question, scope.label());
            if (query.isBlank()) query = question;
        }
        if (resolution.documentId() != null) {
            try (var c=db.getConnection();var p=c.prepareStatement("SELECT title FROM rag_document WHERE id=?")) {
                p.setString(1,resolution.documentId());try(var r=p.executeQuery()) {
                    if(r.next()) {
                        String trimmed=stripName(question,r.getString(1));
                        if(!trimmed.isBlank()) query=trimmed;
                    }
                }
            } catch(SQLException e) { throw new IllegalStateException("文档范围读取失败",e); }
        }
        Integer page = null;
        var pageMatch=java.util.regex.Pattern.compile("第\\s*([1-9][0-9]*)\\s*页").matcher(question);
        while(pageMatch.find()) try { page=Integer.valueOf(pageMatch.group(1)); } catch(NumberFormatException ignored) { }
        final String actualQuery = query;
        float[] vector = embedder.embed(query);
        var imageLexical = imageSignals(query);
        var rows = new LinkedHashMap<String,Hit>();
        var inputs = new ArrayList<KnowledgeHybridRanker.Chunk>();
        var decisions = new LinkedHashMap<String,CandidateDecision>();
        String sql = """
                SELECT c.*,d.title,d.location,d.source_id,v.embedding,v.mod_id,v.mod_name,v.chunk_type
                FROM rag_content_chunk c JOIN rag_document d ON c.document_id=d.id LEFT JOIN rag_vectors v ON v.chunk_id=c.id
                WHERE 1=1
                """;
        var params = new ArrayList<String>();
        if (scope != null) {
            String marks = String.join(",", Collections.nCopies(scope.sourceIds().size(), "?"));
            sql += " AND (d.source_id IN (" + marks + ") OR EXISTS(SELECT 1 FROM rag_document_source l WHERE l.document_id=d.id AND l.source_id IN (" + marks + ")))";
            params.addAll(scope.sourceIds()); params.addAll(scope.sourceIds());
        }
        if (resolution.documentId() != null) { sql += " AND d.id=?"; params.add(resolution.documentId()); }
        if (page != null) { sql += " AND c.page_number=?"; params.add(page.toString()); }
        try (var c = db.getConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) p.setString(i+1, params.get(i));
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    String text = r.getString("content");
                    String chunkId=r.getString("id"), documentId=r.getString("document_id");
                    String location=r.getString("location"), heading=r.getString("heading");
                    boolean tierDifference=tierLabelDifference(query,text);
                    double lexical = Math.max(KnowledgeSearchText.overlap(query, text), imageLexical.getOrDefault(r.getString("id"),0.0)*0.85);
                    byte[] stored=r.getBytes("embedding");
                    if(stored==null) {
                        decisions.put(chunkId,new CandidateDecision(chunkId,documentId,location,heading,text.length(),null,lexical,null,0,"MISSING_VECTOR",tierDifference));
                        continue;
                    }
                    double semantic = cosine(vector, stored);
                    if(page!=null && resolution.documentId()!=null) semantic=Math.max(0.35,semantic);
                    String method = r.getString("extraction_method");
                    inputs.add(new KnowledgeHybridRanker.Chunk(chunkId,documentId,r.getString("title"),heading,text,
                            r.getInt("ordinal"),method,semantic,imageLexical.getOrDefault(chunkId,0.0)));
                    rows.put(chunkId,new Hit(new VectorStore.SearchResult(r.getString("id"), text, r.getString("mod_id"),
                            r.getString("mod_name"), r.getString("chunk_type"), 0, r.getString("location")),
                            r.getString("document_id"), r.getString("title"), r.getString("heading"),
                            (Integer)r.getObject("page_number"), method, lexical));
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("图文知识检索失败", e); }
        long rankingStart=System.nanoTime();
        var ranking=KnowledgeHybridRanker.rank(query,inputs);
        long rankingTimeMs=(System.nanoTime()-rankingStart)/1_000_000;
        for(var candidate:ranking.candidates()) {
            var input=candidate.chunk();var hit=rows.get(input.id());var signals=candidate.signals();
            decisions.put(input.id(),new CandidateDecision(input.id(),input.documentId(),hit.result().sourcePath(),input.heading(),input.text().length(),
                    input.semantic(),candidate.lexical(),candidate.score(),candidate.rank(),candidate.decision(),tierLabelDifference(query,input.text()),
                    new RankingSignals(signals.keyword(),signals.sectionKeyword(),signals.fusion(),signals.quality(),signals.heading(),signals.section(),signals.phase(),signals.intent(),
                            signals.keywordRank(),signals.semanticRank(),signals.sectionRank(),signals.matchedTerms())));
        }
        var selected=ranking.selected().stream().map(candidate -> {
            var hit=rows.get(candidate.chunk().id());var old=hit.result();
            return new Hit(new VectorStore.SearchResult(old.chunkId(),old.chunkText(),old.modId(),old.modName(),old.type(),candidate.score(),old.sourcePath()),
                    hit.documentId(),hit.title(),hit.heading(),hit.page(),hit.method(),candidate.lexical());
        }).toList();
        var totals=new TreeMap<String,Integer>();decisions.values().forEach(d -> totals.merge(d.decision(),1,Integer::sum));
        var ordered=decisions.values().stream().sorted(Comparator.<CandidateDecision>comparingInt(d -> d.decision().equals("SELECTED")?0:1)
                .thenComparing(Comparator.comparingDouble((CandidateDecision d) -> d.score()==null?-1:d.score()).reversed())
                .thenComparing(CandidateDecision::chunkId)).limit(200).toList();
        var diagnostics=new Diagnostics(query,embedder.profile(),decisions.size(),selected.size(),Collections.unmodifiableMap(totals),ordered,decisions.size()>ordered.size(),
                KnowledgeHybridRanker.STRATEGY,ranking.aliases().stream().map(a -> new AliasExpansion(a.original(),a.equivalent(),a.documentId(),a.chunkId())).toList(),ranking.queryTerms(),rankingTimeMs,null);
        var sources = new ArrayList<AnswerArtifacts.Source>();
        var sourceIds = new HashMap<String, String>();
        for (var hit : selected) {
            String id = "s" + BuildRequest.fingerprint(hit.result().chunkId()).substring(0, 20);
            sourceIds.put(hit.result().chunkId(), id);
            String sourceName=hit.result().modName();
            String title=sourceName==null || sourceName.equals(hit.title()) ? hit.title() : sourceName+" — "+hit.title();
            sources.add(new AnswerArtifacts.Source(id, hit.documentId(), title, hit.result().sourcePath(), hit.heading(), hit.page(), hit.method(), hit.result().chunkText()));
        }
        String focus=query+" "+ranking.aliases().stream().map(KnowledgeHybridRanker.AliasExpansion::equivalent).collect(java.util.stream.Collectors.joining(" "));
        var context=new KnowledgeContextBuilder(db).build(selected.stream().map(Hit::result).toList(),sources,focus);
        sources=new ArrayList<>(context.sources());sourceIds.clear();sourceIds.putAll(context.diagnostics().chunkSources());
        diagnostics=new Diagnostics(diagnostics.query(),diagnostics.embeddingProfile(),diagnostics.scanned(),diagnostics.selected(),diagnostics.decisions(),
                diagnostics.candidates(),diagnostics.truncated(),diagnostics.strategy(),diagnostics.aliases(),diagnostics.queryTerms(),diagnostics.rankingTimeMs(),context.diagnostics());
        var pictures = List.<AnswerArtifacts.Picture>of();
        var artifacts=new AnswerArtifacts(scope == null ? resolution.documentId()==null ? null : "document:"+resolution.documentId() : scope.id(),scope == null ? null : scope.label(),sources,pictures);
        var retrieval = new Retrieval(selected.stream().map(Hit::result).toList(),artifacts,evidence(artifacts,diagnostics),selected.stream().mapToDouble(Hit::lexical).max().orElse(0),diagnostics);
        return withImages(retrieval,actualQuery);
    }

    private Retrieval merge(List<Retrieval> results,String question) {
        var seeds=new LinkedHashMap<String,VectorStore.SearchResult>();
        // 每个定向查询先保留两个种子，再补首轮结果，避免新侧重点被旧候选挤出预算。
        for(int i=1;i<results.size();i++) results.get(i).hits().stream().limit(2).forEach(h -> seeds.putIfAbsent(h.chunkId(),h));
        results.getFirst().hits().forEach(h -> seeds.putIfAbsent(h.chunkId(),h));
        for(var result:results) result.hits().forEach(h -> seeds.putIfAbsent(h.chunkId(),h));
        var hits=seeds.values().stream().limit(24).toList();
        var sources=new ArrayList<AnswerArtifacts.Source>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT c.*,d.title,d.location FROM rag_content_chunk c JOIN rag_document d ON d.id=c.document_id WHERE c.id=?")) {
            for(var hit:hits) {
                p.setString(1,hit.chunkId());try(var r=p.executeQuery()) {if(r.next()) sources.add(new AnswerArtifacts.Source("s"+BuildRequest.fingerprint(hit.chunkId()).substring(0,20),
                        r.getString("document_id"),r.getString("title"),r.getString("location"),r.getString("heading"),(Integer)r.getObject("page_number"),r.getString("extraction_method"),hit.chunkText()));}
            }
        } catch(SQLException e) {throw new IllegalStateException("补检上下文读取失败",e);}
        var aliases=results.stream().filter(r -> r.diagnostics()!=null).flatMap(r -> r.diagnostics().aliases().stream()).distinct().toList();
        var context=new KnowledgeContextBuilder(db).build(hits,sources,question+" "+aliases.stream().map(AliasExpansion::equivalent).collect(java.util.stream.Collectors.joining(" ")));
        var initial=results.getFirst();var d=initial.diagnostics();
        var diagnostics=new Diagnostics(d.query(),d.embeddingProfile(),d.scanned(),d.selected(),d.decisions(),d.candidates(),d.truncated(),d.strategy(),aliases,d.queryTerms(),
                results.stream().mapToLong(r -> r.diagnostics().rankingTimeMs()).sum(),context.diagnostics());
        var artifacts=new AnswerArtifacts(initial.artifacts().scopeId(),initial.artifacts().scopeLabel(),context.sources(),List.of());
        return withImages(new Retrieval(hits,artifacts,"",results.stream().mapToDouble(Retrieval::bestLexical).max().orElse(0),diagnostics),question);
    }

    public Retrieval hydrate(Retrieval retrieval,String question) {
        if(!imageFetching || hydrator==null || retrieval.hits().isEmpty()) return retrieval;
        String imageQuery=imageQuery(retrieval,question);
        var sourceIds=new HashMap<String,String>();
        for(var hit:retrieval.hits()) {
            String id=sourceId(retrieval,hit.chunkId());
            if(retrieval.artifacts().sources().stream().anyMatch(s -> s.id().equals(id))) sourceIds.put(hit.chunkId(),id);
        }
        var present=new ArrayList<>(retrieval.artifacts().pictures());
        imageSearch.search(imageQuery,retrieval.artifacts(),retrieval.diagnostics()).repairs().stream().filter(p -> present.stream().noneMatch(i -> i.id().equals(p.id()))).forEach(present::add);
        var extra=hydrator.hydrate(imageQuery,retrieval.hits().stream().filter(h -> sourceIds.containsKey(h.chunkId())).toList(),present,sourceIds);
        var pictures=new ArrayList<AnswerArtifacts.Picture>();var seen=new HashSet<String>();var sourceCounts=new HashMap<String,Integer>();
        java.util.stream.Stream.concat(present.stream().filter(hydrator::available),extra.stream()).forEach(p -> {
            if(pictures.size()<4 && !seen.contains(p.assetId()) && sourceCounts.getOrDefault(p.sourceId(),0)<2) {
                pictures.add(p);seen.add(p.assetId());sourceCounts.merge(p.sourceId(),1,Integer::sum);
            }
        });
        var artifacts=retrieval.artifacts();
        var expanded=new AnswerArtifacts(artifacts.scopeId(),artifacts.scopeLabel(),artifacts.sources(),pictures);
        var d=retrieval.diagnostics();
        var imageAudit=d==null?null:d.images();
        if(imageAudit!=null) imageAudit=new KnowledgeImageSearch.Audit(imageAudit.inspected(),Math.max(0,imageAudit.pending()-hydrator.audit().resolved()),pictures.size(),pictures.isEmpty()?imageAudit.status():"SELECTED_AFTER_CACHE",imageAudit.truncated(),imageAudit.candidates(),hydrator.audit());
        if(d!=null) d=new Diagnostics(d.query(),d.embeddingProfile(),d.scanned(),d.selected(),d.decisions(),d.candidates(),d.truncated(),d.strategy(),d.aliases(),d.queryTerms(),d.rankingTimeMs(),d.context(),KnowledgeCoverage.restrict(d.coverage(),expanded),imageAudit);
        return new Retrieval(retrieval.hits(),expanded,evidence(expanded,d),retrieval.bestLexical(),d);
    }

    private Retrieval withImages(Retrieval retrieval,String question) {
        var result=imageSearch.search(imageQuery(retrieval,question),retrieval.artifacts(),retrieval.diagnostics());
        var old=retrieval.artifacts();var artifacts=new AnswerArtifacts(old.scopeId(),old.scopeLabel(),old.sources(),result.pictures());
        var d=retrieval.diagnostics();
        var diagnostics=new Diagnostics(d.query(),d.embeddingProfile(),d.scanned(),d.selected(),d.decisions(),d.candidates(),d.truncated(),d.strategy(),d.aliases(),d.queryTerms(),d.rankingTimeMs(),d.context(),d.coverage(),result.audit());
        return new Retrieval(retrieval.hits(),artifacts,evidence(artifacts,diagnostics),retrieval.bestLexical(),diagnostics);
    }

    private static String imageQuery(Retrieval retrieval,String fallback) {
        var d=retrieval.diagnostics();
        return d==null?fallback:d.query()+" "+d.aliases().stream().map(AliasExpansion::equivalent).distinct().limit(8).collect(java.util.stream.Collectors.joining(" "));
    }

    static String sourceId(Retrieval retrieval,String chunkId) {
        return retrieval.diagnostics()!=null && retrieval.diagnostics().context()!=null?retrieval.diagnostics().context().chunkSources().get(chunkId):"s"+BuildRequest.fingerprint(chunkId).substring(0,20);
    }

    static List<String> contextIds(Retrieval retrieval,AnswerArtifacts artifacts) {
        if(retrieval==null) return List.of();
        if(retrieval.diagnostics()==null || retrieval.diagnostics().context()==null) return retrieval.hits().stream().map(VectorStore.SearchResult::chunkId).toList();
        var ids=artifacts.sources().stream().map(AnswerArtifacts.Source::id).collect(java.util.stream.Collectors.toSet());
        return retrieval.diagnostics().context().blockSources().entrySet().stream().filter(e -> ids.contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
    }

    static String aliasEvidence(Diagnostics diagnostics) {
        if(diagnostics==null || diagnostics.aliases()==null || diagnostics.aliases().isEmpty()) return "";
        var text=new StringBuilder("名称消歧（当前范围原文中的显式括注，仅证明名称对应，不证明机制或配方）：\n");
        for(var alias:diagnostics.aliases()) text.append(alias.original()).append(" → ").append(alias.equivalent())
                .append("；原文块=").append(alias.chunkId()).append('\n');
        return text.append('\n').toString();
    }

    public static String evidence(AnswerArtifacts artifacts,Diagnostics diagnostics) {
        var text=new StringBuilder(aliasEvidence(diagnostics));var sources=new HashMap<String,AnswerArtifacts.Source>();
        if(diagnostics!=null && diagnostics.context()!=null && diagnostics.context().truncated())
            text.append("证据范围：部分原文因长度限制仅提供命中片段，不得声称已穷尽文档或整个知识库。\n\n");
        if(diagnostics!=null && diagnostics.coverage()!=null) {
            text.append("问题要点检查（词面匹配只提示候选依据，回答前仍需核对主体、版本和事实是否充分）：\n");
            for(var check:KnowledgeCoverage.restrict(diagnostics.coverage(),artifacts).finalChecks()) text.append(check.facet()).append("：").append(check.status()).append("；来源=").append(check.sourceIds()).append('\n');
            text.append("未找到候选依据的部分须明确说明本轮证据缺口，不得用通用模组知识补齐。\n\n");
        }
        for(var source:artifacts.sources()) {
            sources.put(source.id(),source);
            text.append("来源 [[source:").append(source.id()).append("]]：").append(source.title()).append("；章节=").append(source.heading())
                    .append("；页码=").append(source.pageNumber()).append("；提取方式=").append(source.method()).append('\n').append(source.evidence()).append("\n\n");
        }
        for(var picture:artifacts.pictures()) {
            text.append("图片 [[image:").append(picture.id()).append("]]：").append(picture.caption()).append("；用途=").append(picture.role());
            var source=sources.get(picture.sourceId());
            if(source==null) text.append("；上下文=").append(picture.context());
            else {
                text.append("；对应来源 [[source:").append(source.id()).append("]]");
                String context=Objects.toString(picture.context(),"");int start=context.indexOf("\nOCR识别：");
                if(start<0) start=context.indexOf("\n模型图像描述（推断）：");
                if(start>=0) {
                    String extra=context.substring(start);
                    if(!source.evidence().contains(extra)) text.append(extra);
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    private Map<String,Double> imageSignals(String query) {
        var scores = new HashMap<String,Double>();
        try (var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT l.chunk_id,o.caption,LEFT(i.ocr_text,6000),LEFT(i.description,2000) FROM rag_chunk_image l JOIN rag_image_occurrence o ON l.occurrence_id=o.id JOIN rag_image_asset i ON o.image_id=i.id")) {
            while(r.next()) scores.merge(r.getString(1), KnowledgeSearchText.overlap(query,
                    Objects.toString(r.getString(2),"")+" "+Objects.toString(r.getString(3),"")+" "+Objects.toString(r.getString(4),"")),Math::max);
        } catch(SQLException e) { throw new IllegalStateException("图片描述检索失败",e); }
        return scores;
    }

    private record Hit(VectorStore.SearchResult result, String documentId, String title, String heading, Integer page, String method, double lexical) {}
    private static String stripName(String question,String name) {
        String normalized=KnowledgeSearchText.normalize(name);
        if(normalized.isBlank()) return question;
        String pattern=normalized.codePoints().mapToObj(cp -> java.util.regex.Pattern.quote(new String(Character.toChars(cp))))
                .collect(java.util.stream.Collectors.joining("[\\p{P}\\s]*"));
        return question.replaceAll("(?iu)"+pattern," ").strip();
    }
    // 流程阶段与表格强度梯度可能使用相同 T 标签，差异只能作为诊断信息，不能硬过滤。
    private static boolean tierLabelDifference(String query,String text) {
        var pattern=java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])t[0-9]+(?![a-z0-9])");
        var requested=new HashSet<String>();var available=new HashSet<String>();
        var q=pattern.matcher(query);while(q.find()) requested.add(q.group().toLowerCase(Locale.ROOT));
        var t=pattern.matcher(text);while(t.find()) available.add(t.group().toLowerCase(Locale.ROOT));
        return !requested.isEmpty() && !available.isEmpty() && Collections.disjoint(requested,available);
    }
    private static double cosine(float[] query, byte[] bytes) {
        if (bytes == null || bytes.length != query.length * Float.BYTES) throw new IllegalStateException("资料向量维度不匹配");
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double dot=0,a=0,b=0; for(float q: query) { float v=buffer.getFloat(); dot+=q*v; a+=q*q; b+=v*v; }
        double score=a==0 || b==0 ? 0 : dot/Math.sqrt(a*b);
        if(!Double.isFinite(score)) throw new IllegalStateException("资料向量包含非有限数值");
        return score;
    }
}
