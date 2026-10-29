package yagen.waitmydawn.kb.model;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 发布事务保留内容相同的行，避免补充构建重写全部 LOB 与向量页。 */
final class BuildTableDelta {
    private BuildTableDelta() {}

    static Map<String,Integer> removeChangedRows(Connection target, Connection source, String table) throws Exception {
        try (var statement=target.createStatement();var count=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {
            count.next();if(count.getLong(1)==0)return null;
        }
        var needed=new HashMap<String,Integer>();
        try(var statement=source.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
            while(rows.next())needed.merge(digest(rows),1,Integer::sum);
        }
        var keys=new TreeMap<Short,String>();
        try(var columns=target.getMetaData().getPrimaryKeys(null,null,table)) {
            while(columns.next())keys.put(columns.getShort("KEY_SEQ"),columns.getString("COLUMN_NAME"));
        }
        if(keys.isEmpty())throw new SQLException("Build table requires a primary key: "+table);
        String where=keys.values().stream().map(k->k+"=?").collect(java.util.stream.Collectors.joining(" AND "));
        var removed=new ArrayList<Object[]>();
        // 先完成游标扫描再删除，避免驱动在同表更新时改变遍历范围。
        try(var statement=target.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
            while(rows.next()) {
                String hash=digest(rows);int left=needed.getOrDefault(hash,0);
                if(left>0){needed.put(hash,left-1);continue;}
                var identity=new Object[keys.size()];int i=0;for(String key:keys.values())identity[i++]=rows.getObject(key);removed.add(identity);
            }
        }
        try(var delete=target.prepareStatement("DELETE FROM "+table+" WHERE "+where)) {
            int count=0;for(var key:removed){for(int i=0;i<key.length;i++)delete.setObject(i+1,key[i]);delete.addBatch();if(++count%100==0)delete.executeBatch();}delete.executeBatch();
        }
        return needed;
    }

    static boolean take(Map<String,Integer> needed,ResultSet row)throws Exception {
        if(needed==null)return true;String hash=digest(row);int count=needed.getOrDefault(hash,0);if(count==0)return false;needed.put(hash,count-1);return true;
    }

    private static String digest(ResultSet row)throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");var metadata=row.getMetaData();
        for(int i=1;i<=metadata.getColumnCount();i++) {
            if(metadata.isAutoIncrement(i))continue;
            byte[] value;
            if(metadata.getColumnType(i)==Types.BLOB)value=row.getBytes(i);
            else{String text=row.getString(i);value=text==null?null:text.getBytes(StandardCharsets.UTF_8);}
            // 长度前缀区分空值、空串和不同字段的拼接；自增 ID 不参与内容身份。
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(value==null?-1:value.length).array());if(value!=null)hash.update(value);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
