package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;

/** 中文语言资源无效或没有可索引内容时继续英文回退，不修补损坏的源文件。 */
public final class JarLanguageExtractor {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Result(List<TextChunker.TextChunk> chunks,List<String> warnings){public Result{chunks=List.copyOf(chunks);warnings=List.copyOf(warnings);}}
    public Result extract(Path jarPath,String modId,String modName)throws IOException{
        var warnings=new ArrayList<String>();var roots=new LinkedHashSet<String>(List.of(modId,modId.toLowerCase(Locale.ROOT)));
        try(var jar=new JarFile(jarPath.toFile())){
            for(String language:List.of("zh_cn","en_us"))for(String namespace:roots){
                String path="assets/"+namespace+"/lang/"+language+".json";var entry=jar.getJarEntry(path);if(entry==null)continue;
                try(var input=jar.getInputStream(entry)){
                    byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IOException("语言文件超过2MB向量提取上限");
                    String text=new String(bytes,StandardCharsets.UTF_8);var parsed=JSON.readTree(text);if(parsed==null||!parsed.isObject())throw new IOException("语言文件根节点不是对象");
                    var chunks=new TextChunker().fromLangJson(text,modId,modName,path);if(!chunks.isEmpty())return new Result(chunks,warnings);
                }catch(IOException invalid){
                    String position=invalid instanceof com.fasterxml.jackson.core.JsonProcessingException json&&json.getLocation()!=null?"（行 "+json.getLocation().getLineNr()+"，列 "+json.getLocation().getColumnNr()+"）":"";
                    warnings.add(modId+"：Lang 向量提取跳过 "+path+position+"，尝试其他语言");
                }
            }
        }
        return new Result(List.of(),warnings);
    }
}
