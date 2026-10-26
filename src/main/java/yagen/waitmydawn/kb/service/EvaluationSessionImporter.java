package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.BuildRequest;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** 将评测会话及不可变图片快照发布到会话列表；不打开或修改目标知识数据库。 */
public final class EvaluationSessionImporter {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Imported(String id,String title,boolean reused) {}
    public record Result(List<Imported> sessions,int imageAssets,Path destination) {}
    private record Prepared(ChatHistoryService.ChatSession session,Map<String,Path> pictures) {}
    public Result importRun(Path source,Path destination,String prefix)throws IOException {
        if(prefix==null || prefix.isBlank() || prefix.length()>80)throw new IOException("会话前缀必须为 1–80 字符");
        NativeIconPack.rejectSensitive(source);NativeIconPack.rejectSensitive(destination);
        Path root=source.toRealPath();ArchivePaths.resolve(root,"sessions");
        Path output=destination.toRealPath();ArchivePaths.resolve(output,"sessions");
        if(root.equals(output))throw new IOException("请选择独立评测目录，不能导入目录自身");
        Path sessionDir=root.resolve("sessions");if(!Files.isDirectory(sessionDir,LinkOption.NOFOLLOW_LINKS))throw new IOException("所选目录缺少 sessions 子目录");
        var prepared=new ArrayList<Prepared>();long sessionBytes=0,assetBytes=0;var assets=new LinkedHashMap<String,Path>();
        try(var paths=Files.newDirectoryStream(sessionDir,"*.json")) {
            for(Path path:paths) {
                cancelled();ArchivePaths.resolve(sessionDir,path.getFileName().toString());
                if(prepared.size()>=1000 || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>16L*1024*1024)throw new IOException("会话文件数量、类型或大小超限");
                sessionBytes+=Files.size(path);if(sessionBytes>128L*1024*1024)throw new IOException("会话总大小超过 128 MiB");
                byte[] bytes;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(16*1024*1024+1);}
                if(bytes.length>16*1024*1024)throw new IOException("会话文件过大");
                var original=JSON.readValue(bytes,ChatHistoryService.ChatSession.class);
                if(original.sessionId==null || !original.sessionId.matches("[a-zA-Z0-9_-]+") || !path.getFileName().toString().equals(original.sessionId+".json")
                        || original.title==null || original.title.length()>4000 || original.messages==null || original.messages.isEmpty() || original.messages.size()>1000)throw new IOException("会话身份或消息列表无效："+path.getFileName());
                var referenced=new LinkedHashMap<String,Path>();
                for(var message:original.messages) {
                    if(message==null || message.content()==null || !Set.of("user","maa").contains(message.role()))throw new IOException("会话消息格式无效");
                    if(message.artifacts()==null)continue;
                    if(message.artifacts().pictures().size()>32)throw new IOException("单条消息图片数量超限");
                    for(var image:message.artifacts().pictures()) {
                        Path file=AnswerMedia.resolveImage(root,image);referenced.put(image.assetId(),file);
                        if(!assets.containsKey(image.assetId())) {assets.put(image.assetId(),file);assetBytes+=Files.size(file);}
                        if(assets.size()>10000 || assetBytes>512L*1024*1024)throw new IOException("图片归档预算超限");
                    }
                }
                var imported=new ChatHistoryService.ChatSession();
                String identity=BuildRequest.fingerprint(JSON.writeValueAsString(original.messages));
                imported.sessionId="review_"+BuildRequest.fingerprint(prefix+"\n"+identity).substring(0,32);
                imported.title=prefix+"｜"+original.title.replaceFirst("^(Eval:|AutoTest:)\\s*","");
                imported.createdAt=LocalDateTime.now().toString();imported.dataDir=output.toString();imported.messages=new ArrayList<>(original.messages);
                prepared.add(new Prepared(imported,referenced));
            }
        }
        if(prepared.isEmpty())throw new IOException("所选目录没有可导入会话");
        prepared.sort(Comparator.comparing(p -> p.session().sessionId));
        var history=new ChatHistoryService(output);var reused=new HashSet<String>();
        for(var item:prepared) {
            ArchivePaths.resolve(output,"sessions/"+item.session().sessionId+".json");
            var current=history.load(item.session().sessionId);
            if(current==null)continue;
            if(!current.messages.equals(item.session().messages) || !current.title.equals(item.session().title) || !current.dataDir.equals(output.toString()))throw new IOException("目标会话身份冲突，未覆盖："+item.session().sessionId);
            reused.add(item.session().sessionId);
        }
        var targetAssets=new ImageAssetStore(output);
        for(var entry:assets.entrySet()) {
            cancelled();byte[] bytes;try(var input=Files.newInputStream(entry.getValue(),LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(32*1024*1024+1);}
            if(bytes.length>32*1024*1024)throw new IOException("图片资产过大");targetAssets.restore(entry.getKey(),bytes);
        }
        var result=new ArrayList<Imported>();
        for(var item:prepared) {
            cancelled();if(!reused.contains(item.session().sessionId))saveNew(output,item.session());
            result.add(new Imported(item.session().sessionId,item.session().title,reused.contains(item.session().sessionId)));
        }
        return new Result(List.copyOf(result),assets.size(),output);
    }
    private static void saveNew(Path root,ChatHistoryService.ChatSession session)throws IOException {
        Path dir=ArchivePaths.resolve(root,"sessions");Files.createDirectories(dir);
        Path target=ArchivePaths.resolve(dir,session.sessionId+".json"),temp=Files.createTempFile(dir,"review-",".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),session);
            // 不使用 REPLACE_EXISTING，避免并发导入覆盖刚创建的用户会话。
            Files.move(temp,target);
        } finally {Files.deleteIfExists(temp);}
    }
    private static void cancelled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Session import cancelled");}
}
