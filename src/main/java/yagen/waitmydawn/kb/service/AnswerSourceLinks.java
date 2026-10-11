package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 来源链接写入回答 Markdown；会话持久化与重新渲染无需依赖当前检索状态。 */
public final class AnswerSourceLinks {
    private AnswerSourceLinks() {}
    private static final Pattern NAME = Pattern.compile("(?m)^(?:名称|标题)：+\\s*(.+)$");
    private static final Pattern PROTECTED = Pattern.compile("(?s)(```.*?```|`[^`]*`|!?\\[[^\\]]*\\]\\([^\\n]*?\\)|https?://[^\\s<>]+)");

    public static String enrich(String answer, List<VectorStore.SearchResult> evidence) {
        if (answer == null || evidence == null) return answer;
        Map<String, Set<String>> candidates = new HashMap<>();
        for (var chunk : evidence) {
            String url = chunk.sourcePath();
            if (!safeUrl(url)) continue;
            var matcher = NAME.matcher(chunk.chunkText());
            while (matcher.find()) {
                String name = matcher.group(1).split(" - ")[0].trim();
                for (String alias : name.split("[（()）]")) {
                    alias = alias.trim();
                    if (alias.length() >= 2 && alias.length() <= 80 && !alias.matches(".*[\\[\\]\\n].*"))
                        candidates.computeIfAbsent(alias, k -> new HashSet<>()).add(url);
                }
            }
        }
        var names = candidates.keySet().stream().filter(n -> candidates.get(n).size() == 1)
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        if (names.isEmpty()) return answer;
        var pattern = Pattern.compile(String.join("|", names.stream().map(Pattern::quote).toList()));
        StringBuilder output = new StringBuilder();
        var protectedMatch = PROTECTED.matcher(answer);
        int end = 0;
        while (protectedMatch.find()) {
            output.append(linkPlain(answer.substring(end, protectedMatch.start()), pattern, candidates));
            output.append(protectedMatch.group()); end = protectedMatch.end();
        }
        output.append(linkPlain(answer.substring(end), pattern, candidates));
        return output.toString();
    }

    private static String linkPlain(String text, Pattern names, Map<String, Set<String>> urls) {
        var matcher = names.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String url = urls.get(matcher.group()).iterator().next().replace("(", "%28").replace(")", "%29");
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement("[" + matcher.group() + "](" + url + ")"));
        }
        matcher.appendTail(result); return result.toString();
    }

    public static boolean safeUrl(String value) {
        try {
            var uri = java.net.URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception invalid) { return false; }
    }
}
