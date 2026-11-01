package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.io.IOException;
import java.util.*;

/** 注册结构、资源模板和声明多方块具有不同证据边界，不能相互冒充世界实例。 */
public final class RuntimeStructures {
    public static final Set<String> CATEGORIES=Set.of("structures","structure_sets","template_pools","structure_processors","structure_templates","multiblocks");
    public static final Set<String> DEFINITIONS=Set.of("structures","structure_sets","template_pools","structure_processors");
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Cell(int x,int y,int z,String block,JsonNode properties,String role){}
    public record Reference(String category,String id){}
    private RuntimeStructures(){}
    static void validate(String category,JsonNode row)throws IOException {
        if(DEFINITIONS.contains(category)){if(!row.path("runtimeLoaded").asBoolean()||!row.path("definition").isObject())throw new IOException("运行时结构定义无效");return;}
        if(category.equals("multiblocks")){resource(row.path("controller"));if(!Set.of("DECLARED","UNSUPPORTED").contains(row.path("status").asText()))throw new IOException("多方块声明状态无效");if(row.path("status").asText().equals("UNSUPPORTED")){if(!row.path("reason").isTextual())throw new IOException("缺少未适配原因");return;}if(!row.path("conditions").isArray()||!row.path("steps").isArray())throw new IOException("多方块条件或步骤缺失");}
        else if(category.equals("structure_templates")){if(!Set.of("COMPLETE","METADATA_ONLY").contains(row.path("geometryStatus").asText())||!row.path("runtimeLoaded").asBoolean())throw new IOException("模板几何状态无效");if(row.path("geometryStatus").asText().equals("METADATA_ONLY")){int[] dimensions=triple(row.path("size"));for(int dimension:dimensions)if(dimension<0||dimension>4096)throw new IOException("模板尺寸无效");if(!row.path("geometryReason").isTextual())throw new IOException("模板几何缺口缺少原因");return;}}
        else throw new IOException("未知结构类别");
        int[] size=triple(row.path("size")),origin=category.equals("multiblocks")?triple(row.path("origin")):new int[3];for(int i=0;i<3;i++)if(size[i]<0||size[i]>4096||origin[i]<0||origin[i]>=Math.max(1,size[i]))throw new IOException("布局尺寸或原点无效");
        var seen=new HashSet<String>();var cells=cells(category,row);if(cells.size()>16384)throw new IOException("布局方块数量超限");for(var cell:cells){resource(JSON.getNodeFactory().textNode(cell.block()));int[] pos={cell.x()+origin[0],cell.y()+origin[1],cell.z()+origin[2]};for(int i=0;i<3;i++)if(pos[i]<0||pos[i]>=size[i])throw new IOException("布局方块超出尺寸");if(!seen.add(Arrays.toString(pos)))throw new IOException("布局坐标重复");}
        if(category.equals("structure_templates")&&row.path("blockCount").asInt(-1)!=cells.size())throw new IOException("模板方块计数不一致");
    }
    private static void resource(JsonNode id)throws IOException{if(!id.isTextual()||id.asText().length()>500||!id.asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IOException("布局注册名无效");}
    static int[] triple(JsonNode array)throws IOException{if(!array.isArray()||array.size()!=3)throw new IOException("三维坐标无效");int[] result=new int[3];for(int i=0;i<3;i++){if(!array.get(i).isIntegralNumber()||!array.get(i).canConvertToInt())throw new IOException("坐标不是整数");result[i]=array.get(i).asInt();}return result;}
    public static List<Cell> cells(String category,JsonNode row)throws IOException {
        var out=new ArrayList<Cell>();if(category.equals("multiblocks")){if(!row.path("cells").isArray())throw new IOException("多方块布局缺失");for(var cell:row.path("cells")){int[] pos=triple(cell.path("pos"));out.add(new Cell(pos[0],pos[1],pos[2],cell.path("block").asText(),cell.path("properties"),cell.path("role").asText()));}}
        else{if(!row.path("blocks").isArray())throw new IOException("模板方块布局缺失");var palettes=row.path("palettes");var palette=!palettes.isEmpty()?palettes.get(0):row.path("palette");for(var cell:row.path("blocks")){int index=cell.path("state").asInt(-1);if(!cell.path("state").isIntegralNumber()||index<0||index>=palette.size())throw new IOException("模板调色板索引无效");for(var alternative:palettes)if(index>=alternative.size())throw new IOException("模板备选调色板索引无效");int[] pos=triple(cell.path("pos"));var state=palette.get(index);out.add(new Cell(pos[0],pos[1],pos[2],state.path("Name").asText(),state.path("Properties"),""));}}return List.copyOf(out);
    }
    public static boolean renderable(String category,JsonNode row){return !row.has("error")&&(category.equals("multiblocks")&&row.path("status").asText().equals("DECLARED")||category.equals("structure_templates")&&row.path("geometryStatus").asText().equals("COMPLETE")&&row.path("blockCount").asInt()>0);}
    public static boolean air(String id){return Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:structure_void").contains(id);}
    static Map<String,Integer> materials(String category,JsonNode row)throws IOException{var result=new TreeMap<String,Integer>();if(!renderable(category,row))return result;for(var cell:cells(category,row))if(!air(cell.block()))result.merge(cell.block(),1,Integer::sum);return result;}
    static String searchText(String category,JsonNode row)throws IOException{if(DEFINITIONS.contains(category))return row.toString();return row.path("id").asText()+" "+row.path("name").asText()+" "+row.path("controller").asText()+" "+row.path("conditions")+" "+row.path("steps")+" "+materials(category,row).keySet()+" "+references(row)+" "+row.path("resource").asText()+" "+row.path("geometryStatus").asText();}
    static String aliases(String category,JsonNode row,Map<String,JsonNode> languages){var names=new LinkedHashSet<String>();if(row.has("name"))names.add(row.path("name").asText());var ids=new TreeSet<String>();try{ids.addAll(materials(category,row).keySet());}catch(IOException ignored){}ids.add(row.path("controller").asText());for(var lang:languages.values())for(String id:ids){String key=id.replace(':','.');for(String prefix:List.of("block.","item.")){String name=lang.path(prefix+key).asText();if(!name.isBlank())names.add(name);}}return String.join("、",names);}
    public static String describe(String category,JsonNode row){if(row.has("error"))return "结构采集失败："+row.path("error").asText();String id=row.path("id").asText();
        if(DEFINITIONS.contains(category))return "结构注册定义 "+category+" · "+id+"\n"+row.path("definition")+"\n仅注册定义；spacing/separation/权重和频率不是玩家遭遇概率，不能据此给出实际坐标。结构通过start_pool、模板池location、处理器等明确引用关联，不从名称猜测模板。Java生成部件和动态事件未被该JSON完整表达。";
        if(category.equals("multiblocks")&&row.path("status").asText().equals("UNSUPPORTED"))return "未适配多方块 "+id+"："+row.path("reason").asText()+"；不能据此猜测布局或认定游戏没有此机制。";
        var out=new StringBuilder((category.equals("multiblocks")?"多方块工作示例 ":"结构资源模板 ")+row.path("name").asText(id)+" · "+id+"\n尺寸 "+row.path("size")+"；原点 "+row.path("origin")+"；布局状态 "+row.path("geometryStatus").asText(row.path("status").asText())+"\n");
        if(row.path("geometryStatus").asText().equals("METADATA_ONLY"))return out.append("仅有元数据，几何未导出：").append(row.path("geometryReason").asText()).toString();
        try{out.append("材料计数（模板默认第1套调色板或已声明工作示例，不是掉落清单，也不保证等于物品消耗量）：\n");int shown=0;for(var entry:materials(category,row).entrySet()){if(++shown>80){out.append("更多材料见完整目录。\n");break;}out.append(entry.getKey()).append(" × ").append(entry.getValue()).append('\n');}}catch(IOException failure){out.append("布局无法解析：").append(failure.getMessage());}
        out.append("朝向：X正向东、Z负向北、Y正向上；多方块坐标相对控制器，模板坐标相对资源原点。空白是未指定单元，不自动等同于必须留空气。\n");
        if(category.equals("multiblocks"))out.append("控制器：").append(row.path("controller").asText()).append("；配方类型 ").append(row.path("recipeType").asText()).append("；属性 ").append(row.path("dragonType").asText()).append("\n组装/运行条件：").append(row.path("conditions")).append("\n操作步骤：").append(row.path("steps"));
        return out.append("\n这是布局声明而非游戏内已经搭建/运行成功的机器；模板不是结构实际位置，处理器、备选调色板、jigsaw与实体NBT会改变放置结果。未声明的多方块需要额外适配，不能据此判定不存在。\n").append(row.path("limitation").asText()).toString();
    }
    static Set<Reference> references(JsonNode row){var refs=new LinkedHashSet<Reference>();walk(row,"",refs);return refs;}
    private static void walk(JsonNode node,String key,Set<Reference> refs){if(node.isObject()){node.fields().forEachRemaining(e->walk(e.getValue(),e.getKey(),refs));return;}if(node.isArray()){node.forEach(child->walk(child,key,refs));return;}if(!node.isTextual()||!node.asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))return;String category=switch(key){case "start_pool","pool","fallback"->"template_pools";case "location"->"structure_templates";case "processors"->"structure_processors";case "structure"->"structures";case "other_set"->"structure_sets";default->null;};if(category!=null)refs.add(new Reference(category,node.asText()));}
    static boolean recipeMatches(JsonNode multiblock,JsonNode recipe,String id){if(!multiblock.path("status").asText().equals("DECLARED"))return false;for(var explicit:multiblock.path("recipeIds"))if(explicit.asText().equals(id))return true;String type=multiblock.path("recipeType").asText();if(type.isBlank()||!type.equals(recipe.path("type").asText()))return false;String field=multiblock.path("recipeVariantField").asText();return field.isBlank()||recipe.path(field).asText().equals(multiblock.path("recipeVariantValue").asText(multiblock.path("dragonType").asText()));}
    static List<RuntimeCatalog.Entry> related(DatabaseBuilder db,String profile,RuntimeCatalog.Entry entry)throws Exception {
        if(!CATEGORIES.contains(entry.category())&&!Set.of("registries","recipes").contains(entry.category()))return List.of();
        var query=new RuntimeStructuredQuery(db,profile);var row=JSON.readTree(entry.json());String id=row.path("id").asText(entry.key());
        var key=new RuntimeStructuredQuery.Key(entry.category(),id);var found=new LinkedHashMap<String,RuntimeCatalog.Entry>();
        for(boolean incoming:List.of(false,true))for(var link:query.related(key,incoming,0,100).links()) {
            var target=incoming?link.from():link.to();
            if(!(CATEGORIES.contains(target.category())||target.category().equals("recipes")))continue;
            for(var record:query.get(target.category(),target.id()).rows())found.put(target.toString(),new RuntimeCatalog.Entry(target.id(),target.category()+" · "+target.id(),target.category(),record.data().toString()));
        }
        return List.copyOf(found.values());
    }
    static List<VectorStore.SearchResult> evidence(DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        if(!question.matches("(?is).*(结构|多方块|布局|搭建|组装|朝向|锻炉|制作|合成|获取|获得|加工|材料|structure|multiblock).*"))return List.of();
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return List.of();var query=new RuntimeStructuredQuery(db,profile);
        var targets=new LinkedHashSet<>(ids);var matcher=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);while(matcher.find())targets.add(matcher.group());
        var selected=new LinkedHashMap<RuntimeStructuredQuery.Key,RuntimeStructuredQuery.Row>();var notes=new LinkedHashSet<String>();
        var recipes=new LinkedHashMap<String,JsonNode>();
        for(String target:targets.stream().limit(20).toList()) {
            for(String category:CATEGORIES.stream().sorted().toList()) {
                var page=query.get(category,target);notes.addAll(page.warnings());
                for(var row:page.rows()) {
                    selected.put(row.key(),row);
                    var expansion=query.expand(row.key(),4,12);notes.addAll(expansion.warnings());
                    for(var related:expansion.rows())if(CATEGORIES.contains(related.key().category()))selected.put(related.key(),related);
                }
            }
            for(var link:query.related(new RuntimeStructuredQuery.Key("registries",target),false,"CONTROLLER",0,20).links())
                for(var row:query.get(link.to().category(),link.to().id()).rows())selected.put(row.key(),row);
            for(var index:query.get("acquisition_index",target).rows()) {
                for(var source:index.data().path("sources")) {
                    if(recipes.size()>=100){notes.add("配方关联达到100条预算，未穷尽所有加工关系。");break;}
                    if(!source.path("category").asText().equals("recipes"))continue;
                    for(var recipe:query.get("recipes",source.path("recordId").asText()).rows())if(!recipe.data().has("error"))recipes.put(recipe.key().id(),recipe.data().path("definition"));
                }
            }
        }
        var machines=query.page("multiblocks",null,RuntimeStructuredQuery.State.any(),0,100);if(machines.nextOffset()>=0)notes.add("多方块候选超过100条，当前配方匹配未覆盖后续候选。");
        for(var machine:machines.rows())if(recipes.entrySet().stream().anyMatch(recipe->recipeMatches(machine.data(),recipe.getValue(),recipe.getKey())))selected.put(machine.key(),machine);
        var out=new ArrayList<VectorStore.SearchResult>();
        if(selected.size()>12)notes.add("结构证据超过12条预算，当前展示不是完整关联全集。");
        for(var row:selected.values().stream().limit(12).toList()) {
            if(row.data().has("error"))continue;
            String text=describe(row.key().category(),row.data());
            if(row.key().category().equals("multiblocks"))for(var recipe:recipes.entrySet())if(recipeMatches(row.data(),recipe.getValue(),recipe.getKey()))text+="\n关联运行时配方（动态结果仍需核对原始条件）："+recipe.getKey()+" "+recipe.getValue();
            out.add(query.evidence(row,text+"\n"+String.join("\n",notes)));
        }
        return List.copyOf(out);
    }
}
