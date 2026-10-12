package yagen.waitmydawn.kb.ui;

import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import java.awt.Desktop;
import java.net.URI;
import yagen.waitmydawn.kb.service.AnswerSourceLinks;

/** 目录与回答共用的 HTTP/HTTPS 外部来源入口。 */
public final class SourceLinks {
    private SourceLinks() {}
    public static Hyperlink create(String label, String url) {
        var link = new Hyperlink(label);
        link.setTooltip(new Tooltip(url));
        link.setDisable(!AnswerSourceLinks.safeUrl(url));
        link.setOnAction(event -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    throw new UnsupportedOperationException("系统不支持打开浏览器");
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception failure) { link.setTooltip(new Tooltip("无法打开链接，请复制地址到浏览器：" + url)); }
        });
        return link;
    }
}
