package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;

/** 任务锁保护活动暂存文件；缓存资产独立于任务回滚，回收仍由引用维护负责。 */
public final class BuildTaskJournal implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ThreadLocal<BuildTaskJournal> CURRENT=new ThreadLocal<>();
    private static final Map<Thread,BuildTaskJournal> ACTIVE=new ConcurrentHashMap<>();
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final Path root,folder;
    private final String id;
    private final FileChannel channel;
    private final FileLock lock;
    private final BuildTaskJournal previous;
    private final Thread owner=Thread.currentThread();
    private final Map<String,Long> assets=new ConcurrentHashMap<>();
    private final Map<String,String> imageFailures=new ConcurrentHashMap<>();
    private String state="RUNNING";
    private volatile boolean cancellationRequested;
    private final long started=System.currentTimeMillis();
    private BuildTaskJournal(Path root,String id)throws IOException {
        this.root=root.toAbsolutePath().normalize();this.id=id;
        folder=ArchivePaths.resolve(this.root,"build-tasks/"+id);Files.createDirectories(folder);
        channel=FileChannel.open(folder.resolve("task.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=channel.tryLock();if(lock==null){channel.close();throw new IOException("构建任务锁不可用");}
        previous=CURRENT.get();try{write();}catch(IOException failure){try{lock.release();}finally{channel.close();}throw failure;}CURRENT.set(this);ACTIVE.put(owner,this);
    }
    static BuildTaskJournal begin(Path directory)throws IOException{return new BuildTaskJournal(directory,UUID.randomUUID().toString());}
    static BuildTaskJournal current(){return CURRENT.get();}
    public static void checkCancellation(){BuildCancellation.check();}
    boolean cancelRequested(){return cancellationRequested;}
    static Runnable carry(Runnable work) {
        var captured=CURRENT.get();
        return ()->{var previous=CURRENT.get();if(captured==null)CURRENT.remove();else CURRENT.set(captured);try{work.run();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}};
    }
    Path staging(){return root.resolve("staging-"+id);}
    Path temporaryDirectory()throws IOException {Path tmp=ArchivePaths.resolve(root,"build-tasks/"+id+"/tmp");Files.createDirectories(tmp);return tmp;}
    void created(String hash,long bytes){assets.putIfAbsent(hash,bytes);}
    int createdImages(){return assets.size();}
    long createdBytes(){return assets.values().stream().mapToLong(Long::longValue).sum();}
    String imageFailure(String key){return imageFailures.get(key);}
    void imageFailure(String key,String failure){imageFailures.putIfAbsent(key,failure);}
    synchronized void state(String value)throws IOException {
        if(value.equals("PUBLISHING"))BuildCancellation.check();
        state=value;write();
    }
    /** 取消与进入发布共用监视器，避免在 JDBC 提交过程中关闭底层文件通道。 */
    public static boolean requestCancellation(Thread thread) {
        var task=ACTIVE.get(thread);if(task==null){thread.interrupt();return true;}
        synchronized(task){if(task.state.equals("PUBLISHING"))return false;task.cancellationRequested=true;return true;}
    }
    private void write()throws IOException {
        var data=JSON.createObjectNode().put("format","mkb-build-task").put("schemaVersion",1).put("id",id).put("state",state)
                .put("startedAt",started).put("updatedAt",System.currentTimeMillis()).put("createdImages",createdImages()).put("createdImageBytes",createdBytes());
        Path temporary=folder.resolve("task.json.tmp");Files.write(temporary,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
        try{Files.move(temporary,folder.resolve("task.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,folder.resolve("task.json"),StandardCopyOption.REPLACE_EXISTING);}
    }
    public record Recovery(int tasks,int files,long bytes,List<String> warnings){}
    /** 应在取得数据目录独占锁后调用；单任务文件锁额外保护同进程中的活动任务。 */
    public static Recovery recover(Path directory)throws IOException {
        Path root=directory.toAbsolutePath().normalize();int tasks=0,files=0;long bytes=0;var warnings=new ArrayList<String>();
        Path journals=ArchivePaths.resolve(root,"build-tasks");
        if(Files.isDirectory(journals,LinkOption.NOFOLLOW_LINKS))try(var children=Files.newDirectoryStream(journals)) {
            for(Path child:children) {
                String id=child.getFileName().toString();if(!id.matches(UUID_PATTERN)||!Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))continue;
                ArchivePaths.resolve(root,"build-tasks/"+id);
                try(var channel=FileChannel.open(ArchivePaths.resolve(root,"build-tasks/"+id+"/task.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                    FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException active){continue;}if(lock==null)continue;
                    try(lock) {
                        var removed=cleanStaging(root,id);files+=removed[0];bytes+=removed[1];
                        long[] tmp=cleanTemporary(child.resolve("tmp"));files+=tmp[0];bytes+=tmp[1];
                        Path metadata=child.resolve("task.json");
                        if(Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)&&Files.size(metadata)<=65536) {
                            var data=JSON.readTree(metadata.toFile());
                            if(data!=null&&data.isObject()&&"mkb-build-task".equals(data.path("format").asText())&&id.equals(data.path("id").asText())
                                    &&Set.of("RUNNING","PUBLISHING").contains(data.path("state").asText())) {
                                ((com.fasterxml.jackson.databind.node.ObjectNode)data).put("state","RECOVERED").put("updatedAt",System.currentTimeMillis());
                                JSON.writerWithDefaultPrettyPrinter().writeValue(metadata.toFile(),data);tasks++;
                            }
                        }
                        Files.deleteIfExists(child.resolve("task.json.tmp"));
                    }
                }catch(IOException|RuntimeException failure){warnings.add(id+"："+failure.getClass().getSimpleName());}
            }
        }
        // 旧版本没有任务日志，仅识别约定的 UUID 文件名；打开的 H2 文件由操作系统拒绝删除。
        try(var stages=Files.newDirectoryStream(root,"staging-*.mv.db")) {
            for(Path stage:stages){String name=stage.getFileName().toString();String id=name.substring(8,name.length()-6);
                if(!id.matches(UUID_PATTERN)||Files.exists(journals.resolve(id),LinkOption.NOFOLLOW_LINKS))continue;
                try{var removed=cleanStaging(root,id);files+=removed[0];bytes+=removed[1];tasks++;}catch(IOException failure){warnings.add(name+"：暂存文件仍被占用或不可清理");}
            }
        }
        var result=new Recovery(tasks,files,bytes,List.copyOf(warnings));
        if(files>0||!warnings.isEmpty())LoggerFactory.getLogger(BuildTaskJournal.class).info("Build recovery: {}",result);
        return result;
    }
    static long[] cleanStaging(Path root,String id)throws IOException {
        if(!id.matches(UUID_PATTERN))throw new IOException("无效构建任务身份");long count=0,bytes=0;
        for(String extension:List.of(".mv.db",".trace.db",".lock.db",".temp.db",".mv.db.tempFile",".mv.db.newFile")) {
            Path file=ArchivePaths.resolve(root,"staging-"+id+extension);
            if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){long size=Files.size(file);Files.delete(file);count++;bytes+=size;}
        }
        return new long[]{count,bytes};
    }
    private static long[] cleanTemporary(Path folder)throws IOException {
        if(!Files.exists(folder,LinkOption.NOFOLLOW_LINKS))return new long[]{0,0};
        if(Files.isSymbolicLink(folder))throw new IOException("任务临时目录含符号链接");
        long[] removed={0,0};
        Files.walkFileTree(folder,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException {
                if(attrs.isSymbolicLink()||attrs.isOther())throw new IOException("任务临时目录含重解析点");return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException {
                if(attrs.isSymbolicLink()||attrs.isOther())throw new IOException("任务临时文件含重解析点");Files.delete(file);removed[0]++;removed[1]+=attrs.size();return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(directory);return FileVisitResult.CONTINUE;}
        });return removed;
    }
    String finish(String outcome)throws IOException {
        var removed=cleanStaging(root,id);var temporary=cleanTemporary(folder.resolve("tmp"));state(outcome);
        String label=switch(outcome){case "PUBLISHED"->"构建已发布";case "CANCELLED"->"构建已取消";case "UNCHANGED"->"构建无新增内容";default->"构建失败";};
        return label+"：已清理暂存文件 "+(removed[0]+temporary[0])+" 个；本次新增图片保留 "+createdImages()+" 张（"+String.format(Locale.ROOT,"%.2f",createdBytes()/1048576.0)+" MiB），后续按引用及保留期回收；已下载资源缓存保留供复用。";
    }
    @Override public void close()throws IOException{if(previous==null)CURRENT.remove();else CURRENT.set(previous);if(previous!=null&&previous.owner==owner)ACTIVE.put(owner,previous);else ACTIVE.remove(owner,this);try{lock.release();}finally{channel.close();}}
}
