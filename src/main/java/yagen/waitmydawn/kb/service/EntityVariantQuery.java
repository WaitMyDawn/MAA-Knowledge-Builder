package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 查询根据声明轴域限定值；未知组合不回落到另一职业、品种或充能状态。 */
final class EntityVariantQuery {
 private EntityVariantQuery(){}
 static Boolean matches(JsonNode row,String question){String q=question.toLowerCase(Locale.ROOT);var axes=row.path("state").path("traits").path("axes");var domains=row.path("variantCoverage").path("axes").deepCopy();
  if(!domains.isObject())domains=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
  var traits=row.path("state").path("traits");for(var pair:List.of(new String[]{"sex","sex"},new String[]{"growth_stage","growthStage"}))if(!domains.has(pair[0])&&traits.has(pair[1]))((com.fasterxml.jackson.databind.node.ObjectNode)domains).putArray(pair[0]).addObject().put("id",traits.path(pair[1]).asText()).put("label",traits.path(pair[1]).asText());
  if(q.matches("(?s).*(充能|闪电苦力怕|带电).*" )&&!domains.has("charged"))return false;
  if(q.contains("驯服")&&!domains.has("tamed"))return false;
  if(q.contains("剪毛")&&!domains.has("sheared"))return false;
  if(q.matches("(?s).*(牧师|农民|图书管理员|渔夫|盔甲匠|屠夫|制图师|牧羊人|武器匠|工具匠|皮匠|制箭师|石匠).*" )&&!domains.has("profession"))return false;
  if(q.matches("(?s).*(雌性|雄性|母龙|公龙).*" )&&!domains.has("sex"))return false;
  if(q.matches("(?s).*(一阶|二阶|三阶|四阶|五阶|[1-5]阶|第[1-5]阶段).*" )&&!domains.has("growth_stage"))return false;
  var explicit=java.util.regex.Pattern.compile("\\b(variant|profession|biome|level|charged|tamed|sheared|color|age|sex|growth_stage)=").matcher(q);while(explicit.find())if(!domains.has(explicit.group(1)))return false;
var fields=domains.fields();boolean requested=false;
  while(fields.hasNext()){var axis=fields.next();String wanted=null;for(var option:axis.getValue()){String value=option.path("id").asText(),label=option.path("label").asText().toLowerCase(Locale.ROOT);if(q.contains(axis.getKey()+"="+value.toLowerCase(Locale.ROOT))||label.length()>1&&!label.equals(value)&&q.contains(label))wanted=value;}
   if(axis.getKey().equals("charged")&&q.matches("(?s).*(充能|闪电苦力怕|带电).*"))wanted="true";
   if(axis.getKey().equals("charged")&&q.matches("(?s).*(未充能|普通苦力怕).*"))wanted="false";
   if(axis.getKey().equals("tamed")&&q.contains("驯服"))wanted=q.contains("未驯服")?"false":"true";
   if(axis.getKey().equals("sheared")&&q.contains("剪毛"))wanted=q.contains("未剪毛")?"false":"true";
   if(axis.getKey().equals("profession")){String[][] names={{"农民","farmer"},{"牧师","cleric"},{"图书管理员","librarian"},{"渔夫","fisherman"},{"盔甲匠","armorer"},{"屠夫","butcher"},{"制图师","cartographer"},{"牧羊人","shepherd"},{"武器匠","weaponsmith"},{"工具匠","toolsmith"},{"皮匠","leatherworker"},{"制箭师","fletcher"},{"石匠","mason"},{"傻子","nitwit"},{"失业","none"}};for(var name:names)if(q.contains(name[0]))wanted="minecraft:"+name[1];}
   if(axis.getKey().equals("biome")){String[][] names={{"沙漠","desert"},{"丛林","jungle"},{"平原","plains"},{"热带草原","savanna"},{"雪地","snow"},{"沼泽","swamp"},{"针叶林","taiga"}};for(var name:names)if(q.contains(name[0]))wanted="minecraft:"+name[1];}
   if(axis.getKey().equals("level")){for(int level=1;level<=5;level++)if(q.contains(level+"级"))wanted=Integer.toString(level);if(q.contains("大师"))wanted="5";}
   if(axis.getKey().equals("variant")&&row.path("id").asText().equals("minecraft:parrot")){String[][] names={{"红蓝","red_blue"},{"蓝色","blue"},{"绿色","green"},{"黄蓝","yellow_blue"},{"灰色","gray"}};for(var name:names)if(q.contains(name[0]))wanted=name[1];}
   if(axis.getKey().equals("variant")&&row.path("id").asText().equals("minecraft:cat")){String[][] names={{"虎斑","tabby"},{"狸花","tabby"},{"暹罗","siamese"},{"布偶","ragdoll"},{"波斯","persian"},{"英国短毛","british_shorthair"},{"三花","calico"},{"白猫","white"},{"纯黑","all_black"}};for(var name:names)if(q.contains(name[0]))wanted="minecraft:"+name[1];}
   if(axis.getKey().equals("sex")){if(q.matches("(?s).*(雌性|母龙).*"))wanted="female";if(q.matches("(?s).*(雄性|公龙).*"))wanted="male";}
   if(axis.getKey().equals("growth_stage")){String[] stages={"一阶","二阶","三阶","四阶","五阶"};for(int i=0;i<5;i++)if(q.contains(stages[i])||q.contains((i+1)+"阶")||q.contains("第"+(i+1)+"阶段"))wanted=Integer.toString(i+1);}
   if(wanted!=null){requested=true;String actual=axes.path(axis.getKey()).asText();if(actual.isBlank()&&axis.getKey().equals("sex"))actual=row.path("state").path("traits").path("sex").asText();if(actual.isBlank()&&axis.getKey().equals("growth_stage"))actual=row.path("state").path("traits").path("growthStage").asText();if(!actual.equals(wanted))return false;}
  }return requested?Boolean.TRUE:null;
 }
}
