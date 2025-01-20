package project.daedalus.compiletime;

import java.io.DataInputStream;
import java.io.IOException;

public final class IndexEntry implements Comparable<IndexEntry> {
    final int entryId;          // Originally 'byte' int field
    DataBlock dataBlock;        // Originally 'void' Lclass2
    DataInputStream dataIn;     // Originally 'new' Ljava/io/DataInputStream
    boolean hasMeta;            // Originally 'int' Z (boolean)

    public IndexEntry(int id) {
        this.entryId = id;
    }

    /**
     * Load associated DataBlock from dataIn using DataProcessor.readDataBlock.
     * Returns the length of the dataBlock's payload or 0 if none.
     */
    int loadData() throws IOException {
        this.dataBlock = DataProcessor.readDataBlock(this.dataIn, this.hasMeta);
        if (this.dataBlock == null) {
            return 0;
        }
        return this.dataBlock.payload.length;
    }

    @Override
    public int compareTo(IndexEntry other) {
        int cmp = this.dataBlock.compareTo(other.dataBlock);
        if (cmp != 0) return cmp;
        return Integer.signum(this.entryId - other.entryId);
    }
}
