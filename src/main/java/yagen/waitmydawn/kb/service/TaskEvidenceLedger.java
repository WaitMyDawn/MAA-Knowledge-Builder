package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 每个对象的需求独立登记；读取、原文定位和结构化规则证明具有不同状态。 */
final class TaskEvidenceLedger {
    record Key(String subject,String requirement) {}
    record Entry(String subject,String requirement,String state,List<String> sources,String quote) {}
    private final Map<Key,String> patterns=new LinkedHashMap<>();
    private final Map<Key,LinkedHashSet<String>> reads=new LinkedHashMap<>();
    private final Map<Key,String> quotes=new HashMap<>();
    private final Set<Key> supported=new HashSet<>();
    private final Map<String,Set<String>> aliases=new HashMap<>();
    void require(String subject,String requirement,String pattern){patterns.putIfAbsent(new Key(subject,requirement),pattern);}
    void alias(String subject,String label){if(label!=null&&!label.isBlank())aliases.computeIfAbsent(subject,ignored->new HashSet<>()).add(label);}
    void read(String subject,String requirement,String source){
        var key=new Key(subject,requirement);if(patterns.containsKey(key))reads.computeIfAbsent(key,ignored->new LinkedHashSet<>()).add(source);
    }
    void rule(String subject,String requirement,String source){read(subject,requirement,source);var key=new Key(subject,requirement);if(patterns.containsKey(key))supported.add(key);}
    void document(String subject,String requirement,VectorStore.SearchResult source,String quote){
        var key=new Key(subject,requirement);String pattern=patterns.get(key);
        if(pattern==null)throw new IllegalArgumentException("Unknown subject/requirement; use query_task_status");
        if(source.type().startsWith("structured_")||source.type().equals("category_catalog"))throw new IllegalArgumentException("Catalog membership does not document actions");
        String text=normalize(source.chunkText()),excerpt=normalize(quote);
        if(excerpt.length()<8||excerpt.length()>800||!text.contains(excerpt))throw new IllegalArgumentException("Quote must be an exact bounded excerpt of the returned source");
        if(!Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(TaskEvidence.positiveText(quote)).find())throw new IllegalArgumentException("Excerpt does not support the requested requirement");
        var names=aliases.getOrDefault(subject,Set.of());
        if(!names.isEmpty()&&names.stream().noneMatch(name->text.contains(normalize(name))))throw new IllegalArgumentException("Source does not identify the required subject");
        read(subject,requirement,source.chunkId());quotes.put(key,quote);supported.add(key);
    }
    List<Entry> entries(){return patterns.keySet().stream().map(key->new Entry(key.subject(),key.requirement(),supported.contains(key)?quotes.containsKey(key)?"DOCUMENTED_EXCERPT":"STRUCTURED_RULE":reads.containsKey(key)?"READ_UNCONFIRMED":"PENDING",List.copyOf(reads.getOrDefault(key,new LinkedHashSet<>())),quotes.get(key))).toList();}
    List<String> missing(){return patterns.keySet().stream().filter(key->!supported.contains(key)).map(key->key.subject()+" / "+key.requirement()).toList();}
    boolean has(String subject,String requirement){return patterns.containsKey(new Key(subject,requirement));}
    private static String normalize(String value){return Objects.toString(value,"").replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
}
