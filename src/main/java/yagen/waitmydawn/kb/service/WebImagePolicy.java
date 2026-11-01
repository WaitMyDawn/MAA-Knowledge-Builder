package yagen.waitmydawn.kb.service;

import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import yagen.waitmydawn.kb.model.*;

/** 网页图像准入独立于实机资产；候选选择只读取本轮证据与已保存的图片引用。 */
final class WebImagePolicy {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(WebImagePolicy.class);
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
    static String topic(String question,AnswerArtifacts.Source source) {
        if(source.location()!=null&&question.contains(source.location())&&source.title()!=null&&!source.title().isBlank()
                &&question.matches("(?is).*(这个模组|该模组|模组是什么|模组介绍|页面概览|总览|overview).*"))return source.title();
        return question;
    }
    static Set<String> select(DatabaseBuilder db,AnswerArtifacts artifacts,String question) {
        if(!question.matches("(?s).*(图片|配图|配[^，。；\\n]{0,16}图|示意|截图|展示|外观|长什么样|结构|多方块|搭建|组装|步骤|流程|召唤|怎么打|如何打|攻略).*")){log.info("web images: question has no image intent");return Set.of();}
        var candidates=new ArrayList<Candidate>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT r.*,b.content AS block_content FROM rag_web_image_reference r LEFT JOIN rag_content_block b ON b.document_id=r.document_id AND b.ordinal=r.block_ordinal WHERE r.document_id=? AND r.status<>'EXCLUDED_SMALL'")) {
            for(var source:artifacts.sources()) {
                String documentId=source.documentId();
                if(documentId==null||documentId.isBlank())continue;
                // 网页补检的向量来源用 "vector:<hash>" 标识，落库时只有 <hash>。
                if(documentId.startsWith("vector:"))documentId=documentId.substring("vector:".length());
                p.setString(1,documentId);try(var rows=p.executeQuery()){while(rows.next()) {
                    String caption=Objects.toString(rows.getString("caption"),""),step=Objects.toString(rows.getString("step_text"),""),text=Objects.toString(rows.getString("block_content"),"");
                    boolean bound=!text.isBlank()&&source.evidence().contains(text);
                    // 图片所属文本块已被本轮证据覆盖，就说明这张图属于本次回答的段落；
                    // 题面很长时词重叠天然偏低，只把它当排序信号，不再当硬门槛。
                    if(!bound)continue;
                    String focus=topic(question,source);
                    double topic=Math.max(KnowledgeSearchText.overlap(focus,caption),Math.max(KnowledgeSearchText.overlap(focus,step),KnowledgeSearchText.overlap(focus,text)));
                    boolean titled=!caption.isBlank()||!step.isBlank();
                    if(topic<=0&&!titled)continue;
                    candidates.add(new Candidate(rows.getString("id"),rows.getString("url"),topic+(step.isBlank()?0:0.02)+(titled?0.01:0)));
                }}
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("问答图片候选读取失败",e);}
        RetrievalTelemetry.operation("images.candidates",0,candidates.size());
        var selected=new LinkedHashSet<String>();var urls=new HashSet<String>();
        for(var candidate:candidates.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id)).toList()) {
            if(urls.add(WebUrlPolicy.normalize(candidate.url())))selected.add(candidate.id());
            if(selected.size()==4)break;
        }
        log.info("web images: sources={} candidates={} selected={}",artifacts.sources().size(),candidates.size(),selected.size());RetrievalTelemetry.operation("images.selected",0,selected.size());return selected;
    }
}
