package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 先保留命中段落，再按文档、页码及提取方式边界扩展完整上下文。 */
final class KnowledgeContextBuilder {
    record Result(List<AnswerArtifacts.Source> sources,KnowledgeRetrievalService.ContextDiagnostics diagnostics) {}
    private record Raw(String id,String documentId,String path,String lineage,int ordinal,Integer page,String kind,String table,String text,String method) {}
    private record Included(Raw raw,String text) {}
    private final DatabaseBuilder db;
    KnowledgeContextBuilder(DatabaseBuilder db) {this.db=db;}

    Result build(List<VectorStore.SearchResult> hits,List<AnswerArtifacts.Source> original,String focus) {
        int budget=Math.max(1000,Math.min(32000,Integer.getInteger("maa.qa.contextMaxChars",12000)));
        var rawByDoc=new HashMap<String,List<Raw>>();var byId=new HashMap<String,Raw>();var links=new LinkedHashMap<String,List<String>>();
        var originalByChunk=new HashMap<String,AnswerArtifacts.Source>();
        for(var hit:hits) original.stream().filter(s -> s.id().equals("s"+BuildRequest.fingerprint(hit.chunkId()).substring(0,20))).findFirst().ifPresent(s -> originalByChunk.put(hit.chunkId(),s));
        try(var c=db.getConnection()) {
            try(var tables=c.getMetaData().getTables(null,null,"rag_chunk_block",null)) {
                if(tables.next()) for(var hit:hits) {
                    try(var p=c.prepareStatement("SELECT b.* FROM rag_chunk_block l JOIN rag_content_block b ON b.id=l.block_id WHERE l.chunk_id=? ORDER BY b.ordinal")) {
                        p.setString(1,hit.chunkId());try(var rows=p.executeQuery()) {
                            var ids=new ArrayList<String>();while(rows.next()) {ids.add(rows.getString("id"));}
                            links.put(hit.chunkId(),ids);
                        }
                    }
                }
            }
            for(var source:original) {
                if(rawByDoc.containsKey(source.documentId()) || links.values().stream().allMatch(List::isEmpty)) continue;
                var raw=new ArrayList<Raw>();
                try(var p=c.prepareStatement("SELECT * FROM rag_content_block WHERE document_id=? ORDER BY ordinal LIMIT 20001")) {
                    p.setString(1,source.documentId());try(var rows=p.executeQuery()) {
                        while(rows.next()) {
                            var block=new Raw(rows.getString("id"),rows.getString("document_id"),rows.getString("heading_path"),rows.getString("lineage"),rows.getInt("ordinal"),
                                    (Integer)rows.getObject("page_number"),rows.getString("kind"),rows.getString("table_id"),rows.getString("content"),rows.getString("extraction_method"));
                            raw.add(block);byId.put(block.id(),block);
                        }
                    }
                }
                rawByDoc.put(source.documentId(),raw);
            }
        } catch(java.sql.SQLException e) {throw new IllegalStateException("完整资料上下文读取失败",e);}
        var candidates=new LinkedHashSet<String>();links.values().forEach(candidates::addAll);
        for(var ids:links.values()) for(String id:ids) {
            var anchor=byId.get(id);if(anchor==null) continue;
            var document=rawByDoc.get(anchor.documentId());String parent=parent(anchor.lineage());
            boolean focused=KnowledgeSearchText.overlap(focus,anchor.path())>0;
            String parentPath=anchor.path().contains(" > ")?anchor.path().substring(0,anchor.path().lastIndexOf(" > ")):"";
            var leaf=focused?document.stream().filter(b -> compatible(anchor,b) && b.lineage().startsWith(anchor.lineage())).toList():List.<Raw>of();
            var family=parent==null || KnowledgeSearchText.overlap(focus,parentPath)==0?List.<Raw>of():document.stream().filter(b -> compatible(anchor,b) && b.lineage().startsWith(parent)).toList();
            var expanded=bounded(family)?family:bounded(leaf)?leaf:document.stream().filter(b -> compatible(anchor,b) && b.lineage().equals(anchor.lineage()) && Math.abs((long)b.ordinal()-anchor.ordinal())<=1).toList();
            expanded.forEach(b -> candidates.add(b.id()));
        }
        var included=new ArrayList<Included>();var seen=new HashMap<String,String>();var duplicates=new HashMap<String,String>();int chars=0;boolean truncated=false;
        for(String id:candidates) {
            var raw=byId.get(id);if(raw==null) {truncated=true;continue;}
            String text=raw.text();
            if(text.length()>4000) {
                text=hits.stream().filter(h -> links.getOrDefault(h.chunkId(),List.of()).contains(id)).map(VectorStore.SearchResult::chunkText).findFirst().orElse("");
                truncated=true;
            }
            String key=raw.documentId()+"\n"+raw.path()+"\n"+raw.page()+"\n"+text.strip();
            if(text.isBlank()) continue;
            if(seen.containsKey(key)) {duplicates.put(id,seen.get(key));continue;}
            if(chars+text.length()+raw.path().length()+30>budget) {truncated=true;continue;}
            included.add(new Included(raw,text));seen.put(key,id);chars+=text.length()+raw.path().length()+30;
        }
        var documentOrder=new HashMap<String,Integer>();for(var source:original) documentOrder.putIfAbsent(source.documentId(),documentOrder.size());
        included.sort(Comparator.comparingInt((Included i) -> documentOrder.getOrDefault(i.raw().documentId(),Integer.MAX_VALUE)).thenComparingInt(i -> i.raw().ordinal()));
        var sources=new ArrayList<AnswerArtifacts.Source>();var blockSources=new LinkedHashMap<String,String>();var chunkSources=new LinkedHashMap<String,String>();
        var groups=new ArrayList<List<Included>>();
        for(var item:included) {
            var group=groups.isEmpty()?null:groups.getLast();
            if(group==null || !compatible(group.getLast().raw(),item.raw()) || !group.getLast().raw().lineage().equals(item.raw().lineage())
                    || !Objects.equals(group.getLast().raw().method(),item.raw().method())
                    || group.getLast().raw().ordinal()+1!=item.raw().ordinal()
                    || group.stream().mapToInt(i -> i.text().length()).sum()+item.text().length()>4000) {group=new ArrayList<>();groups.add(group);}
            group.add(item);
        }
        for(var group:groups) {
            var first=group.getFirst().raw();
            var origin=original.stream().filter(s -> s.documentId().equals(first.documentId())).findFirst().orElseThrow();
            String id="s"+BuildRequest.fingerprint(group.stream().map(i -> i.raw().id()).collect(java.util.stream.Collectors.joining("\n"))).substring(0,20);
            var text=new StringBuilder();String table=null,header=null;
            for(var item:group) {
                String part=item.text();int newline=part.indexOf('\n');
                String currentHeader=newline>0?part.substring(0,newline):null;
                if(item.raw().table()!=null && item.raw().table().equals(table) && Objects.equals(header,currentHeader) && currentHeader!=null) part=part.substring(newline+1);
                else {table=item.raw().table();header=currentHeader;}
                text.append(part).append("\n\n");blockSources.put(item.raw().id(),id);
            }
            String method=first.method()+(group.stream().anyMatch(i -> !i.text().equals(i.raw().text()))?"+EXCERPT":"");
            sources.add(new AnswerArtifacts.Source(id,first.documentId(),origin.title(),origin.location(),first.path(),first.page(),method,text.toString().strip()));
        }
        duplicates.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {if(blockSources.containsKey(e.getValue())) blockSources.put(e.getKey(),blockSources.get(e.getValue()));});
        for(var hit:hits) {
            for(String id:links.getOrDefault(hit.chunkId(),List.of())) if(blockSources.containsKey(id)) {chunkSources.put(hit.chunkId(),blockSources.get(id));break;}
            if(chunkSources.containsKey(hit.chunkId())) continue;
            var source=originalByChunk.get(hit.chunkId());if(source==null) continue;
            if(chars+source.evidence().length()>budget) {truncated=true;continue;}
            sources.add(source);chars+=source.evidence().length();chunkSources.put(hit.chunkId(),source.id());blockSources.put(hit.chunkId(),source.id());
        }
        var diagnostics=new KnowledgeRetrievalService.ContextDiagnostics(links.values().stream().anyMatch(l -> !l.isEmpty())?StructuredChunker.VERSION:"legacy-index-only",
                List.copyOf(blockSources.keySet()),Map.copyOf(blockSources),Map.copyOf(chunkSources),chars,budget,truncated);
        return new Result(List.copyOf(sources),diagnostics);
    }

    private static boolean compatible(Raw a,Raw b) {
        var nativeMethods=Set.of("NATIVE","TABLE","ADJACENT");
        return a.documentId().equals(b.documentId()) && Objects.equals(a.page(),b.page())
                && (Objects.equals(a.method(),b.method()) || a.method()!=null && b.method()!=null && nativeMethods.contains(a.method()) && nativeMethods.contains(b.method()));
    }
    private static boolean bounded(List<Raw> blocks) {return !blocks.isEmpty() && blocks.size()<=48 && blocks.stream().mapToLong(b -> b.text().length()).sum()<=8000;}
    private static String parent(String lineage) {
        String[] parts=lineage.split("/");if(parts.length<4) return null;
        int end=lineage.lastIndexOf('/',lineage.length()-2);return lineage.substring(0,end+1);
    }
}
