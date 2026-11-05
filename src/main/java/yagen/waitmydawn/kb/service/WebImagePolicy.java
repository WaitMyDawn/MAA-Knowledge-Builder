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
    /**
     * 检索阶段已选图片仍不能覆盖复合步骤，或整轮没有任何插图时的兜底候选。
     * cached 表示该引用已有下载资产；否则由媒体服务按引用地址补取。
     */
    record Fallback(String referenceId,String documentId,String url,String caption,String stepText,String heading,String blockId,
                    String blockText,String status,String role,int width,int height,boolean cached,
                    String assetId,String relativePath,double score){
        Fallback withScore(double value){return new Fallback(referenceId,documentId,url,caption,stepText,heading,blockId,blockText,status,role,width,height,cached,assetId,relativePath,value);}
    }
    static boolean imageIntent(String question){return question!=null&&question.matches("(?s).*(图片|插图|配图|配[^，。；\\n]{0,16}图|示意|截图|展示|外观|长什么样|结构|多方块|搭建|组装|步骤|流程|召唤|怎么打|如何打|攻略).*");}
    static String topic(String question,AnswerArtifacts.Source source) {
        if(source.location()!=null&&question.contains(source.location())&&source.title()!=null&&!source.title().isBlank()
                &&question.matches("(?is).*(这个模组|该模组|模组是什么|模组介绍|页面概览|总览|overview).*"))return source.title();
        return question;
    }
    static Set<String> select(DatabaseBuilder db,AnswerArtifacts artifacts,String question) {
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

    /**
     * 复合操作题按缺失步骤反向挑选网页图片。优先选标题/图注直接命中该步骤的引用；
     * 同一文档没有尺寸合规图时，才把来源里的小图标作为“来源图标”补进来。
     */
    static List<Fallback> facetFallbacks(DatabaseBuilder db,AnswerArtifacts artifacts,String question,List<String> facets,int limit) {
        if(facets==null||facets.isEmpty()||artifacts==null)return List.of();
        var rows=rows(db,artifacts);var selected=new ArrayList<Fallback>();var chosen=new HashSet<String>();
        // 每个缺失步骤各取一张；不能把总名额先花在同一个步骤上。
        for(var facet:facets) {
            var best=rows.stream().map(row->score(row,facet,question)).filter(Objects::nonNull)
                    .sorted(Comparator.comparingDouble(Fallback::score).reversed().thenComparing(Fallback::referenceId))
                    .filter(candidate->chosen.add(candidate.referenceId())).findFirst();
            if(best.isPresent())selected.add(best.get());
            if(selected.size()>=Math.max(1,limit))break;
        }
        return List.copyOf(selected);
    }
    private static Fallback score(Fallback row,String facet,String question) {
        String text=join(row.caption(),row.stepText(),row.heading(),row.blockText());
        boolean heading=matches(row.heading(),facet),caption=matches(row.caption(),facet),step=matches(row.stepText(),facet),body=matches(row.blockText(),facet);
        if(!heading&&!caption&&!step&&!body)return null;
        // 同一表格里多行图片共享整块正文，只有行首说明能区分“辅助升级”与“武器”。
        boolean leading=matches(prefix(row.stepText(),48),facet);
        double value=(heading?3:0)+(caption?2:0)+(step?1.5:0)+(body?1:0)+(leading?2.5:0)
                +KnowledgeSearchText.overlap(question,text)+(row.cached()?1:0)+areaScore(row.width(),row.height());
        return row.withScore(value);
    }

    /**
     * 整轮没有任何可用插图时的页面级保底：只在本轮网页来源内挑选，
     * 以题面相关度为先，其次取更大的正文图；没有文本命中时也会留下该页最大的一张。
     */
    static List<Fallback> pageFallbacks(DatabaseBuilder db,AnswerArtifacts artifacts,String question,int limit) {
        if(artifacts==null)return List.of();
        var scored=new ArrayList<Fallback>();
        for(var row:rows(db,artifacts)) {
            if("PAGE_PREVIEW".equals(row.role()))continue;
            if(!row.cached()&&!"AVAILABLE".equals(row.status()))continue;
            String text=join(row.caption(),row.stepText(),row.heading(),row.blockText());
            double topic=KnowledgeSearchText.overlap(question,text);
            var source=sourceFor(artifacts,row.documentId(),row.heading());
            double section=source==null?0:KnowledgeSearchText.overlap(question,join(source.heading(),source.evidence()));
            scored.add(row.withScore(3*topic+1.5*section+areaScore(row.width(),row.height())));
        }
        return take(scored,limit);
    }

    private static double areaScore(int width,int height){return Math.min(1.0,Math.max(0,width)*Math.max(0,height)/500000.0);}
    private static String join(String... values){var text=new StringBuilder();for(String value:values)if(value!=null&&!value.isBlank()){if(!text.isEmpty())text.append(' ');text.append(value);}return text.toString();}
    private static String prefix(String text,int max){String value=Objects.toString(text,"");return value.length()<=max?value:value.substring(0,max);}
    private static boolean matches(String text,String facet){String pattern=GuideIllustrationCoverage.pattern(facet);return !pattern.isEmpty()&&java.util.regex.Pattern.compile(pattern,java.util.regex.Pattern.CASE_INSENSITIVE).matcher(Objects.toString(text,"")).find();}
    private static String documentId(String id){return id!=null&&id.startsWith("vector:")?id.substring("vector:".length()):id;}
    private static AnswerArtifacts.Source sourceFor(AnswerArtifacts artifacts,String documentId,String heading) {
        var candidates=artifacts.sources().stream().filter(source->Objects.equals(documentId(source.documentId()),documentId)).toList();
        if(candidates.isEmpty())return null;
        return candidates.stream().filter(source->heading!=null&&heading.equals(source.heading())).findFirst()
                .or(() -> candidates.stream().filter(source->heading!=null&&source.heading()!=null&&(heading.contains(source.heading())||source.heading().contains(heading))).findFirst())
                .orElse(candidates.getFirst());
    }
    private static List<Fallback> rows(DatabaseBuilder db,AnswerArtifacts artifacts) {
        var documents=artifacts.sources().stream().map(AnswerArtifacts.Source::documentId).map(WebImagePolicy::documentId)
                .filter(Objects::nonNull).filter(id->!id.isBlank()).distinct().toList();
        if(documents.isEmpty())return List.of();
        var rows=new ArrayList<Fallback>();
        try(var c=db.getConnection();var p=c.prepareStatement("""
                SELECT r.id,r.document_id,r.url,r.caption,r.step_text,r.status,
                       b.id AS block_id,b.heading_path,b.content,
                       o.image_id,o.role,a.relative_path,a.width,a.height
                FROM rag_web_image_reference r
                LEFT JOIN rag_content_block b ON b.document_id=r.document_id AND b.ordinal=r.block_ordinal
                LEFT JOIN rag_image_occurrence o ON o.id=r.id
                LEFT JOIN rag_image_asset a ON a.id=o.image_id
                WHERE r.document_id=? AND r.status<>'FAILED'
                ORDER BY r.block_ordinal,r.id
                """)) {
            for(String document:documents) {
                p.setString(1,document);
                try(var result=p.executeQuery()){while(result.next()) {
                    String asset=result.getString("image_id"),path=result.getString("relative_path");
                    boolean cached=asset!=null&&path!=null;
                    rows.add(new Fallback(result.getString("id"),result.getString("document_id"),result.getString("url"),
                            Objects.toString(result.getString("caption"),""),Objects.toString(result.getString("step_text"),""),
                            Objects.toString(result.getString("heading_path"),""),
                            result.getString("block_id"),Objects.toString(result.getString("content"),""),
                            Objects.toString(result.getString("status"),""),Objects.toString(result.getString("role"),"ILLUSTRATION"),
                            result.getInt("width"),result.getInt("height"),cached,asset,path,0));
                }}
            }
        } catch(java.sql.SQLException e){throw new IllegalStateException("网页图片兜底候选读取失败",e);}
        return List.copyOf(rows);
    }
    private static List<Fallback> take(List<Fallback> scored,int limit) {
        var selected=new ArrayList<Fallback>();var references=new HashSet<String>();var urls=new HashSet<String>();
        for(var candidate:scored.stream().sorted(Comparator.comparingDouble(Fallback::score).reversed()
                .thenComparing(Fallback::referenceId)).toList()) {
            if(candidate.url()==null||candidate.url().isBlank())continue;
            String url;try{url=WebUrlPolicy.normalize(candidate.url());}catch(IllegalArgumentException invalid){url=candidate.url();}
            if(!references.add(candidate.referenceId())||!urls.add(url))continue;
            selected.add(candidate);if(selected.size()>=Math.max(1,limit))break;
        }
        return List.copyOf(selected);
    }
}
