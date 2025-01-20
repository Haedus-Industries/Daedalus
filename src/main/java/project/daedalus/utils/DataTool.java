package project.daedalus.utils;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A standalone archive tool to compress directories. It does not have any
 * dependencies except for the Java libraries.
 * <p>
 * Unlike other compression tools, it splits the data into chunks and sorts the
 * chunks, so that large directories or files that contain duplicate data are
 * compressed much better.
 */
public class DataTool {

    /**
     * The file header.
     */
    private static final byte[] HEADER = {'H', '2', 'A', '1'};

    public static void compress(String fromDir, String toFile, int level) throws IOException {
        System.out.println("[DataTool] Starting compression from: " + fromDir + " to: " + toFile + " with level: " + level);
        final long start = System.nanoTime();
        long size = getSize(new File(fromDir), new Runnable() {
            int count;
            long lastTime = start;

            @Override
            public void run() {
                count++;
                if (count % 1000 == 0) {
                    long now = System.nanoTime();
                    if (now - lastTime > TimeUnit.SECONDS.toNanos(3)) {
                        lastTime = now;
                    }
                }
            }
        });
        InputStream in = getDirectoryInputStream(fromDir);
        String temp = toFile + ".temp";
        OutputStream out = new BufferedOutputStream(new FileOutputStream(toFile), 1024 * 1024);
        Deflater def = new Deflater();
        def.setLevel(level);
        out = new BufferedOutputStream(new DeflaterOutputStream(out, def), 1024 * 1024);
        sort(in, out, temp, size);
        in.close();
        out.close();
        System.out.println("[DataTool] Compression completed. Output file: " + toFile);
    }


