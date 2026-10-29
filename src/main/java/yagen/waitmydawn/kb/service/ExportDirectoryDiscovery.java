package yagen.waitmydawn.kb.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 仅枚举 mods 的同级目录；不递归扫描实例，也不读取候选包内容。 */
public final class ExportDirectoryDiscovery {
    private ExportDirectoryDiscovery() {}

    public static List<Path> siblings(Path mods) throws IOException {
        Path normalized=mods.toAbsolutePath().normalize(),parent=normalized.getParent();
        if(parent==null || !Files.isDirectory(normalized,LinkOption.NOFOLLOW_LINKS))return List.of();
        try(var files=Files.list(parent)) {
            return files.filter(p->!p.equals(normalized) && Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                    .filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("mkb-export"))
                    .sorted(Comparator.comparing(p->p.getFileName().toString(),String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    public static String append(String entries,Path selected) {
        Path normalized=selected.toAbsolutePath().normalize();
        if(entries.lines().filter(s->!s.isBlank()).anyMatch(s->{
            try{return Path.of(s.strip()).toAbsolutePath().normalize().equals(normalized);}catch(InvalidPathException ignored){return false;}
        }))return entries;
        return entries.stripTrailing()+(entries.isBlank()?"":"\n")+normalized;
    }
    public static String selectSnapshot(String entries,Path selected) {
        var retained=new ArrayList<String>();
        for(String line:entries.lines().filter(s->!s.isBlank()).toList()) {
            try{
                Path path=Path.of(line.strip());boolean named=path.getFileName()!=null&&path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("mkb-export");
                if(named||NativeIconPack.detected(path))continue;
            }catch(Exception ignored){}
            retained.add(line);
        }
        retained.add(selected.toAbsolutePath().normalize().toString());return String.join("\n",retained);
    }
}
