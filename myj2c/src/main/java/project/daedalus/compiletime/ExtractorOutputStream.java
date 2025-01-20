package project.daedalus.compiletime;

import java.io.*;

public final class ExtractorOutputStream extends OutputStream {
    private ByteArrayOutputStream buffer;
    private OutputStream actualOut;
    private File outputFile;
    private long remaining;      // How many bytes left for current file segment
    private long lastModified;   // Timestamp to set after writing
    private boolean readOnly;
    final String originalName;
    final String baseDir;
    final String altName;

    public ExtractorOutputStream(String originalName, String baseDir, String altName) {
        this.originalName = originalName;
        this.baseDir = baseDir;
        this.altName = altName;
        this.buffer = new ByteArrayOutputStream();
        this.remaining = 4L; // Initially we expect a 4-byte header
        System.out.println("[ExtractorOutputStream] Initialized with originalName=" + originalName + ", baseDir=" + baseDir + ", altName=" + altName);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int toWrite = len;
        while (toWrite > 0) {
            if (this.actualOut == null || this.remaining <= 1L) {
                this.write(b[off] & 0xFF);
                off++;
                toWrite--;
            } else {
                int chunk = (int) Math.min(toWrite, this.remaining - 1L);
                if (this.outputFile != null && (this.originalName == null || this.outputFile.getName().equals(this.originalName))) {
                    System.out.println("[ExtractorOutputStream] Writing " + chunk + " bytes to disk file: " + this.outputFile.getAbsolutePath());
                    this.actualOut.write(b, off, chunk);
                } else if (this.actualOut != null) {
                    System.out.println("[ExtractorOutputStream] Writing " + chunk + " bytes to memory (discarding later)");
                    this.actualOut.write(b, off, chunk);
                }
                this.remaining -= chunk;
                off += chunk;
                toWrite -= chunk;
            }
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (this.actualOut != null) {
            this.actualOut.write(b);
            this.remaining--;
            if (this.remaining <= 0) {
                // Done writing this file
                this.actualOut.close();

                if (this.actualOut instanceof FileOutputStream || this.actualOut instanceof BufferedOutputStream) {
                    long fileSize = this.outputFile.length();
                    System.out.println("[ExtractorOutputStream] Finished writing file: " + this.outputFile.getAbsolutePath() + ", size: " + fileSize);

                    printFileBytes(this.outputFile, 64);

                    this.outputFile.setLastModified(this.lastModified);
                    if (this.readOnly) {
                        this.outputFile.setReadOnly();
                    }
                } else {
                    // We wrote into a memory buffer and will discard
                    System.out.println("[ExtractorOutputStream] Wrote data into memory buffer for a non-matching file, discarding.");
                }

                this.actualOut = null;
                this.remaining = 4L; // Reset for next entry
            }
            return;
        }

        // Still reading header into buffer
        this.buffer.write(b);
        this.remaining--;
        if (this.remaining <= 0) {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
            if (buffer.size() == 4) {
                int metaLength = din.readInt();
                System.out.println("[ExtractorOutputStream] Read initial int length: " + metaLength);
                this.remaining = metaLength - 4L;
                if (this.remaining > 16384L) {
                    throw new IOException("Illegal directory stream");
                }
                this.buffer.reset();
                return;
            }

            boolean isFile = (din.read() == 1);
            this.readOnly = (din.read() == 1);
            this.lastModified = DataProcessor.readVarLong(din);
            if (isFile) {
                this.remaining = DataProcessor.readVarLong(din);
                System.out.println("[ExtractorOutputStream] Parsed file metadata:");
                System.out.println("  isFile: " + isFile);
                System.out.println("  readOnly: " + this.readOnly);
                System.out.println("  lastModified: " + this.lastModified);
                System.out.println("  fileLength: " + this.remaining);
            } else {
                this.remaining = 4L;
                System.out.println("[ExtractorOutputStream] Parsed directory metadata:");
                System.out.println("  isFile: " + isFile);
                System.out.println("  readOnly: " + this.readOnly);
                System.out.println("  lastModified: " + this.lastModified);
            }

            String fileName = din.readUTF();
            String fullPath = this.baseDir + File.separator + fileName;
            this.outputFile = new File(fullPath);

            System.out.println("[ExtractorOutputStream] Processing " + (isFile ? "file" : "directory") + ": " + fileName);
            System.out.println("[ExtractorOutputStream] originalName=" + originalName + ", altName=" + altName);
            System.out.println("[ExtractorOutputStream] Will write to: " + this.outputFile.getAbsolutePath());

            if (isFile) {
                boolean writeToDisk = (this.originalName == null || this.outputFile.getName().equals(this.originalName));
                if (this.altName != null && writeToDisk) {
                    fullPath = this.baseDir + File.separator + this.altName;
                    this.outputFile = new File(fullPath);
                    System.out.println("[ExtractorOutputStream] Renamed file to: " + this.outputFile.getAbsolutePath());
                }

                if (this.remaining == 0L) {
                    // Empty file
                    System.out.println("[ExtractorOutputStream] Empty file detected, creating empty file.");
                    this.outputFile.getParentFile().mkdirs();
                    this.outputFile.createNewFile();
                    this.remaining = 4L;
                } else {
                    if (writeToDisk) {
                        this.outputFile.getParentFile().mkdirs();
                        this.actualOut = new BufferedOutputStream(new FileOutputStream(this.outputFile), 1048576);
                        System.out.println("[ExtractorOutputStream] Writing file to disk.");
                    } else {
                        // Non-matching file name -> discard by writing to memory
                        this.actualOut = new ByteArrayOutputStream();
                        System.out.println("[ExtractorOutputStream] Non-matching file, will write to memory and discard.");
                    }
                }
            } else {
                // Directory
                System.out.println("[ExtractorOutputStream] Creating directory: " + this.outputFile.getAbsolutePath());
                this.outputFile.mkdirs();
                this.outputFile.setLastModified(this.lastModified);
                if (this.readOnly) {
                    this.outputFile.setReadOnly();
                }
            }

            this.buffer.reset();
        }
    }

    private void printFileBytes(File f, int maxBytes) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[maxBytes];
            int read = fis.read(buf);
            if (read > 0) {
                System.out.println("[ExtractorOutputStream] First " + read + " bytes of " + f.getName() + ": " + bytesToHex(buf, read));
            }
        } catch (IOException e) {
            System.out.println("[ExtractorOutputStream] Could not read bytes from " + f.getName() + ": " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString();
    }
}
