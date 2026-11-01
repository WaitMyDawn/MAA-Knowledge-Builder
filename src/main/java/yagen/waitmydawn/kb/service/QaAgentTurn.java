package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 单轮工具执行状态固定资料范围和快照，网络能力不向任意生成 URL 开放。 */
final class QaAgentTurn implements AgentToolLoop.Executor {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final DatabaseBuilder db;private final VectorStore vectors;private final KnowledgeRetrievalService knowledge;private final UrlAgent urls;private final QaPipeline.QaResult result;private final String question,selected,previous,profile;private final Runnable media;
    private final KnowledgeRetrievalService.Resolution scope;
    private final Map<String,EntityAgent.AliasHit> allowed=new LinkedHashMap<>();
    private KnowledgeRetrievalService.Retrieval retrieval;
    private final Map<String,EntityAgent.ResolvedEntity> focus=new LinkedHashMap<>();
    private final Set<String> structuredFacets=new HashSet<>();
    private final Map<String,List<WikiScraperService.SearchCandidate>> discoveries=new LinkedHashMap<>();
    private boolean searched,prepared;private int pages,discoveryNudges;private final Map<String,String> downloaded=new HashMap<>();
    String clarification;boolean imagesRequested;boolean inheritImages;String rawQuestion;
    /** 最近一次未唯一解析的名称与种类，用于区分“同族对象”和真正重名。 */
    private String lastResolutionName,lastResolutionKind,lastResolutionNamespace;
    /** 同一命名空间、同一显示名的多条记录是家族变体，不是重名歧义。 */
    static boolean isVariantFamily(List<RuntimeNameIndex.Candidate> candidates){
        if(candidates==null||candidates.size()<2)return false;
        if(commonNamespace(candidates)==null)return false;
        String first=RuntimeNameIndex.normalize(candidates.getFirst().name());
        for(var candidate:candidates)if(!RuntimeNameIndex.normalize(candidate.name()).equals(first))return false;
        return true;
    }
    static String commonNamespace(List<RuntimeNameIndex.Candidate> candidates){
        if(candidates==null||candidates.size()<2)return null;
        String namespace=null;
        for(var candidate:candidates){String id=candidate.registry();int colon=id.indexOf(':');if(colon<=0)return null;String current=id.substring(0,colon);if(namespace==null)namespace=current;else if(!namespace.equals(current))return null;}
        return namespace;
    }
    QaAgentTurn(DatabaseBuilder db,VectorStore vectors,KnowledgeRetrievalService knowledge,UrlAgent urls,QaPipeline.QaResult result,String question,String selected,String previous,Runnable media){this.db=db;this.vectors=vectors;this.knowledge=knowledge;this.urls=urls;this.result=result;this.question=question;this.selected=selected;this.previous=previous;this.media=media;profile=RuntimeKnowledgeStore.active(db);scope=knowledge.resolve(question,selected,previous);if(scope.warning()!=null)clarification=scope.warning();result.mediaAxes=EntityAppearanceState.requestedAxes(question);}
    private String text(JsonNode args,String field){var value=args.path(field);if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>4096)throw new IllegalArgumentException("Missing/invalid "+field);return value.asText();}
    private int offset(JsonNode args){int value=args.path("offset").asInt(0);if(value<0)throw new IllegalArgumentException("Invalid offset");return value;}
    private int limit(JsonNode args){return limit(args,2);}
    /** 实体图标是可枚举的轻量视图，允许一页 20 条；其余规则分页仍限制 1–2 条。 */
    private int limit(JsonNode args,int maximum){int value=args.has("limit")?args.path("limit").asInt(1):1;if(value<1||value>maximum)throw new IllegalArgumentException("limit must be 1.."+maximum);return value;}
    private RuntimeStructuredQuery runtime(){if(profile==null)throw new IllegalArgumentException("CAPABILITY_MISSING: no selected snapshot");if(scope.documentId()!=null||scope.scope()!=null&&"MODPACK".equals(scope.scope().kind()))throw new IllegalArgumentException("Selected document scope excludes runtime evidence");return new RuntimeStructuredQuery(db,profile);}
    @Override public String execute(String tool,JsonNode args)throws Exception {
        BuildCancellation.check();if(clarification!=null&&!tool.equals("resolve_objects"))return JSON.writeValueAsString(Map.of("status","CLARIFICATION_REQUIRED","message",clarification));
        if(!result.conversationTargets.isEmpty()&&Set.of("query_attributes","query_images","query_runtime").contains(tool)&&args.has("id")&&(tool.equals("query_images")||tool.equals("query_attributes")||Set.of("entity_attributes","entity_state_attributes","entity_icons").contains(args.path("category").asText()))&&!result.conversationTargets.contains(args.path("id").asText()))throw new IllegalArgumentException("Object is outside the current follow-up focus: "+result.conversationTargets);
        if(tool.equals("resolve_objects")){
            String name=text(args,"name");if(profile!=null&&name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")){var byId=new RuntimeNameIndex(db,profile);String localized=byId.nameOf(name);if(localized!=null)return JSON.writeValueAsString(Map.of("status","RESOLVED_BY_ID","id",name,"name",localized,"translationKey",Objects.toString(byId.translationKeyOf(name),""),"note","中文名以本地化语言表为准，请使用该名称，不要自行改写。"));}
            String kind=args.path("kind").asText(null);if(kind!=null)kind=switch(kind.toLowerCase(Locale.ROOT)){case "entities","entity_type"->"ENTITY";case "items"->"ITEM";case "potion"->"potions";case "effect"->"effects";case "enchantment"->"enchantments";case "mod"->"mod_metadata";default->kind;};
            var names=profile==null?null:new RuntimeNameIndex(db,profile);var found=names==null?new RuntimeNameIndex.Resolution("NOT_FOUND",List.of()):names.resolve(name,args.path("mod").asText(null),kind);
            if(names!=null&&!found.status().equals("UNIQUE")) {
                var full=names.mentions(question,kind).stream().filter(match->match.status().equals("UNIQUE")&&match.candidates().stream().anyMatch(candidate->(!args.has("mod")||candidate.registry().startsWith(args.path("mod").asText()+":"))&&RuntimeNameIndex.normalize(candidate.name()).contains(RuntimeNameIndex.normalize(name)))).toList();
                if(full.size()==1)found=full.getFirst();
            }
            if(found.status().equals("NOT_FOUND")&&names!=null){var family=names.list(args.path("mod").asText(null),kind,name,0,20);if(!family.isEmpty())found=new RuntimeNameIndex.Resolution("FAMILY_CANDIDATES",family);}
            if(!found.status().equals("UNIQUE")){lastResolutionName=name;lastResolutionKind=kind;lastResolutionNamespace=commonNamespace(found.candidates());}
            if(!found.status().equals("UNIQUE")&&isVariantFamily(found.candidates())){
                var variants=JSON.createArrayNode();int attached=0;var entities=new ArrayList<>(result.resolvedEntities);
                for(var candidate:found.candidates()){variants.addObject().put("id",candidate.registry()).put("name",candidate.name());
                    if(attached>=8)continue;attached++;focus.putIfAbsent(candidate.registry(),new EntityAgent.ResolvedEntity(candidate.registry(),RuntimeRules.namespace(candidate.registry()),1,"family-focus"));result.mediaTargets.add(candidate.registry());if(entities.stream().noneMatch(entity->entity.registry().equals(candidate.registry())))entities.add(new EntityAgent.ResolvedEntity(candidate.registry(),RuntimeRules.namespace(candidate.registry()),1,"family-name-index"));}
                result.resolvedEntities=entities;
                return JSON.writeValueAsString(Map.of("status","FAMILY_BY_VARIANT","family",name,"variants",variants,
                        "guidance","同一家族的不同变体，不是需要澄清的重名：先说明共有机制（获取方式、用途、制作），再按变体列出差异；变体已加入本轮对象集合，可直接用 query_images 取图。",
                        "nextTools","query_acquisition for the shared mechanism, then query_images for the family."));
            }
            if(found.status().equals("UNIQUE")){var candidate=found.candidates().getFirst();focus.putIfAbsent(candidate.registry(),new EntityAgent.ResolvedEntity(candidate.registry(),RuntimeRules.namespace(candidate.registry()),1,"tool-focus"));result.mediaTargets.add(candidate.registry());var entities=new ArrayList<>(result.resolvedEntities);if(entities.stream().noneMatch(entity->entity.registry().equals(candidate.registry())))entities.add(new EntityAgent.ResolvedEntity(candidate.registry(),RuntimeRules.namespace(candidate.registry()),1,"tool-name-index"));result.resolvedEntities=entities;}
            return JSON.writeValueAsString(Map.of("resolution",found,"suggestions",found.candidates().isEmpty()&&names!=null?names.suggest(name,args.path("mod").asText(null),kind):List.of(),"webCandidates",candidates(name),"nextTools","For appearance or requested images use query_images directly; it checks exact-state availability. Attribute records do not establish image availability. For facts choose the corresponding rule tool."));
        }
        if(tool.equals("search_documents")){
            String query=text(args,"query");searched=true;retrieval=knowledge.retrieve(query,scope,false);
            var hits=new ArrayList<>(retrieval.hits());var local=urls.fetchAndRetrieve(List.of(),List.of(),List.of(query));if(!documentScope())for(var sub:local.subQueryResults())hits.addAll(sub.chunks().stream().filter(this::applicable).toList());hits.removeIf(hit->!applicable(hit));addHits(hits.stream().limit(10).toList());merge(retrieval.artifacts(),false);
            return JSON.writeValueAsString(Map.of("status",hits.isEmpty()?"NOT_FOUND":"FOUND","hits",hits.stream().limit(6).toList(),"webCandidates",candidates(query)));
        }
        if(tool.equals("discover_sources"))return discover(args);
        if(tool.equals("query_environment")){var snapshot=RuntimeEvidencePolicy.snapshot(db);if(snapshot==null)return "{\"status\":\"CAPABILITY_MISSING\"}";String value=JSON.writeValueAsString(Map.of("snapshot",snapshot,"installedMembershipComplete",snapshot.membershipComplete(),"websiteImportIsInstallation",false));addHits(List.of(new VectorStore.SearchResult("environment:"+profile,value,null,null,"structured_environment",1,"mkb-runtime:"+profile+"/environment")));return value;}
        if(tool.equals("list_objects")){int size=args.has("limit")?limit(args,100):args.has("query")?20:args.has("mod")?100:20;var objects=new RuntimeNameIndex(db,profile).list(args.path("mod").asText(null),args.path("kind").asText(null),args.path("query").asText(null),offset(args),size);var rows=JSON.createArrayNode();for(var object:objects){var entry=rows.addObject();entry.put("id",object.registry());if(!object.name().isBlank())entry.put("name",object.name());}return JSON.writeValueAsString(Map.of("objects",rows,"returned",objects.size(),"nextOffset",objects.size()==size?offset(args)+size:-1,"filterHint","Registry ids are stable: filter by id or name substring, or list one mod once with a larger limit, then batch-read or batch-fetch images."));}
        if(tool.equals("query_objects")){var ids=JSON.readTree(text(args,"ids_json"));if(!ids.isArray()||ids.isEmpty()||ids.size()>8)throw new IllegalArgumentException("ids_json requires 1..8 registered ids");var records=JSON.createArrayNode();var query=runtime();String category=text(args,"category");if(!Set.of("entity_attributes","item_attributes","registries").contains(category))throw new IllegalArgumentException("Unsupported bulk category");focus.clear();result.mediaTargets.clear();for(var idNode:ids){String object=idNode.asText();RecipePlan.resourceId(object);if(!result.conversationTargets.isEmpty()&&!result.conversationTargets.contains(object))throw new IllegalArgumentException("Object is outside current follow-up focus");focus.put(object,new EntityAgent.ResolvedEntity(object,RuntimeRules.namespace(object),1,"object-batch"));result.mediaTargets.add(object);var page=category.equals("registries")?query.page(category,object,RuntimeStructuredQuery.State.any(),0,2):query.get(category,object);for(var row:page.rows())records.add(category.equals("entity_attributes")&&!args.has("section")?attributeProjection(query,row):recordView(query,row,args.path("section").asText("")));}return JSON.writeValueAsString(Map.of("records",records));}
        if(tool.equals("request_clarification")){
            String question=text(args,"question");
            if(lastResolutionName!=null&&lastResolutionNamespace!=null){
                var family=names().list(lastResolutionNamespace,lastResolutionKind,lastResolutionName,0,20);
                if(family.size()>=2){var members=JSON.createArrayNode();for(var candidate:family)members.addObject().put("id",candidate.registry()).put("name",candidate.name());
                    return JSON.writeValueAsString(Map.of("status","FAMILY_NOT_AMBIGUOUS","family",lastResolutionName,"members",members,"guidance","这些是同模组同一家族的对象，不是需要澄清的重名：先回答共有机制或按类别说明差异，再列出各成员；确有必要才澄清具体成员。"));}
            }
            clarification=question;return JSON.writeValueAsString(java.util.Map.of("status","CLARIFICATION_REQUIRED","question",clarification));
        }
        if(tool.equals("query_task_status"))return coverageContext();
        if(tool.equals("supplement_web")){
            if(localOnly()||question.matches("(?s).*(不(?:用|要|必).*联网|不要访问网页|仅查本地|仅用本地|离线).*"))throw new IllegalArgumentException("This turn is restricted to local evidence");
            if(!searched)throw new IllegalArgumentException("Search existing documents first");String gap=text(args,"gap"),url=WebUrlPolicy.normalize(text(args,"url"));if(downloaded.containsKey(url))return downloaded.get(url);
            if(pages>=4)throw new IllegalArgumentException("Web page budget exhausted");if(!allowed.containsKey(url))throw new IllegalArgumentException("URL must be a returned candidate");if(scope.documentId()!=null)throw new IllegalArgumentException("Cannot expand a single document scope");
            pages++;try(var cause=RetrievalTelemetry.resourceCause("AGENT_EVIDENCE_GAP")){var fetched=urls.fetchAndRetrieve(List.of(allowed.get(url)),List.of(),List.of(question));var hits=fetched.subQueryResults().stream().flatMap(sub->sub.chunks().stream()).distinct().limit(10).toList();addHits(hits);retrieval=knowledge.retrieve(question,scope,false);merge(retrieval.artifacts(),false);String response=JSON.writeValueAsString(Map.of("status",hits.isEmpty()?"NO_USABLE_EVIDENCE":"FOUND","gap",gap,"hits",hits));downloaded.put(url,response);return response;}
        }
        if(tool.equals("query_images")){
            text(args,"need");
            var targets=new ArrayList<String>();
            if(args.has("ids_json")){var ids=JSON.readTree(text(args,"ids_json"));if(!ids.isArray()||ids.isEmpty()||ids.size()>8)throw new IllegalArgumentException("ids_json requires 1..8 registered ids");for(var node:ids)targets.add(node.asText());}
            else if(args.has("id"))targets.add(text(args,"id"));
            if(!targets.isEmpty()){
                for(String target:targets){RecipePlan.resourceId(target);if(!focus.containsKey(target)&&result.resolvedEntities.stream().noneMatch(object->object.registry().equals(target))&&!registered(target))throw new IllegalArgumentException("Resolve the image object first");}
                result.mediaTargets.clear();result.mediaTargets.addAll(targets);
                String stateKey=args.path("state_key").asText("").trim();
                result.mediaStateKey=targets.size()==1&&stateKey.matches("[0-9a-fA-F]{8,64}")?stateKey:null;
                String state=args.path("state").asText("");
                result.mediaQuestion=String.join(" ",targets)+" "+(state.isBlank()?String.join(" ",MediaRequestPlanner.selectors(question).values()):state)+" 展示实体图片";
                prepared=false;result.runtimeMediaPrepared=false;
            }
            if(result.vectorResults.isEmpty()&&result.recipeResults.isEmpty()&&result.resolvedEntities.isEmpty())throw new IllegalArgumentException("Retrieve evidence or resolve objects first");
            imagesRequested=true;if(retrieval!=null&&!retrieval.hits().isEmpty())try(var cause=RetrievalTelemetry.resourceCause("AGENT_SELECTED_IMAGES")){retrieval=new KnowledgeRetrievalService.Retrieval(result.vectorResults,result.artifacts,retrieval.evidence(),retrieval.bestLexical(),retrieval.diagnostics());retrieval=knowledge.hydrate(retrieval,question);merge(retrieval.artifacts(),true);}
            if(!prepared){media.run();prepared=true;}return JSON.writeValueAsString(Map.of("status",result.artifacts.pictures().isEmpty()?"NO_MATCHING_IMAGES":"READY","pictures",result.artifacts.pictures(),"missing",result.mediaWarnings,"selectionPolicy","Only images bound to retrieved evidence and relevant context; a requested state_key without an exported image is reported as missing, never replaced by another state."));
        }
        var query=runtime();String id=args.path("id").asText();
        if(tool.equals("query_brewing"))return brewing(query,text(args,"id"),args);
        if(tool.equals("query_recipes")||tool.equals("query_acquisition")){
            id=text(args,"id");var index=query.get("acquisition_index",id);var values=JSON.createArrayNode();int count=0;
            var sources=new ArrayList<JsonNode>();for(var row:index.rows())for(var source:row.data().path("sources"))if((!tool.equals("query_recipes")||source.path("category").asText().equals("recipes"))&&RuntimeStructuredQuery.supports(source.path("category").asText()))sources.add(source);
            int pageSize=tool.equals("query_acquisition")?limit(args,20):limit(args);var categories=new TreeMap<String,Integer>();for(var source:sources)categories.merge(source.path("category").asText("unknown"),1,Integer::sum);
            for(var source:sources.stream().skip(offset(args)).limit(pageSize).toList()){String category=source.path("category").asText();for(var record:query.get(category,source.path("recordId").asText()).rows()){
                    var view=recordView(query,record,args.path("section").asText(""));
                    if(Set.of("trades","loot_tables").contains(category)){
                        var matched=JSON.createArrayNode();collectMatching(record.data(),id,matched);
                        if(!matched.isEmpty()){view.set("matchedEntries",matched);view.put("matchedNote","与目标物品直接相关的条目；同池其他候选未展开，原池概率与抽取次数仍以池级字段为准。");addHits(List.of(query.evidence(record,"目标条目：\n"+matched.toPrettyString())));}
                    }
                    values.add(view);
                }count++;}
            if(tool.equals("query_recipes"))imagesRequested=true;
            boolean acquisitionAsked=question.matches("(?s).*(获取|获得|途径|来源|哪里).*");
            for(var value:values)if(value.has("data")&&!value.path("data").has("error")){String kind=value.path("category").asText("");if(!acquisitionAsked||!Set.of("recipes").contains(kind))structuredFacets.add("获取途径");}
            var response=new LinkedHashMap<String,Object>();response.put("status",sources.isEmpty()?"NOT_FOUND":"FOUND");response.put("coverage",index.coverage());response.put("sourceCategories",categories);response.put("sources",values);response.put("total",sources.size());response.put("nextOffset",offset(args)+count<sources.size()?offset(args)+count:-1);String mining=BlockMiningRules.describe(db,profile,id);if(mining!=null)response.put("blockMining",mining);return JSON.writeValueAsString(response);
        }
        String category=tool.equals("query_attributes")?args.has("state_key")||args.has("baby")||args.has("equipped")||args.has("origin")||args.has("filters_json")?"entity_state_attributes":"entity_attributes":tool.equals("query_trades")?"trades":tool.equals("query_loot")?"loot_tables":text(args,"category");
        RuntimeStructuredQuery.Page page;
        var typedFilters=new TreeMap<String,String>();for(String field:List.of("entity","profession","level","station","outputPotionId","inputPotionId"))if(args.has(field))typedFilters.put(field,args.path(field).asText());
        if(tool.equals("query_trades")||!typedFilters.isEmpty()){page=query.query(category,typedFilters,offset(args),limit(args));}
        else if(!id.isBlank()&&Set.of("registries","entity_icons").contains(category)&&!id.contains("|")){page=query.page(category,id,new RuntimeStructuredQuery.State(args.path("state_key").asText(null),args.has("baby")?args.path("baby").asBoolean():null,args.has("equipped")?args.path("equipped").asBoolean():null,args.path("origin").asText(null)),offset(args),category.equals("entity_icons")?limit(args,20):limit(args));}
        else if(!args.has("filters_json")&&!id.isBlank()&&!category.equals("entity_state_attributes")){page=query.get(category,id);if(category.equals("brewing")&&page.rows().isEmpty()&&!query.get("potions",id).rows().isEmpty())return brewing(query,id,args);if(tool.equals("query_attributes")&&page.rows().isEmpty())page=query.get("item_attributes",id);}
        else if(args.has("filters_json")){var parsed=JSON.readTree(text(args,"filters_json"));if(!parsed.isObject())throw new IllegalArgumentException("filters_json must be object");var filters=new TreeMap<String,String>();parsed.fields().forEachRemaining(field->filters.put(field.getKey(),field.getValue().asText()));if(!id.isBlank())filters.put("id",id);page=query.states(category,null,new RuntimeStructuredQuery.State(args.path("state_key").asText(null),args.has("baby")?args.path("baby").asBoolean():null,args.has("equipped")?args.path("equipped").asBoolean():null,args.path("origin").asText(null)),filters,offset(args),limit(args));}
        else if(category.equals("brewing"))return JSON.writeValueAsString(Map.of("status","FILTER_REQUIRED","message","Use query_brewing with the resolved output potion id, or query_runtime with outputPotionId/inputPotionId. Unfiltered recipes are not evidence for a specific potion."));
        else page=query.page(category,category.equals("entity_state_attributes")?text(args,"id"):args.path("subject").asText(null),new RuntimeStructuredQuery.State(args.path("state_key").asText(null),args.has("baby")?args.path("baby").asBoolean():null,args.has("equipped")?args.path("equipped").asBoolean():null,args.path("origin").asText(null)),offset(args),limit(args));
        if(tool.equals("query_trades")&&page.rows().isEmpty()) {
            var available=new TreeMap<String,Set<String>>();for(var pool:query.page("trades",null,RuntimeStructuredQuery.State.any(),0,100).rows())for(String field:List.of("entity","profession","level"))available.computeIfAbsent(field,ignored->new TreeSet<>()).add(pool.data().path(field).asText());
            return JSON.writeValueAsString(Map.of("status",page.status(),"total",0,"availableFilters",available,"filtersMayBePartial",true));
        }
        var records=JSON.createArrayNode();for(var row:page.rows())records.add(recordView(query,row,args.path("section").asText("")));
        return JSON.writeValueAsString(Map.of("status",page.status(),"coverage",page.coverage(),"total",page.total(),"nextOffset",page.nextOffset(),"warnings",page.warnings(),"records",records));
    }
    private String brewing(RuntimeStructuredQuery query,String output,JsonNode args)throws Exception {
        // The three standard potion containers fit one bounded semantic response.
        var page=query.query("brewing",Map.of("outputPotionId",output),offset(args),args.has("limit")?limit(args):3);var routes=JSON.createArrayNode();
        for(var row:page.rows()) {
            var route=routes.addObject();route.set("rule",recordView(query,row,""));var chain=query.brewingChain(row.key().id());route.put("prerequisites",String.join("\n",chain.warnings()));route.put("truncated",chain.truncated());
            addHits(List.of(query.evidence(row,"酿造前置路线：\n"+String.join("\n",chain.warnings()))));
        }
        return JSON.writeValueAsString(Map.of("status",page.status(),"coverage",page.coverage(),"total",page.total(),"nextOffset",page.nextOffset(),"routes",routes,"warnings",page.warnings()));
    }
    private JsonNode compactTrade(JsonNode node,String pointer) {
        if(node.isArray()&&node.size()>8)return JSON.createObjectNode().put("view","CANDIDATE_ARRAY_SUMMARY").put("count",node.size()).put("section",pointer).put("message","Read this exact section for individual candidates; no candidate list was inferred.");
        if(node.isObject()){var copy=JSON.createObjectNode();node.fields().forEachRemaining(field->copy.set(field.getKey(),compactTrade(field.getValue(),pointer+"/"+field.getKey().replace("~","~0").replace("/","~1"))));return copy;}
        if(node.isArray()){var copy=JSON.createArrayNode();for(int i=0;i<node.size();i++)copy.add(compactTrade(node.get(i),pointer+"/"+i));return copy;}return node;
    }
    /** 批量属性响应省略修饰符明细而保留全部已采集标量；细节仍由单条JSON指针查询。 */
    private JsonNode attributeProjection(RuntimeStructuredQuery query,RuntimeStructuredQuery.Row row)throws Exception {
        var view=JSON.createObjectNode().put("id",row.key().id()).put("category",row.key().category()).put("view","ATTRIBUTE_SCALARS");
        view.set("environment",query.environment());var data=view.putObject("data");
        for(String field:List.of("id","context","difficulty","status","error","attributeMapOnly","worldTickAdvanced"))if(row.data().has(field))data.set(field,row.data().path(field));
        var attributes=data.putArray("attributes");int index=0;
        for(var attribute:row.data().path("attributes")){
            var value=attributes.addObject().put("detailsSection","/attributes/"+index++);
            for(String field:List.of("attribute","name","base","effective","value","unit","error"))if(attribute.has(field))value.set(field,attribute.path(field));
        }
        view.put("projection","Modifier and other non-scalar details are omitted. Read the record section for complete conditions; values are not final combat damage.");if(row.data().path("attributeMapOnly").asBoolean(false)){view.put("coverage","ATTRIBUTE_MAP_ONLY");view.put("coverageNote","只有 AttributeMap 标量：模组代码声明的法术强度/抗性等可能不在此记录中，不要把 1.0 当作实际机制值。");}
        if(defaultsOnly(row.data())){view.put("defaultsOnly",true);view.put("defaultsNote","该实体的模组自定义属性全部为 1.0 且没有修饰符，说明导出只取到工厂默认值；不要把这些 1.0 当作实际法术强度或抗性，需要时用网页或源码核对并说明差异。");}
        addHits(List.of(query.evidence(row,view.toPrettyString())));
        String object=row.data().path("id").asText();if(object.contains(":")&&result.resolvedEntities.stream().noneMatch(entity->entity.registry().equals(object))){var objects=new ArrayList<>(result.resolvedEntities);objects.add(new EntityAgent.ResolvedEntity(object,RuntimeRules.namespace(object),1,"attribute-projection"));result.resolvedEntities=objects;}
        return view;
    }
    /** JSON 指针容忍前导斜杠与 data. 前缀，避免为同一字段重复试错。 */
    static String normalizePointer(String section){String value=section==null?"":section.trim();if(value.isEmpty())return value;if(value.startsWith("data."))value=value.substring(5);else if(value.startsWith("data/"))value=value.substring(5);if(!value.startsWith("/"))value="/"+value;return value;}
    /** 在交易/战利品记录里定位直接包含目标物品的条目，避免整池展开或逐个 section 翻页。 */
    private void collectMatching(JsonNode node,String target,ArrayNode out){
        if(out.size()>=8)return;
        if(node.isObject()){if(node.size()<=12&&node.toString().contains(target)){out.add(node);return;}node.elements().forEachRemaining(child->collectMatching(child,target,out));return;}
        if(node.isArray())node.elements().forEachRemaining(child->collectMatching(child,target,out));
    }
    private RuntimeNameIndex namesIndex;
    private RuntimeNameIndex names()throws Exception {if(namesIndex==null)namesIndex=new RuntimeNameIndex(db,profile);return namesIndex;}
    /** 模组自定义属性全是 1.0 且无修饰符时，只能说明拿到的是工厂默认值。 */
    static boolean defaultsOnly(JsonNode data){
        String id=data.path("id").asText("");int colon=id.indexOf(':');if(colon<=0)return false;
        String namespace=id.substring(0,colon);var attributes=data.path("attributes");if(!attributes.isArray())return false;
        int checked=0;
        for(var attribute:attributes){if(!attribute.path("attribute").asText("").startsWith(namespace+":"))continue;checked++;if(attribute.path("base").asDouble(0)!=1.0||attribute.path("effective").asDouble(0)!=1.0||attribute.path("modifiers").size()>0)return false;}
        return checked>=3;
    }
    /** 多方块按层给出 3×3 网格与材料计数，避免模型在原始 cells 数组里逐个坐标找。 */
    private ObjectNode multiblockView(RuntimeStructuredQuery query,RuntimeStructuredQuery.Row row)throws Exception {
        var data=row.data();var view=JSON.createObjectNode().put("id",row.key().id()).put("category","multiblocks").put("section","");
        view.set("environment",query.environment());
        for(String field:List.of("name","adapter","size","origin","status","coverage","limitation","controller","conditions","steps","dragonType"))if(data.has(field))view.set(field,data.get(field));
        var cells=data.path("cells");var counts=new TreeMap<String,Integer>();
        for(var cell:cells)counts.merge(cell.path("block").asText(""),1,Integer::sum);
        var materials=view.putArray("materials");
        for(var entry:counts.entrySet()){if(entry.getKey().isBlank())continue;String localized=names().nameOf(entry.getKey());materials.addObject().put("block",entry.getKey()).put("name",localized==null?entry.getKey():localized).put("count",entry.getValue());}
        int sx=data.path("size").path(0).asInt(3),sy=data.path("size").path(1).asInt(3),sz=data.path("size").path(2).asInt(3);
        int ox=data.path("origin").path(0).asInt(sx/2),oy=data.path("origin").path(1).asInt(sy/2),oz=data.path("origin").path(2).asInt(sz/2);
        var layers=view.putArray("layers");
        for(int y=0;y<sy;y++){
            var layer=layers.addObject().put("y",y-oy);var grid=layer.putArray("grid");
            for(int x=0;x<sx;x++){
                var line=grid.addArray();
                for(int z=0;z<sz;z++){
                    String block="";
                    for(var cell:cells)if(cell.path("pos").path(0).asInt()==x-ox&&cell.path("pos").path(1).asInt()==y-oy&&cell.path("pos").path(2).asInt()==z-oz){block=cell.path("block").asText("");break;}
                    if(block.isBlank()){line.add("");continue;}
                    String localized=names().nameOf(block);
                    line.add(localized==null?block.substring(block.indexOf(':')+1):localized);
                }
            }
        }
        view.put("layoutNote","每层按 X 正向、Z 负向排列；空白表示该格不指定方块，不自动等同于空气。材料计数是模板用量，不等于物品消耗量。");
        addHits(List.of(query.evidence(row,"JSON multiblock layout\n"+view.toPrettyString())));
        return view;
    }    private ObjectNode recordView(RuntimeStructuredQuery query,RuntimeStructuredQuery.Row row,String section)throws Exception {
        if(section.isBlank()&&row.key().category().equals("entity_icons"))return iconView(query,row);
        if(section.isBlank()&&row.key().category().equals("loot_tables"))return lootView(query,row);
        if(row.key().category().equals("multiblocks")){result.multiblockId=row.key().id();if(section.isBlank())return multiblockView(query,row);}
        var node=section.isBlank()?row.data():row.data().at(normalizePointer(section));var view=JSON.createObjectNode().put("id",row.key().id()).put("category",row.key().category()).put("section",section);view.set("environment",query.environment());
        if(node.isMissingNode())return view.put("status","SECTION_NOT_FOUND");
        if(row.key().category().equals("trades")&&section.isBlank()){node=compactTrade(node,"");view.put("view","RULES_WITH_CANDIDATE_COUNTS");}
        if(node.toString().length()>12000){view.put("status","NEEDS_SECTION");var fields=view.putArray("sections");if(node.isObject())node.fieldNames().forEachRemaining(field->fields.add(section+"/"+field.replace("~","~0").replace("/","~1")));else if(node.isArray())for(int i=0;i<Math.min(node.size(),100);i++){var entry=fields.addObject().put("pointer",section+"/"+i);for(String key:List.of("attribute","id","name","type","effect"))if(node.path(i).path(key).isTextual())entry.put(key,node.path(i).path(key).asText());}view.put("size",node.size());return view;}
        if(Set.of("registries","entity_attributes","entity_state_attributes","entity_icons","spawn_rules").contains(row.key().category())) {
            String object=row.data().path("id").asText();if(object.contains(":")&&result.resolvedEntities.stream().noneMatch(entity->entity.registry().equals(object))){var entities=new ArrayList<>(result.resolvedEntities);entities.add(new EntityAgent.ResolvedEntity(object,RuntimeRules.namespace(object),1,"runtime-tool"));result.resolvedEntities=entities;}
        }
        view.set("data",node);if(profile!=null){String localized=names().nameOf(row.key().id());if(localized!=null){view.put("name",localized);String key=names().translationKeyOf(row.key().id());if(key!=null)view.put("translationKey",key);}}if(row.key().category().equals("recipes")){String recipeId=row.key().id();int slash=recipeId.indexOf('/');if(slash>0){String station=recipeId.substring(0,slash);view.put("workstation",station);String stationName=names().nameOf(station);if(stationName==null)stationName=names().nameOf(station+"_empty");if(stationName!=null)view.put("workstationName",stationName);}}if(node.path("attributeMapOnly").asBoolean(false)){view.put("coverage","ATTRIBUTE_MAP_ONLY");view.put("coverageNote","只有 AttributeMap 标量：模组在代码里声明的数值（如学派法术强度与抗性）可能不在此记录中，不要把这些 1.0 当作实际机制值；需要时用来源核对。");}if(defaultsOnly(row.data())){view.put("defaultsOnly",true);view.put("defaultsNote","该实体的模组自定义属性全部为 1.0 且没有修饰符，说明导出只取到工厂默认值；不要把这些 1.0 当作实际法术强度或抗性，需要时用网页或源码核对并说明差异。");}var hit=query.evidence(row,"JSON section "+(section.isBlank()?"/":section)+"\n"+node.toPrettyString());addHits(List.of(hit));
        if(section.isBlank()&&row.key().category().equals("multiblocks")&&RuntimeStructures.renderable("multiblocks",row.data()))structuredFacets.add("搭建布局与操作");
        if(row.key().category().equals("recipes")&&!row.data().has("error")){var recipe=new RuntimeCatalog(db).recipe(profile,row.key().id());if(!result.recipeResults.contains(recipe)){var recipes=new ArrayList<>(result.recipeResults);recipes.add(recipe);result.recipeResults=recipes;result.recipesToRender=recipes.stream().limit(3).toList();}}
        return view;
    }
    private List<?> candidates(String query){var explicit=java.util.regex.Pattern.compile("https?://[^\\s<>]+",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(question);while(explicit.find())try{String url=WebUrlPolicy.normalize(explicit.group().replaceAll("[，。；）)]+$",""));allowed.putIfAbsent(url,new EntityAgent.AliasHit(question,url,url,null,1,"target"));}catch(IllegalArgumentException ignored){}var candidates=vectors.searchAliasesByText(query,5,null);for(var candidate:candidates)try{String url=WebUrlPolicy.normalize(candidate.url());allowed.put(url,new EntityAgent.AliasHit(question,candidate.label(),url,candidate.modId(),candidate.score(),"target"));}catch(IllegalArgumentException ignored){}return allowed.values().stream().map(candidate->Map.of("label",candidate.label(),"url",candidate.url())).toList();}
    /** 已登记对象可直接取图，避免为 id 再消耗一次名称解析。 */
    private boolean registered(String id)throws Exception {if(profile==null)return false;try(var c=db.getConnection();var p=c.prepareStatement("SELECT 1 FROM rag_runtime_object_name WHERE profile_id=? AND registry_id=? LIMIT 1")){p.setString(1,profile);p.setString(2,id);try(var rows=p.executeQuery()){return rows.next();}}}
    boolean documentScope(){return scope.documentId()!=null||scope.scope()!=null&&!scope.scope().id().startsWith("runtime:");}
    String scopeId(){return scope.documentId()!=null?"document:"+scope.documentId():scope.scope()==null?null:scope.scope().id();}
    List<EntityAgent.ResolvedEntity> focusObjects(){return focus.isEmpty()?result.resolvedEntities:List.copyOf(focus.values());}
    private boolean localOnly(){return LocalQuestionPolicy.decide(question).localOnly()||question.matches("(?s).*(不要联网|不用联网|不必联网|仅查本地|仅用本地|离线).*");}
    private boolean applicable(VectorStore.SearchResult hit){
        if(scope.documentId()!=null)return true;
        if(scope.scope()!=null&&"MODPACK".equals(scope.scope().kind()))return true;
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT 1 FROM rag_document d JOIN rag_knowledge_source s ON s.id=d.source_id WHERE d.location=? AND s.scope_kind='MODPACK'")){
            p.setString(1,hit.sourcePath());try(var rows=p.executeQuery()){return !rows.next();}
        }catch(java.sql.SQLException failure){throw new IllegalStateException("Evidence applicability lookup failed",failure);}
    }
    private String discover(JsonNode args)throws Exception {
        if(localOnly())throw new IllegalArgumentException("This turn is restricted to local evidence");
        if(!searched)throw new IllegalArgumentException("Search local documents before discovering new sources");
        if(scope.documentId()!=null)throw new IllegalArgumentException("Cannot expand a single document scope");
        String query=text(args,"query"),gap=text(args,"gap");
        if(discoveries.containsKey(query))return JSON.writeValueAsString(Map.of("candidates",discoveries.get(query),"gap",gap,"reused",true));
        if(discoveries.size()>=2)throw new IllegalArgumentException("Source discovery budget exhausted");
        if(!Set.of("mcmod","minecraft_wiki").contains(args.path("source").asText()))throw new IllegalArgumentException("source must be mcmod or minecraft_wiki");
        discoveries.put(query,List.of());
        var scraper=new WikiScraperService();scraper.setResourceDatabase(db);
        List<WikiScraperService.SearchCandidate> found;
        try(var cause=RetrievalTelemetry.resourceCause("AGENT_SOURCE_DISCOVERY")){found=scraper.discoverPages(query,args.path("source").asText().equals("minecraft_wiki"));}
        discoveries.put(query,found);
        for(var candidate:found){String url=WebUrlPolicy.normalize(candidate.url());allowed.put(url,new EntityAgent.AliasHit(question,candidate.title(),url,(args.path("source").asText().equals("minecraft_wiki")?"minecraft":args.path("mod").asText(null)),1,"target"));}
        return JSON.writeValueAsString(Map.of("candidates",found,"gap",gap,"candidateIsEvidence",false));
    }
String coverageContext(){var base=coverageContextBody();return pages>0?base:base+" 本轮尚未发起任何网页检索；如果本地资料不足以回答，先 discover_sources 再 supplement_web。";}
    String coverageContextBody(){return "Required facts: "+TaskEvidence.requirements(question).keySet()+"; missing facts: "+missingFacts()+". Attributes and loot do not substitute for requested actions. Search or discover a precise source for missing facts; disclose unresolved gaps without claiming completion.";}
    private List<String> missingFacts(){
        if(TaskEvidence.requirements(question).isEmpty())return List.of();
        var namespaces=new LinkedHashSet<String>();focus.values().forEach(object->namespaces.add(object.modId()));
        String bound=knowledge.boundModId(scope.scope());if(bound!=null)namespaces.add(bound);
        if(namespaces.isEmpty()&&profile!=null)try{for(var mention:new RuntimeNameIndex(db,profile).mentions(question,"ENTITY"))if(mention.status().equals("UNIQUE"))namespaces.add(RuntimeRules.namespace(mention.candidates().getFirst().registry()));}catch(Exception failure){throw new IllegalStateException("Task subject lookup failed",failure);}
        String text=result.vectorResults.stream().filter(hit->namespaces.isEmpty()||hit.modId()==null||namespaces.contains(hit.modId())).filter(hit->!Objects.toString(hit.type(),"").startsWith("structured_")&&(!hit.sourcePath().startsWith("mkb-runtime:")||Set.of("runtime_multiblocks","runtime_workstation_rules","runtime_brewing").contains(hit.type()))).map(VectorStore.SearchResult::chunkText).collect(java.util.stream.Collectors.joining("\n"));
        return TaskEvidence.missing(question,text).stream().filter(facet->!structuredFacets.contains(facet)).toList();
    }
    /** 战利品表附带抽取与选中语义，避免把“候选之一”读成“同时掉落”。 */
    private ObjectNode lootView(RuntimeStructuredQuery query,RuntimeStructuredQuery.Row row)throws Exception {
        var node=row.data();var view=JSON.createObjectNode().put("id",row.key().id()).put("category","loot_tables").put("section","");
        view.set("environment",query.environment());
        if(node.toString().length()>12000){view.put("status","NEEDS_SECTION");var fields=view.putArray("sections");node.fieldNames().forEachRemaining(field->fields.add("/"+field.replace("~","~0").replace("/","~1")));return view;}
        view.set("data",node);if(profile!=null){String localized=names().nameOf(row.key().id());if(localized!=null){view.put("name",localized);String key=names().translationKeyOf(row.key().id());if(key!=null)view.put("translationKey",key);}}
        var pools=ChestLootRenderer.pools(node.path("definition"));var summary=new StringBuilder();
        for(int i=0;i<pools.size();i++){var pool=pools.get(i);summary.append("池").append(i+1).append("：抽取").append(ChestLootRenderer.number(pool.rule().path("rolls"))).append("次");
            if(pool.slots().size()>1)summary.append("，每次从").append(pool.slots().size()).append("个候选中按权重选中1个");
            summary.append("；被选中条目按各自数量掉落，不是全部候选同时掉落");if(i+1<pools.size())summary.append("。");}
        if(!summary.isEmpty()){view.put("selectionSemantics",summary.toString());addHits(List.of(query.evidence(row,"战利品抽取语义：\n"+summary)));}
        return view;
    }
    /** 实体图标按状态枚举时只返回身份与状态，避免整条导出记录挤占上下文。 */
    private ObjectNode iconView(RuntimeStructuredQuery query,RuntimeStructuredQuery.Row row)throws Exception {
        String state=EntityAppearanceState.key(row.data());var icon=JSON.createObjectNode().put("id",row.key().id()).put("category","entity_icons").put("stateKey",state)
            .put("stateLabel",EntityAppearanceState.label(row.data())).put("status",row.data().path("status").asText()).put("representative",row.data().path("representative").asBoolean(false))
            .put("origin",row.data().path("state").path("origin").asText("DEFAULT_INSTANCE"));
        if(row.data().has("file"))icon.put("file",row.data().path("file").asText());
        var axes=row.data().path("state").path("traits").path("axes");if(axes.isObject()&&!axes.isEmpty())icon.set("axes",axes.deepCopy());
        var view=JSON.createObjectNode().put("id",row.key().id()).put("category","entity_icons").put("section","").put("view","ENTITY_ICON_STATE");view.set("environment",query.environment());view.set("data",icon);
        String object=row.key().id();if(object.contains(":")&&result.resolvedEntities.stream().noneMatch(entity->entity.registry().equals(object))){var objects=new ArrayList<>(result.resolvedEntities);objects.add(new EntityAgent.ResolvedEntity(object,RuntimeRules.namespace(object),1,"runtime-tool"));result.resolvedEntities=objects;}
        addHits(List.of(query.evidence(row,"JSON entity icon state\n"+icon.toPrettyString())));return view;
    }
    private List<String> unfetchedCandidates(){return allowed.entrySet().stream().filter(entry->!downloaded.containsKey(entry.getKey())).map(Map.Entry::getKey).limit(4).toList();}
    /** 介绍/机制类问题在已发现候选页却未抓取时，先提示补检一次，避免把“没查”当成“没有”。 */
    private boolean narrativeQuestion(){return question.matches("(?s).*(介绍|是什么|有什么|怎么|如何|为什么|机制|特点|用处|用途|玩法|教程|攻略|生成|掉落).*");}
    @Override public String readinessGap(){
        if(clarification!=null||localOnly())return null;
        if(!missingFacts().isEmpty()){var gap=coverageContext();if(pages<=1){var pending=unfetchedCandidates();if(!pending.isEmpty())gap+=" 本轮只抓取了 "+pages+" 页，仍缺 "+missingFacts()+"；请从候选页再补一页，不要直接下结论。";}return gap;}
        if(discoveryNudges==0&&pages==0&&narrativeQuestion()){var pending=unfetchedCandidates();if(!pending.isEmpty()){discoveryNudges++;return "Candidate pages were discovered or offered but not fetched: "+pending+". If the answer still needs unconfirmed facts (spawn, drops, behaviour, usage or steps), fetch one with supplement_web first; if the local evidence already answers everything, state plainly which parts remain unconfirmed.";}}
        return null;
    }
    private void addHits(List<VectorStore.SearchResult> hits){var all=new LinkedHashMap<String,VectorStore.SearchResult>();for(var hit:result.vectorResults)all.put(hit.sourcePath()+"/"+hit.chunkId(),hit);for(var hit:hits)if(applicable(hit))all.put(hit.sourcePath()+"/"+hit.chunkId(),hit);result.vectorResults=List.copyOf(all.values());}
    private void merge(AnswerArtifacts artifacts,boolean images){var sources=new LinkedHashMap<String,AnswerArtifacts.Source>();result.artifacts.sources().forEach(source->sources.put(source.id(),source));artifacts.sources().forEach(source->sources.put(source.id(),source));var pictures=new LinkedHashMap<String,AnswerArtifacts.Picture>();result.artifacts.pictures().forEach(picture->pictures.put(picture.id(),picture));if(images)artifacts.pictures().forEach(picture->pictures.put(picture.id(),picture));result.artifacts=new AnswerArtifacts(artifacts.scopeId(),artifacts.scopeLabel(),new ArrayList<>(sources.values()),new ArrayList<>(pictures.values()),result.artifacts.citedSourceIds());}
    @Override public String stopReason(){return clarification==null?null:"CLARIFICATION_REQUIRED";}
    /** 文档/网页证据轮次补齐相关插图；纯本地规则题不触发，也不改变已选实体图。 */
    void hydrateImages(){
        if(localOnly())return;
        if(retrieval==null||retrieval.hits().isEmpty())return;
        try(var cause=RetrievalTelemetry.resourceCause("TURN_HYDRATE_IMAGES")){var merged=new KnowledgeRetrievalService.Retrieval(result.vectorResults,result.artifacts,retrieval.evidence(),retrieval.bestLexical(),retrieval.diagnostics());retrieval=knowledge.hydrate(merged,question);merge(retrieval.artifacts(),true);}
    }
    void prepareFinalMedia(){
        String axisSource=rawQuestion!=null&&rawQuestion.matches("(?s).*(改成|换成|改为|只看|只要|仅看).*")?rawQuestion:question;result.mediaAxes=EntityAppearanceState.requestedAxes(axisSource);
        boolean visual=question.matches("(?s).*(图片|展示|外观|配图|长什么样|介绍|比较|对比|区别|分别).*");
        boolean followUp=question.matches("(?s).*(只看|改成|换成|再展示|再看|其他条件不变|它的|它们的|继续|同样).*");
        if(!result.resolvedEntities.isEmpty()&&(visual||(inheritImages&&followUp)))imagesRequested=true;
        if(imagesRequested&&rawQuestion!=null&&rawQuestion.matches("(?s).*(只看|只要|仅看|只展示|仅展示|只保留|只介绍).*")){
            try{var ids=new ArrayList<String>();for(var mention:new RuntimeNameIndex(db,profile).mentions(rawQuestion,"ENTITY"))if(mention.status().equals("UNIQUE")&&mention.candidates().size()==1)ids.add(mention.candidates().getFirst().registry());
                if(!ids.isEmpty()){result.mediaTargets.clear();result.mediaTargets.addAll(ids);}}
            catch(Exception failure){throw new IllegalStateException("收窄对象解析失败",failure);}
        }
        if(imagesRequested&&!prepared){media.run();prepared=true;}
    }
}
