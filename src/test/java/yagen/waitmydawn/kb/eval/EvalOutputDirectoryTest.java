package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EvalOutputDirectoryTest {
    @TempDir Path temp;
    @Test void customOutputDoesNotChangeBaseDatabaseIdentityAndCannotOverwrite() throws Exception {
        var config=new EvalRunner.Config(temp.resolve("base"),Path.of("test-set/golden-set-modEnv1.json"),Set.of(),"unit",1,false);
        String previous=System.getProperty("maa.eval.outputRoot");
        try {
            System.clearProperty("maa.eval.outputRoot");
            assertEquals(config.dataDir().resolve("eval-runs/run-a"),EvalRunner.outputDirectory(config,"run-a"));
            var output=temp.resolve("external-sessions");System.setProperty("maa.eval.outputRoot",output.toString());
            assertEquals(output.resolve("run-a"),EvalRunner.outputDirectory(config,"run-a"));
            assertEquals(temp.resolve("base"),config.dataDir());
            Files.createDirectories(output.resolve("run-a"));
            assertThrows(java.io.IOException.class,()->EvalRunner.outputDirectory(config,"run-a"));
            for(String id:new String[]{"..",".","../escape","a/b","a\\b"})
                assertThrows(IllegalArgumentException.class,()->EvalRunner.outputDirectory(config,id));
        }finally{if(previous==null)System.clearProperty("maa.eval.outputRoot");else System.setProperty("maa.eval.outputRoot",previous);}
    }
}
