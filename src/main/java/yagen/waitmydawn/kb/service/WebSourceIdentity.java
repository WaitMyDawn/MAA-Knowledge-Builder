package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.*;

/** 页面归属由目录关系和声明的 MOD 来源确认，搜索命中的标签不能重写归属。 */
public final class WebSourceIdentity {
    public record Owner(String id,String label) {}
    private final DatabaseBuilder db;
    private final Map<String,List<Owner>> cache=new HashMap<>();
    public WebSourceIdentity(DatabaseBuilder db){this.db=db;}
    public List<Owner> owners(String url){
        if(url==null||!url.startsWith("http"))return List.of();
        return cache.computeIfAbsent(url,this::load);
    }
    private List<Owner> load(String url){
        var ids=new TreeMap<String,String>();
        try(var c=db.getConnection()){
            try(var p=c.prepareStatement("SELECT DISTINCT c.mod_id FROM rag_web_category c JOIN rag_web_category_member m ON m.category_id=c.category_id WHERE m.item_url=? UNION SELECT COALESCE(s.mod_id,s.scope_id) FROM rag_knowledge_source s WHERE s.scope_kind='MOD' AND s.location=?")){
                p.setString(1,url);p.setString(2,url);try(var r=p.executeQuery()){while(r.next())if(r.getString(1)!=null)ids.put(r.getString(1),r.getString(1));}
            }
            for(String id:new ArrayList<>(ids.keySet())){
                try(var p=c.prepareStatement("SELECT display_name FROM rag_knowledge_source WHERE scope_kind='MOD' AND (scope_id=? OR mod_id=?) ORDER BY id")){
                    p.setString(1,id);p.setString(2,id);try(var r=p.executeQuery()){if(r.next()&&r.getString(1)!=null)ids.put(id,r.getString(1));}
                }
                if(ids.get(id).equals(id)){var entry=db.findModEntry(id);if(entry!=null&&entry.getDisplayName()!=null)ids.put(id,entry.getDisplayName());}
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("Web page ownership lookup failed",e);}
        return ids.entrySet().stream().map(entry->new Owner(entry.getKey(),entry.getValue())).toList();
    }
    public VectorStore.SearchResult canonicalize(VectorStore.SearchResult hit){
        var owners=owners(hit.sourcePath());if(owners.size()!=1)return hit;
        var owner=owners.getFirst();return new VectorStore.SearchResult(hit.chunkId(),hit.chunkText(),owner.id(),owner.label(),hit.type(),hit.score(),hit.sourcePath());
    }
    public boolean permits(VectorStore.SearchResult hit,KnowledgeRetrievalService.Resolution scope,String namespace){
        if(scope.documentId()!=null)return linked(hit.sourcePath(),List.of(),scope.documentId());
        if(scope.scope()==null)return true;
        if(scope.scope().id().startsWith("runtime:"))return true;
        var owners=owners(hit.sourcePath());
        if(!owners.isEmpty())return owners.stream().anyMatch(owner->owner.id().equals(scope.scope().id())||Objects.equals(owner.id(),namespace));
        return linked(hit.sourcePath(),scope.scope().sourceIds(),null);
    }
    private boolean linked(String url,List<String> sources,String document){
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT d.id,d.source_id,l.source_id FROM rag_document d LEFT JOIN rag_document_source l ON l.document_id=d.id WHERE d.location=?")){
            p.setString(1,url);try(var r=p.executeQuery()){while(r.next())if(Objects.equals(document,r.getString(1))||sources.contains(r.getString(2))||sources.contains(r.getString(3)))return true;}
        }catch(java.sql.SQLException e){throw new IllegalStateException("Web source linkage lookup failed",e);}
        return false;
    }
}
