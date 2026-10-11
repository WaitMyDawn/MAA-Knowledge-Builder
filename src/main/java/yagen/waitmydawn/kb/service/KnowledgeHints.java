package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.*;

/** 人工校验的领域关系只用于路由，机制答案仍必须读取正文；关系限定模组和真实目录条目。 */
public final class KnowledgeHints {
    public record Relation(String modId,String kind,List<String> aliases,String label,String url,String evidence) {}
    private static final List<Relation> RELATIONS=load();
    private KnowledgeHints() {}
    private static List<Relation> load() {
        try(var in=KnowledgeHints.class.getResourceAsStream("/knowledge-hints.json")) {
            var json=new ObjectMapper(); var root=json.readTree(Objects.requireNonNull(in));
            return List.of(json.treeToValue(root.path("relations"),Relation[].class));
        } catch(Exception e) { throw new IllegalStateException("Invalid curated knowledge relations",e); }
    }
    public static List<Relation> forMod(String modId) { return RELATIONS.stream().filter(r->r.modId().equals(modId)).toList(); }
    public static List<Relation> match(DatabaseBuilder db,String modId,String text,String kind) {
        if(modId==null || text==null) return List.of();
        var catalog=new HashMap<String,String>();
        for(var page:db.loadWebPages(modId)) catalog.putAll(page.subWebPage());
        String lower=text.toLowerCase(Locale.ROOT);
        return forMod(modId).stream().filter(r->r.kind().equals(kind))
                .filter(r->r.aliases().stream().anyMatch(alias->lower.contains(alias.toLowerCase(Locale.ROOT))))
                .filter(r->r.url().equals(catalog.get(r.label()))).toList();
    }
}
