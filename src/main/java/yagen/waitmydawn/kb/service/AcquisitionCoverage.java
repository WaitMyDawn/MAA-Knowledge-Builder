package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 获取来源先覆盖不同渠道，再翻页展开；一个渠道的多条配方不能挤掉掉落和容器入口。 */
final class AcquisitionCoverage {
    private final Map<String,Set<String>> required=new LinkedHashMap<>(),visited=new LinkedHashMap<>();
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
    void offered(String id,List<JsonNode> sources){var categories=required.computeIfAbsent(id,ignored->new LinkedHashSet<>());for(var source:sources)categories.addAll(channels(source));}
    void read(String id,JsonNode source){visited.computeIfAbsent(id,ignored->new HashSet<>()).addAll(channels(source));}
    List<String> missing(){var result=new ArrayList<String>();required.forEach((id,categories)->{for(String category:categories)if(!visited.getOrDefault(id,Set.of()).contains(category))result.add(id+" / "+category);});return List.copyOf(result);}
}
