package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 环境确认属于来源草稿，不参与已发布知识内容摘要。 */
public final class ScanEnvironmentStore {
    private final DatabaseBuilder db;
    public ScanEnvironmentStore(DatabaseBuilder db){this.db=db;}
    public static void init(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS rag_scan_environment(id VARCHAR(64) PRIMARY KEY,folder VARCHAR(4000) NOT NULL,jar_path VARCHAR(4000) NOT NULL,loader VARCHAR(50) NOT NULL,mc_version VARCHAR(100) NOT NULL)");s.execute("ALTER TABLE rag_scan_environment ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");}}
    private static String folder(Path folder){return folder.toAbsolutePath().normalize().toString();}
    public void save(Path folder,GameEnvironment environment){
        String root=folder(folder);
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_scan_environment(id,folder,jar_path,loader,mc_version,updated_at) KEY(id) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)")){
            p.setString(1,BuildRequest.fingerprint(root));p.setString(2,root);p.setString(3,environment.jarPath());p.setString(4,environment.loader());p.setString(5,environment.minecraftVersion());p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("扫描环境保存失败",e);}
    }
    public Path lastFolder(){try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT folder FROM rag_scan_environment ORDER BY updated_at DESC,id")){return r.next()?Path.of(r.getString(1)):null;}catch(SQLException e){throw new IllegalStateException("扫描目录读取失败",e);}}
    public GameEnvironment forJar(Path jar){
        Path path=jar.toAbsolutePath().normalize();GameEnvironment match=null;int depth=-1;
        try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT folder,jar_path,loader,mc_version FROM rag_scan_environment")){
            while(r.next()){Path root=Path.of(r.getString(1));if(path.startsWith(root)&&root.getNameCount()>depth){depth=root.getNameCount();match=new GameEnvironment(r.getString(2),r.getString(3),r.getString(4));}}
            return match;
        }catch(SQLException e){throw new IllegalStateException("扫描环境读取失败",e);}
    }
    public GameEnvironment forFolder(Path folder){return forJar(folder.toAbsolutePath().normalize().resolve(".scan-environment"));}
}
