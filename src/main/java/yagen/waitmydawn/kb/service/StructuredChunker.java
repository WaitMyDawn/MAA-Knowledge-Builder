package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 完整原文块独立保存；检索块按实际分词预算生成，原文范围保持可追溯。 */
final class StructuredChunker {
    static final String VERSION="structured-v1";
    static final int TOKEN_LIMIT=224;
    record Indexed(TextChunker.TextChunk chunk,List<String> blockIds,String heading,Integer page,String method) {}

    static List<Indexed> split(List<KnowledgeContent.Block> blocks,String title,String modId,String modName,String type,String location,EmbeddingService embedder) {
        var result=new ArrayList<Indexed>();var pending=new ArrayList<KnowledgeContent.Block>();
        for(var block:blocks) {
            if(!pending.isEmpty()) {
                var previous=pending.getLast();
                String combined=pending.stream().map(KnowledgeContent.Block::text).collect(java.util.stream.Collectors.joining("\n\n"))+"\n\n"+block.text();
                if(!previous.documentId().equals(block.documentId()) || !previous.headingPath().equals(block.headingPath()) || !previous.lineage().equals(block.lineage()) || !Objects.equals(previous.pageNumber(),block.pageNumber())
                        || !Objects.equals(previous.method(),block.method()) || !previous.kind().equals("PARAGRAPH") || !block.kind().equals("PARAGRAPH")
                        || embedder.tokenCount(prefix(block.headingPath(),embedder)+combined)>TOKEN_LIMIT) {
                    emit(pending,result,modId,modName,type,location,embedder);pending.clear();
                }
            }
            pending.add(block);
        }
        emit(pending,result,modId,modName,type,location,embedder);return List.copyOf(result);
    }

    private static void emit(List<KnowledgeContent.Block> blocks,List<Indexed> result,String modId,String modName,String type,String location,EmbeddingService embedder) {
        if(blocks.isEmpty()) return;
        String body=blocks.stream().map(KnowledgeContent.Block::text).filter(t -> !t.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
        if(body.isBlank()) return;
        var first=blocks.getFirst();String prefix=prefix(first.headingPath(),embedder);int start=0;
        if(first.kind().equals("TABLE_ROW") && blocks.size()==1) {
            int newline=body.indexOf('\n');
            if(newline>0 && (body.startsWith("表头：") || body.startsWith("首行（原文）："))) {
                prefix=addPrefix(prefix,body.substring(0,newline),embedder);body=body.substring(newline+1);
            }
            String[] cells=body.split("\\|",3);
            if(cells.length>1) prefix=addPrefix(prefix,"行首（原文）："+cells[0].strip()+" | "+cells[1].strip(),embedder);
        }
        while(start<body.length()) {
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Structured chunking cancelled");
            int low=start+1,high=Math.min(body.length(),start+2048),end=start;
            while(low<=high) {
                int middle=(low+high)>>>1;
                if(embedder.tokenCount(prefix+body.substring(start,middle))<=TOKEN_LIMIT) {end=middle;low=middle+1;} else high=middle-1;
            }
            if(end<=start) throw new IllegalStateException("标题前缀未保留正文 Token 预算");
            if(end<body.length()) {
                int boundary=-1;for(int i=start;i<end;i++) if("\n。！？.!?；;".indexOf(body.charAt(i))>=0 && i>start+(end-start)/2) boundary=i+1;
                if(boundary>start) end=boundary;
                if(Character.isHighSurrogate(body.charAt(end-1))) end--;
            }
            String text=prefix+body.substring(start,end).strip();
            String id=first.documentId()+"/"+VERSION+"/"+BuildRequest.fingerprint(blocks.stream().map(KnowledgeContent.Block::id).collect(java.util.stream.Collectors.joining("\n"))+"\n"+start+"\n"+text);
            result.add(new Indexed(new TextChunker.TextChunk(id,text,modId,modName,type,location),blocks.stream().map(KnowledgeContent.Block::id).toList(),first.headingPath(),first.pageNumber(),first.method()));
            if(end==body.length()) break;
            int next=Math.max(start+1,end-24);
            if(Character.isLowSurrogate(body.charAt(next))) next++;
            start=next;
        }
    }

    private static String prefix(String heading,EmbeddingService embedder) {
        String text="章节："+heading;
        while(embedder.tokenCount(text)>64) {
            int end=text.offsetByCodePoints(text.length(),-1);text=text.substring(0,end);
        }
        return text+"\n";
    }

    private static String addPrefix(String prefix,String extra,EmbeddingService embedder) {
        while(!extra.isEmpty() && embedder.tokenCount(prefix+extra+"\n")>96) extra=extra.substring(0,extra.offsetByCodePoints(extra.length(),-1));
        return extra.isEmpty()?prefix:prefix+extra+"\n";
    }
}
