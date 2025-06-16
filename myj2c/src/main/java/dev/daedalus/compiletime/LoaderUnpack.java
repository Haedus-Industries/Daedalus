package dev.daedalus.compiletime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class LoaderUnpack {
    public static void registerNativesForClass(int n, Class clazz) {
    }

    static {
        String osName = System.getProperty("os.name").toLowerCase();
        String archName = System.getProperty("os.arch").toLowerCase();

        System.out.println("[LoaderUnpack] Detected OS: " + osName + ", Arch: " + archName);

        String arch;
        if (archName.equals("x86_64") || archName.equals("amd64")) {
            arch = "x64";
        } else if (archName.equals("aarch64")) {
            arch = "arm64";
        } else if (archName.equals("arm")) {
            arch = "arm32";
        } else if (archName.equals("x86")) {
            arch = "x86";
        } else {
            arch = "raw" + archName;
        }

        String suffix;
        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            suffix = "linux.so";
        } else if (osName.contains("win")) {
            suffix = "windows.dll";
        } else if (osName.contains("mac")) {
            suffix = "macos.dylib";
        } else {
            suffix = "raw" + osName;
        }

        System.out.println("[LoaderUnpack] Using arch: " + arch + ", suffix: " + suffix);

        String resourcePath = "/" + LoaderUnpack.class.getPackage().getName().replace(".", "/") + "/data.dat";
        System.out.println("[LoaderUnpack] Resource path: " + resourcePath);

        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        if (!tempDir.exists()) {
            System.out.println("[LoaderUnpack] Temp dir does not exist, creating: " + tempDir.getAbsolutePath());
            tempDir.mkdirs();
        } else {
            System.out.println("[LoaderUnpack] Using existing temp dir: " + tempDir.getAbsolutePath());
        }

        File libTempFile;
        File datTempFile;
        try {
            libTempFile = File.createTempFile("lib", null);
            datTempFile = File.createTempFile("dat", null);
            libTempFile.deleteOnExit();
            datTempFile.deleteOnExit();

            System.out.println("[LoaderUnpack] Created temp lib file: " + libTempFile.getAbsolutePath());
            System.out.println("[LoaderUnpack] Created temp dat file: " + datTempFile.getAbsolutePath());

            if (!libTempFile.exists() || !datTempFile.exists()) {
                System.err.println("[LoaderUnpack] Temp files not found after creation!");
                throw new IOException();
            }
        } catch (IOException e) {
            System.err.println("[LoaderUnpack] Failed to create temp files: " + e.getMessage());
            throw new UnsatisfiedLinkError("Failed to create temp file");
        }

        // Copy data.dat from resources
        try (InputStream in = LoaderUnpack.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[LoaderUnpack] Failed to open resource: " + resourcePath);
                throw new UnsatisfiedLinkError(String.format("Failed to open dat file: %s", resourcePath));
            }
            try (FileOutputStream fos = new FileOutputStream(datTempFile)) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            System.out.println("[LoaderUnpack] Successfully copied data.dat to: " + datTempFile.getAbsolutePath());
        } catch (IOException ex) {
            System.err.println("[LoaderUnpack] Failed to copy data.dat: " + ex.getMessage());
            throw new UnsatisfiedLinkError(String.format("Failed to copy file: %s", ex.getMessage()));
        }

        String basePath = System.getProperty("java.io.tmpdir");
        String libName = arch + "-" + suffix;
        System.out.println("[LoaderUnpack] Base path: " + basePath);
        System.out.println("[LoaderUnpack] Final library name: " + libName);

        try {
            // Fully reconstruct the library using DataProcessor
            System.out.println("[LoaderUnpack] Starting library reconstruction...");
            DataProcessor.reconstructLibrary(datTempFile.getAbsolutePath(), basePath, libName, libTempFile.getName());
            System.out.println("[LoaderUnpack] Library reconstruction completed.");
        } catch (Exception e) {
            System.err.println("[LoaderUnpack] Failed to reconstruct library from: " + resourcePath);
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        datTempFile.deleteOnExit();
        String finalLibPath = libTempFile.getAbsolutePath();
        System.out.println("[LoaderUnpack] Attempting to load library: " + finalLibPath);

        try {
            System.load(finalLibPath);
            System.out.println("[LoaderUnpack] Successfully loaded native library: " + finalLibPath);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[LoaderUnpack] Failed to load library: " + finalLibPath);
            e.printStackTrace();
        }
    }
}
