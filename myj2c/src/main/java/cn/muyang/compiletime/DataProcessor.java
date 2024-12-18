package cn.muyang.compiletime;

import java.io.*;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class DataProcessor {

    public static DataBlock readDataBlock(DataInputStream in, boolean hasMeta) throws IOException {
        DataBlock db = DataBlock.readFrom(in, hasMeta);
        if (db == null) {
            System.out.println("[DataProcessor] readDataBlock returned null (end of data)");
        } else {
            System.out.println("[DataProcessor] Read DataBlock:");
            System.out.println(" - Offsets: " + db.offsets);
            System.out.println(" - hasMeta: " + hasMeta);
            if (db.payload != null) {
                System.out.println(" - Payload length: " + db.payload.length);
            } else {
                System.out.println(" - Payload is null");
            }
            if (hasMeta && db.getMetadata() != null) {
                System.out.println(" - Metadata: " + Arrays.toString(db.getMetadata()));
            }
        }
        return db;
    }

    public static long readVarLong(InputStream in) throws IOException {
        long value = 0L;
        int shift = 0;
        while (shift < 64) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            value |= ((long)(b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        return value;
    }

    private static int writeVarLong(OutputStream out, long val) throws IOException {
        int count = 0;
        long value = val;
        while ((value & ~0x7FL) != 0) {
            out.write((int)((value & 0x7F) | 0x80));
            value >>>= 7;
            count++;
        }
        out.write((int)value);
        return count + 1;
    }

    public static void reconstructLibrary(String inputDataPath, String baseDir, String finalName, String tempLibName) {
        try {
            System.out.println("[DataProcessor] reconstructLibrary called with:");
            System.out.println(" - inputDataPath: " + inputDataPath);
            System.out.println(" - baseDir: " + baseDir);
            System.out.println(" - finalName: " + finalName);
            System.out.println(" - tempLibName: " + tempLibName);

            FileInputStream fis = new FileInputStream(inputDataPath);
            BufferedInputStream bis = new BufferedInputStream(fis, 0x100000);
            Inflater inflater = new Inflater();
            InflaterInputStream iis = new InflaterInputStream(bis, inflater, 0x100000);

            OutputStream out = createExtractorOutputStream(baseDir, finalName, tempLibName);
            copyAndProcess(iis, out, baseDir);

            inflater.end();
            iis.close();
            out.close();
            System.out.println("[DataProcessor] Library reconstruction completed.");
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static OutputStream createExtractorOutputStream(String baseDir, String finalName, String altName) {
        System.out.println("[DataProcessor] createExtractorOutputStream baseDir=" + baseDir + ", finalName=" + finalName + ", altName=" + altName);
        new File(baseDir).mkdirs();
        return new ExtractorOutputStream(finalName, baseDir, altName);
    }

    private static void copyAndProcess(InputStream in, OutputStream out, String tempPath) throws IOException {
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempPath + ".temp"), 0x100000));
        DataInputStream dis = new DataInputStream(in);

        byte[] magic = {72,50,65,49}; // "H2A1"
        byte[] readMagic = new byte[4];
        dis.readFully(readMagic);
        if (!Arrays.equals(readMagic, magic)) {
            dos.close();
            throw new IOException("Invalid header");
        }
        System.out.println("[DataProcessor] Magic header verified: H2A1");

        // read total size (not used directly here)
        long totalSize = readVarLong(dis);
        System.out.println("[DataProcessor] Total size read (ignored): " + totalSize);

        long cumulativeBytes = 0L;
        List<Long> offsetsList = new ArrayList<>();
        boolean done = false;

        System.out.println("[DataProcessor] Starting first pass with hasMeta=false");
        while (!done) {
            TreeMap<Long,byte[]> dataMap = new TreeMap<>();
            int limit = 16777216;
            int sumBytes = 0;
            boolean reachedEnd = false;

            while (sumBytes < limit) {
                DataBlock db = readDataBlock(dis, false);
                if (db == null) {
                    reachedEnd = true;
                    break;
                }
                int blockLen = db.payload.length;
                sumBytes += blockLen;
                for (Long id : db.offsets) {
                    dataMap.put(id, db.payload);
                }
            }

            if (dataMap.size() == 0) {
                done = true;
            } else {
                offsetsList.add(cumulativeBytes);
                for (Map.Entry<Long,byte[]> e : dataMap.entrySet()) {
                    cumulativeBytes += writeVarLong(dos, e.getKey()) + writeVarLong(dos, 0L);
                    cumulativeBytes += writeVarLong(dos, e.getValue().length);
                    dos.write(e.getValue());
                    cumulativeBytes += e.getValue().length;
                }
                cumulativeBytes += writeVarLong(dos, 0L);
                if (reachedEnd) {
                    done = true;
                }
            }
        }

        dos.close();
        long fileLen = new File(tempPath + ".temp").length();
        System.out.println("[DataProcessor] After first pass temp file size: " + fileLen);

        // Merge if needed
        while (offsetsList.size() > 64) {
            System.out.println("[DataProcessor] Merging blocks...");
            List<Long> newOffsets = new ArrayList<>();
            long written = 0L;

            DataOutputStream dos2 = new DataOutputStream(
                    new BufferedOutputStream(
                            new FileOutputStream(tempPath + ".b"), 0x100000));
            List<Long> currentOffsets = offsetsList;

            while (!currentOffsets.isEmpty()) {
                newOffsets.add(written);
                int chunkSize = Math.min(currentOffsets.size(), 64);
                List<Long> sub = currentOffsets.subList(0, chunkSize);

                TreeSet<IndexEntry> entrySet = new TreeSet<>();
                System.out.println("[DataProcessor] buildIndex during merge, hasMeta=false");
                buildIndex(tempPath + ".temp", sub, entrySet, false);

                Iterator<DataBlock> it = createDataBlockIterator(entrySet);
                while (it.hasNext()) {
                    DataBlock block = it.next();
                    long firstID = block.offsets.get(0);
                    written += writeVarLong(dos2, firstID)
                            + writeVarLong(dos2, 0L)
                            + writeVarLong(dos2, block.payload.length);
                    dos2.write(block.payload);
                    written += block.payload.length;
                }

                written += writeVarLong(dos2, 0L);
                currentOffsets = currentOffsets.subList(chunkSize, currentOffsets.size());
            }

            dos2.close();
            new File(tempPath + ".temp").delete();
            new File(tempPath + ".b").renameTo(new File(tempPath + ".temp"));

            offsetsList = newOffsets;
            fileLen = new File(tempPath + ".temp").length();
            System.out.println("[DataProcessor] After merge temp file size: " + fileLen);
        }

        System.out.println("[DataProcessor] Final pass reading data blocks with NO metadata...");
        TreeSet<IndexEntry> finalSet = new TreeSet<>();
        buildIndex(tempPath + ".temp", offsetsList, finalSet, false); // use false here

        Iterator<DataBlock> finalIt = createDataBlockIterator(finalSet);
        while (finalIt.hasNext()) {
            DataBlock block = finalIt.next();
            System.out.println("[DataProcessor] Writing DataBlock with payload size " + block.payload.length);
            System.out.println("[DataProcessor] DataBlock first bytes: " + debugBytes(block.payload, 64));
            out.write(block.payload);
        }

        new File(tempPath + ".temp").delete();
        out.flush();
    }

    private static String debugBytes(byte[] data, int max) {
        int len = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString().trim();
    }

    private static Iterator<DataBlock> createDataBlockIterator(TreeSet<IndexEntry> set) {
        return new DataIterator(set);
    }

    private static long buildIndex(String filename, List<Long> offsets, TreeSet<IndexEntry> treeSet, boolean flag) throws IOException {
        System.out.println("[DataProcessor] buildIndex called with hasMeta=" + flag + " on file: " + filename);
        System.out.println("[DataProcessor] Offsets: " + offsets);

        long total = 0L;
        int bufferSize = (offsets.size() == 0) ? 67108864 : 67108864 / offsets.size();
        if (bufferSize == 0) bufferSize = 4096;

        for (int i = 0; i < offsets.size(); i++) {
            FileInputStream fis = new FileInputStream(filename);
            if (offsets.get(i) > 0) {
                long skipped = fis.skip(offsets.get(i));
                if (skipped < offsets.get(i)) {
                    fis.close();
                    throw new EOFException("Failed to skip to offset");
                }
            }
            IndexEntry entry = new IndexEntry(i);
            entry.hasMeta = flag;
            entry.dataIn = new DataInputStream(new BufferedInputStream(fis, bufferSize));
            int blockSize = entry.loadData();
            System.out.println("[DataProcessor] IndexEntry " + i + " loaded DataBlock of size: " + blockSize);
            if (entry.dataBlock != null) {
                treeSet.add(entry);
            }
            total += blockSize;
        }
        return total;
    }

}
