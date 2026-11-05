package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Element;

/** 仅按 DOM 的非正文标记清理；不依据句子措辞猜测或删除游戏机制。 */
final class WebBodySanitizer {
    static final String VERSION="body-v3";
    static void clean(Element root){
        root.select("script,style,.comment,.comments,.uknowtoomuch,.uknow-too-much,[hidden],[aria-hidden=true]").remove();
        for(var element:root.select("[style]"))if(element.attr("style").matches("(?is).*(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important)?\\s*(?:;|$).*"))element.remove();
    }
}
