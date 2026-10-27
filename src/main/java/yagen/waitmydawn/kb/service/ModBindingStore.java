package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.*;
import java.sql.*;
import java.util.*;

/** 绑定草稿独立保存，不随构建事务清空；发布值存放在模组元数据中。 */
public final class ModBindingStore {
    private final DatabaseBuilder db;private static final ObjectMapper JSON=new ObjectMapper();
    public ModBindingStore(DatabaseBuilder db){this.db=db;}
    public static void init(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS rag_mod_binding_draft(id VARCHAR(64) PRIMARY KEY,mod_id VARCHAR(255) NOT NULL,loader VARCHAR(50),mod_version VARCHAR(100),slug VARCHAR(200),binding_json CLOB)");}}
    private static String key(ModEntry m){return BuildRequest.fingerprint(m.getModId()+"\n"+Objects.toString(m.getLoader(),""));}
    public void save(ModEntry m){
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_mod_binding_draft KEY(id) VALUES(?,?,?,?,?,?)")){
            p.setString(1,key(m));p.setString(2,m.getModId());p.setString(3,m.getLoader());p.setString(4,m.getVersion());p.setString(5,m.getSlug());p.setString(6,m.getMcmodBinding()==null?null:JSON.writeValueAsString(m.getMcmodBinding()));p.executeUpdate();
        }catch(Exception e){throw new IllegalStateException("绑定草稿保存失败",e);}
    }
    public boolean apply(ModEntry m,ModEntry published){
        var incoming=m.getMcmodBinding();String incomingSlug=m.getSlug(),incomingUrl=m.getModrinthUrl(),incomingSource=m.getSource();
        if(published!=null&&Objects.equals(published.getLoader(),m.getLoader())){
            if(m.getSlug()==null){m.setSlug(published.getSlug());m.setModrinthUrl(published.getModrinthUrl());m.setSource(published.getSource());}
            if(m.getMcmodBinding()==null)m.setMcmodBinding(published.getMcmodBinding());
        }
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT * FROM rag_mod_binding_draft WHERE id=?")) {
            p.setString(1,key(m));try(var r=p.executeQuery()){
                if(!r.next()){if(ModUrlBinding.vanilla(m))m.setMcmodBinding(ModUrlBinding.vanilla());return false;}
                m.setSlug(r.getString("slug"));m.setModrinthUrl(m.getSlug()==null?null:"https://modrinth.com/mod/"+m.getSlug());
                String value=r.getString("binding_json");var binding=value==null?null:JSON.readValue(value,ModUrlBinding.class);
                if(binding!=null&&!Objects.equals(m.getVersion(),r.getString("mod_version")))binding=new ModUrlBinding(binding.url(),binding.chineseName(),binding.englishName(),binding.views(),binding.status(),binding.origin(),binding.checkedAt(),"模组版本已变化，请复核绑定");
                if(incomingSlug!=null&&m.getSlug()==null&&(binding==null||!"MANUAL".equals(binding.origin()))){m.setSlug(incomingSlug);m.setModrinthUrl(incomingUrl);m.setSource(incomingSource);}
                if(incoming!=null&&(binding==null||"MANUAL".equals(incoming.origin())&&!"MANUAL".equals(binding.origin()))){
                    if("MANUAL".equals(incoming.origin())){m.setSlug(incomingSlug);m.setModrinthUrl(incomingUrl);m.setSource(incomingSource);}binding=incoming;
                }
                m.setMcmodBinding(ModUrlBinding.vanilla(m)?ModUrlBinding.vanilla():binding);return true;
            }
        }catch(Exception e){throw new IllegalStateException("绑定草稿读取失败",e);}
    }
    /** 自动草稿中的空 slug 允许重试，人工编辑保留的空值不应被覆盖。 */
    public static boolean shouldLookupSlug(ModEntry m){return m.getSlug()==null&&!ModUrlBinding.vanilla(m)&&(m.getMcmodBinding()==null||!"MANUAL".equals(m.getMcmodBinding().origin()));}
    public static String encode(ModUrlBinding b){try{return b==null?null:JSON.writeValueAsString(b);}catch(Exception e){throw new IllegalArgumentException(e);}}
    public static ModUrlBinding decode(String value)throws SQLException{try{return value==null?null:JSON.readValue(value,ModUrlBinding.class);}catch(Exception e){throw new SQLException("百科绑定元数据不可读",e);}}
    public static String signature(ModUrlBinding b){return b==null?"|false":b.signature();}
}
