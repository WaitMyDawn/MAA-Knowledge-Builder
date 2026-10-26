package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 范围内的词面/向量独立召回、排名融合与确定性重排，不改写存储向量。 */
final class KnowledgeHybridRanker {
    static final String STRATEGY="scoped-bm25-rrf-diversity-v1";
    private static final int BRANCH_LIMIT=80;
    private static final Pattern BILINGUAL=Pattern.compile("([\\p{IsHan}]{2,16})\\s*[(（]([A-Za-z][A-Za-z0-9 _'./:-]{1,63})[)）]");
    private static final Pattern REVERSE=Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9 _'./:-]{1,63})\\s*[(（]([\\p{IsHan}]{2,16})[)）]");
    private static final Pattern IDENTIFIER=Pattern.compile("[a-z][a-z0-9_]*:[a-z0-9_./]+");
    private static final Pattern STAGE=Pattern.compile("(?i)^\\s*(t[0-9]+)\\s*[:：]?\\s*$");
    record Chunk(String id,String documentId,String title,String heading,String text,int ordinal,
                 String method,double semantic,double imageLexical) {}
    record AliasExpansion(String original,String equivalent,String documentId,String chunkId) {}
    record Signals(double keyword,double sectionKeyword,double fusion,double quality,double heading,double section,double phase,double intent,
                   int keywordRank,int semanticRank,int sectionRank,List<String> matchedTerms) {}
    record Ranked(Chunk chunk,double score,double lexical,int rank,String decision,Signals signals) {}
    record Result(List<Ranked> selected,List<Ranked> candidates,List<AliasExpansion> aliases,List<String> queryTerms) {}
    private record Pair(String chinese,String english,String documentId,String chunkId) {}
    private record QueryTerm(String key,List<String> alternatives) {}

