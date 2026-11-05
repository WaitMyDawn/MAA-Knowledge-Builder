package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 前置关系只来自实际配方输入；替代材料是同一需求的选择，不递归展开整棵原料树。 */
final class RecipeDependencies {
    record Group(String key,String kind,List<String> alternatives,String recipeId) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    static List<Group> capture(DatabaseBuilder db,List<QaPipeline.RecipeMatch> recipes){
        if(RuntimeKnowledgeStore.active(db)==null)return List.of();
        var result=new LinkedHashMap<String,Group>();
        try(var connection=db.getConnection()){
            var tags=RecipeResourceStore.tags(connection);
            for(var recipe:recipes.stream().limit(4).toList()){
                var root=JSON.readTree(recipe.recipeJson());var definition=root.has("definition")?root.path("definition"):root;
                String recipeId=root.path("id").asText("recipe:"+BuildRequest.fingerprint(recipe.recipeJson()));
                try{
                    var plan=RecipePlan.parse(definition.toString());
                    for(var slot:plan.slots()){
                        var ids=new TreeSet<String>();var labels=new ArrayList<String>();
                        for(var alternative:slot.ingredient().alternatives()){
                            labels.add(alternative.label());
                            if(alternative.tag())ids.addAll(RecipeResourceStore.resolveTag(alternative.id(),tags).items());else ids.add(alternative.id());
                        }
                        if(!labels.isEmpty()){
                            boolean constrained=slot.ingredient().stateful()||slot.ingredient().unsupported()!=null;
                            String key=String.join(" | ",new TreeSet<>(labels))+(constrained?" [state:"+BuildRequest.fingerprint(slot.ingredient().identity()).substring(0,8)+"]":"");
                            result.putIfAbsent(key,new Group(key,constrained?"ITEM_STATE":"ITEM",List.copyOf(ids),recipeId));
                        }
                    }
                }catch(IllegalArgumentException unsupported){
                    for(String field:List.of("ingredients","ingredient","input","inputs"))collectFluids(definition.path(field),recipeId,result);
                }
                if(result.size()>32)break;
            }
        }catch(Exception failure){throw new IllegalStateException("Recipe dependency extraction failed",failure);}
        return result.values().stream().limit(32).toList();
    }
    private static void collectFluids(JsonNode node,String recipe,Map<String,Group> groups){
        if(node.isArray())for(var child:node)collectFluids(child,recipe,groups);
        else if(node.isObject()){
            String fluid=node.path("fluid").asText();
            if(fluid.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))groups.putIfAbsent("fluid:"+fluid,new Group("fluid:"+fluid,"FLUID",List.of(fluid),recipe));
            for(String field:List.of("ingredient","fluidStack","content"))if(node.has(field))collectFluids(node.path(field),recipe,groups);
        }
    }
}
