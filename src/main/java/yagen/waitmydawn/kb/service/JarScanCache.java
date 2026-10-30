package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** 便携扫描快照只保存公开模组元数据；复用前校验环境与 JAR 内容摘要。 */
public final class JarScanCache {
    public static final String FILE_NAME=".mkb-pre.json";
    public static final String LEGACY_FILE_NAME=".mkb-scan.json";
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Item(String relativePath,long size,long modified,String sha512,ModEntry mod,String checkedAt){}
    public record Step(int completed,int total){}
    public record Progress(Step scan,Step bindUrl){}
    public record Snapshot(int schemaVersion,GameEnvironment environment,List<Item> items,Progress progress){}
    private final Path folder,file;
    private Snapshot snapshot;
    private final Map<String,Item> items=new LinkedHashMap<>();
    private String warning;
    private Path legacyFile;
    private int inventoryTotal;
    public static boolean exists(Path folder){return Files.isRegularFile(folder.resolve(FILE_NAME))||Files.isRegularFile(folder.resolve(LEGACY_FILE_NAME));}
    public JarScanCache(Path folder){this(folder,folder.resolve(FILE_NAME),true);}
    JarScanCache(Path folder,Path file){this(folder,file,false);}
    private JarScanCache(Path folder,Path file,boolean migrate){
        this.folder=folder.toAbsolutePath().normalize();this.file=file.toAbsolutePath().normalize();
        Path input=this.file;
        if(!Files.isRegularFile(input)&&migrate&&Files.isRegularFile(this.folder.resolve(LEGACY_FILE_NAME))){legacyFile=this.folder.resolve(LEGACY_FILE_NAME);input=legacyFile;}
        if(!Files.isRegularFile(input))return;
        try{
            if(Files.size(input)>16*1024*1024)throw new IOException("记录超过16MB");
            snapshot=JSON.readValue(input.toFile(),Snapshot.class);
            if((snapshot.schemaVersion()!=1&&snapshot.schemaVersion()!=2)||snapshot.items()==null||snapshot.items().size()>20000)throw new IOException("不支持的扫描记录格式");
            for(var item:snapshot.items()){
                Path resolved=this.folder.resolve(item.relativePath()).normalize();
                if(!resolved.startsWith(this.folder)||Path.of(item.relativePath()).isAbsolute()||item.sha512()==null||!item.sha512().matches("[0-9a-f]{128}"))throw new IOException("扫描记录包含无效文件路径或摘要");
                items.put(key(resolved),item);
            }
            inventoryTotal=snapshot.progress()==null?items.size():Math.max(items.size(),snapshot.progress().scan().total());
        }catch(Exception e){snapshot=null;items.clear();warning="扫描记录无法读取，将重新扫描："+e.getMessage();}
    }
    private String key(Path path){return folder.relativize(path.toAbsolutePath().normalize()).toString();}
    public Path file(){return file;}
    public String warning(){return warning;}
    public GameEnvironment environment(){return snapshot==null?null:snapshot.environment();}
    public Snapshot snapshot(){return snapshot==null?null:new Snapshot(2,snapshot.environment(),List.copyOf(items.values()),progress());}
    public void begin(GameEnvironment environment){if(!matches(environment)){items.clear();inventoryTotal=0;snapshot=new Snapshot(2,environment,List.of(),new Progress(new Step(0,0),new Step(0,0)));}}
    public boolean matches(GameEnvironment environment){return Objects.equals(environment(),environment);}
    public ModEntry reusable(Path path,String hash,GameEnvironment environment){
        if(!matches(environment))return null;var item=items.get(key(path));if(item==null||!hash.equals(item.sha512()))return null;
        if(item.mod()==null)return null;
        var mod=JSON.convertValue(item.mod(),ModEntry.class);mod.setJarPath(path.toAbsolutePath().normalize().toString());mod.setJarFileName(path.getFileName().toString());return mod;
    }
    public boolean reusableSkipped(Path path,String hash,GameEnvironment environment){var i=items.get(key(path));return matches(environment)&&i!=null&&i.mod()==null&&hash.equals(i.sha512());}
    public boolean retryLookup(Path path){
        var i=items.get(key(path));if(i==null||i.mod()==null)return true;
        if("modrinth-unavailable".equals(i.mod().getSource()))return true;
        if(i.mod().getSlug()!=null||!ModBindingStore.shouldLookupSlug(i.mod()))return false;
        try{return Instant.parse(i.checkedAt()).plusSeconds(86400).isBefore(Instant.now());}catch(Exception e){return true;}
    }
    public void put(Path path,String hash,ModEntry mod)throws IOException{put(path,hash,mod,true);}
    public void put(Path path,String hash,ModEntry mod,boolean refreshed)throws IOException{
        var previous=items.get(key(path));var attr=Files.readAttributes(path,BasicFileAttributes.class);String relative=key(path);
        items.put(relative,new Item(relative,attr.size(),attr.lastModifiedTime().toMillis(),hash,mod,!refreshed&&previous!=null?previous.checkedAt():Instant.now().toString()));
    }
    public void retain(Collection<Path> inventory){inventoryTotal=inventory.size();var keys=new HashSet<String>();inventory.forEach(p->keys.add(key(p)));items.keySet().retainAll(keys);}
    public void updateBinding(ModEntry mod){
        if(mod.getJarPath()==null)return;Path path=Path.of(mod.getJarPath()).toAbsolutePath().normalize();if(!path.startsWith(folder))return;
        var old=items.get(key(path));if(old==null||old.mod()==null)return;
        var copy=JSON.convertValue(old.mod(),ModEntry.class);copy.setSlug(mod.getSlug());copy.setModrinthUrl(mod.getModrinthUrl());copy.setSource(mod.getSource());copy.setMcmodBinding(mod.getMcmodBinding());
        items.put(key(path),new Item(old.relativePath(),old.size(),old.modified(),old.sha512(),copy,old.checkedAt()));
    }
    public static boolean scanReady(ModEntry mod){return mod==null||mod.getSlug()!=null||!"modrinth-unavailable".equals(mod.getSource());}
    public static boolean bindingReady(ModEntry mod){var b=mod.getMcmodBinding();return b!=null&&Set.of("FOUND","NOT_FOUND","DISABLED","BUILTIN").contains(b.status());}
    public static long bindingCompleted(Collection<ModEntry> mods){return mods.stream().filter(JarScanCache::bindingReady).count();}
    public Progress progress(){
        int scanned=0,bound=0,totalMods=0;
        for(var item:items.values()){if(scanReady(item.mod()))scanned++;if(item.mod()!=null){totalMods++;if(bindingReady(item.mod()))bound++;}}
        return new Progress(new Step(scanned,Math.max(items.size(),inventoryTotal)),new Step(bound,totalMods));
    }
    public boolean save(GameEnvironment environment){
        Path temporary=null;
        try{
            snapshot=new Snapshot(2,environment,List.copyOf(items.values()),progress());
            temporary=Files.createTempFile(file.getParent(),".mkb-pre-",".tmp");JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),snapshot);
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
            if(legacyFile!=null){try{Files.deleteIfExists(legacyFile);legacyFile=null;}catch(IOException e){warning="新预处理记录已保存，旧扫描记录未能移除："+e.getMessage();}}
            return true;
        }catch(IOException e){warning="无法保存扫描记录（扫描结果仍可用于本次操作）："+e.getMessage();return false;}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    public record Hashes(String sha512,String sha256){}
    public static String hash(Path path)throws IOException{return hashes(path).sha512();}
    public static Hashes hashes(Path path)throws IOException{
        try{
            var digest=MessageDigest.getInstance("SHA-512");var buildDigest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] bytes=new byte[128*1024];int n;while((n=input.read(bytes))!=-1){BuildCancellation.check();digest.update(bytes,0,n);buildDigest.update(bytes,0,n);}}
            return new Hashes(HexFormat.of().formatHex(digest.digest()),HexFormat.of().formatHex(buildDigest.digest()));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
