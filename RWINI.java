package com.eam.shuangx339.ModTranslater; 
import android.util.Log;
import java.io.*;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;

public class RWINI implements Serializable {
    public File INIFile;
    public File MetaFile;    
    public String MetaDir;
    public boolean isMeedTran=false;
    volatile LinkedHashMap<String,LinkedHashMap<String,Long>> KnotList;   
    ArrayList<String> allkeys=new ArrayList<>(),allvalues=new ArrayList<>();    
    RandomAccessFile db;
    public RWINI(File INIFile, String projectpath, String Meta) throws FileNotFoundException, IOException {
        this.INIFile = INIFile;
        this.MetaDir = FilesHandler.MetaDir;
        MetaFile = new File(MetaDir + projectpath + "/" + Meta.replace(".ini", ".meta"));
        db = new RandomAccessFile(INIFile, "rw");        

        if (MetaFile.exists()) {
            loadMeta();
        } else {
            MetaFile.getParentFile().mkdirs();   
            MetaFile.createNewFile();
            init();
        }
    }
    private void loadMeta() throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(MetaFile)));
        KnotList = new LinkedHashMap<>();
        try {
            LinkedHashMap<String,Long> temp=null;       
            int Knotsize=in.readInt();           
            for (int i=0;i < Knotsize;i++) {
                temp = new LinkedHashMap<>(); 
                for (int j=0,mapsize=in.readInt();j < mapsize;j++) {     
                    temp.put(in.readUTF(), in.readLong());
                }
                KnotList.put(in.readUTF(), temp);
            }
        } finally {
            in.close();
        }

        initList();
    }
    public String getKnotBykey(String key) {
        
        for (Map.Entry en:KnotList.entrySet()) {
            for (String enn:((Map<String,Long>)en.getValue()).keySet()) {
                if (key.equals(enn))                   
                    return en.getKey().toString();                    
            }         
        }
        return null;
    }
    public void init() throws IOException {
        initKnotList();
        initList();       
        saveIniMap();
    }

    private void initKnotList() throws IOException {
        KnotList = new LinkedHashMap<>();
        LinkedHashMap<String,Long> knot = null;
        int index;
        String str;
        //初始化内存中的索引map
        db.seek(0);
        while ((str = db.readLine()) != null) {
            if (str.equals("") || str.startsWith("#"))continue;               
            if (str.startsWith("["))
                KnotList.put(str.trim(), knot = new LinkedHashMap<String,Long>());                            
            else {
                index = str.indexOf(":");          
                if (index == -1)index = str.indexOf("=");
                knot.put(str.substring(0, index).trim(), db.getFilePointer() - (str.length() - index));                
            } 
        }
    }
    private void initList() throws IOException {        
        String ss = null;
        for (Map.Entry en:KnotList.entrySet()) {
            for (Map.Entry enn:((Map<String,Long>)en.getValue()).entrySet()) {
                ss = enn.getKey().toString();
                for (String s:FilesHandler.Trankeys)   
                    if (ss.length() == s.length())
                        if (s.equals(ss))
                            isMeedTran = true;                                                 
                allkeys.add(ss);                 
                allvalues.add(new String(getData((long)enn.getValue()), "utf-8"));
            }
        }       
        allvalues.trimToSize();
        allkeys.trimToSize();

    }

    private void saveIniMap() throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(MetaFile)));
        try {
            out.writeInt(KnotList.size());
            for (Map.Entry en:KnotList.entrySet()) {                
                LinkedHashMap<String,Long> tempmap=(LinkedHashMap) en.getValue();
                out.writeInt(tempmap.size());
                for (Map.Entry enn:tempmap.entrySet()) {                   
                    out.writeUTF(enn.getKey().toString());
                    out.writeLong((long)(enn.getValue()));
                }
                out.writeUTF(en.getKey().toString());
            }            
        } finally {
            out.close();
        }
    }

    private byte[] getData(long pos) throws IOException {
        db.seek(db.length() - 1);

        if (db.readByte() != 10) {                      
            db.setLength(db.length() + 1);
            db.writeByte(10);
        }
        db.seek(pos);
        ArrayList<Byte> arr=new ArrayList<>();
        Byte b ;
        while ((b = db.readByte()) != 10)
            arr.add(b);

        Byte[] BigByte=arr.toArray(new Byte[0]);
        return toPrimitives(BigByte);
    }
    private byte[] toPrimitives(Byte[] oBytes) {
        byte[] bytes = new byte[oBytes.length];

        for (int i = 0; i < oBytes.length; i++) 
            bytes[i] = oBytes[i];


        return bytes;
    }
    private void writeData(long pos, byte[] data) throws IOException {

        db.seek(pos);
        int size=data.length,srclen = 0;
        if (pos == db.length()) {           
            db.setLength(db.length() + data.length);    
        } else {    
            while (db.readByte() != 10)srclen++;            
            ensureLength(pos, size, srclen);
            db.seek(pos);
        }

        db.write(data);

    }
    private void ensureLength(long pos, int datalen, int srclen) {

        try {
            long pd=pos + datalen,ps=pos + srclen;
            db.seek(pd);
            if (datalen <= srclen) {
                for (int i=0;i < (srclen - datalen);i++)
                    db.writeByte(0);
            } else {     
                db.seek(ps);
                long Diff=datalen - srclen;
                db.setLength(db.length() + Diff);               
                byte[] b=new byte[(int)(db.length() - ps)];
                db.read(b);  
                db.seek(pd);
                db.write(b);      
                initKnotList();
            }
        } catch (IOException e) {}

    }

    private long nextAvailablePos() throws IOException {
        return db.length();

    }
    private Long getPos(String key) {

        Object pos = null;
        for (LinkedHashMap en:KnotList.values()) {            
            if ((pos = en.get(key)) != null)              
                return (Long)pos;

        }

        return null;
    }
    private static <K, V> K getKeyByLoop(Map<K, V> map, V value) {
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (Objects.equals(entry.getValue(), value)) {
                return entry.getKey();
            }
        }
        return null;
    }
    public void put(String key, byte[] value) throws IOException {
        Long index = getPos(key);
        if (index == null) 
            index = nextAvailablePos();   

        writeData(index, value);
    }

    public byte[] get(String key) throws IOException {
        Long index = getPos(key);

        if (index != null) 
            return getData(index);

        return null;
    }

    public boolean remove(String key) {
        Long index =getPos(key);
        if (index != null) {


            return true;

        }
        return false;
    }

    public void flush() throws IOException {
        saveIniMap();
        db.getFD().sync();
    }

    public void close() throws IOException {
        flush();
        db.close();
    }


}
