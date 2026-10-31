package yagen.waitmydawn.kb.service;

import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import yagen.waitmydawn.kb.model.*;

/** 网页图像准入独立于实机资产；候选选择只读取本轮证据与已保存的图片引用。 */
final class WebImagePolicy {
    static boolean allowed(int width,int height){return width>160&&height>160;}
    static void check(byte[] bytes)throws IOException {
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new IOException("图片格式无可用解码器");
            var reader=readers.next();
            try{reader.setInput(input);if(!allowed(reader.getWidth(0),reader.getHeight(0)))throw new IOException("SMALL: 图片宽高须分别大于160");}
            finally{reader.dispose();}
        }
    }
    record Candidate(String id,String url,double score){}
    static Set<String> select(DatabaseBuilder db,AnswerArtifacts artifacts,String question) {
        if(!question.matches("(?s).*(图片|配图|示意|截图|展示|外观|长什么样|结构|多方块|搭建|组装|步骤|流程|召唤|怎么打|如何打|攻略).*"))return Set.of();
        var candidates=new ArrayList<Candidate>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT r.*,b.content AS block_content FROM rag_web_image_reference r LEFT JOIN rag_content_block b ON b.document_id=r.document_id AND b.ordinal=r.block_ordinal WHERE r.document_id=? AND r.status<>'EXCLUDED_SMALL'")) {
            for(var source:artifacts.sources()) {
                p.setString(1,source.documentId());try(var rows=p.executeQuery()){while(rows.next()) {
                    String caption=Objects.toString(rows.getString("caption"),""),step=Objects.toString(rows.getString("step_text"),""),text=Objects.toString(rows.getString("block_content"),"");
                    boolean bound=!text.isBlank()&&source.evidence().contains(text);
                    double topic=Math.max(KnowledgeSearchText.overlap(question,caption),KnowledgeSearchText.overlap(question,step));
                    if(!bound||topic<0.3)continue;
                    candidates.add(new Candidate(rows.getString("id"),rows.getString("url"),topic));
                }}
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("问答图片候选读取失败",e);}
        RetrievalTelemetry.operation("images.candidates",0,candidates.size());
        var selected=new LinkedHashSet<String>();var urls=new HashSet<String>();
        for(var candidate:candidates.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id)).toList()) {
            if(urls.add(WebUrlPolicy.normalize(candidate.url())))selected.add(candidate.id());
            if(selected.size()==4)break;
        }
        RetrievalTelemetry.operation("images.selected",0,selected.size());return selected;
    }
}
