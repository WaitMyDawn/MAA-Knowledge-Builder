package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 对照固定版本的官方分词结果；诊断不改变已有索引的分词行为。 */
public final class TokenizerDiagnostics {
    private TokenizerDiagnostics() {}
    public record Case(String text,int maxLength,List<Long> expected,List<Long> actual,boolean matches) {}
    public record Reference(String implementation,String source,String vocabSha256,boolean vocabularyMatches,
                            int matched,int total,List<Case> cases) {}
    public record TextStats(int tokens,int unknownTokens,int hanCharacters,int unknownHanCharacters,boolean truncated) {}

    public static Reference compare(Path vocabulary,Path reference) throws Exception {
        var root=new ObjectMapper().readTree(reference.toFile());
        String hash=sha256(vocabulary);var tokenizer=new WordPieceTokenizer(vocabulary);var results=new ArrayList<Case>();
        for(var sample:root.path("cases")) {
            var expected=new ArrayList<Long>();sample.path("ids").forEach(id -> expected.add(id.asLong()));
            var actual=Arrays.stream(tokenizer.encode(sample.path("text").asText(),sample.path("maxLength").asInt())).boxed().toList();
            results.add(new Case(sample.path("text").asText(),sample.path("maxLength").asInt(),expected,actual,expected.equals(actual)));
        }
        if(results.isEmpty()) throw new IllegalArgumentException("官方分词参考集为空");
        return new Reference(root.path("referenceImplementation").asText(),root.path("source").asText(),hash,
                hash.equals(root.path("vocabSha256").asText()),(int)results.stream().filter(Case::matches).count(),results.size(),List.copyOf(results));
    }

    public static TextStats inspect(String text,WordPieceTokenizer tokenizer,long unknownId,int maxLength) {
        var ids=tokenizer.encode(text,Integer.MAX_VALUE);int unknown=0,han=0,unknownHan=0;
        for(int i=1;i<ids.length-1;i++) if(ids[i]==unknownId) unknown++;
        for(int cp:text.codePoints().toArray()) if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN) {
            han++;if(tokenizer.encode(new String(Character.toChars(cp)),8)[1]==unknownId) unknownHan++;
        }
        return new TextStats(ids.length-2,unknown,han,unknownHan,ids.length>maxLength);
    }

    public static String sha256(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(file)) {byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1) digest.update(bytes,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
