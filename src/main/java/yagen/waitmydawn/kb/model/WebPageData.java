package yagen.waitmydawn.kb.model;

import java.util.Map;

/** 网页正文与来源信息的持久化契约。 */
public interface WebPageData {
    String title();
    String url();
    String content();
    String source();
    Map<String, String> subWebPage();
}
