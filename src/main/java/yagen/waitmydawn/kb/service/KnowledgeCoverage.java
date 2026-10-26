package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 以可审计的词面要点触发有界补检；命中仅表示存在候选依据，不证明事实充分。 */
public final class KnowledgeCoverage {
    record Facet(String name, List<String> terms,int minimumMatches) {
        Facet(String name,List<String> terms) {this(name,terms,1);}
    }
    public record Check(String facet, String status, List<String> sourceIds) {}
    public record Attempt(int round, String facet, String query, List<String> addedChunkIds) {}
    public record Audit(String method, int maxRounds, int queryBudget, List<Check> initial,
                        List<Check> finalChecks, List<Attempt> attempts, String stopReason, int mergedSeeds) {}
    record Result(KnowledgeRetrievalService.Retrieval retrieval, Audit audit) {}

    static Audit restrict(Audit audit,AnswerArtifacts artifacts) {
        if(audit==null) return null;
        var available=artifacts.sources().stream().map(AnswerArtifacts.Source::id).collect(java.util.stream.Collectors.toSet());
        var checks=audit.finalChecks().stream().map(check -> {
            var ids=check.sourceIds().stream().filter(available::contains).toList();
            return new Check(check.facet(),ids.isEmpty()?"NOT_FOUND_IN_CONTEXT":"CANDIDATE_EVIDENCE",ids);
        }).toList();
        return new Audit(audit.method(),audit.maxRounds(),audit.queryBudget(),audit.initial(),checks,audit.attempts(),audit.stopReason(),audit.mergedSeeds());
    }

    static List<Facet> plan(String query, AnswerArtifacts evidence) {
        var facets = new ArrayList<Facet>();
        add(facets, query.matches("(?s).*(装备|护甲|盔甲).*"), "护甲装备", "护甲", "盔甲", "套装", "装备");
        add(facets, query.contains("武器"), "武器", "武器", "战刃", "剑", "弓");
        add(facets, query.matches("(?s).*(饰品|饰物).*"), "饰品", "饰品", "饰物");
        add(facets, query.matches("(?s).*(组装|搭建|装配).*"), "组装", "组装", "装配", "组建", "搭建");
        add(facets, query.matches("(?s).*(升级|强化).*"), "升级", "升级", "强化", "扩充", "槽位");
        if (query.matches("(?s).*(怎么打|如何打|打法|击败|打败).*")) {
            add(facets, true, "战前准备", "准备", "药水", "抗性", "装备", "免疫");
            add(facets, true, "阶段与攻击", "阶段", "攻击", "破盾", "召唤", "屏障");
            add(facets, true, "应对风险", "躲避", "远离", "注意", "避免", "回复", "回血", "窃取");
            String body = evidence.sources().stream().map(s -> s.heading()+"\n"+s.evidence()).collect(java.util.stream.Collectors.joining("\n"));
            if (body.contains("主世界") && body.contains("下界")) {
                add(facets, true, "主世界", "主世界");
                add(facets, true, "下界", "下界");
            }
        } else if (query.matches("(?s).*(怎么玩|如何玩|玩法).*")) {
            add(facets, true, "入门与前置", "入门", "首先", "起步", "法杖", "魔杖", "前置");
            add(facets, true, "核心操作", "使用", "右键", "仪式", "操作", "放置", "合成");
            add(facets, true, "资源与限制", "能量", "消耗", "资源", "材料", "限制", "需要");
        }
        // 未识别的连接短语也保留独立侧重点，避免只有固定领域词才能触发补检。
        {
            String[] parts = query.split("[+＋、]|以及|并且|同时");
            if (parts.length > 1) for (String part : parts) {
                if(facets.stream().anyMatch(f -> f.terms().stream().anyMatch(part::contains))
                        || part.matches("(?s).*(怎么玩|如何玩|玩法|怎么打|如何打|打法|击败|打败).*")) continue;
                var terms = KnowledgeSearchText.terms(part).stream().limit(12).toList();
                if (!terms.isEmpty()) facets.add(new Facet(part.strip(), terms,Math.max(1,(int)Math.ceil(terms.size()*0.6))));
            }
        }
        return facets.stream().limit(6).toList();
    }

