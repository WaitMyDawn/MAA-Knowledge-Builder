package yagen.waitmydawn.kb.renderer;
import java.awt.Font;
/** 字体随应用资源加载，图片渲染不依赖操作系统是否安装同名字体。 */
public final class CardFont {
    private static final Font BASE=load();
    private CardFont(){}
    private static Font load(){try(var in=CardFont.class.getResourceAsStream("/fonts/ChillRoundGothic-Medium.ttf")){if(in!=null)return Font.createFont(Font.TRUETYPE_FONT,in);}catch(java.io.IOException|java.awt.FontFormatException e){org.slf4j.LoggerFactory.getLogger(CardFont.class).warn("Card font unavailable; using platform fallback",e);}return new Font(Font.SANS_SERIF,Font.PLAIN,20);}
    public static Font at(int style,int size){return BASE.deriveFont(style,(float)size);}
}
