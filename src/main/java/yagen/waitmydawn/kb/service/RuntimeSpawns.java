package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 配置入口与实际生成事件分开表达，空的静态池不能排除脚本生成或召唤。 */
public final class RuntimeSpawns {
    public static final String CATEGORY="spawn_rules";
    private static final ObjectMapper JSON=new ObjectMapper();
    private RuntimeSpawns(){}
    static void validate(JsonNode row)throws IOException {
        if(!row.path("coverage").asText().equals("ENUMERABLE_CONFIGURATION_ONLY")||!row.path("mobCategory").isTextual())throw new IOException("生成规则覆盖声明无效");
        var placement=row.path("placement");if(!Set.of("REGISTERED","NOT_REGISTERED","UNAVAILABLE").contains(placement.path("status").asText()))throw new IOException("生成放置状态无效");
        if(placement.path("status").asText().equals("REGISTERED")&&(!placement.path("type").isTextual()||!placement.path("heightmap").isTextual()||!placement.path("conditionCoverage").asText().equals("OPAQUE_CODE")))throw new IOException("生成放置条件缺失");
        for(String collection:List.of("biomeSpawns","structureSpawns")){
            var entries=row.path(collection);if(!entries.isArray()||entries.size()>100000)throw new IOException("生成池列表无效");var seen=new HashSet<String>();
            for(var entry:entries){String owner=collection.equals("biomeSpawns")?"biome":"structure";resource(entry.path(owner));if(!entry.path("category").isTextual()||!entry.path("entryIndex").canConvertToInt()||entry.path("entryIndex").asInt()<0||!seen.add(entry.path(owner).asText()+"/"+entry.path("category").asText()+"/"+entry.path("entryIndex")))throw new IOException("生成池条目身份无效");
                for(String field:List.of("weight","poolWeight","minCount","maxCount"))if(!entry.path(field).isIntegralNumber()||!entry.path(field).canConvertToLong()||entry.path(field).asLong()<0)throw new IOException("生成池数值无效："+field);
                if(entry.path("poolWeight").asLong()<entry.path("weight").asLong()||entry.path("maxCount").asLong()<entry.path("minCount").asLong())throw new IOException("生成权重或数量范围无效");
                if(collection.equals("biomeSpawns")){ids(entry.path("referencedByLoadedDimensions"));finite(entry.path("creatureGenerationProbability"));if(entry.has("spawnCost")){finite(entry.path("spawnCost").path("energyBudget"));finite(entry.path("spawnCost").path("charge"));}}
                else{if(!Set.of("PIECE","STRUCTURE").contains(entry.path("boundingBox").asText())||!entry.path("relation").asText().equals("STRUCTURE_POOL_OVERRIDE"))throw new IOException("结构生成覆盖范围无效");ids(entry.path("allowedBiomes"));}
            }
        }
    }
    private static void resource(JsonNode value)throws IOException{if(!value.isTextual()||value.asText().length()>500||!value.asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IOException("生成关系注册名无效");}
    private static void ids(JsonNode values)throws IOException{if(!values.isArray()||values.size()>100000)throw new IOException("生成关联列表无效");for(var value:values)resource(value);}
    private static void finite(JsonNode value)throws IOException{if(!value.isNumber()||!Double.isFinite(value.asDouble()))throw new IOException("生成成本或群系概率无效");}
    static String aliases(JsonNode row,Map<String,JsonNode> languages){var names=new LinkedHashSet<String>();String id=row.path("id").asText();for(var language:languages.values()){String name=language.path("entity."+id.replace(':','.')).asText();if(!name.isBlank())names.add(name);for(var entry:row.path("biomeSpawns")){name=language.path("biome."+entry.path("biome").asText().replace(':','.')).asText();if(!name.isBlank())names.add(name);}}return String.join("、",names);}
    public static String describe(JsonNode row){
        if(row.has("error"))return "生成规则采集失败："+row.path("error").asText();
        var out=new StringBuilder("实体生成配置 "+row.path("id").asText()+"；类别 "+row.path("mobCategory").asText()+"\n仅可枚举配置；权重不是实际遭遇概率；未列出不表示不存在；动态事件、召唤与代码谓词未穷举。\n");
        out.append("生成放置：").append(row.path("placement")).append("。REGISTERED只表示注册了放置规则；NOT_REGISTERED不等于无法生成；UNAVAILABLE表示导出无法读取。条件谓词是代码入口，未执行或解析光照、方块、难度与阶段判定。\n");
        for(var entry:row.path("biomeSpawns"))out.append("群系 ").append(entry.path("biome").asText()).append("；池 ").append(entry.path("category").asText()).append("；条目 ").append(entry.path("entryIndex")).append("；权重 ").append(entry.path("weight")).append(" / 池总权重 ").append(entry.path("poolWeight")).append("；数量 ").append(entry.path("minCount")).append('–').append(entry.path("maxCount")).append("；已加载维度生成器引用 ").append(entry.path("referencedByLoadedDimensions")).append("；群系区块生成生物参数 ").append(entry.path("creatureGenerationProbability")).append("；生成成本 ").append(entry.path("spawnCost")).append('\n');
        for(var entry:row.path("structureSpawns"))out.append("结构生成池覆盖 ").append(entry.path("structure").asText()).append("；范围 ").append(entry.path("boundingBox").asText()).append("；池 ").append(entry.path("category").asText()).append("；权重 ").append(entry.path("weight")).append(" / ").append(entry.path("poolWeight")).append("；数量 ").append(entry.path("minCount")).append('–').append(entry.path("maxCount")).append("；结构允许群系 ").append(entry.path("allowedBiomes")).append('\n');
        return out.append("权重不是玩家实际遭遇概率；数量范围不是每区块保证数量。维度列表仅表示已加载维度的生成器引用群系，不证明特定地点或其他维度无法生成；结构池覆盖受边界/生成算法影响，不是保证刷怪，也不是召唤配方。未列出不表示不存在；动态事件、特殊生成器、召唤与结构部件代码未穷举，世界刷怪笼NBT也不属于此配置枚举。\n").toString();
    }
    static boolean requested(String question){return question.matches("(?is).*(生成|刷怪|刷出|出现|哪里|在哪|遇到|召唤|spawn|biome).*" );}
    static boolean configurationOnly(String question){return requested(question)&&question.matches("(?is).*(群系|生成池|生成配置|生成权重|生成数量|自然生成|哪里生成|在哪.{0,6}生成|biome).*" )&&!question.matches("(?is).*(召唤|打法|怎么打|攻略|制作|教程|条件|光照|亮度|结构|阶段|难度|掉落|交易|合成|获得|获取|用途|驯服|繁殖|属性|攻击|血量|推荐|怎么|如何|command|summon).*" );}
    static boolean covered(Collection<String> ids,List<VectorStore.SearchResult> evidence){return !ids.isEmpty()&&ids.stream().allMatch(id->evidence.stream().anyMatch(hit->hit.sourcePath().endsWith("/spawn_rules/"+id)&&hit.chunkText().contains("\n群系 ")&&!hit.chunkText().contains("[生成规则证据预览已截断]")&&!hit.chunkText().contains("[生成配置部分覆盖]")));}
    static String preview(String text){return text.length()<=20000?text:text.substring(0,20000)+"\n[生成规则证据预览已截断] 不能据此前缀断言完整群系列表；完整记录见所选快照的生成目录。";}
    static List<VectorStore.SearchResult> evidence(DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        if(!requested(question))return List.of();String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return List.of();var targets=new LinkedHashSet<>(ids);var matcher=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);while(matcher.find())targets.add(matcher.group());var result=new ArrayList<VectorStore.SearchResult>();boolean complete=false;
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT descriptor FROM rag_runtime_snapshot WHERE profile_id=?")){p.setString(1,profile);try(var rows=p.executeQuery()){if(rows.next())complete=JSON.readTree(rows.getString(1)).path("categories").path("spawn_rules").path("status").asText().equals("COMPLETE");}}
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='spawn_rules' AND record_id=?")){for(String id:targets.stream().limit(20).toList()){p.setString(1,profile);p.setString(2,id);try(var rows=p.executeQuery()){if(rows.next()){var row=JSON.readTree(rows.getString(1));if(row.has("error"))continue;String text=(complete?"":"[生成配置部分覆盖] 本快照的生成类别存在采集或读取缺口，不能将已列出的池视为完整列表。\n")+describe(row);result.add(new VectorStore.SearchResult(yagen.waitmydawn.kb.model.BuildRequest.fingerprint(text),preview(text),RuntimeRules.namespace(id),id,"runtime_spawn_rules",1,"mkb-runtime:"+profile+"/spawn_rules/"+id));}}}}return List.copyOf(result);
    }
}