    private static void add(List<Facet> facets, boolean enabled, String name, String... terms) {
        if (enabled) facets.add(new Facet(name, List.of(terms)));
    }

    static List<Check> check(List<Facet> facets, AnswerArtifacts artifacts) {
        return facets.stream().map(f -> {
            var ids = artifacts.sources().stream().filter(s -> {
                String body = KnowledgeHybridRanker.body(s.evidence());
                return body.length() >= 8 && !"METADATA_ONLY".equals(s.method())
                        && f.terms().stream().filter(t -> KnowledgeSearchText.normalize(body).contains(KnowledgeSearchText.normalize(t))).count()>=f.minimumMatches();
            }).map(AnswerArtifacts.Source::id).toList();
            return new Check(f.name(), ids.isEmpty()?"NOT_FOUND_IN_CONTEXT":"CANDIDATE_EVIDENCE", ids);
        }).toList();
    }

    static Result run(String query, KnowledgeRetrievalService.Retrieval initial,
                      Function<String, KnowledgeRetrievalService.Retrieval> search,
                      BiFunction<List<KnowledgeRetrievalService.Retrieval>, String, KnowledgeRetrievalService.Retrieval> merge) {
        int rounds = Math.max(0, Math.min(2, Integer.getInteger("maa.qa.supplementRounds", 2)));
        int budget = Math.max(0, Math.min(6, Integer.getInteger("maa.qa.supplementQueries", 6)));
        var facets = plan(query, initial.artifacts());
        var first = check(facets, initial.artifacts());
        var attempts = new ArrayList<Attempt>();
        var results = new ArrayList<KnowledgeRetrievalService.Retrieval>(); results.add(initial);
        var known = new HashSet<String>(); initial.hits().forEach(h -> known.add(h.chunkId()));
        var asked = new HashSet<String>(); var current = initial;
        String stop = facets.isEmpty()?"NO_EXPLICIT_FACETS":rounds==0 || budget==0?"DISABLED":"ROUND_BUDGET";
        for (int round = 1; round <= rounds && attempts.size() < budget && !facets.isEmpty(); round++) {
            var missing = check(facets, current.artifacts()).stream().filter(c -> c.sourceIds().isEmpty())
                    .sorted(Comparator.comparingLong(c -> attempts.stream().filter(a -> a.facet().equals(c.facet())).count())).limit(3).toList();
            if (missing.isEmpty()) { stop="CANDIDATES_FOR_ALL_FACETS"; break; }
            int added = 0;
            for (var gap : missing) {
                if (attempts.size() >= budget) break;
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Supplement retrieval cancelled");
                var facet = facets.stream().filter(f -> f.name().equals(gap.facet())).findFirst().orElseThrow();
                String focused = query.replaceAll("怎么玩|有什么用|怎么|如何|推荐|玩法|装备|护甲|盔甲|武器|饰品|饰物|组装|装配|升级|强化|以及|并且|并|[+＋、]", " ")+" "+facet.name();
                if (round > 1) focused += " "+String.join(" ",facet.terms());
                if (!asked.add(focused)) continue;
                var found = search.apply(focused);
                var fresh = found.hits().stream().map(VectorStore.SearchResult::chunkId).filter(id -> !known.contains(id)).toList();
                known.addAll(fresh); added += fresh.size(); results.add(found);
                attempts.add(new Attempt(round,facet.name(),focused,fresh));
            }
            current=merge.apply(results,query);
            if (check(facets,current.artifacts()).stream().allMatch(c -> !c.sourceIds().isEmpty())) {stop="CANDIDATES_FOR_ALL_FACETS";break;}
            if (added==0 && check(facets,current.artifacts()).stream().filter(c -> c.sourceIds().isEmpty())
                    .allMatch(c -> attempts.stream().anyMatch(a -> a.facet().equals(c.facet())))) {stop="NO_NEW_SEEDS";break;}
            if (attempts.size()>=budget) stop="QUERY_BUDGET";
        }
        return new Result(current,new Audit("lexical-facets-v1-partial-evidence",rounds,budget,first,check(facets,current.artifacts()),List.copyOf(attempts),stop,current.hits().size()));
    }
}
