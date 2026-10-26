package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.config.DataDirLock;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.*;

/** 外部构建目标拥有独立目录锁和数据库生命周期，当前目标复用应用持有的锁。 */
public final class BuildTarget implements AutoCloseable {
    private final DataDirLock lock;
    private final DatabaseBuilder database;
    private final AppConfig config;
    private BuildTarget(DataDirLock lock,DatabaseBuilder database,AppConfig config) {
        this.lock=lock;this.database=database;this.config=config;
    }
    public static BuildTarget open(Path directory,AppConfig current,DatabaseBuilder currentDb) throws java.io.IOException {
        Path real=directory.toRealPath();
        if(Files.isSameFile(real,current.getDataDir())) return new BuildTarget(null,currentDb,current);
        if(!Files.isRegularFile(real.resolve("rag_data.mv.db")))
            throw new java.io.IOException("所选目录中没有基础数据库 rag_data.mv.db");
        var lock=DataDirLock.acquire(real);
        DatabaseBuilder database=null;
        try {
            var config=current.forBuildTarget(real);
            database=new DatabaseBuilder(config);database.initDatabase();
            return new BuildTarget(lock,database,config);
        } catch(Exception e) {
            if(database!=null) database.shutdown();lock.close();
            throw new java.io.IOException("无法打开目标知识库",e);
        }
    }
    public DatabaseBuilder database() {return database;}
    public AppConfig config() {return config;}
    public boolean external() {return lock!=null;}
    @Override public void close() {
        if(lock!=null) try {database.shutdown();} finally {lock.close();}
    }
}
