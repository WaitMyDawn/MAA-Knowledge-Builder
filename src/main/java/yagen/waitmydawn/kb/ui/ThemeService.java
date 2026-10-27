package yagen.waitmydawn.kb.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import java.util.Objects;

/** FX 线程管理场景外观，系统偏好读取不涉及应用凭据。 */
public final class ThemeService implements AutoCloseable {
    private final AppConfig config;
    private final WindowsAppearance windows;
    private final Timeline watcher;
    private boolean dark;
    private final ListChangeListener<Window> listener = change -> {
        while (change.next()) for (Window window : change.getAddedSubList()) {
            apply(window); javafx.application.Platform.runLater(() -> { if(window.isShowing()) apply(window); });
        }
    };

    public ThemeService(AppConfig config) {
        this.config = config;
        WindowsAppearance adapter = null;
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try { adapter = new WindowsAppearance(); }
            catch (RuntimeException | LinkageError e) {
                LoggerFactory.getLogger(ThemeService.class).warn("Native appearance unavailable: {}", e.toString());
            }
        }
        windows = adapter;
        dark = resolve();
        Window.getWindows().addListener(listener);
        watcher = new Timeline(new KeyFrame(Duration.seconds(2), event -> refresh()));
        watcher.setCycleCount(Timeline.INDEFINITE);
        watcher.play();
    }

    public void setMode(ThemeMode mode) { config.setThemeMode(mode); refresh(); }
    public void prepare(Scene scene) {
        if(scene==null) return;
        String css = Objects.requireNonNull(ThemeService.class.getResource("/ui/workspace.css")).toExternalForm();
        if (!scene.getStylesheets().contains(css)) scene.getStylesheets().add(css);
        scene.getRoot().getStyleClass().removeAll("theme-dark", "theme-light");
        scene.getRoot().getStyleClass().add(dark ? "theme-dark" : "theme-light");
        String style=scene.getRoot().getStyle().replaceAll("-progress-(?:scan|bind|build)-color\\s*:[^;]+;?","").replaceAll("-mod-(?:id|slug|url)-color\\s*:[^;]+;?","");
        scene.getRoot().setStyle(style+";-mod-id-color:"+config.getModFieldColor(dark,"modid")+";-mod-slug-color:"+config.getModFieldColor(dark,"slug")+";-mod-url-color:"+config.getModFieldColor(dark,"url")+";-progress-scan-color:"+config.getProgressColor(dark,"scan")+";-progress-bind-color:"+config.getProgressColor(dark,"bind")+";-progress-build-color:"+config.getProgressColor(dark,"build")+";");
    }
    public void refreshPalette(){for(Window window:Window.getWindows())apply(window);}
    private boolean resolve() {
        return config.getThemeMode() == ThemeMode.DARK ||
                (config.getThemeMode() == ThemeMode.SYSTEM && windows != null && windows.darkSystemTheme());
    }
    private void refresh() {
        boolean next = resolve();
        if (next == dark) return;
        dark = next;
        for (Window window : Window.getWindows()) apply(window);
    }
    private void apply(Window window) {
        if (window.getScene() != null) prepare(window.getScene());
        if(window instanceof Stage stage&&stage.getIcons().isEmpty())AppIcons.apply(stage);
        if (windows != null && window instanceof Stage stage) {
            try { windows.applyTitleBar(stage.getTitle(), dark); }
            catch (RuntimeException | LinkageError e) {
                LoggerFactory.getLogger(ThemeService.class).debug("Title bar appearance unavailable", e);
            }
        }
    }
    @Override public void close() { watcher.stop(); Window.getWindows().removeListener(listener); }
}
