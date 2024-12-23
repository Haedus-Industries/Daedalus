package project.Daedalus.compiletime;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;

public final class DataBlock implements Comparable<DataBlock> {
    ArrayList<Long> offsets; // Originally 'void' ArrayList<Long>
    final byte[] payload;    // Originally 'new' [B
    private final int[] metadata; // Originally 'int' [I

    public DataBlock(ArrayList<Long> offsets, int[] metadata, byte[] payload) {
        this.offsets = offsets;
        this.metadata = metadata;
        this.payload = payload;
    }

    public int[] getMetadata() {
        return metadata;
    }

    /**
     * Read a DataBlock from the DataInputStream.
     * The format:
     * - Sequence of varLong until 0 encountered -> offsets.
     * - If readMetadata = true, read 4 ints.
     * - varLong for payload length, then read payload bytes.
     */
    public static DataBlock readFrom(DataInputStream in, boolean readMetadata) {
        try {
            ArrayList<Long> offsetList = new ArrayList<>();
            long val;
            while ((val = DataProcessor.readVarLong(in)) != 0L) {
                offsetList.add(val);
            }
            if (offsetList.isEmpty()) {
                in.close();
                return null;
            }

            int[] meta = null;
            if (readMetadata) {
                meta = new int[4];
                for (int i = 0; i < meta.length; i++) {
                    meta[i] = in.readInt();
                }
            }

            int length = (int)DataProcessor.readVarLong(in);
            byte[] data = new byte[length];
            in.readFully(data);
            return new DataBlock(offsetList, meta, data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Compare two DataBlocks.
     * If metadata is null, compare by first offset long.
     * If metadata present, compare by metadata arrays and then payload.
     */
    @Override
    public int compareTo(DataBlock other) {
        if (this.metadata == null || other.metadata == null) {
            if (this.metadata == null && other.metadata == null) {
                return Long.compare(this.offsets.get(0), other.offsets.get(0));
            }
            return this.metadata == null ? -1 : 1;
        }

        for (int i = 0; i < this.metadata.length; i++) {
            int comparison = Integer.compare(this.metadata[i], other.metadata[i]);
            if (comparison != 0) {
                return comparison;
            }
        }

        int lengthComparison = Integer.compare(this.payload.length, other.payload.length);
        if (lengthComparison != 0) {
            return lengthComparison;
        }

        for (int i = 0; i < this.payload.length; i++) {
            int byteComparison = Byte.compare(this.payload[i], other.payload[i]);
            if (byteComparison != 0) {
                return byteComparison;
            }
        }

        return 0;
    }
}
