package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 图标按导出环境与完整注册名查询，不参与知识向量召回，也不自动跨环境选择。 */
public final class ItemIconStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    public record StoredIcon(String profileId,String itemId,String assetId,String relativePath,String origin,String compatibility) {}
    public record Profile(String id,String minecraftVersion,String loader,String origin,String compatibility,
                          String manifestHash,String environmentHash,int icons,String location) {
        @Override public String toString() { return id == null ? "仅静态图标 / 缺失占位" : loader + " " + minecraftVersion + " · " + icons + " 图标 · " + origin + " · " + compatibility + " · " + id.substring(0,8); }
        public static Profile none() { return new Profile(null,null,null,null,null,null,null,0,null); }
    }
    private final DatabaseBuilder db;
    public ItemIconStore(DatabaseBuilder db){this.db=db;}
    public static void init(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_item_icon_profile(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(64) NOT NULL,manifest_hash VARCHAR(64) NOT NULL,environment_hash VARCHAR(64) NOT NULL,origin VARCHAR(32) NOT NULL,compatibility VARCHAR(40) NOT NULL,manifest_json CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_item_icon(id VARCHAR(64) PRIMARY KEY,profile_id VARCHAR(64) NOT NULL,item_id VARCHAR(500) NOT NULL,variant VARCHAR(40) NOT NULL,image_id VARCHAR(64),status VARCHAR(32) NOT NULL,error_text VARCHAR(2000),UNIQUE(profile_id,item_id,variant))");
            s.execute("ALTER TABLE rag_item_icon ALTER COLUMN variant VARCHAR(80)");
        }
    }
    public void importPack(BuildRequest.Source source,NativeIconPack.Pack prepared,BuildSourceProcessor.Context context)throws Exception {
        var pack=NativeIconPack.read(prepared.root());
        if(!pack.manifestHash().equals(prepared.manifestHash()))throw new IllegalStateException("预检查后导出包内容发生变化");
        new RuntimeKnowledgeStore(db).importPack(source,pack,context);
        String previousSelected=activeProfileId();boolean updateSelection=false;
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT id,manifest_hash,environment_hash FROM rag_item_icon_profile WHERE source_id=?")) {
            p.setString(1,source.id());try(var r=p.executeQuery()) {while(r.next()) {
                if(r.getString(2).equals(pack.manifestHash())){context.result().reusedIconPacks++;return;}
                if(r.getString(1).equals(previousSelected)&&r.getString(3).equals(pack.environmentHash()))updateSelection=true;
            }}
        }
        String compatibility=compatibility(pack,db.findAllModEntries());
        String profile=BuildRequest.fingerprint(source.id()+"\n"+pack.manifestHash());
        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_item_icon_profile VALUES(?,?,?,?,?,?,?)")) {
            p.setString(1,profile);p.setString(2,source.id());p.setString(3,pack.manifestHash());p.setString(4,pack.environmentHash());p.setString(5,pack.origin());p.setString(6,compatibility);p.setString(7,pack.manifestJson());p.executeUpdate();
        }
        for(var icon:pack.icons()) {
            context.checkCancelled();String image=null;
            if(Set.of("OK","TRANSPARENT").contains(icon.status())) {
                NativeIconPack.validate(pack.root(),icon,pack.rendering().path("width").asInt());
                byte[] bytes;try(var input=Files.newInputStream(ArchivePaths.resolve(pack.root(),icon.file()),LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(8*1024*1024+1);}
                if(bytes.length>8*1024*1024)throw new IllegalStateException("图标超过导入上限");
                var asset=context.images().put(bytes);
                if(!asset.id().equals(icon.sha256()))throw new IllegalStateException("导入期间图标内容发生变化");
                context.content().saveImage(asset);image=asset.id();
                if(icon.status().equals("TRANSPARENT"))context.result().transparentNativeIcons++;else context.result().nativeIcons++;
            } else context.result().failedNativeIcons++;
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_item_icon VALUES(?,?,?,?,?,?,?)")) {
                p.setString(1,BuildRequest.fingerprint(profile+"\n"+icon.itemId()+ (icon.variant().equals("default")?"":"\n"+icon.variant())));p.setString(2,profile);p.setString(3,icon.itemId());p.setString(4,icon.variant());p.setString(5,image);p.setString(6,icon.status());p.setString(7,icon.error());p.executeUpdate();
            }
        }
        context.result().nativeIconPacks++;
        if(updateSelection&&!compatibility.equals("CONFLICT")){selectProfile(profile);context.result().warnings.add("已更新当前来源的图标环境；旧会话图片快照保持不变。");}
        if(new SourceLifecycleStore(db).strings("SELECT profile_id FROM rag_runtime_snapshot WHERE source_id=?",source.id()).isEmpty())context.result().warnings.add("旧版仅图标包仅供历史回放，不满足完整快照构建要求。");
        if(context.progress()!=null)context.progress().onProgress("icons",context.result().nativeIcons,pack.icons().size(),"已导入图标包："+source.location());
    }
    static String compatibility(NativeIconPack.Pack pack,List<ModEntry> installed) {
        return compatibility(pack.environment(), installed);
    }
    static String compatibility(com.fasterxml.jackson.databind.JsonNode environment,List<ModEntry> installed) {
        if(installed.isEmpty())return "UNVERIFIED";boolean matched=false,unknown=false;
        var declared=new HashMap<String,String>();environment.path("mods").forEach(m -> declared.put(m.path("id").asText(),m.path("version").asText()));
        for(var mod:installed) {
            if(mod.getLoader()!=null && !mod.getLoader().isBlank() && !mod.getLoader().equals("vanilla") && !mod.getLoader().equals(environment.path("loader").asText()))return "CONFLICT";
            String mc=mod.getMcVersion();if(mc!=null && mc.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?") && !mc.equals(environment.path("minecraftVersion").asText()))return "CONFLICT";
            String version=declared.get(mod.getModId());
            if(version==null || mod.getVersion()==null || mod.getVersion().contains("${"))unknown=true;
            else if(!version.equals(mod.getVersion()))return "CONFLICT";else matched=true;
        }
        return matched && !unknown?"DECLARED_MATCH":"UNVERIFIED";
    }
    public List<Profile> profiles() {
        var mods = db.findAllModEntries(); var result = new ArrayList<Profile>();
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT p.*,s.location,(SELECT COUNT(*) FROM rag_item_icon i WHERE i.profile_id=p.id AND i.status IN ('OK','TRANSPARENT')) AS icon_count FROM rag_item_icon_profile p JOIN rag_knowledge_source s ON s.id=p.source_id ORDER BY p.id")) {
            while (r.next()) {
                var environment = JSON.readTree(r.getString("manifest_json")).path("environment");
                result.add(new Profile(r.getString("id"),environment.path("minecraftVersion").asText(),environment.path("loader").asText(),r.getString("origin"),compatibility(environment,mods),r.getString("manifest_hash"),r.getString("environment_hash"),r.getInt("icon_count"),r.getString("location")));
            }
        } catch (Exception e) { throw new IllegalStateException("图标环境读取失败",e); }
        return List.copyOf(result);
    }
    public String activeProfileId() {
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT meta_value FROM rag_build_metadata WHERE name='icon_profile'")) { return r.next() ? r.getString(1) : null; }
        catch (SQLException e) { throw new IllegalStateException("图标环境选择读取失败",e); }
    }
    public void selectProfile(String id) {
        if (id != null) {
            var profile = profiles().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("图标环境不存在"));
            if (profile.compatibility().equals("CONFLICT")) throw new IllegalArgumentException("图标环境与基础库的游戏、加载器或模组版本冲突");
        }
        try (var c = db.getConnection(); var p = c.prepareStatement(id == null ? "DELETE FROM rag_build_metadata WHERE name='icon_profile'" : "MERGE INTO rag_build_metadata(name,meta_value) KEY(name) VALUES('icon_profile',?)")) {
            if (id != null) p.setString(1,id); p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("图标环境选择保存失败",e); }
    }
    public Optional<StoredIcon> findDefault(String profileId,String itemId) {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT p.origin,p.compatibility,i.image_id,a.relative_path FROM rag_item_icon i JOIN rag_item_icon_profile p ON p.id=i.profile_id JOIN rag_image_asset a ON a.id=i.image_id WHERE i.profile_id=? AND i.item_id=? AND i.variant='default' AND i.status IN ('OK','TRANSPARENT')")) {
            p.setString(1,profileId);p.setString(2,itemId);try(var r=p.executeQuery()) {return r.next()?Optional.of(new StoredIcon(profileId,itemId,r.getString(3),r.getString(4),r.getString(1),r.getString(2))):Optional.empty();}
        } catch(SQLException e){throw new IllegalStateException("图标精确查询失败",e);}
    }
    public boolean hasUsableIcons() {
        try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM rag_item_icon WHERE status IN ('OK','TRANSPARENT')")) {r.next();return r.getLong(1)>0;}
        catch(SQLException e){throw new IllegalStateException("图标数量查询失败",e);}
    }
}
