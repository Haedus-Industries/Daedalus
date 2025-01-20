package project.daedalus.compiletime;

import java.io.IOException;
import java.util.Iterator;
import java.util.TreeSet;

public final class DataIterator implements Iterator<DataBlock> {
    final TreeSet<IndexEntry> entries; // Originally 'int' Ljava/util/TreeSet in class4

    public DataIterator(TreeSet<IndexEntry> set) {
        this.entries = set;
    }

    @Override
    public boolean hasNext() {
        return !entries.isEmpty();
    }

    /**
     * Return the next DataBlock by:
     * - Getting the first entry
     * - Removing it from the set
     * - Returning its current DataBlock
     * - Calling loadData() on the entry
     * - If the entry still has a DataBlock after loading, re-add it to the set
     */
    public DataBlock nextBlock() throws IOException {
        IndexEntry e = entries.first();
        entries.remove(e);
        DataBlock block = e.dataBlock;
        e.loadData();
        if (e.dataBlock != null) {
            entries.add(e);
        }
        return block;
    }

    @Override
    public DataBlock next() {
        try {
            return nextBlock();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
