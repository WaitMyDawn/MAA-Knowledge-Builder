package yagen.waitmydawn.kb.service;

import java.nio.file.Path;
import java.util.*;

/** 离线导入评测快照，不读取模型设置、凭据或数据库。 */
public final class EvalSessionImportRunner {
    private EvalSessionImportRunner() {}
    public static void main(String[] args)throws Exception {
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {
            if(i+1>=args.length || !Set.of("--run-dir","--data-dir","--prefix").contains(args[i]) || options.put(args[i],args[i+1])!=null)throw new IllegalArgumentException("参数：--run-dir <评测目录> --data-dir <软件数据目录> [--prefix <会话前缀>]");
        }
        for(String key:List.of("--run-dir","--data-dir"))if(!options.containsKey(key))throw new IllegalArgumentException("缺少参数："+key);
        var result=new EvaluationSessionImporter().importRun(Path.of(options.get("--run-dir")),Path.of(options.get("--data-dir")),options.getOrDefault("--prefix","评测回放"));
        result.sessions().forEach(s -> System.out.println((s.reused()?"复用：":"导入：")+s.title()+" | "+s.id()));
        System.out.println("图片资产="+result.imageAssets()+"；目标="+result.destination()+"；在问答页点击“刷新对话”后查看。");
    }
}
