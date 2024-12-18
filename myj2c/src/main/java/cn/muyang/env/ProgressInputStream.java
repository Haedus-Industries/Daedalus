package cn.muyang.env;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProgressInputStream extends FilterInputStream {
    private final long totalBytes;
    private long bytesRead;
    private final ProgressListener listener;

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public ProgressInputStream(InputStream in, long totalBytes, ProgressListener listener) {
        super(in);
        this.totalBytes = totalBytes;
        this.listener = listener;
    }

    @Override
    public int read() throws IOException {
        int byteRead = super.read();
        if (byteRead != -1) {
            bytesRead++;
            listener.onProgress(bytesRead, totalBytes);
        }
        return byteRead;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytesRead = super.read(b, off, len);
        if (bytesRead != -1) {
            this.bytesRead += bytesRead;
            listener.onProgress(this.bytesRead, totalBytes);
        }
        return bytesRead;
    }
}