package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.jar.JarFile;

/** 先检查所选目录；只在无候选时检查直属上级，避免混入相邻实例。 */
public final class GameEnvironmentDetector {
    public record Candidate(Path jar,String loader,String version) {}
    public List<Candidate> discover(Path folder)throws IOException {
        var inside=search(folder,true);if(!inside.isEmpty())return inside;
        return folder.toAbsolutePath().normalize().getParent()==null?List.of():search(folder.toAbsolutePath().normalize().getParent(),false);
    }
    private List<Candidate> search(Path folder,boolean recursive)throws IOException{
        var results=new ArrayList<Candidate>();
        try(var files=Files.walk(folder,recursive?Integer.MAX_VALUE:1)){
            for(Path file:files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList()){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("环境识别已取消");
                var candidate=inspect(file);candidate.ifPresent(results::add);
            }
        }
        return List.copyOf(results);
    }
    public Optional<Candidate> inspect(Path file){
        try(var jar=new JarFile(file.toFile())){
            var version=jar.getJarEntry("version.json");
            if(version==null||version.getSize()>256*1024||jar.getJarEntry("net/minecraft/client/main/Main.class")==null)return Optional.empty();
            String id;
            try(var input=jar.getInputStream(version)){
                byte[] bytes=input.readNBytes(256*1024+1);if(bytes.length>256*1024)return Optional.empty();
                id=new ObjectMapper().readTree(bytes).path("id").asText(null);
            }
            if(id==null||id.isBlank())return Optional.empty();
            String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
            String loader=name.contains("neoforge")?"neoforge":name.contains("forge")?"forge":name.contains("fabric")?"fabric":null;
            return Optional.of(new Candidate(file.toAbsolutePath().normalize(),loader,id));
        }catch(IOException|RuntimeException failure){return Optional.empty();}
    }
}
