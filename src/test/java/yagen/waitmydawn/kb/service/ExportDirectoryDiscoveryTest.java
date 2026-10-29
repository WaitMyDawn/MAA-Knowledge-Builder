package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportDirectoryDiscoveryTest {
    @TempDir Path temp;
    @Test void findsOnlySiblingDirectoriesAndAppendsWithoutReplacingOrDuplicating()throws Exception {
        Path mods=Files.createDirectory(temp.resolve("mods"));
        Path a=Files.createDirectory(temp.resolve("MKB-Export")),b=Files.createDirectory(temp.resolve("mkb-export-backup"));
        Files.createDirectories(mods.resolve("MKB-Export-nested"));Files.createDirectory(temp.resolve("unrelated"));Files.createFile(temp.resolve("MKB-Export-file"));
        assertEquals(java.util.List.of(a,b),ExportDirectoryDiscovery.siblings(mods));
        String original=temp.resolve("notes").toString();String appended=ExportDirectoryDiscovery.append(original,a);
        assertEquals(original+"\n"+a,appended);assertEquals(appended,ExportDirectoryDiscovery.append(appended,a.resolve(".")));
        assertEquals(java.util.List.of(),ExportDirectoryDiscovery.siblings(temp.resolve("missing")));
    }
    @Test void selectingSnapshotReplacesOldExportsAndPreservesOrdinarySources()throws Exception {
        Path old=Files.createDirectories(temp.resolve("MKB-Export-old")),selected=Files.createDirectories(temp.resolve("MKB-Export-new")),notes=temp.resolve("notes");
        assertEquals(notes+"\n"+selected,ExportDirectoryDiscovery.selectSnapshot(notes+"\n"+old,selected));
        assertEquals(notes+"\n"+selected,ExportDirectoryDiscovery.selectSnapshot(notes+"\n"+selected,selected));
    }
}
