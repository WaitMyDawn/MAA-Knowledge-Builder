package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.awt.*;
import yagen.waitmydawn.kb.model.*;

/** 规则卡展示原始池、条件和函数，不将权重换算为最终概率。 */
public final class LootCardService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final DatabaseBuilder db;private final Path directory;
    public LootCardService(DatabaseBuilder db,Path directory){this.db=db;this.directory=directory;}
    public Path cached(String profile,String category,String id)throws Exception {return render(profile,category,id,true);}
    public Path render(String profile,String category,String id)throws Exception {long started=System.nanoTime();try{return render(profile,category,id,false);}finally{RetrievalTelemetry.operation("render.loot.total",System.nanoTime()-started,1);}}
    private Path render(String profile,String category,String id,boolean cacheOnly)throws Exception {
        if(!Objects.equals(profile,RuntimeKnowledgeStore.active(db)))throw new IllegalArgumentException("渲染前请在问答页选择该快照，避免混用图标环境");
        String raw;try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category=? AND record_id=?")){p.setString(1,profile);p.setString(2,category);p.setString(3,id);try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("掉落记录已不存在");raw=r.getString(1);}}
        var icons=new ItemIconService(db,directory).snapshot();String key=BuildRequest.fingerprint("loot-card-v6\n"+profile+"\n"+icons.revision()+"\n"+category+"\n"+id+"\n"+raw);
        Path folder=directory.resolve("generated/loot");if(!cacheOnly)Files.createDirectories(folder);Path file=folder.resolve(key+".png"),meta=folder.resolve(key+".json");
        try{if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(meta,LinkOption.NOFOLLOW_LINKS)&&JSON.readTree(meta.toFile()).path("pngHash").asText().equals(ArtifactFingerprint.sha256(file))){RetrievalTelemetry.operation("render.loot.hit",0,1);return file;}}catch(java.io.IOException invalid){/* 损坏的派生缓存可从快照规则重新生成。 */}
        RetrievalTelemetry.operation("render.loot.miss",0,1);if(cacheOnly)return null;
        var row=JSON.readTree(raw);if(row.has("error")||!row.path("definition").isObject())throw new IllegalArgumentException("该记录没有可渲染的有效规则，请查看原始JSON");var image=ChestLootRenderer.render(id,profile,row.path("definition"),icons);
        Path tmp=Files.createTempFile(folder,"loot-",".png");try{javax.imageio.ImageIO.write(image,"PNG",tmp.toFile());Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
        var report=JSON.createObjectNode().put("fingerprint",key).put("pngHash",ArtifactFingerprint.sha256(file)).put("profile",profile).put("category",category).put("id",id);Files.writeString(meta,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));return file;
    }
    public void attach(QaPipeline.QaResult result,String question){
        if(!question.matches("(?s).*(掉落|战利品).*"))return;
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return;var seen=new HashSet<String>();
        for(var hit:result.vectorResults){if(seen.size()>=2)break;if(hit.score()<=0.3)continue;String category=hit.type().equals("runtime_loot_tables")?"loot_tables":hit.type().equals("runtime_loot_modifiers")?"loot_modifiers":null;if(category==null)continue;
            String prefix="mkb-runtime:"+profile+"/"+category+"/";String location=hit.sourcePath();
            if(!location.startsWith(prefix))try(var c=db.getConnection();var p=c.prepareStatement("SELECT source_path FROM rag_vectors WHERE chunk_id=? AND chunk_type=?")){p.setString(1,hit.chunkId());p.setString(2,hit.type());try(var r=p.executeQuery()){if(r.next())location=r.getString(1);}}catch(Exception failure){result.mediaWarnings.add("掉落条目来源读取失败");}
            if(!location.startsWith(prefix))continue;String id=location.substring(prefix.length());if(!seen.add(category+id))continue;
            try{var file=render(profile,category,id);var assets=new ImageAssetStore(directory);var asset=assets.put(Files.readAllBytes(file));String source="s"+BuildRequest.fingerprint(prefix+id).substring(0,20);
                var sources=new ArrayList<>(result.artifacts.sources());var pictures=new ArrayList<>(result.artifacts.pictures());sources.add(new AnswerArtifacts.Source(source,prefix+id,id,prefix+id,"战利品规则",null,"RUNTIME_SNAPSHOT",hit.chunkText()));pictures.add(new AnswerArtifacts.Picture("i"+asset.id().substring(0,20),asset.id(),asset.relativePath(),"战利品规则："+id,source,"LOOT",java.util.List.of(hit.chunkId()),hit.chunkText()));result.artifacts=new AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),sources,pictures,result.artifacts.citedSourceIds());
            }catch(Exception e){result.mediaWarnings.add("战利品图："+id+" · "+e.getMessage());}
        }
    }
}
