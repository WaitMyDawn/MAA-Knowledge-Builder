package yagen.waitmydawn.kb;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeadlessRunnerTest {

    @Test
    void parsesDialogueIndexes() {
        assertEquals(Set.of(4, 5), HeadlessRunner.parseDialogueIndexes("4,5"));
        assertEquals(Set.of(5), HeadlessRunner.parseDialogueIndexes(" 5 "));
        assertEquals(Set.of(1, 2, 3), HeadlessRunner.parseDialogueIndexes("1,2,3"));
    }

    @Test
    void rejectsInvalidDialogueIndexes() {
        assertThrows(IllegalArgumentException.class, () -> HeadlessRunner.parseDialogueIndexes("0"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessRunner.parseDialogueIndexes("4,0"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessRunner.parseDialogueIndexes("a"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessRunner.parseDialogueIndexes(""));
        assertThrows(IllegalArgumentException.class, () -> HeadlessRunner.parseDialogueIndexes("4,,5x"));
    }
}
