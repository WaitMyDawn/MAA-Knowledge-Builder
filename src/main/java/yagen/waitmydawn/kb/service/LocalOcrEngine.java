package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** OCR 仅使用本机引擎；不可用与未识别到文字是不同状态。 */
public interface LocalOcrEngine {
    record Result(String text, String status, String language) {}
    Result recognize(BufferedImage image) throws Exception;

    static LocalOcrEngine windows() { return new WindowsEngine(); }
    static LocalOcrEngine windows(int timeoutSeconds){return new WindowsEngine(timeoutSeconds);}

    final class WindowsEngine implements LocalOcrEngine {
        private final int timeoutSeconds;
        public WindowsEngine(){this(45);}
        public WindowsEngine(int timeoutSeconds){this.timeoutSeconds=Math.max(1,Math.min(45,timeoutSeconds));}
        @Override public Result recognize(BufferedImage image) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("OCR cancelled");
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                return new Result("", "UNAVAILABLE", "");
            Path scratch = Files.createTempDirectory("maa-ocr-");
            Process process = null;
            try {
                Path input = scratch.resolve("input.png"), output = scratch.resolve("result.json"), script = scratch.resolve("ocr.ps1");
                try (var resource = LocalOcrEngine.class.getResourceAsStream("/ocr/windows-ocr.ps1")) {
                    if (resource == null) throw new java.io.IOException("缺少 Windows OCR 资源");
                    Files.copy(resource, script);
                }
                BufferedImage prepared = image;
                if (image.getWidth() > 9500 || image.getHeight() > 9500) {
                    double scale = Math.min(9500.0 / image.getWidth(), 9500.0 / image.getHeight());
                    prepared = new BufferedImage(Math.max(1, (int)(image.getWidth() * scale)), Math.max(1, (int)(image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
                    var graphics = prepared.createGraphics();
                    try { graphics.drawImage(image, 0, 0, prepared.getWidth(), prepared.getHeight(), null); }
                    finally { graphics.dispose(); }
                }
                ImageIO.write(prepared, "png", input.toFile());
                String systemRoot = System.getenv("SystemRoot");
                Path executable = Path.of(systemRoot == null ? "C:/Windows" : systemRoot,
                        "System32/WindowsPowerShell/v1.0/powershell.exe");
                process = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString(), "-InputPath", input.toString(), "-OutputPath", output.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return new Result("", "TIMEOUT", "");
                if (!Files.exists(output)) return new Result("", "UNAVAILABLE", "");
                var result = new ObjectMapper().readTree(output.toFile());
                // Windows 将中文词拆成空格分隔的字；仅合并汉字间横向空白，保留换行和英文词界。
                String text = result.path("text").asText("").replaceAll("(?<=\\p{IsHan})[\\t ]+(?=\\p{IsHan})", "");
                return new Result(text, result.path("status").asText("UNAVAILABLE"), result.path("language").asText(""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("OCR cancelled");
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                for (String file : java.util.List.of("input.png", "result.json", "ocr.ps1")) Files.deleteIfExists(scratch.resolve(file));
                Files.deleteIfExists(scratch);
            }
        }
    }
}
