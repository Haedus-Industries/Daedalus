package cn.muyang.utils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ReflectionUtil modified to return a list of .class resource names from a package,
 * rather than loading the classes.
 */
public class ReflectionUtil {
    /**
     * Returns a list of .class resource names from the specified package.
     * Each returned string will be something like "cn/muyang/compiletime/Loader.class"
     */
    public static List<String> getClassResourceNamesInPackage(String packageName) {
        List<String> classResources = new ArrayList<>();
        String path = packageName.replace('.', '/');
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();
                if ("file".equals(protocol)) {
                    // It's a directory on the filesystem
                    File directory = new File(resource.toURI());
                    if (directory.exists()) {
                        findClassResourcesInDirectory(directory, path, classResources);
                    }
                } else if ("jar".equals(protocol)) {
                    // It's inside a jar
                    String jarPath = resource.getPath();
                    // On Windows, resource.getPath() might start with "file:/"
                    // We'll handle that:
                    if (jarPath.startsWith("file:")) {
                        jarPath = jarPath.substring("file:".length());
                    }
                    jarPath = jarPath.replaceAll("!.*$", "");
                    File jarFile = new File(jarPath);
                    if (jarFile.exists()) {
                        findClassResourcesInJar(jarFile, path, classResources);
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
        return classResources;
    }

    private static void findClassResourcesInDirectory(File directory, String packagePath, List<String> classResources) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // Recursively scan subdirectories
                findClassResourcesInDirectory(file, packagePath + "/" + file.getName(), classResources);
            } else if (file.getName().endsWith(".class")) {
                // Found a class file
                classResources.add(packagePath + "/" + file.getName());
            }
        }
    }

    private static void findClassResourcesInJar(File jarFile, String packagePath, List<String> classResources) {
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.startsWith(packagePath) && entryName.endsWith(".class") && !entry.isDirectory()) {
                    classResources.add(entryName);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
