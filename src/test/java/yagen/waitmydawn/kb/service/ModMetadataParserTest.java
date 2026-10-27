package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ModMetadataParserTest {
    @TempDir Path temp;
    Path jar(String filename,String authors,String range)throws Exception{
        var path=temp.resolve(filename);
        String toml="license=\"MIT\"\n[[mods]]\nmodId=\"farmingforblockheads\"\nversion=\"14.0.2\"\ndisplayName=\"Farming for Blockheads\"\nauthors="+authors+"\ndescription=\"中文描述\"\n[[dependencies.farmingforblockheads]]\nmodId=\"minecraft\"\nversionRange=\""+range+"\"\n";
        try(var zip=new JarOutputStream(Files.newOutputStream(path))){zip.putNextEntry(new JarEntry("META-INF/mods.toml"));zip.write(toml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return path;
    }
    @Test void authorArrayDoesNotAbortScanAndKeepsAllNames()throws Exception{
        var entry=new ModMetadataParser().parse(jar("CustomSkinLoader_ForgeActive-14.19.1.jar","[\"xfl03\",\"JLChnToZ\"]","[1.17.1,)"));
        assertNotNull(entry);assertEquals("xfl03, JLChnToZ",entry.getAuthors());assertEquals("中文描述",entry.getDescription());assertEquals("1.17.1",entry.getMcVersion());
    }
    @Test void confirmedEnvironmentOverridesDependencyForEveryMod()throws Exception{
        var environment=new yagen.waitmydawn.kb.model.GameEnvironment(temp.resolve("1.20.1-Forge-MKB.jar").toString(),"forge","1.20.1");
        var file=jar("farmingforblockheads-forge-1.20.1-14.0.2.jar","\"BlayTheNinth\"","[1.20,1.21)");
        assertEquals("1.20",new ModMetadataParser().parse(file).getMcVersion());
        var entry=new ModMetadataParser().parse(file,environment);assertEquals("1.20.1",entry.getMcVersion());assertEquals("14.0.2",entry.getVersion());
        assertEquals("1.20.1",new ModMetadataParser().parse(jar("other-1.19.2.jar","[]","[1.17.1,)"),environment).getMcVersion());
    }
    @Test void malformedJarIsSkippedAndNextJarCanStillBeParsed()throws Exception{
        var bad=temp.resolve("bad.jar");try(var zip=new JarOutputStream(Files.newOutputStream(bad))){zip.putNextEntry(new JarEntry("META-INF/mods.toml"));zip.write("[[mods]]\nauthors=[ broken".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        var parser=new ModMetadataParser();assertNull(parser.parse(bad));assertNotNull(parser.parse(jar("good.jar","\"Author\"","[1.20.1,)")));
    }
}
