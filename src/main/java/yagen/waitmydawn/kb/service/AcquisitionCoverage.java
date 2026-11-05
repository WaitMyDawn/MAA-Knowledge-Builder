package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 获取来源先覆盖不同渠道，再翻页展开；一个渠道的多条配方不能挤掉掉落和容器入口。 */
final class AcquisitionCoverage {
    private final Map<String,Set<String>> required=new LinkedHashMap<>(),visited=new LinkedHashMap<>();
    private final Map<String,Set<String>> offeredRecords=new LinkedHashMap<>(),readRecords=new LinkedHashMap<>();
    private final Map<String,Set<String>> unresolved=new LinkedHashMap<>();
    static List<JsonNode> interleave(List<JsonNode> sources){
        var groups=new LinkedHashMap<String,ArrayDeque<JsonNode>>();
        for(var source:sources)groups.computeIfAbsent(String.join("+",channels(source)),ignored->new ArrayDeque<>()).add(source);
        var result=new ArrayList<JsonNode>();boolean added;
        do{added=false;for(var group:groups.values())if(!group.isEmpty()){result.add(group.remove());added=true;}}while(added);
        return result;
    }
    private static Set<String> channels(JsonNode source){
        String category=source.path("category").asText();var result=new TreeSet<String>();
        if(category.equals("loot_tables"))for(var entry:source.path("entrypoints"))if(!entry.path("sourceKind").asText().isBlank())result.add(category+"/"+entry.path("sourceKind").asText());
        if(result.isEmpty())result.add(category);return result;
    }
    void offered(String id,List<JsonNode> sources){var categories=required.computeIfAbsent(id,ignored->new LinkedHashSet<>());for(var source:sources){categories.addAll(channels(source));offeredRecords.computeIfAbsent(id,ignored->new LinkedHashSet<>()).add(source.path("category").asText()+"/"+source.path("recordId").asText());}}
    void read(String id,JsonNode source){visited.computeIfAbsent(id,ignored->new HashSet<>()).addAll(channels(source));readRecords.computeIfAbsent(id,ignored->new LinkedHashSet<>()).add(source.path("category").asText()+"/"+source.path("recordId").asText());}
    void unresolved(String id,JsonNode source){if(id==null||id.isBlank())return;String record=source.path("recordId").asText("");if(record.isBlank())record=source.path("category").asText("unknown");unresolved.computeIfAbsent(id,ignored->new LinkedHashSet<>()).add(source.path("category").asText("unknown")+"/"+record);}
    List<String> unresolved(){var result=new ArrayList<String>();unresolved.forEach((id,sources)->sources.forEach(source->result.add(id+" / "+source)));return List.copyOf(result);}
    void resolvedAll(){unresolved.clear();}
    List<Map<String,Object>> records(){var rows=new ArrayList<Map<String,Object>>();offeredRecords.forEach((id,sources)->{var unread=new LinkedHashSet<>(sources);unread.removeAll(readRecords.getOrDefault(id,Set.of()));rows.add(Map.of("subject",id,"total",sources.size(),"read",sources.size()-unread.size(),"unread",unread.stream().limit(20).toList(),"allStoredSourcesRead",unread.isEmpty()));});return rows;}
    List<String> missing(){var result=new ArrayList<String>();required.forEach((id,categories)->{for(String category:categories)if(!visited.getOrDefault(id,Set.of()).contains(category))result.add(id+" / "+category);});return List.copyOf(result);}
}
