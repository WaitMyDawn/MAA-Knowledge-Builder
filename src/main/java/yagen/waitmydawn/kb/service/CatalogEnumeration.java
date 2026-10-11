package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.agent.EntityAgent;
import yagen.waitmydawn.kb.dto.RetrievalPlan;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.*;

/** 目录负责候选资格，详情正文负责属性证明；预算未覆盖的成员保持未知。 */
public final class CatalogEnumeration {
    private final DatabaseBuilder db;
    private final RagAgentService llm;
    public CatalogEnumeration(DatabaseBuilder db,RagAgentService llm) { this.db=db; this.llm=llm; }
    public record Selection(List<EntityAgent.AliasHit> hits,String evidence) {}
    public Selection select(RetrievalPlan plan) {
        return select(plan,List.of());
    }
    public Selection select(RetrievalPlan plan,List<String> subjects) {
        var raw=new ArrayList<WebCatalogStore.Entry>();
        var modByUrl=new HashMap<String,String>();
        List<String> modIds=plan.modId()==null?db.findAllModEntries().stream().map(m -> m.getModId()).toList():List.of(plan.modId());
        for(String modId:modIds) for(var entry:new WebCatalogStore(db).entries(modId,plan.topCategory())) {
            raw.add(entry);modByUrl.putIfAbsent(entry.url(),modId);
        }
        var candidates=new LinkedHashMap<String,WebCatalogStore.Entry>();
        boolean biological=plan.objectType().equals("ENTITY") && plan.question().contains("生物")
                && !plan.question().matches("(?s).*(实体|技术|交通|载具).*" );
        for(var entry:raw) if(entry.role().equals("entry") && !(biological && entry.path().stream()
                .anyMatch(p->p.matches(".*(技术性实体|技术实体|交通工具|载具).*" )))) candidates.putIfAbsent(entry.url(),entry);
        if(candidates.isEmpty()) return new Selection(List.of(),"分类索引缺失或当前分类为空，不能推断该模组没有这些对象；旧库需补抓分类。");
        // 主体约束与行为筛选分开，不能把“龙有哪些”退化为整个生物目录的随机举例。
        var generic=Set.of("生物","实体","动物","物品","方块","维度","世界","群系","法术","附魔","结构");
        var families=subjects.stream().filter(s->s!=null && !s.isBlank() && !generic.contains(s)).toList();
        if(!families.isEmpty() && !plan.needsDetails() && plan.filters().isEmpty()) {
            var scoped=new LinkedHashMap<String,WebCatalogStore.Entry>();
            candidates.forEach((url,entry)->{
                if(families.stream().anyMatch(subject->Objects.toString(entry.chineseName(),"").contains(subject)
                        || Objects.toString(entry.englishName(),"").toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT))
                        || entry.path().stream().anyMatch(path->path.contains(subject)))) scoped.put(url,entry);
            });
            if(!scoped.isEmpty()) { candidates.clear(); candidates.putAll(scoped); }
        }
        boolean explicitCount=plan.question().matches("(?s).*[一二三四五六七八九十0-9]+(?:个|种|例).*" );
        int limit=plan.needsDetails()?Math.max(plan.requestedCount(),Integer.getInteger("maa.enumeration.maxPages",12))
                :explicitCount?plan.requestedCount():30;
        limit=Math.min(30,limit);
        var selected=new ArrayList<WebCatalogStore.Entry>();
        if(llm!=null && (plan.needsDetails() || !plan.filters().isEmpty())) {
            StringBuilder prompt=new StringBuilder("Select candidate URLs that match the question's requested objects/family, not unrelated catalog members. For basic examples, names and category paths prove membership. For behavioral filters select promising pages for verification; names/categories are not proof of taming/riding. Only copy URLs from candidates. Return a JSON array of at most "+limit+" URLs. Question: "+plan.question()+"\n");
            var cache=new PublicKnowledgeCache(db);
            for(var e:candidates.values()) {
                prompt.append(e.label()).append(" | ").append(String.join(" > ",e.path())).append(" | ").append(e.url());
                var page=cache.find(e.url());
                if(page!=null) {
                    String body=page.page().content();
                    var lines=body.lines().filter(line->line.matches(".*(驯服|骑乘|繁殖|中立|被动|敌对|陆地|水生|水中|生成).*" )).limit(3).toList();
                    if(!lines.isEmpty()) prompt.append(" | 本地正文线索：").append(String.join("；",lines));
                }
                prompt.append('\n');
            }
            try {
                var result=llm.askWithUsage("Enumeration.select",prompt.toString());
                String response=result==null?"[]":result.text();
                int start=response.indexOf('['),end=response.lastIndexOf(']');
                if(start>=0 && end>start) for(var value:new ObjectMapper().readTree(response.substring(start,end+1))) {
                    var entry=candidates.get(value.asText());
                    if(entry!=null && !selected.contains(entry) && selected.size()<limit) selected.add(entry);
                }
            } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Enumeration selection failed; use bounded catalog candidates: {}",e.getClass().getSimpleName()); }
        }
        if(selected.isEmpty()) {
            var paths=new HashSet<List<String>>();
            for(var entry:candidates.values()) if(paths.add(entry.path()) && selected.size()<limit) selected.add(entry);
            for(var entry:candidates.values()) if(!selected.contains(entry) && selected.size()<limit) selected.add(entry);
        }
        StringBuilder evidence=new StringBuilder("目录范围："+modIds+"；目录去重成员 "+candidates.size()+"；本次选取 "+selected.size()+"。\n");
        if(plan.needsDetails()) evidence.append("以下为待核验候选，只有详情正文证明筛选条件后才能列入答案。未核验成员保持未知。\n");
        else evidence.append("以下名称及分类可作为举例证据，不能据此推断行为机制或注册名。\n");
        for(var entry:selected) evidence.append("- ").append(entry.label()).append(" | ").append(String.join(" > ",entry.path())).append(" | 模组：").append(modByUrl.get(entry.url())).append(" | 来源：").append(entry.categoryUrl()).append(" | 详情：").append(entry.url()).append('\n');
        if(plan.exhaustive() && (selected.size()<candidates.size() || plan.needsDetails())) evidence.append("覆盖状态：未完成完整属性核验，不能声称这是全部符合条件的对象。\n");
        var hits=selected.stream().map(e -> new EntityAgent.AliasHit(plan.question(),e.label(),e.url(),modByUrl.get(e.url()),1)).toList();
        return new Selection(hits,evidence.toString());
    }
}
