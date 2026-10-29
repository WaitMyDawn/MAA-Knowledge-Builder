package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import yagen.waitmydawn.kb.model.BuildRequest;
import java.io.IOException;
import java.util.*;

/** 外观键跨导出器和桌面一致；旧版无状态记录映射为 default。 */
final class EntityAppearanceState {
    static String key(JsonNode row)throws IOException {
        if(!row.has("state")){if(row.has("stateKey")&&!row.path("stateKey").asText().equals("default"))throw new IOException("缺少外观状态");return "default";}
        var state=row.path("state");String origin=state.path("origin").asText();
        if(!state.isObject()||!state.path("traits").isObject()||!Set.of("DEFAULT_INSTANCE","SPAWN_INITIALIZED","OBSERVED_INSTANCE","DECLARED_STATE").contains(origin))throw new IOException("外观状态结构无效");
        String canonical=canonical(state).replace("\u2028","\\u2028").replace("\u2029","\\u2029");if(canonical.length()>65536)throw new IOException("外观状态超过预算");
        String key=origin.equals("DEFAULT_INSTANCE")&&state.path("traits").isEmpty()&&state.size()==2?"default":BuildRequest.fingerprint(canonical);
        if(!key.equals(row.path("stateKey").asText()))throw new IOException("外观状态键不匹配");return key;
    }
    private static String canonical(JsonNode node) {
        if(node.isObject()){var parts=new ArrayList<String>();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);for(String name:names)parts.add(com.fasterxml.jackson.databind.node.TextNode.valueOf(name).toString()+":"+canonical(node.path(name)));return "{"+String.join(",",parts)+"}";}
        if(node.isArray()){var parts=new ArrayList<String>();for(var child:node)parts.add(canonical(child));return "["+String.join(",",parts)+"]";}
        if(node.isNumber())return node.decimalValue().stripTrailingZeros().toPlainString();return node.toString();
    }
    static int preference(JsonNode row){if(!row.path("representative").asBoolean(false)&&row.path("state").path("origin").asText().equals("DECLARED_STATE")){int differences=2;var fields=row.path("state").path("traits").path("axes").fields();while(fields.hasNext()){var axis=fields.next();var domain=row.path("variantCoverage").path("axes").path(axis.getKey());if(domain.isArray()&&!domain.isEmpty()&&!domain.get(0).path("id").asText().equals(axis.getValue().asText()))differences++;}return differences;}return row.path("representative").asBoolean(false)?(row.path("state").path("origin").asText().equals("OBSERVED_INSTANCE")?-1:0):row.path("stateKey").asText("default").equals("default")?1:2;}
    static String path(JsonNode row)throws IOException {String base="entities/"+row.path("id").asText().replace(':','/');String key=key(row);return key.equals("default")?base+".png":base+"/states/"+key+".png";}
    static String readablePath(JsonNode row)throws IOException {
        String key=key(row);if(key.equals("default"))return path(row);
        String prefix=switch(row.path("state").path("origin").asText()){case "SPAWN_INITIALIZED"->"spawn";case "OBSERVED_INSTANCE"->"observed";case "DECLARED_STATE"->"declared";default->"default";};
        var traits=row.path("state").path("traits");String age=traits.path("age").asText();if(Set.of("adult","baby").contains(age))prefix+="_"+age;
        String color=traits.path("color").asText();if(color.matches("[a-z_]{1,20}"))prefix+="_"+color;
        if(traits.path("equipment").isObject())prefix+=traits.path("equipment").isEmpty()?"_unequipped":"_equipped";
        return path(row).replace(key+".png",prefix+"_"+key.substring(0,16)+".png");
    }
    static String flatBase(JsonNode row)throws IOException {
        String key=key(row);if(key.equals("default"))return path(row).replaceFirst("\\.png$","");String readable=readablePath(row);return path(row).substring(0,path(row).lastIndexOf("/states/"))+"_"+readable.substring(readable.lastIndexOf("/states/")+8).replaceFirst("_[0-9a-f]{16}\\.png$","");
    }
    static boolean flatCandidate(JsonNode row)throws IOException {
        String file=row.path("file").asText(),base=flatBase(row);if(file.equals(base+".png"))return true;if(!file.startsWith(base+"_")||!file.endsWith(".png"))return false;String number=file.substring(base.length()+1,file.length()-4);try{int n=Integer.parseInt(number);return n>=2&&Integer.toString(n).equals(number);}catch(NumberFormatException invalid){return false;}
    }
    static Map<String,String> flatPaths(JsonNode records)throws IOException {
        var rows=new TreeMap<String,JsonNode>();for(var row:records)if(!row.has("error")&&!row.has("imageRef")&&row.has("file"))rows.put(row.path("id").asText()+"|"+key(row),row);
        var result=new HashMap<String,String>();var used=new HashSet<String>();for(var entry:rows.entrySet())if(entry.getKey().endsWith("|default")){String file=path(entry.getValue());used.add(file);result.put(entry.getKey(),file);}
        for(var entry:rows.entrySet())if(!result.containsKey(entry.getKey())){String base=flatBase(entry.getValue()),file=base+".png";int number=2;while(!used.add(file))file=base+"_"+number+++".png";result.put(entry.getKey(),file);}for(var row:records)if(row.has("imageRef")){String id=row.path("id").asText(),owner=result.get(id+"|"+row.path("imageRef").asText());if(owner==null)throw new IOException("缺失图片引用目标");result.put(id+"|"+key(row),owner);}return result;
    }
    static String label(JsonNode row) {
        var traits=row.path("state").path("traits");if(traits.isMissingNode()||traits.isEmpty())return "默认实体外观";
        var parts=new ArrayList<String>();String origin=row.path("state").path("origin").asText();parts.add(origin.equals("OBSERVED_INSTANCE")?"实际实体捕获":origin.equals("SPAWN_INITIALIZED")?(traits.path("initialization").asText().equals("SAFE_DETACHED_SETUP")?"安全准备样本":"生成初始化样本"):"声明的外观状态");
        String age=traits.path("age").asText();if(age.equals("baby"))parts.add("幼年");else if(age.equals("adult"))parts.add("成年");else if(age.equals("juvenile"))parts.add("成长期");if(traits.has("growthStage"))parts.add("成长阶段："+traits.path("growthStage").asInt());if(traits.has("sex"))parts.add(traits.path("sex").asText().equals("male")?"雄性":"雌性");
        if(traits.has("variant"))parts.add(traits.path("variant").asText());if(traits.has("color"))parts.add("颜色："+traits.path("color").asText());
        var fields=traits.path("axes").fields();while(fields.hasNext()){var axis=fields.next();String label=axis.getValue().asText();for(var option:row.path("variantCoverage").path("axes").path(axis.getKey()))if(option.path("id").asText().equals(label)){label=option.path("label").asText(label);break;}parts.add(axis.getKey()+"="+label);}
        if(traits.path("equipment").isObject())parts.add(traits.path("equipment").isEmpty()?"无槽位装备":"含装备快照");return String.join("；",parts);
    }
    private static String stateQuestion(String question){return question.replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+"," ");}
    static boolean specific(String question) {
        question=stateQuestion(question);return question.matches("(?s).*(雌性|雄性|母龙|公龙|[1-5]阶|第[1-5]阶段|一阶|二阶|三阶|四阶|五阶|成长阶段|充能|闪电苦力怕|带电|驯服|品种|职业|牧师|农民|图书管理员|渔夫|盔甲匠|屠夫|制图师|牧羊人|武器匠|工具匠|皮匠|制箭师|石匠|默认实例|安全准备样本|初始化样本|生成初始化|实际捕获|实际实体捕获|幼年|成年|幼体|宝宝|穿着|穿戴|戴着|手持|装备|持有|裸装|空手|变种|红色|蓝色|绿色|白色|黑色|黄色|紫色|棕色|橙色|粉色|灰色|青色).*" )
                ||java.util.regex.Pattern.compile("\\b(baby|adult|red|blue|green|white|black|yellow|purple|brown|orange|pink|gray|cyan|lime|magenta)\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(question).find();
    }
    static boolean equipmentMatches(JsonNode row,Set<String> requested) {
        var actual=new HashSet<String>();for(var slot:row.path("state").path("traits").path("equipment")) {
            String id=slot.isTextual()?slot.asText():slot.path("item").asText(slot.path("id").asText());if(!id.isBlank())actual.add(id);
        }
        return requested.stream().filter(id->!id.equals(row.path("id").asText())).allMatch(actual::contains);
    }
    static boolean matches(JsonNode row,String question)throws IOException {
        Boolean axes=EntityVariantQuery.matches(row,question);if(Boolean.FALSE.equals(axes))return false;
        if(!specific(question)&&axes==null)return key(row).equals("default")||row.path("representative").asBoolean(false);
        String q=stateQuestion(question).toLowerCase(Locale.ROOT);var traits=row.path("state").path("traits");boolean matched=Boolean.TRUE.equals(axes);
        String origin=row.path("state").path("origin").asText("DEFAULT_INSTANCE");
        if(q.contains("默认实例")){if(!origin.equals("DEFAULT_INSTANCE"))return false;matched=true;}
        if(q.contains("安全准备样本")||q.contains("初始化样本")||q.contains("生成初始化")){if(!origin.equals("SPAWN_INITIALIZED"))return false;matched=true;}
        if(q.contains("实际捕获")||q.contains("实际实体捕获")){if(!origin.equals("OBSERVED_INSTANCE"))return false;matched=true;}

        if(q.matches("(?s).*(幼年|幼体|宝宝|baby).*")){if(!traits.path("age").asText().equals("baby"))return false;matched=true;}
        if(q.matches("(?s).*(成年|adult).*")){if(!traits.path("age").asText().equals("adult"))return false;matched=true;}
        String[][] compoundColors={{"light_blue","浅蓝色","淡蓝色","light blue","light_blue"},{"light_gray","浅灰色","淡灰色","light gray","light grey","light_gray"},{"lime","黄绿色","酸橙色","lime"},{"magenta","品红色","magenta"}};
        for(String[] color:compoundColors)for(int i=1;i<color.length;i++)if(q.contains(color[i])){if(!traits.path("color").asText().equals(color[0]))return false;matched=true;for(int j=1;j<color.length;j++)q=q.replace(color[j]," ");break;}
        String[] colors={"red:红色","blue:蓝色","green:绿色","white:白色","black:黑色","yellow:黄色","purple:紫色","brown:棕色","orange:橙色","pink:粉色","gray:灰色","cyan:青色"};
        for(String color:colors){if(axes!=null&&!traits.has("color"))break;String[] names=color.split(":");if(q.contains(names[1])||java.util.regex.Pattern.compile("\\b"+names[0]+"\\b").matcher(q).find()){if(!traits.path("color").asText().equals(names[0]))return false;matched=true;}}
        if(q.contains("空手")){if(!traits.path("equipment").isObject()||traits.path("equipment").has("mainhand")||traits.path("equipment").has("offhand"))return false;matched=true;}
        else if(q.matches("(?s).*(无装备|没有装备|未装备|裸装|没穿).*")){if(!traits.has("equipment")||!traits.path("equipment").isObject()||!traits.path("equipment").isEmpty())return false;matched=true;}
        else if(q.matches("(?s).*(穿着|穿戴|戴着|装备|持有|手持).*")){if(!traits.path("equipment").isObject()||traits.path("equipment").isEmpty())return false;matched=true;}
        if(q.contains("变种")&&axes==null){String variant=traits.path("variant").asText();if(variant.isBlank()||!q.contains(variant.toLowerCase(Locale.ROOT)))return false;matched=true;}
        return matched;
    }
}
