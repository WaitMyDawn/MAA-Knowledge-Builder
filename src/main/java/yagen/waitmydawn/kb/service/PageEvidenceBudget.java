package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 相同页面的章节限额防止长章节挤掉制作步骤、获取方式等独立证据。 */
public final class PageEvidenceBudget {
    private static final Pattern SECTION=Pattern.compile("(?:^|\\n)章节：([^\\n]+)");
    private PageEvidenceBudget() {}
    public static String section(VectorStore.SearchResult chunk) {
        var match=SECTION.matcher(chunk.chunkText());
        return match.find()?match.group(1):"正文";
    }
    /** 保留相邻条件句的正文上下文；向量身份和已保存的短块不变。 */
    public static List<VectorStore.SearchResult> withNeighbors(List<VectorStore.SearchResult> page,
                                                               List<VectorStore.SearchResult> selected) {
        var result = new ArrayList<VectorStore.SearchResult>();
        for (var anchor : selected) {
            int index = -1;
            for (int i = 0; i < page.size(); i++) if (page.get(i).chunkId().equals(anchor.chunkId())) { index = i; break; }
            StringBuilder body = new StringBuilder();
            if (index >= 0) for (int i = Math.max(0, index - 1); i <= Math.min(page.size() - 1, index + 1); i++) {
                var adjacent = page.get(i);
                if (!Objects.equals(adjacent.sourcePath(), anchor.sourcePath()) || !section(adjacent).equals(section(anchor))) continue;
                String text = adjacent.chunkText().replaceAll("(?m)^(?:名称|标题|章节)：[^\\n]*\\n?", "").strip();
                int overlap = Math.min(24, Math.min(body.length(), text.length()));
                while (overlap > 0 && !body.toString().endsWith(text.substring(0, overlap))) overlap--;
                if (!body.isEmpty() && overlap == 0) body.append('\n');
                body.append(text.substring(overlap));
            }
            result.add(new VectorStore.SearchResult(anchor.chunkId(), body.isEmpty() ? anchor.chunkText()
                    : anchor.chunkText().replaceFirst("(?s)((?:名称|标题)：[^\\n]*\\n)?(章节：[^\\n]*\\n)?.*", "$1$2") + body,
                    anchor.modId(), anchor.modName(), anchor.type(), anchor.score(), anchor.sourcePath()));
        }
        return result;
    }

    public static List<VectorStore.SearchResult> select(List<VectorStore.SearchResult> ranked,int limit) {
        var ordered=ranked.stream().sorted(Comparator.comparingDouble(VectorStore.SearchResult::score).reversed()).toList();
        var result=new ArrayList<VectorStore.SearchResult>();
        var counts=new HashMap<String,Integer>();
        int perSection=Math.max(2,limit/3);
        for(var chunk:ordered) {
            String section=section(chunk);
            if(counts.getOrDefault(section,0)>=perSection) continue;
            result.add(chunk);counts.merge(section,1,Integer::sum);
            if(result.size()==limit) return result;
        }
        for(var chunk:ordered) if(!result.contains(chunk) && result.size()<limit) result.add(chunk);
        return result;
    }
}
