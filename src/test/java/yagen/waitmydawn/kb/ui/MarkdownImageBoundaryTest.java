package yagen.waitmydawn.kb.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownImageBoundaryTest {

    @Test void onlyImagesInsideExplicitRootsAreAllowed() throws Exception {
        Path temp = Files.createTempDirectory(Path.of("target"), "image-boundary-");
        Path generated = Files.createDirectory(temp.resolve("generated"));
        Path image = Files.write(generated.resolve("recipe.png"), new byte[]{1});
        Path outside = Files.write(temp.resolve("outside.png"), new byte[]{1});
        Path text = Files.write(generated.resolve("secret.txt"), new byte[]{1});

        assertTrue(MarkdownRenderer.isAllowedImage(image, List.of(generated)));
        assertFalse(MarkdownRenderer.isAllowedImage(outside, List.of(generated)));
        assertFalse(MarkdownRenderer.isAllowedImage(text, List.of(generated)));
        assertFalse(MarkdownRenderer.isAllowedImage(image, List.of()));
        Files.delete(image); Files.delete(outside); Files.delete(text);
        Files.delete(generated); Files.delete(temp);
    }
}
