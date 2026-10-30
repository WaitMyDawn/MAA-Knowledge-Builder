package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 构建级诊断上下文；阶段墙钟与可重叠的累计工作时间分开记录，不参与发布事务。 */
public final class BuildTrace {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private record Context(BuildTrace trace,long parent){}
    private final Context previous;
    private final Path directory;
    private final long started;
    private final String startedAt;
    private final KnowledgeBuilder.ProgressCallback progress;
    private final List<Span> spans=new ArrayList<>();
    private final Map<String,long[]> work=new TreeMap<>();
    private final Map<String,Object> inputs=new LinkedHashMap<>();
    private final Map<String,Map<String,long[]>> stageWork=new TreeMap<>();
    private final List<String> diagnosticErrors=new ArrayList<>();
    private BufferedWriter events;
    private long nextId;
    private volatile boolean finished;
    private boolean truncated;
    private String failureClass;
    private static final int MAX_SPANS=4000;
    private BuildTrace(Path directory,KnowledgeBuilder.ProgressCallback progress,long started,String startedAt) {
        this.started=started;this.startedAt=startedAt;
        this.directory=directory;this.progress=progress;previous=CURRENT.get();CURRENT.set(new Context(this,0));
        try{Files.createDirectories(directory);events=Files.newBufferedWriter(directory.resolve("events.jsonl"),java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
        catch(IOException failure){diagnosticError(failure);}
        event(Map.of("event","BUILD_START","at",startedAt,"schemaVersion",1));
        var initialization=new Span(++nextId,0,"task.initialize","任务记录与诊断初始化",started,Thread.currentThread().getName(),true);
        initialization.end=System.nanoTime();spans.add(initialization);
        event(Map.of("event","STAGE_RECORDED","id",initialization.id,"parentId",0,"stage",initialization.stage,"offsetMs",0,"elapsedMs",millis(initialization.end-started)));
        message("任务与诊断初始化结束 · "+String.format(Locale.ROOT,"%.3f",millis(initialization.end-started)/1000)+"s");
    }
    static BuildTrace begin(Path directory,KnowledgeBuilder.ProgressCallback progress){return begin(directory,progress,System.nanoTime(),Instant.now().toString());}
    static BuildTrace begin(Path directory,KnowledgeBuilder.ProgressCallback progress,long started,String startedAt){return new BuildTrace(directory,progress,started,startedAt);}
    public static Scope phase(String stage,String label){return open(stage,label,true);}
    public static Scope step(String stage,String label){return open(stage,label,false);}
    private static Scope open(String stage,String label,boolean visible) {
        var context=CURRENT.get();if(context==null||context.trace.finished)return new Scope(null,null,null);
        return context.trace.open(context,stage,safeLabel(label),visible);
    }
    private synchronized Scope open(Context previous,String stage,String label,boolean visible) {
        if(finished)return new Scope(null,null,null);
        var span=new Span(++nextId,previous.parent,stage,label,System.nanoTime(),Thread.currentThread().getName(),visible);
        if(spans.size()<MAX_SPANS)spans.add(span);else truncated=true;
        CURRENT.set(new Context(this,span.id));
        event(Map.of("event","STAGE_START","id",span.id,"parentId",span.parent,"stage",stage,"label",label,"at",Instant.now().toString(),"thread",span.thread));
        if(visible)message("开始 "+stage+" · "+label);
        return new Scope(this,span,previous);
    }
    public static final class Scope implements AutoCloseable {
        private final BuildTrace trace;private final Span span;private final Context previous;private boolean closed;
        private Scope(BuildTrace trace,Span span,Context previous){this.trace=trace;this.span=span;this.previous=previous;}
        @Override public void close(){if(closed)return;closed=true;if(trace==null)return;try{trace.end(span);}finally{restore(trace.finished?trace.previous:previous);}}
    }
    private static final class Span {
        final long id,parent,start;final String stage,label,thread;final boolean visible;long end;
        Span(long id,long parent,String stage,String label,long start,String thread,boolean visible){this.id=id;this.parent=parent;this.stage=stage;this.label=label;this.start=start;this.thread=thread;this.visible=visible;}
    }
    private synchronized void end(Span span) {
        if(span.end!=0||finished)return;span.end=System.nanoTime();
        double ms=millis(span.end-span.start);
        event(Map.of("event","STAGE_END","id",span.id,"stage",span.stage,"elapsedMs",ms,"at",Instant.now().toString()));
        if(span.visible)message("结束 "+span.stage+" · "+span.label+" · "+String.format(Locale.ROOT,"%.3f",ms/1000)+"s");
    }
    static Runnable carry(Runnable action) {
        var captured=CURRENT.get();return ()->{var previous=CURRENT.get();restore(captured);try{action.run();}finally{restore(previous);}};
    }
    private static void restore(Context context){if(context==null)CURRENT.remove();else CURRENT.set(context);}
    static void work(String kind,long nanos,long units) {
        var context=CURRENT.get();if(context==null||context.trace.finished)return;
        context.trace.addWork(context.parent,kind,nanos,units);
    }
    private synchronized void addWork(long parent,String kind,long nanos,long units) {
        if(finished)return;
        add(work,kind,nanos,units);add(stageWork.computeIfAbsent(Long.toString(parent),ignored->new TreeMap<>()),kind,nanos,units);
    }
    private static void add(Map<String,long[]> metrics,String kind,long nanos,long units){var value=metrics.computeIfAbsent(kind,ignored->new long[2]);value[0]+=Math.max(0,nanos);value[1]+=Math.max(0,units);}
    static void network(boolean image,String url,int status,long nanos,boolean retry,boolean redirect) {
        var context=CURRENT.get();if(context==null||context.trace.finished)return;
        String kind=image?"http.image":"http.document";
        context.trace.addWork(context.parent,kind,nanos,1);
        if(retry)context.trace.addWork(context.parent,"http.retry",0,1);
        if(status==301||status==302||status==303||status==307||status==308)context.trace.addWork(context.parent,"http.redirect",0,1);
        if(status==0||status>=400)context.trace.addWork(context.parent,"http.error",0,1);
        context.trace.event(Map.of("event","HTTP_END","parentId",context.parent,"kind",kind,"resource",safeLabel(url),"status",status,"elapsedMs",millis(nanos),"retry",retry,"redirect",redirect));
    }
    void failure(Throwable failure){failureClass=failure.getClass().getSimpleName();}
    synchronized void metadata(String key,Object value){inputs.put(key,value);}
    private synchronized void event(Map<String,?> event) {
        if(events==null)return;try{events.write(JSON.writeValueAsString(event));events.newLine();events.flush();}
        catch(IOException failure){diagnosticError(failure);try{events.close();}catch(IOException ignored){}events=null;}
    }
    private void diagnosticError(Exception failure){String type=failure.getClass().getSimpleName();if(!diagnosticErrors.contains(type))diagnosticErrors.add(type);LoggerFactory.getLogger(BuildTrace.class).warn("Build timing persistence unavailable: {}",type);}
    private void message(String message) {
        LoggerFactory.getLogger(BuildTrace.class).info("Build timing: {}",message);
        if(progress!=null)try{progress.onProgress("timing",0,0,"[计时] "+message);}catch(RuntimeException failure){LoggerFactory.getLogger(BuildTrace.class).debug("Build timing listener unavailable",failure);}
    }
    static String safeLabel(String label) {
        String safe=Objects.toString(label,"");
        if(safe.startsWith("http://")||safe.startsWith("https://"))try{var uri=URI.create(safe);safe=Objects.toString(uri.getHost(),"")+Objects.toString(uri.getRawPath(),"");}catch(IllegalArgumentException ignored){safe="invalid-url";}
        safe=safe.replaceAll("[\\r\\n\\t]"," ");return safe.length()>240?safe.substring(0,240):safe;
    }
    private static double millis(long nanos){return Math.max(0,nanos)/1_000_000.0;}
    private static List<Map<String,Object>> metrics(Map<String,long[]> values) {
        var rows=new ArrayList<Map<String,Object>>();values.forEach((kind,value)->rows.add(Map.of("kind",kind,"workMs",millis(value[0]),"units",value[1])));return rows;
    }
    long finish(String outcome,KnowledgeBuilder.BuildResult result) {
        long end=System.nanoTime();
        try {
            synchronized(this) {
                for(var span:spans)if(span.end==0){span.end=end;event(Map.of("event","STAGE_UNCLOSED","id",span.id,"stage",span.stage));}
                finished=true;
                var rows=new ArrayList<Map<String,Object>>();
                for(var span:spans)rows.add(Map.of("id",span.id,"parentId",span.parent,"stage",span.stage,"label",span.label,"offsetMs",millis(span.start-started),"elapsedMs",millis(span.end-span.start),"thread",span.thread));
                var report=new LinkedHashMap<String,Object>();
                report.put("schemaVersion",1);report.put("startedAt",startedAt);report.put("finishedAt",Instant.now().toString());report.put("outcome",outcome);report.put("failureClass",failureClass);report.put("wallMs",millis(end-started));
                report.put("inputs",new LinkedHashMap<>(inputs));
                report.put("spans",rows);report.put("spansTruncated",truncated);report.put("workMetrics",metrics(work));
                long covered=0,last=started;
                for(var span:spans.stream().filter(s->s.parent==0).sorted(Comparator.comparingLong(s->s.start)).toList()) {
                    long right=Math.min(end,span.end),left=Math.max(last,span.start);if(right>left)covered+=right-left;last=Math.max(last,right);
                }
                report.put("topLevelCoveredMs",millis(covered));report.put("unattributedMs",millis(end-started-covered));
                var perStage=new TreeMap<String,Object>();stageWork.forEach((id,values)->perStage.put(id,metrics(values)));report.put("workBySpan",perStage);report.put("diagnosticErrors",List.copyOf(diagnosticErrors));
                if(result!=null)report.put("counts",Map.of("vectors",result.embeddings,"addedVectors",result.addedEmbeddings,"documents",result.documents,"tutorials",result.tutorials,"webImages",result.webImages,"webImageFailures",result.deferredWebImages,"imageAttempts",result.webImageAttempts,"imageReused",result.reusedWebImages));
                event(Map.of("event","BUILD_END","outcome",outcome,"wallMs",millis(end-started)));
                if(events!=null)try{events.close();events=null;}catch(IOException failure){diagnosticError(failure);}
                try{writeAtomic(directory.resolve("timing.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));writeAtomic(directory.resolve("timing.md"),markdown(report,rows));}
                catch(IOException failure){diagnosticError(failure);}
            }
            if(result!=null&&!diagnosticErrors.isEmpty())result.warnings.add("构建计时报告未完整保存，请检查应用日志："+directory);
            message("构建状态 "+outcome+"；总墙钟 "+String.format(Locale.ROOT,"%.3f",millis(end-started)/1000)+"s（含预检查和清理，不含 Scan/Bind Url）；报告："+directory.resolve("timing.md"));
            return (end-started)/1_000_000;
        }catch(RuntimeException failure){
            diagnosticError(failure);if(result!=null)result.warnings.add("构建计时汇总不可用，请检查应用日志："+directory);
            return (end-started)/1_000_000;
        }finally{
            if(events!=null)try{events.close();}catch(IOException ignored){}events=null;
            restore(previous);
        }
    }
    private static void writeAtomic(Path path,String text)throws IOException {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temporary,text);
        try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    private String markdown(Map<String,Object> report,List<Map<String,Object>> rows) {
        var text=new StringBuilder("# Build 阶段与计时\n\n");
        text.append("- 开始（UTC）：").append(startedAt).append("\n- 状态：").append(report.get("outcome")).append("\n- 总墙钟：").append(report.get("wallMs")).append(" ms\n- 异常类型：").append(Objects.toString(failureClass,"无")).append("\n\n");
        text.append("运行输入（模式、预热设置、快照及模型指纹）：\n\n```json\n");
        try{text.append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(inputs));}catch(IOException ignored){text.append("输入元数据不可用");}
        text.append("\n```\n\n");
        text.append("总墙钟覆盖快照预检查到清理结束；不含此前 Scan/Bind Url，也不含计时报告自身最后落盘。父阶段包含子阶段，不能把所有行相加。累计工作时间可因流水线并发超过墙钟，不是独占 CPU 时间。阶段结束只表示计时范围闭合，不证明该阶段业务验收通过。\n\n");
        text.append("## 顶层阶段\n\n| 阶段 | 对象 | 墙钟 ms |\n| --- | --- | ---: |\n");
        for(var row:rows)if(((Number)row.get("parentId")).longValue()==0)text.append("| ").append(row.get("stage")).append(" | ").append(row.get("label").toString().replace("|","\\|")).append(" | ").append(row.get("elapsedMs")).append(" |\n");
        text.append("\n顶层区间并集：").append(report.get("topLevelCoveredMs")).append(" ms；未归属墙钟：").append(report.get("unattributedMs")).append(" ms（范围间开销及未单独打点，不推定为网络或推理）。\n");
        text.append("\n未出现的阶段可能被跳过或因提前终止未进入，不等于已测得零耗时。JSON 中保留层级、每阶段工作计数；events.jsonl 增量保留阶段及脱敏请求事件，可定位未正常结束的构建。\n\n## 累计工作与计数\n\n| 类型 | 累计 ms | 数量 |\n| --- | ---: | ---: |\n");
        for(var metric:metrics(work))text.append("| ").append(metric.get("kind")).append(" | ").append(metric.get("workMs")).append(" | ").append(metric.get("units")).append(" |\n");
        text.append("\nnetwork.wait 包含限流门等待；http.* 记录实际请求尝试的传输时间，图片本地处理与下载可交错，不能简单相加。embedding.document 是实际文档嵌入时间及块数；vector.write 是向量数据库写入时间及行数。缓存命中另记，不算成实际推理。图片细分包含读取缓存与等待，非纯网络传输。\n\n");
        text.append("诊断错误：").append(diagnosticErrors).append("；阶段明细截断：").append(truncated).append("。报告不存在或有诊断错误时，应检查应用日志，不把报告失败视为业务发布失败。\n");
        text.append("\n## 完整阶段层级\n\n| 编号 | 父编号 | 阶段 | 对象 | 墙钟 ms |\n| ---: | ---: | --- | --- | ---: |\n");
        for(var row:rows)text.append("| ").append(row.get("id")).append(" | ").append(row.get("parentId")).append(" | ").append(row.get("stage")).append(" | ").append(row.get("label").toString().replace("|","\\|").replace("<","&lt;").replace(">","&gt;")).append(" | ").append(row.get("elapsedMs")).append(" |\n");
        return text.toString();
    }
}