    static Result rank(String question,List<Chunk> chunks) {
        var pairs=aliases(chunks);
        var expansions=new ArrayList<AliasExpansion>();var terms=query(question,pairs,expansions);
        var keyword=new HashMap<String,Double>();var lexical=new HashMap<String,Double>();var heading=new HashMap<String,Double>();
        var bodies=new HashMap<String,String>();var frequencies=new HashMap<String,Map<String,Integer>>();
        var features=new HashMap<String,Set<String>>();
        var length=new HashMap<String,Integer>();var df=new HashMap<String,Integer>();
        var sections=sections(chunks);var sectionLexical=new HashMap<String,Double>();
        for(var chunk:chunks) {
            String body=body(chunk.text());bodies.put(chunk.id(),body);
            var counts=counts(body,terms);var headings=counts(Objects.toString(chunk.heading(),""),terms);
            var combined=new HashMap<>(counts);headings.forEach((k,v) -> combined.merge(k,2*v,Integer::sum));
            combined.keySet().forEach(k -> df.merge(k,1,Integer::sum));frequencies.put(chunk.id(),combined);
            var tokens=KnowledgeSearchText.terms(body);features.put(chunk.id(),tokens);length.put(chunk.id(),tokenLength(body));
            double coverage=coverage(counts,terms),headCoverage=coverage(headings,terms);
            if(terms.stream().anyMatch(t -> t.alternatives().stream().anyMatch(a -> KnowledgeSearchText.normalize(a).equals(KnowledgeSearchText.normalize(chunk.heading()))))) headCoverage=1;
            lexical.put(chunk.id(),Math.max(coverage,chunk.imageLexical()*0.85));heading.put(chunk.id(),headCoverage);
            sectionLexical.merge(sections.get(chunk.id()),Math.max(coverage,headCoverage),Math::max);
        }
        double averageLength=length.values().stream().mapToInt(i->i).average().orElse(1);
        for(var chunk:chunks) {
            double bm25=0;
            for(var term:terms) {
                int tf=frequencies.get(chunk.id()).getOrDefault(term.key(),0);if(tf==0) continue;
                double idf=Math.log(1+(chunks.size()-df.getOrDefault(term.key(),0)+0.5)/(df.getOrDefault(term.key(),0)+0.5));
                bm25+=idf*tf*2.2/(tf+1.2*(0.25+0.75*length.get(chunk.id())/averageLength));
            }
            keyword.put(chunk.id(),bm25);
        }
        var keywordRanks=ranks(chunks,keyword,0.000001);
        var semanticValues=new HashMap<String,Double>();chunks.forEach(c -> semanticValues.put(c.id(),c.semantic()));
        var semanticRanks=ranks(chunks,semanticValues,0.40);
        var imageRanks=ranks(chunks,chunks.stream().collect(java.util.stream.Collectors.toMap(Chunk::id,Chunk::imageLexical)),0.000001);
        var phase=phaseProximity(question,chunks);
        var sectionRanks=sectionRanks(chunks,sections,keyword,question,bodies);
        var sectionKeyword=new HashMap<String,Double>();chunks.forEach(c -> sectionKeyword.merge(sections.get(c.id()),keyword.get(c.id()),Math::max));
        double maxKeyword=keyword.values().stream().mapToDouble(v->v).max().orElse(0);
        var scored=new ArrayList<Ranked>();
        for(var chunk:chunks) {
            String id=chunk.id();int kr=keywordRanks.getOrDefault(id,0),sr=semanticRanks.getOrDefault(id,0),ir=imageRanks.getOrDefault(id,0);
            int sectionRank=sectionRanks.getOrDefault(id,0);
            double fusion=(rrf(kr)+rrf(sr))/2;
            if(ir>0) fusion=Math.max(fusion,(rrf(ir)+rrf(sr))/2);
            if(sectionRank>0) fusion=Math.max(fusion,0.75*(rrf(sectionRank)+rrf(sr))/2);
            double quality=quality(question,chunk,bodies.get(id));
            double section=sectionLexical.getOrDefault(sections.get(id),0.0);
            double contextualKeyword=sectionRank==0?0:sectionKeyword.get(sections.get(id))*0.65;
            double intent=instructionPrior(question,bodies.get(id));
            double sectionCoverage=sectionRank>0 && bodies.get(id).length()>=50?section*0.60:0;
            double score=(0.25*fusion+0.30*(maxKeyword==0?0:Math.max(keyword.get(id),contextualKeyword)/maxKeyword)+0.20*lexical.get(id)
                    +0.20*Math.max(0,sectionCoverage-lexical.get(id))+0.10*heading.get(id)+0.15*section+0.18*phase.getOrDefault(id,0.0)+0.18*intent)*quality;
            var matched=frequencies.get(id).keySet().stream().sorted().toList();
            var signals=new Signals(keyword.get(id),contextualKeyword,fusion,quality,heading.get(id),section,phase.getOrDefault(id,0.0),intent,kr,sr,sectionRank,matched);
            String decision=kr==0 && sr==0 && ir==0 && sectionRank==0?"OUTSIDE_BRANCH_POOL":"CANDIDATE";
            scored.add(new Ranked(chunk,Math.min(1,score),lexical.get(id),0,decision,signals));
        }
        scored.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r -> r.chunk().id()));
        var ranks=new HashMap<String,Integer>();int candidateRank=0;
        for(var candidate:scored) if(candidate.decision().equals("CANDIDATE")) ranks.put(candidate.chunk().id(),++candidateRank);
        var remaining=new ArrayList<>(scored.stream().filter(r -> r.decision().equals("CANDIDATE")).toList());
        var decisions=new HashMap<String,String>();var selected=new ArrayList<Ranked>();
        var seen=new HashSet<String>();var sectionCounts=new HashMap<String,Integer>();var covered=new HashSet<String>();
        var facets=Arrays.stream(question.split("[+＋、,，]|以及|并且|并"))
                .map(s -> s.replaceAll("(?i)(?<![a-z0-9])t[0-9]+(?![a-z0-9])","").replaceAll("的|推荐|如何|怎么|哪些|有什么用","").strip())
                .filter(s -> !s.isBlank()).toList();
        if(facets.size()<2) facets=List.of();
        int facetCursor=0;
        while(!remaining.isEmpty()) {
            if(selected.size()>=10) {
                for(var candidate:remaining) decisions.put(candidate.chunk().id(),"RESULT_LIMIT");
                break;
            }
            Ranked best=null;double bestUtility=-Double.MAX_VALUE;
            String facet=facetCursor<facets.size()*2?facets.get(facetCursor%facets.size()):null;
            facetCursor++;
            for(var candidate:remaining) {
                double novelty=candidate.signals().matchedTerms().stream().filter(t -> !covered.contains(t)).count()/(double)Math.max(1,terms.size());
                double similarity=selected.stream().mapToDouble(s -> similarity(features.get(s.chunk().id()),features.get(candidate.chunk().id()))).max().orElse(0);
                double utility=candidate.score()+0.16*novelty-0.12*similarity;
                if(facet!=null) {
                    double facetMatch=KnowledgeSearchText.overlap(facet,Objects.toString(candidate.chunk().heading(),""));
                    utility+=0.25*facetMatch+0.15*candidate.signals().phase()*facetMatch;
                }
                if(utility>bestUtility) {best=candidate;bestUtility=utility;}
            }
            remaining.remove(best);String id=best.chunk().id(),section=sections.get(id);
            String duplicateKey=bodies.get(id).replaceAll("\\s+"," ").strip();
            String decision=best.score()<0.12?"BELOW_SCORE":seen.contains(duplicateKey)?"DUPLICATE":
                    sectionCounts.getOrDefault(section,0)>=5?"SECTION_LIMIT":selected.size()>=10?"RESULT_LIMIT":"SELECTED";
            decisions.put(id,decision);
            if(decision.equals("SELECTED")) {
                selected.add(new Ranked(best.chunk(),best.score(),best.lexical(),ranks.get(id),decision,best.signals()));
                seen.add(duplicateKey);sectionCounts.merge(section,1,Integer::sum);covered.addAll(best.signals().matchedTerms());
            }
        }
        var finalCandidates=new ArrayList<Ranked>();
        for(var candidate:scored) finalCandidates.add(new Ranked(candidate.chunk(),candidate.score(),candidate.lexical(),ranks.getOrDefault(candidate.chunk().id(),0),
                decisions.getOrDefault(candidate.chunk().id(),candidate.decision()),candidate.signals()));
        return new Result(List.copyOf(selected),List.copyOf(finalCandidates),List.copyOf(expansions),terms.stream().map(QueryTerm::key).toList());
    }

    private static double rrf(int rank) {return rank==0?0:61.0/(60+rank);}
    private static Map<String,Integer> ranks(List<Chunk> chunks,Map<String,Double> scores,double threshold) {
        var result=new HashMap<String,Integer>();
        var sorted=chunks.stream().filter(c -> scores.getOrDefault(c.id(),0.0)>=threshold)
                .sorted(Comparator.comparingDouble((Chunk c) -> scores.get(c.id())).reversed().thenComparing(Chunk::id)).limit(BRANCH_LIMIT).toList();
        for(int i=0;i<sorted.size();i++) result.put(sorted.get(i).id(),i+1);
        return result;
    }

    private static Map<String,Integer> sectionRanks(List<Chunk> chunks,Map<String,String> sections,Map<String,Double> keyword,
                                                    String query,Map<String,String> bodies) {
        var groups=new HashMap<String,List<Chunk>>();chunks.forEach(c -> groups.computeIfAbsent(sections.get(c.id()),k -> new ArrayList<>()).add(c));
        var seeds=chunks.stream().filter(c -> keyword.get(c.id())>0)
                .sorted(Comparator.comparingDouble((Chunk c) -> keyword.get(c.id())*quality(query,c,bodies.get(c.id()))*(1+instructionPrior(query,bodies.get(c.id())))).reversed().thenComparing(Chunk::id)).toList();
        var seen=new HashSet<String>();var result=new LinkedHashMap<String,Integer>();
        for(var seed:seeds) {
            String section=sections.get(seed.id());if(!seen.add(section)) continue;
            for(var chunk:groups.get(section).stream().sorted(Comparator.comparingLong((Chunk c) -> Math.abs((long)c.ordinal()-seed.ordinal())).thenComparing(Chunk::id)).limit(5).toList()) {
                result.putIfAbsent(chunk.id(),result.size()+1);if(result.size()>=40) return result;
            }
        }
        return result;
    }

    static String body(String text) {
        return text.replaceFirst("(?s)^(?:标题：[^\\n]*\\n)?(?:章节：[^\\n]*\\n)?","")
                .replaceAll("(?m)图注：[^\\n]*","").strip();
    }

    private static List<Pair> aliases(List<Chunk> chunks) {
        var pairs=new LinkedHashMap<String,Pair>();var ambiguous=new HashMap<String,Set<String>>();
        for(var chunk:chunks) {
            String text=body(chunk.text());
            for(int direction=0;direction<2;direction++) {
                var matcher=(direction==0?BILINGUAL:REVERSE).matcher(text);
                while(matcher.find()) {
                    String cn=matcher.group(direction==0?1:2),en=matcher.group(direction==0?2:1).strip();
                    if(en.length()<2 || en.matches("(?i).*(https?|www|minecraft:).*")) continue;
                    String key=en.toLowerCase(Locale.ROOT);
                    ambiguous.computeIfAbsent(key,k -> new HashSet<>()).add(cn);
                    pairs.putIfAbsent(cn+"\n"+key,new Pair(cn,en,chunk.documentId(),chunk.id()));
                }
            }
        }
        return pairs.values().stream().filter(p -> ambiguous.get(p.english().toLowerCase(Locale.ROOT)).size()==1)
                .sorted(Comparator.comparingInt((Pair p)->Math.max(p.chinese().length(),p.english().length())).reversed()
                        .thenComparing(Pair::chinese).thenComparing(Pair::english)).toList();
    }

    private static List<QueryTerm> query(String question,List<Pair> pairs,List<AliasExpansion> expansions) {
        String remaining=question;var terms=new LinkedHashMap<String,QueryTerm>();
        for(var pair:pairs) {
            String matched=contains(remaining,pair.english())?pair.english():contains(remaining,pair.chinese())?pair.chinese():null;
            if(matched==null || expansions.size()>=8) continue;
            String equivalent=matched.equals(pair.chinese())?pair.english():pair.chinese();
            String key="alias:"+pair.english().toLowerCase(Locale.ROOT);
            terms.putIfAbsent(key,new QueryTerm(key,List.of(pair.chinese(),pair.english())));
            expansions.add(new AliasExpansion(matched,equivalent,pair.documentId(),pair.chunkId()));
            remaining=remaining.replaceAll("(?iu)"+Pattern.quote(matched)," ");
        }
        remaining=remaining.replaceAll("(?i)\\b(?:how|what|which|is|are|the|a|an|to|do|does|can|i|you|play|use|guide|recommend|recommendation|and|or|in|of|for)\\b"," ");
        for(String term:KnowledgeSearchText.terms(remaining.replaceAll("的|并且|以及|并|和|与|玩法|入门|流程|怎么玩|有什么用"," ")))
            terms.putIfAbsent(term,new QueryTerm(term,List.of(term)));
        return List.copyOf(terms.values());
    }

    private static boolean contains(String text,String term) {
        if(term.matches("[\\p{IsHan}]+")) return text.contains(term);
        return Pattern.compile("(?iu)(?<![a-z0-9_])"+Pattern.quote(term)+"(?![a-z0-9_])").matcher(text).find();
    }

    private static Map<String,Integer> counts(String text,List<QueryTerm> terms) {
        var result=new HashMap<String,Integer>();
        for(var term:terms) {
            int count=0;
            for(String alias:term.alternatives()) {
                int aliasCount=0;
                var matcher=Pattern.compile(alias.matches("[\\p{IsHan}]+")?Pattern.quote(alias):"(?iu)(?<![a-z0-9_])"+Pattern.quote(alias)+"(?![a-z0-9_])").matcher(text);
                while(matcher.find()) aliasCount++;
                count=Math.max(count,aliasCount);
            }
            if(count>0) result.put(term.key(),count);
        }
        return result;
    }

    private static double coverage(Map<String,Integer> counts,List<QueryTerm> terms) {return counts.size()/(double)Math.max(1,terms.size());}

    private static int tokenLength(String text) {
        var words=Pattern.compile("[a-zA-Z0-9_]+|[\\p{IsHan}]+").matcher(text);int length=0;
        while(words.find()) length+=words.group().matches("[\\p{IsHan}]+")?Math.max(1,words.group().length()-1):1;
        return Math.max(1,length);
    }

    private static Map<String,String> sections(List<Chunk> chunks) {
        var sorted=chunks.stream().sorted(Comparator.comparing(Chunk::documentId).thenComparingInt(Chunk::ordinal).thenComparing(Chunk::id)).toList();
        var result=new HashMap<String,String>();String previous="",key="";
        for(var chunk:sorted) {
            String value=chunk.documentId()+"\n"+chunk.heading();
            if(!value.equals(previous)) {key=value+"\n"+chunk.ordinal();previous=value;}
            result.put(chunk.id(),key);
        }
        return result;
    }

    /** 邻近的独立阶段标题仅作排序先验，不推断缺失的父子标题关系。 */
    private static Map<String,Double> phaseProximity(String question,List<Chunk> chunks) {
        var requested=Pattern.compile("(?i)(?<![a-z0-9])t[0-9]+(?![a-z0-9])").matcher(question);
        if(!requested.find()) return Map.of();String phase=requested.group().toLowerCase(Locale.ROOT);
        var sorted=chunks.stream().sorted(Comparator.comparing(Chunk::documentId).thenComparingInt(Chunk::ordinal).thenComparing(Chunk::id)).toList();
        var result=new HashMap<String,Double>();String doc="",active="",heading="";int distance=100;
        for(var chunk:sorted) {
            if(!chunk.documentId().equals(doc)) {doc=chunk.documentId();active="";heading="";distance=100;}
            if(!Objects.equals(heading,chunk.heading())) {
                heading=chunk.heading();var marker=STAGE.matcher(Objects.toString(heading,""));
                if(marker.matches()) {active=marker.group(1).toLowerCase(Locale.ROOT);distance=0;} else distance++;
            }
            if(active.equals(phase) && distance<=8) result.put(chunk.id(),1-distance/9.0);
        }
        return result;
    }

    private static double quality(String query,Chunk chunk,String body) {
        double score="METADATA_ONLY".equals(chunk.method())?0.55:1;
        boolean configuration=query.matches("(?is).*(配置|开关|参数|config|json|toml|注册名|实体列表).*"),
                guide=query.matches("(?is).*(怎么玩|如何玩|入门|玩法|怎么打|如何打|打法|推荐|组装|升级|有什么用|用途|how.*(?:play|craft|upgrade)|beginner|gameplay|recommend).*");
        if(guide && !configuration) {
            var identifiers=IDENTIFIER.matcher(body);int count=0,characters=0;while(identifiers.find()) {count++;characters+=identifiers.end()-identifiers.start();}
            if(count>=3) score*=0.30;
            if(count>=3 && characters>body.length()*0.6) score*=0.15;
            if(Objects.toString(chunk.title(),"").matches("(?is).*(配置|config|翻译).*")) score*=0.60;
            if(body.length()<50) score*=0.60;
            if(body.matches("(?s).*(此处将推荐|此处仅展示|不展开细说|如果有需要可以进行查阅).*") || body.length()<60 && body.contains("详见")) score*=0.55;
            var captions=Pattern.compile("第[0-9]+张图片").matcher(body);int captionCount=0;while(captions.find()) captionCount++;
            if(captionCount>=2) score*=0.30;
        }
        return score;
    }

    private static double instructionPrior(String query,String body) {
        if(query.matches("(?s).*(怎么打|如何打|打法|战斗攻略).*")) {
            int actions=0;for(String term:List.of("需要","使用","不能","远离","避免","免疫","否则","立刻")) if(body.contains(term)) actions++;
            return Math.min(1,actions/4.0);
        }
        if(!query.matches("(?is).*(怎么玩|如何玩|入门|玩法|起步|how.*play|getting started|beginner|gameplay).*")) return 0;
        double result=body.matches("(?s).*(起步|开始|入门|第一[把个次]|最初).*")?0.65:0;
        int steps=0;for(String term:List.of("制作","需要","获得","放入","右击","合成","安装")) if(body.contains(term)) steps++;
        return Math.min(1,result+Math.min(0.35,steps*0.09));
    }

    private static double similarity(Set<String> left,Set<String> right) {
        if(left.isEmpty() || right.isEmpty()) return left.equals(right)?1:0;
        var union=new HashSet<>(left);union.addAll(right);left=new HashSet<>(left);left.retainAll(right);
        return left.size()/(double)union.size();
    }
}
