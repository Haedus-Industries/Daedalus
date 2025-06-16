package dev.daedalus.env;

import dev.daedalus.helpers.ProcessHelper;
import dev.daedalus.utils.FileUtils;
import dev.daedalus.utils.Zipper;
import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

public class SetupManager {
    private static final String OS = System.getProperty("os.name").toLowerCase();
    public static final Locale locale = Locale.getDefault();

    public static void init() {
        checkAndDownloadZigCompiler();
    }

    private static String getPlatformTypeName() {
        String platform = System.getProperty("os.arch").toLowerCase();
        String platformTypeName;
        switch (platform) {
            case "x86_64":
            case "amd64":
                platformTypeName = "x86_64";
                break;
            case "aarch64":
                platformTypeName = "aarch64";
                break;
            case "x86":
                platformTypeName = "i386";
                break;
            default:
                platformTypeName = "";
                break;
        }
        return platformTypeName;
    }

    public static boolean isLinux() {
        return OS.contains("linux");
    }

    public static boolean isMacOS() {
        return OS.contains("mac") && OS.indexOf("os") > 0;
    }

    public static boolean isWindows() {
        return OS.contains("windows");
    }

    public static void checkAndDownloadZigCompiler() {
        String platformTypeName = getPlatformTypeName();
        String fileName = null;
        String dirName = null;
        String version = "0.14.0";

        if (platformTypeName != null && !platformTypeName.isEmpty()) {
            if (isLinux()) {
                fileName = "zig-linux-" + platformTypeName + "-" + version + ".tar.xz";
                dirName = "zig-linux-" + platformTypeName + "-" + version;
            } else if (isMacOS()) {
                fileName = "zig-macos-" + platformTypeName + "-" + version + ".tar.xz";
                dirName = "zig-macos-" + platformTypeName + "-" + version;
            } else if (isWindows()) {
                fileName = "zig-windows-" + platformTypeName + "-" + version + ".zip";
                dirName = "zig-windows-" + platformTypeName + "-" + version;
            }
        } else {
            if (locale.getLanguage().contains("zh")) {
                System.out.println("暂不支持该系统类型,请联系开发者");
            } else {
                System.out.println("This system is not supported. Please contact the developer");
            }
            return;
        }

        try {
            String currentDir = System.getProperty("user.dir");
            File zigDir = new File(currentDir + File.separator + "zig");
            if (zigDir.exists() && zigDir.isDirectory()) {
                System.out.println("Zig compiler is already installed in the 'zig' directory.");
                return;
            }

            if (Files.exists(Paths.get(currentDir + File.separator + dirName))) {
                FileUtils.clearDirectory(currentDir + File.separator + dirName);
            }

            if (locale.getLanguage().contains("zh")) {
                System.out.println("正在下载交叉编译工具");
            } else {
                System.out.println("Downloading cross compilation tool");
            }

            String downloadUrl = "https://ziglang.org/download/" + version + "/" + fileName;

            if (locale.getLanguage().contains("zh")) {
                System.out.println("下载链接：" + downloadUrl);
            } else {
                System.out.println("Download link：" + downloadUrl);
            }

            URL url = URI.create(downloadUrl).toURL();
            URLConnection connection = url.openConnection();
            long totalBytes = connection.getContentLengthLong();

            try (InputStream in = new ProgressInputStream(connection.getInputStream(), totalBytes, (bytesRead, total) -> {
                int progress = (int) ((bytesRead * 100) / total);
                System.out.print("\rDownloading: " + progress + "%");
            })) {
                Files.copy(in, Paths.get(currentDir + File.separator + fileName), StandardCopyOption.REPLACE_EXISTING);
            }

            if (locale.getLanguage().contains("zh")) {
                System.out.println("\n下载完成,正在解压");
            } else {
                System.out.println("\nDownload completed, decompressing");
            }
            unzipFile(currentDir, fileName, currentDir);

            File downloadedDir = new File(currentDir + File.separator + dirName);
            if (downloadedDir.exists() && downloadedDir.isDirectory()) {
                downloadedDir.renameTo(zigDir);
            }

            deleteFile(currentDir, fileName + ".temp");
            deleteFile(currentDir, fileName);
            if (locale.getLanguage().contains("zh")) {
                System.out.println("安装交叉编译工具完成");
            } else {
                System.out.println("Installation of cross compilation tool completed");
            }
            if (!SetupManager.isWindows()) {
                String compilePath = currentDir + File.separator + "zig" + File.separator + "zig";
                ProcessHelper.run(Paths.get(currentDir), 160_000, Arrays.asList("chmod", "777", compilePath));
                if (locale.getLanguage().contains("zh")) {
                    System.out.println("设置运行权限成功");
                } else {
                    System.out.println("Successfully set running permission");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void deleteFile(String path, String file) {
        new File(path + File.separator + file).delete();
    }

    public static void unzipFile(String path, String file, String destination) {
        try {
            Zipper.extract(Paths.get(path + File.separator + file), Paths.get(destination));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static String getZigGlobalCacheDirectory(boolean clear) {
        String dirName = getZigFileName();
        String currentDir = System.getProperty("user.dir");
        Path path = Paths.get(currentDir + File.separator + dirName);
        if (Files.exists(path)) {
            String compilePath = currentDir + File.separator + dirName + File.separator + "zig" + (SetupManager.isWindows() ? ".exe" : "");
            if (Files.exists(Paths.get(compilePath))) {
                try {
                    ProcessHelper.ProcessResult compileRunresult = ProcessHelper.run(path, 160_000,
                            Arrays.asList(compilePath, "env"));
                    Gson gson = new Gson();
                    Map<String, String> map = (Map<String, String>) gson.fromJson(compileRunresult.stdout, Map.class);
                    if (clear) {
                        FileUtils.clearDirectory(map.get("global_cache_dir"));
                    }
                    return map.get("global_cache_dir");
                } catch (IOException ignored) {
                }
            }
        }
        if (locale.getLanguage().contains("zh")) {
            System.out.println("获取zig临时文件目录失败");
        } else {
            System.out.println("Failed to get zig temporary file directory");
        }
        return "";
    }

    private static String getZigFileName() {
        String platformTypeName = getPlatformTypeName();
        String dirName = null;
        if (platformTypeName != null && !platformTypeName.isEmpty()) {
            if (isLinux()) {
                dirName = "zig-linux-" + platformTypeName + "-0.14.0-dev.2435+7575f2121";
            } else if (isMacOS()) {
                dirName = "zig-macos-" + platformTypeName + "-0.14.0-dev.2435+7575f2121";
            } else if (isWindows()) {
                dirName = "zig-windows-" + platformTypeName + "-0.14.0-dev.2435+7575f2121";
            }
        }
        return dirName;
    }
}