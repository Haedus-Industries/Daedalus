package project.daedalus;

import project.daedalus.env.SetupManager;
import project.daedalus.utils.StringUtils;
import project.daedalus.xml.Config;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {

    public static final String VERSION = "2025.0120.06";
    public static final String LOGO = "\n      ███╗   ███╗ ██╗   ██╗    ██╗ ██████╗   ██████╗\n      ████╗ ████║ ╚██╗ ██╔╝    ██║ ╚════██╗ ██╔════╝\n      ██╔████╔██║  ╚████╔╝     ██║  █████╔╝ ██║     \n      ██║╚██╔╝██║   ╚██╔╝ ██   ██║ ██╔═══╝  ██║     \n      ██║ ╚═╝ ██║    ██║  ╚█████╔╝ ███████╗ ╚██████╗\n      ╚═╝     ╚═╝    ╚═╝   ╚════╝  ╚══════╝  ╚═════╝\n\n";

    public static final Locale locale = Locale.getDefault();
    private static final char[] DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    @CommandLine.Command(name = "myj2c-Bytecode-Translator", mixinStandardHelpOptions = true, version = "MYJ2C Bytecode Translator " + VERSION,
            description = "Translator .jar file into .c files and generates output .jar file")
    private static class NativeObfuscatorRunner implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Jar file to transpile")
        private File jarFile;

        @CommandLine.Parameters(index = "1", description = "Output direnctory")
        private String outputDirectory;

        @CommandLine.Option(names = {"-c", "--config"}, defaultValue = "config.xml",
                description = "Config file")
        private File config;

        @CommandLine.Option(names = {"-l", "--libraries"}, description = "Directory for dependent libraries")
        private File librariesDirectory;

        @CommandLine.Option(names = {"--plain-lib-name"}, description = "Plain library name for LoaderPlain")
        private String libraryName;

        @CommandLine.Option(names = {"-u","--lib-url"}, description = "Library url for Loader")
        private String libraryUrl;

        @CommandLine.Option(names = {"-a", "--annotations"}, description = "Use annotations to ignore/include native obfuscation")
        private boolean useAnnotations;

        @CommandLine.Option(names = {"-d"}, description = "Delete Jar file after translator")
        private boolean delete;

        @Override
        public Integer call() throws Exception {
            MYObfuscator obfuscator = new MYObfuscator();
            ExecutorService threadPool = Executors.newCachedThreadPool();
            StringBuilder stringBuilder = new StringBuilder();
            if (Files.exists(config.toPath())) {
                try (BufferedReader br = Files.newBufferedReader(config.toPath())) {
                    String str;
                    while ((str = br.readLine()) != null) {
                        stringBuilder.append(str);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                Path configPath = Files.createFile(config.toPath());
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                if (locale.getLanguage().contains("zh")) {
                    stringBuilder.append("<myj2c>\n" +
                            "\t<targets>\n" +
                            "\t\t<target>WINDOWS_X86_64</target>\n" +
                            "\t\t<!--<target>WINDOWS_AARCH64</target>\n" +
                            "\t\t<target>MACOS_X86_64</target>\n" +
                            "\t\t<target>MACOS_AARCH64</target>-->\n" +
                            "\t\t<target>LINUX_X86_64</target>\n" +
                            "\t\t<!--<target>LINUX_AARCH64</target>-->\n" +
                            "\t</targets>\n" +
                            "\t<options>\n" +
                            "\t<!--<expireDate>" + sdf.format(new Date()) + "</expireDate> 应用过期日期，格式yyyy-MM-dd -->\n" +
                            "\t\t<!--字符串混淆-->\n" +
                            "\t\t<stringObf>false</stringObf>\n" +
                            "\t\t<!--控制流混淆-->\n" +
                            "\t\t<flowObf>false</flowObf>\n" +
                            "\t</options>" +
                            "\t\t<!-- match支持 Ant 风格的路径匹配 ? 匹配一个字符, * 匹配多个字符, ** 匹配多层路径 -->\n" +
                            "\t\t<match className=\"**\" />\n" +
                            "\t\t<!--<match className=\"cn/myj2c/web/**\" />-->\n" +
                            "\t\t<!--<match className=\"cn.myj2c.service.**\" />-->\n" +
                            "\t</include>\n" +
                            "\t<exclude>\n" +
                            "\t\t<!--<match className=\"cn/myj2c/Main\" methodName=\"main\" methodDesc=\"(\\[Ljava/lang/String;)V\"/>-->\n" +
                            "\t\t<!--<match className=\"cn.myj2c.test.**\" />-->\n" +
                            "\t</exclude>\n" +
                            "</myj2c>\n");
                } else {
                    stringBuilder.append("<myj2c>\n" +
                            "\t<targets>\n" +
                            "\t\t<target>WINDOWS_X86_64</target>\n" +
                            "\t\t<!--<target>WINDOWS_AARCH64</target>\n" +
                            "\t\t<target>MACOS_X86_64</target>\n" +
                            "\t\t<target>MACOS_AARCH64</target>-->\n" +
                            "\t\t<target>LINUX_X86_64</target>\n" +
                            "\t\t<!--<target>LINUX_AARCH64</target>-->\n" +
                            "\t</targets>\n" +
                            "\t<options>\n" +
                            "\t<!--<expireDate>" + sdf.format(new Date()) + "</expireDate>  Expiration date, format:yyyy-MM-dd -->\n" +
                            "\t\t<!--String obfuscation-->\n" +
                            "\t\t<stringObf>false</stringObf>\n" +
                            "\t\t<!--Control flow obfuscation-->\n" +
                            "\t\t<flowObf>false</flowObf>\n" +
                            "\t</options>" +
                            "\t<include>\n" +
                            "\t\t<!-- Match supports Ant style path matching? Match one character, * match multiple characters, * * match multiple paths -->\n" +
                            "\t\t<match className=\"**\" />\n" +
                            "\t\t<!--<match className=\"cn/myj2c/web/**\" />-->\n" +
                            "\t\t<!--<match className=\"cn.myj2c.service.**\" />-->\n" +
                            "\t</include>\n" +
                            "\t<exclude>\n" +
                            "\t\t<!--<match className=\"cn/myj2c/Main\" methodName=\"main\" methodDesc=\"(\\[Ljava/lang/String;)V\"/>-->\n" +
                            "\t\t<!--<match className=\"cn.myj2c.test.**\" />-->\n" +
                            "\t</exclude>\n" +
                            "</myj2c>\n");
                }
                Files.write(configPath, stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
                if (locale.getLanguage().contains("zh")) {
                    System.out.println("读取配置文件失败,已为您生成默认配置文件");
                    System.out.println("默认配置编译所有类和方法,会严重影响程序运行性能,请慎重使用该功能");
                    System.out.println("请打开配置文件,配置编译的类和方法后继续运行编译命令");
                } else {
                    System.out.println("Unable to read configuration file. Default config has been generated for you");
                    System.out.println("The default configuration compiles all classes and methods, which will seriously affect the running performance of the program. Please use this function with caution");
                    System.out.println("Please open the configuration file, configure the compiled classes and methods, and then continue to run the command");
                }
                return 0;
            }
            Serializer serializer = new Persister();
            Config configInfo = serializer.read(Config.class, stringBuilder.toString());
            Future future = threadPool.submit(new Callable() {
                @Override
                public Path call() throws IOException {
                    return obfuscator.preProcess(jarFile.toPath(), configInfo, useAnnotations);
                }
            });
            if (locale.getLanguage().contains("zh")) {
                System.out.println("正在检查授权...");
            } else {
                System.out.println("Checking authorization...");
            }
            String key = null;
            try {
                SystemInfo si = new SystemInfo();
                HardwareAbstractionLayer hal = si.getHardware();
                CentralProcessor processor = hal.getProcessor();
                String cpuModel = processor.getProcessorIdentifier().getName();
                cpuModel = cpuModel == null ? "" : cpuModel;
                String processorID = processor.getProcessorIdentifier().getProcessorID();
                processorID = processorID == null ? "" : processorID;
                List<NetworkIF> networkIFs = hal.getNetworkIFs();
                for (NetworkIF networkIF : networkIFs) {
                    if (getLength(networkIF.getIPv4addr()) > 0) {
                        String address = networkIF.getIPv4addr()[0];
                        if (StringUtils.equals(address, "127.0.0.1") || StringUtils.contains(address, "169.254") || StringUtils.equals(address, "0.0.0.0") || StringUtils.equals("00:00:00:00:00:00", networkIF.getMacaddr())) {
                            continue;
                        }
                        key = encodeHex(digest((cpuModel + processorID + networkIF.getDisplayName() + networkIF.getMacaddr().toUpperCase()).getBytes(), "MD5", null, 76));
                    }
                    if (key != null) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (locale.getLanguage().contains("zh")) {
                System.out.println("\n正在初始化系统...");
            } else {
                System.out.println("\nInitializing system...");
            }
            SetupManager.init();
            if (locale.getLanguage().contains("zh")) {
                System.out.println("初始化完成\n");
            } else {
                System.out.println("Initialization complete\n");
            }
            if (locale.getLanguage().contains("zh")) {
                System.out.println("正在读取配置文件:" + config.toPath());
            } else {
                System.out.println("Reading configuration file\n:" + config.toPath());
            }

            List<Path> libs = new ArrayList<>();
            if (librariesDirectory != null) {
                Files.walk(librariesDirectory.toPath(), FileVisitOption.FOLLOW_LINKS)
                        .filter(f -> f.toString().endsWith(".jar") || f.toString().endsWith(".zip"))
                        .forEach(libs::add);
            }
            if (new File(outputDirectory).isDirectory()) {
                File outFile = new File(outputDirectory, jarFile.getName());
                if (outFile.exists()) {
                    outFile.renameTo(new File(outputDirectory, jarFile.getName() + ".BACKUP"));
                }
            } else {
                File outFile = new File(outputDirectory);
                if (outFile.exists()) {
                    outFile.renameTo(new File(outputDirectory + ".BACKUP"));
                }
            }

            obfuscator.process(jarFile.toPath(), (Path) future.get(), Paths.get(outputDirectory), configInfo, libs, libraryName, libraryUrl, useAnnotations, delete);
            return 0;
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println(LOGO + "    MuYang Java to C Bytecode Translator V" + VERSION + "\n\n            Copyright (c) MYJ2C 2022-2024\n\n==========================================================\n");

        System.exit(new CommandLine(new NativeObfuscatorRunner()).setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }


    private static int getLength(final Object array) {
        if (array == null) {
            return 0;
        }
        return Array.getLength(array);
    }


    /**
     * 对字符串进行散列, 支持md5与sha1算法.
     *
     * @param input      需要散列的字符串
     * @param algorithm  散列算法（"SHA-1"、"MD5"）
     * @param salt
     * @param iterations 迭代次数
     * @return
     */
    private static byte[] digest(byte[] input, String algorithm, byte[] salt, int iterations) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            if (salt != null) {
                digest.update(salt);
            }
            byte[] result = digest.digest(input);
            for (int i = 1; i < iterations; i++) {
                digest.reset();
                result = digest.digest(result);
            }
            return result;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    private static String encodeHex(byte[] input) {
        int l = input.length;
        char[] out = new char[l << 1];
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = DIGITS[(0xF0 & input[i]) >>> 4];
            out[j++] = DIGITS[0x0F & input[i]];
        }
        return new String(out);
    }

}
