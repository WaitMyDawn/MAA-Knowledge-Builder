package yagen.waitmydawn.kb.ui;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Windows 原生外观适配；不使用 JavaFX 内部窗口 API。 */
final class WindowsAppearance {
    interface Registry extends StdCallLibrary {
        int RegGetValueW(Pointer key, WString subkey, WString value, int flags,
                         IntByReference type, byte[] data, IntByReference size);
    }
    interface Windows extends StdCallLibrary {
        interface Visitor extends StdCallCallback { boolean invoke(Pointer window, Pointer context); }
        boolean EnumWindows(Visitor visitor, Pointer context);
        int GetWindowThreadProcessId(Pointer window, IntByReference process);
        int GetWindowTextW(Pointer window, char[] title, int length);
    }
    interface Dwm extends StdCallLibrary {
        int DwmSetWindowAttribute(Pointer window, int attribute, IntByReference value, int size);
    }
    private final Registry registry = Native.load("advapi32", Registry.class);
    private final Windows windows = Native.load("user32", Windows.class);
    private final Dwm dwm = Native.load("dwmapi", Dwm.class);

    boolean darkSystemTheme() {
        byte[] data = new byte[4];
        int result = registry.RegGetValueW(new Pointer(0xffffffff80000001L),
                new WString("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
                new WString("AppsUseLightTheme"), 0x10, new IntByReference(), data, new IntByReference(4));
        return result == 0 && ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt() == 0;
    }

    void applyTitleBar(String title, boolean dark) {
        List<Pointer> matches = new ArrayList<>();
        windows.EnumWindows((window, context) -> {
            var process = new IntByReference();
            windows.GetWindowThreadProcessId(window, process);
            if (Integer.toUnsignedLong(process.getValue()) == ProcessHandle.current().pid()) {
                char[] text = new char[512];
                windows.GetWindowTextW(window, text, text.length);
                if (title.equals(Native.toString(text))) matches.add(window);
            }
            return true;
        }, null);
        // 同进程存在同名窗口时不猜测句柄，避免修改另一个窗口。
        if (matches.size() != 1) return;
        Pointer window = matches.getFirst();
        dwm.DwmSetWindowAttribute(window, 20, new IntByReference(dark ? 1 : 0), 4);
        // Windows 11 支持显式标题栏颜色；旧系统保留受支持的原生属性。
        dwm.DwmSetWindowAttribute(window, 35, new IntByReference(dark ? 0x221914 : 0xfaf6f4), 4);
        dwm.DwmSetWindowAttribute(window, 36, new IntByReference(dark ? 0xebe7e2 : 0x332017), 4);
    }
}
