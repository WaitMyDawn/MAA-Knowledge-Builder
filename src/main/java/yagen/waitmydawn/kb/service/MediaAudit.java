package yagen.waitmydawn.kb.service;
import java.util.*;
import java.nio.file.Path;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

/** 审计描述选择依据和资产可用性，不将元数据匹配等同于像素语义正确。 */
public final class MediaAudit {
 private MediaAudit(){}
 public record EntityCandidate(String registry,String stateKey,String decision,String reason,String imageRef,String stateLabel){}
 public record EntityTrace(String profile,int scanned,int requested,int selected,boolean truncated,List<EntityCandidate> candidates,Map<String,String> requestedAxes){}
 static final class Trail {
  final String profile;int scanned,requested,selected;boolean truncated;final Map<String,EntityCandidate> rows=new LinkedHashMap<>();
  final Map<String,String> requestedAxes=new LinkedHashMap<>();
  Trail(String profile){this(profile,"");}
  Trail(String profile,String question){this.profile=profile;var matcher=java.util.regex.Pattern.compile("\\b(variant|sex|growth_stage|age|profession|biome|tamed|charged|sheared|color)=([a-zA-Z0-9_:/.-]+)").matcher(question);while(matcher.find())requestedAxes.put(matcher.group(1),matcher.group(2));}
  void record(com.fasterxml.jackson.databind.JsonNode row,String decision,String reason){String id=row.path("id").asText(),state=row.path("stateKey").asText("default"),key=id+"|"+state;if(!rows.containsKey(key)&&rows.size()>=512){truncated=true;if(!decision.equals("SELECTED"))return;var evict=rows.entrySet().stream().filter(e->!e.getValue().decision().equals("SELECTED")).map(Map.Entry::getKey).findFirst();if(evict.isEmpty())return;rows.remove(evict.get());}rows.put(key,new EntityCandidate(id,state,decision,reason,row.path("imageRef").asText(null),EntityAppearanceState.label(row)));}
  EntityTrace snapshot(){return new EntityTrace(profile,scanned,requested,selected,truncated,List.copyOf(rows.values()),Map.copyOf(requestedAxes));}
 }
 public record PictureCheck(String imageId,String role,String assetId,String sourceId,List<String> chunkIds,boolean displayed,boolean available,String provenance,String context){}
 public record Report(int schemaVersion,EntityTrace entities,List<PictureCheck> pictures,AnswerMedia.Assessment availability,ImageGroundingAssessment.Result documentGrounding){}
 public static Report capture(String question,String answer,AnswerArtifacts artifacts,Path data,EntityTrace entities){
  var used=new HashSet<String>();var matcher=java.util.regex.Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)").matcher(AnswerMedia.referenceText(answer));while(matcher.find())used.add(matcher.group(1));
  var sources=new HashSet<String>();artifacts.sources().forEach(s->sources.add(s.id()));var checks=new ArrayList<PictureCheck>();
  for(var p:artifacts.pictures()){boolean available;try{AnswerMedia.resolveImage(data,p);available=true;}catch(java.io.IOException|IllegalArgumentException missing){available=false;}
   String basis="ENTITY".equals(p.role())?"ENTITY_STATE_METADATA":"RECIPE".equals(p.role())?"RECIPE_METADATA":sources.contains(p.sourceId())?"PROVIDED_DOCUMENT_SOURCE":"UNBOUND";
   String context=Objects.toString(p.context(),"");checks.add(new PictureCheck(p.id(),p.role(),p.assetId(),p.sourceId(),p.chunkIds(),used.contains(p.id()),available,basis,context.substring(0,Math.min(1200,context.length()))));
  }return new Report(1,entities,List.copyOf(checks),AnswerMedia.assess(answer,artifacts,data),ImageGroundingAssessment.assess(question,answer,artifacts));
 }
}