    private static long getSize(File f, Runnable r) {
        // assume a metadata entry is 40 bytes
        long size = 40;
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) {
                for (File c : list) {
                    size += getSize(c, r);
                }
            }
        } else {
            size += f.length();
        }
        r.run();
        return size;
    }

    private static InputStream getDirectoryInputStream(final String dir) {
        File f = new File(dir);
        if (!f.isDirectory() || !f.exists()) {
            throw new IllegalArgumentException("Not an existing directory: " + dir);
        }

        return new InputStream() {
            private final String baseDir;
            private final ArrayList<String> files = new ArrayList<>();
            private String current;
            private ByteArrayInputStream meta;
            private DataInputStream fileIn;
            private long remaining;

            {
                File f = new File(dir);
                baseDir = f.getAbsolutePath();
                addDirectory(f);
            }

            private void addDirectory(File f) {
                File[] list = f.listFiles();
                if (list != null) {
                    // first all directories, then all files
                    for (File c : list) {
                        if (c.isDirectory()) {
                            files.add(c.getAbsolutePath());
                        }
                    }
                    for (File c : list) {
                        if (c.isFile()) {
                            files.add(c.getAbsolutePath());
                        }
                    }
                }
            }

            // Metadata format:
            // int: metadata length
            // byte: 0: directory, 1: file
            // byte: 0: read-write, 1: read-only
            // varLong: lastModified
            // (file only) varLong: file length
            // utf-8: file name

            @Override
            public int read() throws IOException {
                if (meta != null) {
                    int x = meta.read();
                    if (x >= 0) {
                        return x;
                    }
                    meta = null;
                }
                if (fileIn != null) {
                    if (remaining > 0) {
                        int x = fileIn.read();
                        remaining--;
                        if (x < 0) {
                            throw new EOFException();
                        }
                        return x;
                    }
                    fileIn.close();
                    fileIn = null;
                }
                if (files.isEmpty()) {
                    // EOF
                    return -1;
                }

                current = files.remove(files.size() - 1);
                File f = new File(current);
                boolean isFile = f.isFile();

                ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(metaOut);
                out.writeInt(0);
                out.write(isFile ? 1 : 0);
                out.write(!f.canWrite() ? 1 : 0);
                writeVarLong(out, f.lastModified());
                if (isFile) {
                    remaining = f.length();
                    writeVarLong(out, remaining);
                }
                if (!current.startsWith(baseDir)) {
                    throw new IOException("File " + current + " does not start with " + baseDir);
                }
                String n = current.substring(baseDir.length() + 1);
                out.writeUTF(n);
                out.writeInt(metaOut.size());
                out.flush();
                byte[] bytes = metaOut.toByteArray();
                // copy metadata length to beginning
                System.arraycopy(bytes, bytes.length - 4, bytes, 0, 4);
                bytes = Arrays.copyOf(bytes, bytes.length - 4);

                System.out.println("[DataTool] Writing metadata for " + (isFile ? "file" : "directory") + ": " + n);
                System.out.println("  isFile: " + isFile);
                System.out.println("  readOnly: " + (!f.canWrite()));
                System.out.println("  lastModified: " + f.lastModified());
                if (isFile) {
                    System.out.println("  fileLength: " + remaining);
                }

                meta = new ByteArrayInputStream(bytes);
                if (isFile) {
                    fileIn = new DataInputStream(new BufferedInputStream(
                            new FileInputStream(current), 1024 * 1024));
                } else {
                    fileIn = null;
                }
                return meta.read();
            }

            @Override
            public int read(byte[] buff, int offset, int length) throws IOException {
                if (meta != null || fileIn == null || remaining == 0) {
                    return super.read(buff, offset, length);
                }
                int l = (int) Math.min(length, remaining);
                fileIn.readFully(buff, offset, l);
                remaining -= l;
                return l;
            }

        };
    }

    private static void sort(InputStream in, OutputStream out,
                             String tempFileName, long size) throws IOException {
        System.out.println("[DataTool] Starting sorting and chunking...");
        int bufferSize = 32 * 1024 * 1024;
        DataOutputStream tempOut = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(tempFileName), 1024 * 1024));
        byte[] bytes = new byte[bufferSize];
        List<Long> segmentStart = new ArrayList<>();
        long outPos = 0;
        long id = 1;

        while (true) {
            int len = readFully(in, bytes, bytes.length);
            if (len == 0) {
                break;
            }
            TreeMap<Chunk, Chunk> map = new TreeMap<>();
            for (int pos = 0; pos < len; ) {
                int[] key = getKey(bytes, pos, len);
                int l = key[3];
                byte[] buff = Arrays.copyOfRange(bytes, pos, pos + l);
                pos += l;
                Chunk c = new Chunk(null, key, buff);
                Chunk old = map.get(c);
                if (old == null) {
                    c.idList = new ArrayList<>();
                    c.idList.add(id);
                    map.put(c, c);
                } else {
                    old.idList.add(id);
                }
                id++;
            }
            segmentStart.add(outPos);
            for (Chunk c : map.keySet()) {
                outPos += c.write(tempOut, true);
            }
            // end of segment
            outPos += writeVarLong(tempOut, 0);
        }
        tempOut.close();
        long tempSize = new File(tempFileName).length();
        System.out.println("[DataTool] Finished initial chunk writing. Temp size: " + tempSize);

        // merging and sorting chunks if needed
        int blockSize = 64;
        boolean merge = false;
        while (segmentStart.size() > blockSize) {
            merge = true;
            ArrayList<Long> segmentStart2 = new ArrayList<>();
            outPos = 0;
            DataOutputStream tempOut2 = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(tempFileName + ".b"), 1024 * 1024));
            while (segmentStart.size() > 0) {
                segmentStart2.add(outPos);
                int s = Math.min(segmentStart.size(), blockSize);
                List<Long> start = segmentStart.subList(0, s);
                TreeSet<ChunkStream> segmentIn = new TreeSet<>();
                long read = openSegments(start, segmentIn, tempFileName, true);
                Iterator<Chunk> it = merge(segmentIn);
                Chunk last = null;
                while (it.hasNext()) {
                    Chunk c = it.next();
                    if (last == null) {
                        last = c;
                    } else if (last.compareTo(c) == 0) {
                        last.idList.addAll(c.idList);
                    } else {
                        outPos += last.write(tempOut2, true);
                        last = c;
                    }
                }
                if (last != null) {
                    outPos += last.write(tempOut2, true);
                }
                // end of segment
                outPos += writeVarLong(tempOut2, 0);
                segmentStart = segmentStart.subList(s, segmentStart.size());
            }
            segmentStart = segmentStart2;
            tempOut2.close();
            tempSize = new File(tempFileName).length();
            new File(tempFileName).delete();
            tempFileName += ".b";
        }

        TreeSet<ChunkStream> segmentIn = new TreeSet<>();
        long read = openSegments(segmentStart, segmentIn, tempFileName, true);

        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.write(HEADER);
        writeVarLong(dataOut, size);

        System.out.println("[DataTool] Writing final compressed data with header...");
        Chunk last = null;
        Iterator<Chunk> it = merge(segmentIn);
        while (it.hasNext()) {
            Chunk c = it.next();
            if (last == null) {
                last = c;
            } else if (last.compareTo(c) == 0) {
                last.idList.addAll(c.idList);
            } else {
                last.write(dataOut, false);
                last = c;
            }
        }
        if (last != null) {
            last.write(dataOut, false);
        }
        new File(tempFileName).delete();
        writeVarLong(dataOut, 0);
        dataOut.flush();
        System.out.println("[DataTool] Finished writing final compressed .dat file");
    }

    private static long openSegments(List<Long> segmentStart, TreeSet<ChunkStream> segmentIn,
                                     String tempFileName, boolean readKey) throws IOException {
        long inPos = 0;
        int bufferTotal = 64 * 1024 * 1024;
        int bufferPerStream = bufferTotal / segmentStart.size();
        for (int i = 0; i < segmentStart.size(); i++) {
            InputStream in = new FileInputStream(tempFileName);
            in.skip(segmentStart.get(i));
            ChunkStream s = new ChunkStream(i);
            s.readKey = readKey;
            s.in = new DataInputStream(new BufferedInputStream(in, bufferPerStream));
            inPos += s.readNext();
            if (s.current != null) {
                segmentIn.add(s);
            }
        }
        return inPos;
    }

    private static Iterator<Chunk> merge(final TreeSet<ChunkStream> segmentIn) {
        return new Iterator<Chunk>() {

            @Override
            public boolean hasNext() {
                return !segmentIn.isEmpty();
            }

            @Override
            public Chunk next() {
                ChunkStream s = segmentIn.first();
                segmentIn.remove(s);
                Chunk c = s.current;
                int len = s.readNext();
                if (s.current != null) {
                    segmentIn.add(s);
                }
                return c;
            }

        };
    }

    private static int readFully(InputStream in, byte[] buffer, int max)
            throws IOException {
        int result = 0, len = Math.min(max, buffer.length);
        while (len > 0) {
            int l = in.read(buffer, result, len);
            if (l < 0) {
                break;
            }
            result += l;
            len -= l;
        }
        return result;
    }

    private static int[] getKey(byte[] data, int start, int maxPos) {
        int minLen = 4 * 1024;
        int mask = 4 * 1024 - 1;
        long min = Long.MAX_VALUE;
        int pos = start;
        for (int j = 0; pos < maxPos; pos++, j++) {
            if (pos <= start + 10) {
                continue;
            }
            long hash = getSipHash24(data, pos - 10, pos, 111, 11224);
            if (hash < min) {
                min = hash;
            }
            if (j > minLen) {
                if ((hash & mask) == 1) {
                    break;
                }
                if (j > minLen * 4 && (hash & (mask >> 1)) == 1) {
                    break;
                }
                if (j > minLen * 16) {
                    break;
                }
            }
        }
        int len = pos - start;
        int[] counts = new int[8];
        for (int i = start; i < pos; i++) {
            int x = data[i] & 0xff;
            counts[x >> 5]++;
        }
        int cs = 0;
        for (int i = 0; i < 8; i++) {
            cs *= 2;
            if (counts[i] > (len / 32)) {
                cs += 1;
            }
        }
        int[] key = new int[4];
        key[0] = (int) (min >>> 32);
        key[1] = (int) min;
        key[2] = cs;
        key[3] = len;
        return key;
    }

    private static long getSipHash24(byte[] b, int start, int end, long k0,
                                     long k1) {
        long v0 = k0 ^ 0x736f6d6570736575L;
        long v1 = k1 ^ 0x646f72616e646f6dL;
        long v2 = k0 ^ 0x6c7967656e657261L;
        long v3 = k1 ^ 0x7465646279746573L;
        int repeat;
        for (int off = start; off <= end + 8; off += 8) {
            long m;
            if (off <= end) {
                m = 0;
                int i = 0;
                for (; i < 8 && off + i < end; i++) {
                    m |= ((long) b[off + i] & 255) << (8 * i);
                }
                if (i < 8) {
                    m |= ((long) end - start) << 56;
                }
                v3 ^= m;
                repeat = 2;
            } else {
                m = 0;
                v2 ^= 0xff;
                repeat = 4;
            }
            for (int i = 0; i < repeat; i++) {
                v0 += v1;
                v2 += v3;
                v1 = Long.rotateLeft(v1, 13);
                v3 = Long.rotateLeft(v3, 16);
                v1 ^= v0;
                v3 ^= v2;
                v0 = Long.rotateLeft(v0, 32);
                v2 += v1;
                v0 += v3;
                v1 = Long.rotateLeft(v1, 17);
                v3 = Long.rotateLeft(v3, 21);
                v1 ^= v2;
                v3 ^= v0;
                v2 = Long.rotateLeft(v2, 32);
            }
            v0 ^= m;
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private static int writeVarLong(OutputStream out, long x)
            throws IOException {
        int len = 0;
        while ((x & ~0x7f) != 0) {
            out.write((byte) (0x80 | (x & 0x7f)));
            x >>>= 7;
            len++;
        }
        out.write((byte) x);
        return ++len;
    }

    static long readVarLong(InputStream in) throws IOException {
        long x = in.read();
        if (x < 0) {
            throw new EOFException();
        }
        x = (byte) x;
        if (x >= 0) {
            return x;
        }
        x &= 0x7f;
        for (int s = 7; s < 64; s += 7) {
            long b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            b = (byte) b;
            x |= (b & 0x7f) << s;
            if (b >= 0) {
                break;
            }
        }
        return x;
    }

    static class ChunkStream implements Comparable<ChunkStream> {
        final int id;
        Chunk current;
        DataInputStream in;
        boolean readKey;

        ChunkStream(int id) {
            this.id = id;
        }

        int readNext() {
            current = null;
            current = Chunk.read(in, readKey);
            if (current == null) {
                return 0;
            }
            return current.value.length;
        }

        @Override
        public int compareTo(ChunkStream o) {
            int comp = current.compareTo(o.current);
            if (comp != 0) {
                return comp;
            }
            return Integer.signum(id - o.id);
        }
    }

    static class Chunk implements Comparable<Chunk> {
        ArrayList<Long> idList;
        final byte[] value;
        private final int[] sortKey;

        Chunk(ArrayList<Long> idList, int[] sortKey, byte[] value) {
            this.idList = idList;
            this.sortKey = sortKey;
            this.value = value;
        }

        public static Chunk read(DataInputStream in, boolean readKey) {
            try {
                ArrayList<Long> idList = new ArrayList<>();
                while (true) {
                    long x = DataTool.readVarLong(in);
                    if (x == 0) {
                        break;
                    }
                    idList.add(x);
                }
                if (idList.isEmpty()) {
                    // eof
                    in.close();
                    return null;
                }
                int[] key = null;
                if (readKey) {
                    key = new int[4];
                    for (int i = 0; i < key.length; i++) {
                        key[i] = in.readInt();
                    }
                }
                int len = (int) DataTool.readVarLong(in);
                byte[] value = new byte[len];
                in.readFully(value);
                return new Chunk(idList, key, value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        int write(DataOutputStream out, boolean writeKey) throws IOException {
            int len = 0;
            for (long x : idList) {
                len += writeVarLong(out, x);
            }
            len += writeVarLong(out, 0);
            if (writeKey) {
                for (int i = 0; i < sortKey.length; i++) {
                    out.writeInt(sortKey[i]);
                    len += 4;
                }
            }
            len += writeVarLong(out, value.length);
            out.write(value);
            len += value.length;
            return len;
        }

        @Override
        public int compareTo(Chunk o) {
            if (sortKey == null) {
                // sort by id
                long a = idList.get(0);
                long b = o.idList.get(0);
                if (a < b) {
                    return -1;
                } else if (a > b) {
                    return 1;
                }
                return 0;
            }
            for (int i = 0; i < sortKey.length; i++) {
                if (sortKey[i] < o.sortKey[i]) {
                    return -1;
                } else if (sortKey[i] > o.sortKey[i]) {
                    return 1;
                }
            }
            if (value.length < o.value.length) {
                return -1;
            } else if (value.length > o.value.length) {
                return 1;
            }
            for (int i = 0; i < value.length; i++) {
                int a = value[i] & 255;
                int b = o.value[i] & 255;
                if (a < b) {
                    return -1;
                } else if (a > b) {
                    return 1;
                }
            }
            return 0;
        }
    }
}
