package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.*;

/** 目录归属先于名称检索；目录缺口与行为属性不通过注册名或排除其他分类推断。 */
final class FamilyDiscovery {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final DatabaseBuilder db;
    private final String profile;
    private record Category(String id,String parent,String url,int top,String name,List<String> path,int directMembers){}
    FamilyDiscovery(DatabaseBuilder db,String profile){this.db=db;this.profile=profile;}

    ObjectNode find(String mod,String kind,String query,List<String> selected,String predicate,String runtimeCategory,int offset,int limit)throws Exception{
        return find(mod,kind,query,selected,List.of(),predicate,runtimeCategory,offset,limit);
    }
    ObjectNode find(String mod,String kind,String query,List<String> selected,List<String> excluded,String predicate,String runtimeCategory,int offset,int limit)throws Exception{
        if(mod==null||!mod.matches("[a-z0-9_.-]+")||!Set.of("","ITEM","ENTITY").contains(kind)||offset<0||limit<1||limit>40||selected.size()>8||excluded.size()>8)throw new IllegalArgumentException("Invalid family scope or page");
        var categories=categories(mod,kind);var byId=new LinkedHashMap<String,Category>();categories.forEach(category->byId.put(category.id(),category));
        var result=JSON.createObjectNode().put("mod",mod).put("query",query).put("predicate",predicate);
        result.put("membershipScope","Stored source catalog, not all game objects or proof of installation");
        result.put("catalogAvailable",!categories.isEmpty());result.put("mechanicsVerified",false).put("additionalPredicateVerified",false);
        var roots=new LinkedHashSet<>(selected);
        if(!roots.isEmpty()&&!runtimeCategory.isBlank())throw new IllegalArgumentException("Select catalog categories or a runtime category, not both");
        if(roots.isEmpty()&&runtimeCategory.isBlank()&&!query.isBlank()){
            var exact=categories.stream().filter(category->normalize(category.name()).equals(normalize(query))).toList();
            if(exact.size()==1)roots.add(exact.getFirst().id());
        }
        for(String id:excluded)if(!byId.containsKey(id))throw new IllegalArgumentException("Excluded category is outside requested mod/kind: "+id);
        var excludedClosure=new LinkedHashSet<>(excluded);boolean excludedChanged;
        do{excludedChanged=false;for(var category:categories)if(excludedClosure.contains(category.parent()))excludedChanged|=excludedClosure.add(category.id());}while(excludedChanged);
        if(!roots.isEmpty()){
            for(String id:roots)if(!byId.containsKey(id))throw new IllegalArgumentException("Category is outside requested mod/kind: "+id);
            var included=new LinkedHashSet<>(roots);boolean changed;
            do{changed=false;for(var category:categories)if(included.contains(category.parent()))changed|=included.add(category.id());}while(changed);
            included.removeAll(excludedClosure);
            var memberships=new TreeMap<String,List<WebCatalogStore.Entry>>();var store=new WebCatalogStore(db);
            for(int top:categories.stream().filter(category->included.contains(category.id())).map(Category::top).distinct().toList())
                for(var member:store.entries(mod,top))if(included.contains(member.categoryId()))memberships.computeIfAbsent(member.url(),ignored->new ArrayList<>()).add(member);
            boolean exactSubject=query.isBlank()||roots.stream().allMatch(id->byId.get(id).path().stream().anyMatch(label->normalize(label).equals(normalize(query))));
            boolean propertyCheck=!predicate.isBlank()||!exactSubject;
            result.put("status","CATALOG_MEMBERS").put("basis","CATALOG_MEMBERSHIP").put("additionalPredicateVerified",!propertyCheck).put("predicateStatus",propertyCheck?"PENDING":"NOT_REQUESTED");
            var selectedNodes=result.putArray("selectedCategories");for(String id:roots)selectedNodes.add(categoryJson(byId.get(id)));
            var excludedNodes=result.putArray("excludedCategories");for(String id:excluded)excludedNodes.add(categoryJson(byId.get(id)));
            var members=result.putArray("members");int cursor=0;
            for(var group:memberships.entrySet()){
                if(cursor++<offset||members.size()>=limit)continue;
                var member=group.getValue().getFirst();var row=members.addObject().put("url",member.url()).put("name",member.chineseName()).put("englishName",member.englishName()).put("label",member.label()).put("role",member.role());
                row.put("verification",propertyCheck?"PENDING_REQUESTED_PREDICATE":"CATALOG_MEMBERSHIP_ONLY");
                row.put("membershipCount",group.getValue().size());var provenance=row.putArray("memberships");for(var entry:group.getValue().stream().limit(10).toList())provenance.add(categoryJson(byId.get(entry.categoryId())));
                if(group.getValue().size()>10)row.put("membershipMetadataTruncated",true);
                int top=member.topCategory();String runtimeKind=kind.isBlank()?top==WikiScraperService.McmodCategory.ITEM.getId()?"ITEM":top==WikiScraperService.McmodCategory.ENTITY.getId()?"ENTITY":"":kind;
                mapRuntime(row,mod,runtimeKind,member);
                if(result.toString().length()>22000&&members.size()>1){members.remove(members.size()-1);result.put("responseBudgetLimited",true);break;}
            }
            page(result,memberships.size(),offset,members.size());
            result.put("guidance",propertyCheck?"Selected categories supply candidates, not proof of the requested predicate. Read member pages and verify each claim; keep unverified members separate. Do not infer a property by subtracting another category.":"Membership is recorded by this catalog. Read member pages or runtime rules for acquisition, behavior and version applicability; do not infer shared mechanics from membership.");
            return result;
        }
        var options=result.putArray("categoryChoices");
        var ordered=new ArrayList<>(categories);ordered.sort(Comparator.comparingDouble((Category category)->KnowledgeSearchText.overlap(query,String.join(" > ",category.path()))).reversed().thenComparing(category->String.join(" > ",category.path())).thenComparing(Category::id));
        ordered.stream().skip(offset).limit(limit).forEach(category->options.add(categoryJson(category)));
        while(result.toString().length()>14000&&options.size()>1){options.remove(options.size()-1);result.put("responseBudgetLimited",true);}
        result.put("categoryTotal",categories.size()).put("categoryNextOffset",offset+options.size()<categories.size()?offset+options.size():-1);
        var pools=runtimePools(mod,kind);var runtimeOptions=result.putArray("runtimeCategoryChoices");pools.forEach((name,ids)->runtimeOptions.addObject().put("category",name).put("count",ids.size()).put("basis","SPAWN_POOL_CATEGORY"));
        if(!runtimeCategory.isBlank()){
            if(!pools.containsKey(runtimeCategory))throw new IllegalArgumentException("Unknown runtime category; select an advertised category");
            var ids=pools.get(runtimeCategory);var members=result.putArray("members");
            ids.stream().skip(offset).limit(limit).forEach(id->members.addObject().put("id",id).put("basis","SPAWN_POOL_CATEGORY").put("verification","PENDING_REQUESTED_PREDICATE"));
            result.put("status","STRUCTURED_CANDIDATES").put("runtimeCategory",runtimeCategory).put("additionalPredicateVerified",false);
            result.put("basis","SPAWN_POOL_CATEGORY").put("membershipScope","Spawn-pool entries in the selected snapshot; other spawn mechanisms are not covered");
            page(result,ids.size(),offset,members.size());
        }else result.put("status",categories.isEmpty()?"CATALOG_MISSING":"CATEGORY_SELECTION_REQUIRED");
        result.put("guidance","Select catalog category ids using their full paths; matching category names are not required to appear in member names. If the catalog cannot establish the requested family, use available runtime categories as candidates or discover relevant source pages. Missing categories and empty results do not prove nonexistence; predicates need per-member evidence.");
        result.set("fallbackTools",JSON.valueToTree(List.of("enumerate_entities","list_objects","search_documents","discover_sources","supplement_web")));
        return result;
    }
    private List<Category> categories(String mod,String kind)throws Exception{
        var result=new ArrayList<Category>();String sql="SELECT c.*, (SELECT COUNT(*) FROM rag_web_category_member m WHERE m.category_id=c.category_id) AS members FROM rag_web_category c WHERE c.mod_id=? ORDER BY c.category_id";
        try(var connection=db.getConnection();var statement=connection.prepareStatement(sql)){
            statement.setString(1,mod);try(var rows=statement.executeQuery()){while(rows.next()){
                int top=rows.getInt("top_category");if(kind.equals("ITEM")&&top!=WikiScraperService.McmodCategory.ITEM.getId()||kind.equals("ENTITY")&&top!=WikiScraperService.McmodCategory.ENTITY.getId())continue;
                result.add(new Category(rows.getString("category_id"),rows.getString("parent_id"),rows.getString("category_url"),top,rows.getString("name"),List.of(JSON.readValue(rows.getString("path_json"),String[].class)),rows.getInt("members")));
            }}
        }return result;
    }
    private ObjectNode categoryJson(Category category){var node=JSON.createObjectNode().put("id",category.id()).put("name",category.name()).put("url",category.url()).put("topCategory",category.top()).put("directMembers",category.directMembers());node.set("path",JSON.valueToTree(category.path()));return node;}
    private void mapRuntime(ObjectNode row,String mod,String kind,WebCatalogStore.Entry member)throws Exception{
        if(profile==null){row.put("runtimeMapping","NO_ACTIVE_SNAPSHOT");return;}
        var names=new RuntimeNameIndex(db,profile);var found=new LinkedHashMap<String,RuntimeNameIndex.Candidate>();
        for(String name:List.of(Objects.toString(member.chineseName(),""),Objects.toString(member.englishName(),"")))if(!name.isBlank())
            for(var candidate:names.resolve(name,mod,kind.isBlank()?null:kind).candidates())found.putIfAbsent(candidate.registry(),candidate);
        row.put("runtimeMapping",found.isEmpty()?"NO_EXACT_NAME_MATCH":found.size()==1?"EXACT_NAME_CANDIDATE":"AMBIGUOUS_NAME_CANDIDATES");
        row.put("runtimeCandidateCount",found.size());var candidates=row.putArray("runtimeCandidates");for(var candidate:found.values().stream().limit(8).toList())candidates.addObject().put("id",candidate.registry()).put("kind",candidate.kind()).put("name",candidate.name()).put("basis","EXACT_LOCALIZED_NAME");
        if(found.size()>8)row.put("runtimeMappingTruncated",true);
    }
    private SortedMap<String,SortedSet<String>> runtimePools(String mod,String kind)throws Exception{
        var result=new TreeMap<String,SortedSet<String>>();if(profile==null||kind.equals("ITEM"))return result;
        try(var connection=db.getConnection();var statement=connection.prepareStatement("SELECT record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category='spawn_rules' AND record_id LIKE ? ESCAPE '!' ORDER BY record_id")){
            statement.setString(1,profile);statement.setString(2,mod.replace("!","!!").replace("_","!_").replace("%","!%")+":%");
            try(var rows=statement.executeQuery()){while(rows.next()){var data=JSON.readTree(rows.getString(2));if(data.has("error"))continue;var categories=new LinkedHashSet<String>();for(String field:List.of("biomeSpawns","spawns","structureSpawns"))spawnCategories(data.path(field),categories);for(String category:categories)result.computeIfAbsent(category,ignored->new TreeSet<>()).add(rows.getString(1));}}
        }return result;
    }
    private static void spawnCategories(JsonNode node,Set<String> categories){if(node.isArray())for(var child:node)spawnCategories(child,categories);else if(node.isObject()){if(node.path("category").isTextual()&&!node.path("category").asText().isBlank())categories.add(node.path("category").asText());node.elements().forEachRemaining(child->spawnCategories(child,categories));}}
    private static String normalize(String value){return KnowledgeSearchText.normalize(Objects.toString(value,""));}
    private static void page(ObjectNode result,int total,int offset,int returned){result.put("total",total).put("offset",offset).put("returned",returned).put("nextOffset",offset+returned<total?offset+returned:-1).put("allStoredMembersReturned",offset==0&&returned==total);}
}
