package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** 仅限一次准入检查的读取复用；发布前重新开启独立作用域，不复用此前的验证结果。 */
final class SnapshotReadCache implements AutoCloseable {
    private static final ThreadLocal<SnapshotReadCache> CURRENT=new ThreadLocal<>();
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final SnapshotReadCache previous;
    private final Map<Path,String> hashes=new HashMap<>();
    private final Map<Path,JsonNode> documents=new HashMap<>();
    private final Map<JsonNode,Set<String>> validations=new IdentityHashMap<>();
    private SnapshotReadCache(){previous=CURRENT.get();CURRENT.set(this);}
    static SnapshotReadCache begin(){return new SnapshotReadCache();}
    static boolean validated(String category,JsonNode payload,JsonNode declaration,Path root){var current=CURRENT.get();return current!=null&&current.validations.getOrDefault(payload,Set.of()).contains(key(category,declaration,root));}
    static void markValidated(String category,JsonNode payload,JsonNode declaration,Path root){var current=CURRENT.get();if(current!=null)current.validations.computeIfAbsent(payload,ignored->new HashSet<>()).add(key(category,declaration,root));}
    private static String key(String category,JsonNode declaration,Path root){return root+"/"+category+"/"+declaration;}
    static String hash(Path file)throws IOException {
        var current=CURRENT.get();Path key=file.toAbsolutePath().normalize();
        if(current!=null&&current.hashes.containsKey(key))return current.hashes.get(key);
        try {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))>=0){BuildCancellation.check();digest.update(bytes,0,n);}}
            String hash=HexFormat.of().formatHex(digest.digest());if(current!=null)current.hashes.put(key,hash);return hash;
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static JsonNode read(Path file,int limit)throws IOException {
        Path key=file.toAbsolutePath().normalize();var current=CURRENT.get();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("快照文件缺失或超限");
        if(current!=null&&current.documents.containsKey(key))return current.documents.get(key);
        byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes(limit+1);}if(bytes.length>limit)throw new IOException("快照文件超限");
        if(current!=null)try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));String old=current.hashes.putIfAbsent(key,hash);if(old!=null&&!old.equals(hash))throw new IOException("校验期间快照文件变化");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var document=JSON.readTree(bytes);if(document==null||!document.isObject())throw new IOException("快照必须是 JSON 对象");
        if(current!=null)current.documents.put(key,document);return document;
    }
    @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
