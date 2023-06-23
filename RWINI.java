import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

class RWINI implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String META_FILE_EXTENSION = ".ser";
    private static final String COMMENT_PREFIX = "#";
    private static final String SECTION_PREFIX = "[";
    private static final String SECTION_SUFFIX = "]";
    private static final String KEY_VALUE_SEPARATOR = ":";
    private static final Set<String> Trankeys = new HashSet<>(Arrays.asList("displayText", "displayDescription", "text", "description", "isLockedMessage", "showMessageToAllEnemyPlayers", "isLockedAltMessage", "showMessageToPlayer", "cannotPlaceMessage", "showMessageToAllPlayer"));
    public static final String MetaDir = "/sdcard/360/"; // "/sdcard/Android/data/com.eam.shuangx339.ModTranslater/cache/";
    private File metaFile;
    private transient RandomAccessFile randomAccessFile;
    private boolean isClosed = false;
    private int lines;

    public File iniFile;
    private Map<String, Map<String, Integer>> sectionMap;
    private Map<Integer, Long> startPositionCache = new HashMap<>();

    public static RWINI CreatInstance(File inifile, String projectName) throws FileNotFoundException, IOException, NoSuchAlgorithmException, ClassNotFoundException, RuntimeException {
        // 判断传进来的File是否含有需要翻译的键，如果有返回实例并初始化变量，反之返回null
        Map<String, Map<String, Integer>> sectionMap = new HashMap<>();
        int i = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(inifile))) {
            Map<String, Integer> keymap = null;
            String line;
            while ((line = reader.readLine()) != null) {
                i++;

                if (line.startsWith(COMMENT_PREFIX)) {
                    continue;
                }
                if (line.startsWith(SECTION_PREFIX) && line.endsWith(SECTION_SUFFIX)) {
                    String sectionName = line.substring(1, line.length() - 1).trim();
                    sectionMap.put(sectionName, keymap = new HashMap<>());

                } else if (keymap != null) {
                    int separatorIndex;
                    if ((separatorIndex = line.indexOf(KEY_VALUE_SEPARATOR)) == -1) separatorIndex = line.indexOf("=");
                    if (separatorIndex >= 0) {
                        String key = line.substring(0, separatorIndex).trim();
                        if (Trankeys.contains(key))
                            // 将所在列和行通过位运算压缩合并
                            keymap.put(key, i << 5 | separatorIndex);
                    }
                }
            }
        }
        // 将sectionMap里面keymap空的删掉
        Iterator<Map.Entry<String, Map<String, Integer>>> iterator = sectionMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Map<String, Integer>> entry = iterator.next();
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        if (!sectionMap.isEmpty()) {

            String metafilename = MetaDir + projectName + "/" + getMetaFileName(inifile);
            File metaFile = new File(metafilename);
            if (metaFile.exists()) return Deserialize(metaFile);
            else {
                // 如果是空的
                createMetaFile(metaFile, projectName);
                return new RWINI(inifile, metaFile, sectionMap, i);
            }
        }
        return null;
    }

    public RWINI(File iniFile, File metafile, Map<String, Map<String, Integer>> sectionMap, int lines) throws IOException {
        this.metaFile = metafile;
        this.iniFile = iniFile;
        this.sectionMap = sectionMap;
        this.lines = lines;
        randomAccessFile = new RandomAccessFile(iniFile, "rw");
        Serialize();
    }

    private static String getMD5(File iniFile) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(iniFile.getParent().getBytes());
        return bytesToHex(md.digest());
    }

    private static boolean createMetaFile(File inifile, String projectName) throws IOException, RuntimeException {
        File metaDir = new File(MetaDir + projectName);
        metaDir.mkdir();
        File metafile = new File(metaDir, inifile.getName());

        return metafile.createNewFile();
    }

    private static String getMetaFileName(File inifile) throws NoSuchAlgorithmException {
        return String.format("%s_%s%s", getBaseName(inifile.getName()), getMD5(inifile), META_FILE_EXTENSION);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private static String getBaseName(String name) {
        if (name.equals("")) return name;
        return name.substring(0, name.lastIndexOf("."));
    }

    private static RWINI Deserialize(File metafile) throws FileNotFoundException, IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(metafile))) {
            return (RWINI) ois.readObject();
        }
    }

    private void Serialize() throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(metaFile))) {
            oos.writeObject(this);
        }
    }

    public Map<String, Map<String, String>> getMap() throws IOException, IllegalArgumentException {
        Map<String, Map<String, String>> result = new HashMap<>();
        Map<String, String> map;
        for (String s1 : sectionMap.keySet()) {
            result.put(s1, map = new HashMap<String, String>());
            for (String s2 : sectionMap.get(s1).keySet()) map.put(s2, getValue(s1, s2));
        }
        return result;
    }

    public String getValue(String section, String key) throws IOException, IllegalArgumentException {
        int value = EnsureException(section, key);
        // 取出行数和列数
        int line = value >> 5;
        int index = value & ((1 << 5) - 1);
        try {
            randomAccessFile.seek(getStartPosition(line, index));          
            return readTargetStringUntilNewLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private long getStartPosition(int line, int index) throws IOException {
        if (line >= lines) {
            throw new IOException("Over lines");
        }
        Long cachedValue = startPositionCache.get(line);
        if (cachedValue != null) {
            return cachedValue;
        }
        randomAccessFile.seek(0);
        for (int i = 1; i < line; i++) {
            randomAccessFile.readLine();
        }
        Long startPosition = randomAccessFile.getFilePointer() + index + 1;
        startPositionCache.put(line, startPosition);
        return startPosition;
    }

    private String readTargetStringUntilNewLine() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        int currentChar;
        while ((currentChar = randomAccessFile.read()) != -1) {
            if (currentChar == '\n') {
                break;
            }
            stringBuilder.append((char) currentChar);
        }
        return stringBuilder.toString();
    }

    public void set(String sectionName, String key, String newvalue) throws IOException {

        int value = EnsureException(sectionName, key);
        int line = value >> 5;
        int index = value & ((1 << 5) - 1);
        long lastposition = getStartPosition(line, index);
        randomAccessFile.seek(lastposition);
        // 读取一行，计算value的字符串长度，如果新值的长度大于value的长度，则将其后的数据后移，反之则前移
        System.out.println(randomAccessFile.readLine());
        int SpaceLength = (int) (randomAccessFile.getFilePointer() - lastposition - 1);
        System.out.println(SpaceLength);
        int offset;
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            if ((offset = newvalue.length() * 3 - SpaceLength) != 0) {
                // 内容长度发生变化，需要重新计算偏移量并进行内容的重新写入
                byte[] buffer = new byte[1024];
                int bytesRead;
                long currentPosition = randomAccessFile.getFilePointer();
                while ((bytesRead = randomAccessFile.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                randomAccessFile.seek(currentPosition + offset - 1);
                randomAccessFile.writeByte(0x0A);
                randomAccessFile.write(byteArrayOutputStream.toByteArray());
                randomAccessFile.setLength(randomAccessFile.length() + offset);
            }
            // 回写新值
            randomAccessFile.seek(lastposition);
            randomAccessFile.write(newvalue.getBytes());
        }
    }

    private Integer EnsureException(String sectionName, String key) throws IOException, IllegalArgumentException {
        if (isClosed) {
            throw new IOException("INI file has been closed");
        }
        if (!sectionMap.containsKey(sectionName)) {
            throw new IllegalArgumentException("Invalid section name: " + sectionName);
        }

        Map map = sectionMap.get(sectionName);
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        return (Integer) map.get(key);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        randomAccessFile = new RandomAccessFile(iniFile, "rw");
    }
    public void flush() throws IOException {
		Serialize();
        randomAccessFile.getFD().sync();
    }

    public void close() throws IOException {
        if (!isClosed) {
            if (randomAccessFile != null) {
                flush();
                randomAccessFile.close();
                randomAccessFile = null;
            }
            isClosed = true;
        }
    }
}