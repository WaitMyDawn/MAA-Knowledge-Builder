package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.core.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 调用方须持有目录独占锁并串行化构建、会话写入与维护；引用扫描不完整时禁止回收。 */
public final class ImageCacheMaintenance {
    public static final Duration RETENTION=Duration.ofDays(7);
    private static final Set<String> SNAPSHOT_FIELDS=Set.of("assetId","relativePath","originalUrl","referer");
    private static final java.util.regex.Pattern LEGACY_IMAGE=java.util.regex.Pattern.compile("(?i)assets[/\\\\]images[/\\\\]([0-9a-f]{2})[/\\\\]([0-9a-f]{64})\\.(png|jpg|gif)");
    private final DatabaseBuilder db;
    private final Path dataDir;
    private final ImageAssetStore assets;
    private final Clock clock;

    public ImageCacheMaintenance(DatabaseBuilder db,Path dataDir) {this(db,dataDir,Clock.systemUTC());}
    ImageCacheMaintenance(DatabaseBuilder db,Path dataDir,Clock clock) {
        this.db=db;this.dataDir=dataDir.toAbsolutePath().normalize();this.assets=new ImageAssetStore(dataDir);this.clock=clock;
    }
    public record Problem(String assetId,String status) {}
    public record Report(int files,long bytes,int references,int missing,int corrupt,int orphans,long orphanBytes,
                         int removable,long removableBytes,long rawResources,long rawBytes,long buildPending,
                         boolean cleanupSafe,List<Problem> problems,List<String> warnings) {}
    public record Cleanup(int removed,long bytes,Report after) {}
    public record Repair(int attempted,int restored,int unavailable,List<String> messages,Report after) {}
    private record Origin(String url,String referer) {}
    private static final class Reference {
        final String id,path;
        final Set<Origin> origins=new LinkedHashSet<>();
        Reference(String id,String path) {this.id=id;this.path=path;}
    }
    private record Garbage(String relative,long size,long modified) {}
    private static final class Scan {
        final Map<String,Reference> references=new LinkedHashMap<>();
        final List<String> warnings=new ArrayList<>();
        final List<Problem> problems=new ArrayList<>();
        final List<Garbage> garbage=new ArrayList<>();
        long historyBytes,bytes,orphanBytes,removableBytes,rawResources,rawBytes,buildPending;
        int historyFiles,files,missing,corrupt,orphans;
        boolean safe=true;
        Report report() {return new Report(files,bytes,references.size(),missing,corrupt,orphans,orphanBytes,garbage.size(),
                removableBytes,rawResources,rawBytes,buildPending,safe,List.copyOf(problems),List.copyOf(warnings));}
        void blocked(String message) {safe=false;if(warnings.size()<20) warnings.add(message);}
    }

    public Report inspect() throws IOException {return scan().report();}

