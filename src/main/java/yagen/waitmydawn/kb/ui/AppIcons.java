package yagen.waitmydawn.kb.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;
import java.util.Objects;

/** 多尺寸窗口图标由同一透明原图生成，适配 Windows 标题栏与任务切换器。 */
public final class AppIcons {
    private AppIcons() {}
    public static void apply(Stage stage) {
        for(int size:new int[]{16,24,32,48,64,128,256}) {
            try(var stream=Objects.requireNonNull(AppIcons.class.getResourceAsStream("/icon.png"))) {
                var image=new Image(stream,size,size,true,true);
                if(image.isError())throw new IllegalStateException("Invalid application icon",image.getException());
                stage.getIcons().add(image);
            } catch(java.io.IOException failure){throw new IllegalStateException("Unable to load application icon",failure);}
        }
    }
}
