package yagen.waitmydawn.kb;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeadlessRunnerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @Test void missingSnapshotIsRejectedBeforeConfigurationOrDatabaseCreation(){
        var output=temp.resolve("uncreated");
        assertThrows(IllegalArgumentException.class,()->HeadlessRunner.main(new String[]{"--isolated","--data-dir",output.toString(),"--build",temp.toString()}));
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(output));
    }

    @Test void cleanCannotRemoveHistoryBeforeBuildValidation()throws Exception {
        var history=java.nio.file.Files.createDirectories(temp.resolve("sessions")).resolve("keep.json");java.nio.file.Files.writeString(history,"history");
        assertThrows(IllegalArgumentException.class,()->HeadlessRunner.main(new String[]{"--isolated","--data-dir",temp.toString(),"--clean","--build",temp.toString()}));
        assertEquals("history",java.nio.file.Files.readString(history));
    }

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
