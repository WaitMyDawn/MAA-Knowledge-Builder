package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 中英文问题的确定性字面信号，补充向量检索对注册名和装备等级的区分。 */
final class KnowledgeSearchText {
    private KnowledgeSearchText() {}
    static String normalize(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    static Set<String> terms(String question) {
        String text = question.toLowerCase(Locale.ROOT).replaceAll("怎么|如何|什么|哪些|介绍|一下|请问|推荐|这个|那个|里面|资料|教程|有什么|有什么用|可以|是否", " ");
        var terms = new LinkedHashSet<String>();
        var words = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(text);
        while (words.find()) {
            String word = words.group();
            if (word.matches("[a-z0-9_]+")) { if (word.length() > 1) terms.add(word); }
            else if (word.length() >= 2) for (int i = 0; i < word.length() - 1; i++) terms.add(word.substring(i, i + 2));
        }
        return terms;
    }
    static double overlap(String query, String body) {
        var terms = terms(query); if (terms.isEmpty()) return 0;
        String text = normalize(body); double matches = 0;
        for (String term : terms) {
            boolean present = term.matches("[a-z0-9_]+")
                    ? Pattern.compile("(?i)(?<![a-z0-9_])" + Pattern.quote(term) + "(?![a-z0-9_])").matcher(body).find()
                    : text.contains(term);
            if (present) matches++;
        }
        return matches / terms.size();
    }
}