    public Cleanup cleanUnreferenced() throws IOException {
        Scan scan=scan();
        if(!scan.safe) throw new IOException("引用检查未完成，未清理任何图片："+String.join("；",scan.warnings));
        int removed=0;long bytes=0;
        for(var candidate:scan.garbage) {
            cancelled();
            Path file=ArchivePaths.resolve(dataDir.resolve("assets/images"),candidate.relative());
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)!=candidate.size()
                    || Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis()!=candidate.modified()) continue;
            Files.delete(file);removed++;bytes+=candidate.size();
        }
        return new Cleanup(removed,bytes,inspect());
    }

    /** 修复仅从已发布来源或快照保存的网址取回原字节，不刷新正文或替换历史图片身份。 */
    public Repair repairMissing(WikiScraperService scraper,int budget) throws IOException {
        Scan scan=scan();scraper.setResourceDatabase(db);
        int attempted=0,restored=0,unavailable=0;
        var messages=new ArrayList<String>();
        for(var ref:scan.references.values()) {
            cancelled();
            if(assets.available(ref.id,ref.path)) continue;
            boolean fixed=false;
            for(var origin:ref.origins) {
                if(attempted>=Math.max(0,Math.min(16,budget))) break;
                attempted++;
                try {
                    var response=scraper.fetchBuildResource(origin.url(),origin.referer(),true);
                    if(response.status()!=200 || response.bytes()==null) continue;
                    assets.restore(ref.id,response.bytes());fixed=true;restored++;break;
                } catch(java.util.concurrent.CancellationException e) {throw e;}
                catch(IOException | IllegalArgumentException e) {messages.add(ref.id.substring(0,12)+"："+e.getMessage());}
            }
            if(!fixed) unavailable++;
        }
        return new Repair(attempted,restored,unavailable,List.copyOf(messages),inspect());
    }

    private Scan scan() throws IOException {
        var scan=new Scan();
        try {new KnowledgeContentStore(db).validate();}
        catch(IllegalStateException e) {throw new IOException("基础库图文关联不完整，停止图片维护；请检查或重建资料",e);}
        try(var c=db.getConnection();var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT id,relative_path FROM rag_image_asset ORDER BY id")) {
                while(rows.next()) add(scan,rows.getString(1),rows.getString(2),null,null);
            }
            try(var rows=s.executeQuery("SELECT o.image_id,i.relative_path,o.original_location,d.location FROM rag_image_occurrence o JOIN rag_image_asset i ON i.id=o.image_id JOIN rag_document d ON d.id=o.document_id")) {
                while(rows.next()) add(scan,rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4));
            }
            try(var rows=s.executeQuery("SELECT COUNT(*) FROM rag_web_image_reference WHERE status<>'AVAILABLE'")) {rows.next();scan.buildPending=rows.getLong(1);}
            try(var tables=c.getMetaData().getTables(null,null,"rag_public_resource",null)) {
                if(tables.next()) try(var rows=s.executeQuery("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(body)),0) FROM rag_public_resource")) {
                    rows.next();scan.rawResources=rows.getLong(1);scan.rawBytes=rows.getLong(2);
                }
            }
        } catch(java.sql.SQLException e) {throw new IOException("图片引用读取失败",e);}
        readSessions(scan,dataDir.resolve("sessions"));
        Path runs=dataDir.resolve("eval-runs");
        if(Files.exists(runs,LinkOption.NOFOLLOW_LINKS)) {
            ArchivePaths.resolve(dataDir,"eval-runs");
            try(var children=Files.newDirectoryStream(runs)) {
                for(Path run:children) {
                    cancelled();
                    if(scan.historyFiles>=10000) {scan.blocked("历史引用扫描超过文件预算");break;}
                    String name=run.getFileName().toString().toLowerCase(Locale.ROOT);
                    if(name.startsWith(".") || Set.of("secrets","credentials").contains(name)) {scan.blocked("评测目录含无法扫描的子目录");continue;}
                    if(Files.isSymbolicLink(run)) {scan.blocked("评测目录包含符号链接，不能确认历史引用");continue;}
                    if(!Files.isDirectory(run,LinkOption.NOFOLLOW_LINKS)) continue;
                    readSessions(scan,run.resolve("sessions"));
                    readHistory(scan,run.resolve("turns.jsonl"));
                }
            }
        }
        for(var ref:scan.references.values()) {
            cancelled();
            if(!assets.available(ref.id,ref.path)) {
                boolean exists=Files.exists(assets.resolve(ref.id,ref.path),LinkOption.NOFOLLOW_LINKS);
                if(exists) scan.corrupt++; else scan.missing++;
                scan.problems.add(new Problem(ref.id,exists?"损坏":"缺失"));
            }
        }
        inspectFiles(scan);
        return scan;
    }

    private void add(Scan scan,String id,String relative,String original,String referer) {
        try {
            assets.resolve(id,relative);
            var ref=scan.references.computeIfAbsent(id,key -> new Reference(id,relative));
            if(!ref.path.equals(relative)) {scan.blocked("同一图片身份存在不同路径");return;}
            if(original!=null && original.matches("(?i)^https?://.*") && ref.origins.size()<4)
                ref.origins.add(new Origin(WebUrlPolicy.normalize(original),referer!=null && referer.matches("(?i)^https?://.*")?referer:null));
        } catch(IOException | IllegalArgumentException e) {scan.blocked("图片引用路径或网址无效，不能确认完整引用集合");}
    }

    private void readSessions(Scan scan,Path sessions) throws IOException {
        if(!Files.exists(sessions,LinkOption.NOFOLLOW_LINKS)) return;
        ArchivePaths.resolve(dataDir,dataDir.relativize(sessions).toString().replace('\\','/'));
        try(var files=Files.newDirectoryStream(sessions,"*.json")) {
            for(Path file:files) {
                if(scan.historyFiles>=10000) {scan.blocked("历史引用扫描超过文件预算");break;}
                readHistory(scan,file);
            }
        }
    }

    private void readHistory(Scan scan,Path file) {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return;
        cancelled();
        try {
            String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
            if(name.equals(".env") || name.startsWith(".env.") || Set.of("secrets.json","credentials.json").contains(name))
                throw new IOException("不读取敏感文件");
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("不是普通会话文件");
            long size=Files.size(file);scan.historyBytes+=size;
            if(++scan.historyFiles>10000 || size>32L*1024*1024 || scan.historyBytes>128L*1024*1024) throw new IOException("历史引用扫描超过预算");
            var stack=new ArrayDeque<Map<String,String>>();boolean found=false;int roots=0;
            boolean jsonLines=file.getFileName().toString().endsWith(".jsonl");
            try(var parser=new JsonFactory().createParser(file.toFile())) {
                JsonToken token;
                while((token=parser.nextToken())!=null) {
                    cancelled();found=true;
                    if(stack.isEmpty() && token!=JsonToken.START_OBJECT) throw new IOException("历史记录根节点不是对象");
                    if(token==JsonToken.START_OBJECT) {
                        if(stack.isEmpty() && ++roots>1 && !jsonLines) throw new IOException("会话包含多个根节点");
                        if(stack.size()==1 && "observation".equals(parser.currentName())) stack.peek().put("__record","true");
                        stack.push(new HashMap<>());
                    }
                    else if(token==JsonToken.START_ARRAY && stack.size()==1 && "messages".equals(parser.currentName())) stack.peek().put("__messages","true");
                    else if(token==JsonToken.VALUE_STRING && !stack.isEmpty() && parser.currentName()!=null && SNAPSHOT_FIELDS.contains(parser.currentName()))
                        stack.peek().put(parser.currentName(),parser.getText());
                    else if(token==JsonToken.VALUE_STRING && ("content".equals(parser.currentName()) || "answer".equals(parser.currentName()))) {
                        if(stack.size()==1 && "answer".equals(parser.currentName())) stack.peek().put("__record","true");
                        var image=LEGACY_IMAGE.matcher(parser.getText());
                        while(image.find()) add(scan,image.group(2).toLowerCase(Locale.ROOT),image.group(1).toLowerCase(Locale.ROOT)+"/"
                                +image.group(2).toLowerCase(Locale.ROOT)+"."+image.group(3).toLowerCase(Locale.ROOT),null,null);
                    }
                    else if(token==JsonToken.END_OBJECT) {
                        var fields=stack.pop();
                        if(stack.isEmpty() && !(fields.containsKey(jsonLines?"__record":"__messages"))) throw new IOException("历史记录结构不完整");
                        if(fields.containsKey("assetId")) add(scan,fields.get("assetId"),fields.get("relativePath"),fields.get("originalUrl"),fields.get("referer"));
                    }
                }
            }
            if(!found) throw new IOException("空会话文件");
        } catch(IOException | IllegalArgumentException e) {scan.blocked("无法读取历史引用："+file.getFileName());}
    }

    private void inspectFiles(Scan scan) throws IOException {
        Path root=ArchivePaths.resolve(dataDir,"assets/images");
        if(!Files.exists(root)) return;
        long cutoff=clock.millis()-RETENTION.toMillis();
        try(var directories=Files.newDirectoryStream(root)) {
            for(Path directory:directories) {
                cancelled();
                if(Files.isSymbolicLink(directory)) {scan.blocked("图片目录包含符号链接");continue;}
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS) || !directory.getFileName().toString().matches("[0-9a-f]{2}")) continue;
                try(var files=Files.newDirectoryStream(directory)) {
                    for(Path file:files) {
                        cancelled();
                        String name=file.getFileName().toString();
                        if(!name.matches("[0-9a-f]{64}\\.(png|jpg|gif)")) continue;
                        String id=name.substring(0,64),relative=directory.getFileName()+"/"+name;
                        if(!relative.startsWith(id.substring(0,2)+"/")) continue;
                        if(Files.isSymbolicLink(file)) {scan.blocked("图片文件包含符号链接");continue;}
                        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) continue;
                        long size=Files.size(file);scan.files++;scan.bytes+=size;
                        if(scan.references.containsKey(id)) continue;
                        scan.orphans++;scan.orphanBytes+=size;
                        long modified=Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis();
                        if(modified<=cutoff) {scan.garbage.add(new Garbage(relative,size,modified));scan.removableBytes+=size;}
                    }
                }
            }
        }
    }

    private static void cancelled() {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Image maintenance cancelled");
    }
}
