package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.sql.*;
import java.util.*;

/** 二进制 OCR 与文档内的图片用途、步骤锚点分开保存，避免跨文档继承错误语境。 */
public final class GuideImageIndex {
    private final DatabaseBuilder db;
    public GuideImageIndex(DatabaseBuilder db){this.db=db;}
    public static void init(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_image_analysis(id VARCHAR(64) PRIMARY KEY,analysis_version VARCHAR(80) NOT NULL,ocr_status VARCHAR(80) NOT NULL,language VARCHAR(80))");
            s.execute("CREATE TABLE IF NOT EXISTS rag_image_grounding(id VARCHAR(200) PRIMARY KEY,image_id VARCHAR(64) NOT NULL,analysis_version VARCHAR(80) NOT NULL,purpose VARCHAR(40) NOT NULL,basis VARCHAR(40) NOT NULL,block_id VARCHAR(500),lineage VARCHAR(2000),step_text CLOB,relation VARCHAR(40) NOT NULL)");
        }
    }
    public void analysis(String image,String status,String language) {
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_image_analysis KEY(id) VALUES(?,?,?,?)")) {
            p.setString(1,image);p.setString(2,GuideImageSemantics.VERSION);p.setString(3,status);p.setString(4,language);p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("图片分析状态写入失败",e);}
    }
    record Cached(String text,String description,String status,String language) {}
    Cached cached(String image) {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT a.ocr_text,a.description,x.ocr_status,x.language FROM rag_image_asset a LEFT JOIN rag_image_analysis x ON x.id=a.id AND x.analysis_version=? WHERE a.id=?")) {
            p.setString(1,GuideImageSemantics.VERSION);p.setString(2,image);try(var r=p.executeQuery()){return r.next()?new Cached(r.getString(1),r.getString(2),r.getString(3),r.getString(4)):null;}
        }catch(SQLException e){throw new IllegalStateException("图片分析缓存读取失败",e);}
    }
    public GuideImageSemantics.Grounding find(String occurrence) {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT g.*,COALESCE(a.ocr_status,o.analysis_status) AS ocr_status FROM rag_image_grounding g LEFT JOIN rag_image_analysis a ON a.id=g.image_id LEFT JOIN rag_image_occurrence o ON o.id=g.id WHERE g.id=? AND g.analysis_version=?")) {
            p.setString(1,occurrence);p.setString(2,GuideImageSemantics.VERSION);try(var r=p.executeQuery()){return r.next()?new GuideImageSemantics.Grounding(r.getString("purpose"),r.getString("basis"),r.getString("block_id"),r.getString("lineage"),r.getString("step_text"),r.getString("relation"),Objects.toString(r.getString("ocr_status"),"NOT_ANALYZED")):null;}
        }catch(SQLException e){throw new IllegalStateException("图片步骤索引读取失败",e);}
    }
    void save(String occurrence,String image,GuideImageSemantics.Grounding grounding) {
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_image_grounding KEY(id) VALUES(?,?,?,?,?,?,?,?,?)")) {
            p.setString(1,occurrence);p.setString(2,image);p.setString(3,GuideImageSemantics.VERSION);p.setString(4,grounding.purpose());p.setString(5,grounding.basis());p.setString(6,grounding.blockId());p.setString(7,grounding.lineage());p.setString(8,grounding.stepText());p.setString(9,grounding.relation());p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("图片步骤索引写入失败",e);}
    }
    record Anchor(String id,String lineage,int ordinal,String heading,Integer page,String text,String method) {}
    Anchor anchor(String document,int ordinal) {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT * FROM rag_content_block WHERE document_id=? AND ordinal BETWEEN ? AND ? AND kind<>'IMAGE_OCR' ORDER BY ABS(ordinal-?),ordinal")) {
            p.setString(1,document);p.setInt(2,Math.max(0,ordinal-2));p.setInt(3,ordinal+1);p.setInt(4,ordinal);
            var blocks=new ArrayList<Anchor>();try(var r=p.executeQuery()){while(r.next())blocks.add(new Anchor(r.getString("id"),r.getString("lineage"),r.getInt("ordinal"),r.getString("heading_path"),(Integer)r.getObject("page_number"),r.getString("content"),r.getString("extraction_method")));}
            var own=blocks.stream().filter(b->b.ordinal()==ordinal).findFirst().orElse(null);if(own==null)return null;
            if(!own.method().equals("ADJACENT")&&!own.text().isBlank())return own;
            return blocks.stream().filter(b->b.ordinal()!=ordinal&&Objects.equals(b.lineage(),own.lineage())&&Objects.equals(b.page(),own.page())&&!b.text().isBlank()&&!b.method().equals("ADJACENT"))
                    .min(Comparator.<Anchor>comparingInt(b->Math.abs(b.ordinal()-ordinal)).thenComparingInt(Anchor::ordinal)).orElse(own);
        }catch(SQLException e){throw new IllegalStateException("图片段落锚点读取失败",e);}
    }
    void bind(String occurrence,String document,String image,String caption,String ocr,String status,int ordinal) {
        var anchor=anchor(document,ordinal);
        save(occurrence,image,GuideImageSemantics.describe(caption,ocr,status,anchor==null?null:anchor.id(),anchor==null?null:anchor.lineage(),
                anchor==null?"":anchor.heading()+"\n"+anchor.text(),anchor==null?"UNBOUND":anchor.ordinal()==ordinal?"EXACT_BLOCK":"ADJACENT_BLOCK"));
    }
}
