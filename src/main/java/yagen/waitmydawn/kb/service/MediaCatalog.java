package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 名称仅来自当前快照的注册表和语言记录；同名歧义交由请求规划器显式处理。 */
final class MediaCatalog {
 static List<MediaRequestPlanner.Subject> load(DatabaseBuilder db,String profile){
  var json=new ObjectMapper();var translations=new ArrayList<JsonNode>();var names=new LinkedHashMap<String,LinkedHashSet<String>>();var kinds=new HashMap<String,Set<String>>();
  try(var c=db.getConnection()){
   try(var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='language'")){p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next())translations.add(json.readTree(rows.getString(1)).path("translations"));}}
   try(var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='registries' ORDER BY record_id")){p.setString(1,profile);try(var rows=p.executeQuery()){while(rows.next()){var r=json.readTree(rows.getString(1));String id=r.path("id").asText();String kind=r.path("registry").asText().equals("entity_type")?"ENTITY":"ITEM";kinds.computeIfAbsent(id,k->new HashSet<>()).add(kind);var aliases=names.computeIfAbsent(id,k->new LinkedHashSet<>());aliases.add(r.path("displayName").asText());String key=r.path("descriptionId").asText();for(var t:translations)if(t.has(key))aliases.add(t.path(key).asText());aliases.add(id);}}}
  }catch(Exception failure){throw new IllegalStateException("快照配图名称读取失败",failure);}
  return names.entrySet().stream().map(e->new MediaRequestPlanner.Subject(e.getKey(),kinds.get(e.getKey()).size()>1?"BOTH":kinds.get(e.getKey()).iterator().next(),e.getValue().stream().filter(s->!s.isBlank()).toList())).toList();
 }
}
