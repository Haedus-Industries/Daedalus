package dev.daedalus;

import dev.daedalus.asm.ClassMetadataReader;
import dev.daedalus.asm.SafeClassWriter;
import dev.daedalus.cache.*;
import dev.daedalus.utils.*;
import dev.daedalus.env.SetupManager;
import dev.daedalus.helpers.ProcessHelper;
import dev.daedalus.xml.Config;
import dev.daedalus.xml.Match;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MYObfuscator {
    public static Path temp;
    public static final Locale locale = Locale.getDefault();
    private final Snippets snippets;
    private InterfaceStaticClassProvider staticClassProvider;
    private final MethodProcessor methodProcessor;

    private final NodeCache<String> cachedStrings;
    private final ClassNodeCache cachedClasses;
    private final MethodNodeCache cachedMethods;
    private final FieldNodeCache cachedFields;

    private final Map<String, String> classMethodNameMap = new HashMap<>();

    private final Map<String, String> noInitClassMap = new HashMap<>();

    private StringBuilder nativeMethods;

    private BootstrapMethodsPool bootstrapMethodsPool;

    private int currentClassId;
    private int methodIndex;
    private String nativeDir;
    private static final String separator = File.separator;
    private static boolean stringObf = false;
    private static ClassMethodFilter classMethodFilter;

    public MYObfuscator() {
        snippets = new Snippets();
        cachedStrings = new NodeCache<>("(cstrings[%d])");
        cachedClasses = new ClassNodeCache("(cclasses[%d])");
        cachedMethods = new MethodNodeCache("(cmethods[%d])", cachedClasses);
        cachedFields = new FieldNodeCache("(cfields[%d])", cachedClasses);
        methodProcessor = new MethodProcessor(this);
    }

    public Path preProcess(Path inputJarPath, Config config, boolean useAnnotations) {
        if (config.getOptions() != null && "true".equals(config.getOptions().getStringObf())) {
            stringObf = true;
        }
        if (temp == null) {
            try {
                temp = Files.createTempDirectory("native-myj2c-");
            } catch (IOException ignored) {
            }
        }
        List<String> whiteList = new ArrayList<>();
        if (config.getIncludes() != null) {
            for (Match include : config.getIncludes()) {
                StringBuilder stringBuilder = new StringBuilder();
                if (StringUtils.isNotEmpty(include.getClassName())) {
                    stringBuilder.append(include.getClassName().replaceAll("\\.", "/"));
                }
                if (StringUtils.isNotEmpty(include.getMethodName())) {
                    stringBuilder.append("#").append(include.getMethodName());
                    if (StringUtils.isNotEmpty(include.getMethodDesc())) {
                        stringBuilder.append("!").append(include.getMethodDesc());
                    }
                } else {
                    if (StringUtils.isNotEmpty(include.getMethodDesc())) {
                        stringBuilder.append("#**!").append(include.getMethodDesc());
                    }
                }
                whiteList.add(stringBuilder.toString());
            }
        }
        List<String> blackList = new ArrayList<>();
        if (config.getExcludes() != null) {
            for (Match include : config.getExcludes()) {
                StringBuilder stringBuilder = new StringBuilder();
                if (StringUtils.isNotEmpty(include.getClassName())) {
                    stringBuilder.append(include.getClassName().replaceAll("\\.", "/"));
                }
                if (StringUtils.isNotEmpty(include.getMethodName())) {
                    stringBuilder.append("#").append(include.getMethodName());
                    if (StringUtils.isNotEmpty(include.getMethodDesc())) {
                        stringBuilder.append("!").append(include.getMethodDesc());
                    }
                } else {
                    if (StringUtils.isNotEmpty(include.getMethodDesc())) {
                        stringBuilder.append("#**!").append(include.getMethodDesc());
                    }
                }
                blackList.add(stringBuilder.toString());
            }
        }

        Path myj2cFile = temp.resolve(UUID.randomUUID() + ".myj2c");
        classMethodFilter = new ClassMethodFilter(blackList, whiteList, useAnnotations);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(myj2cFile))) {
            File jarFile = inputJarPath.toAbsolutePath().toFile();
            JarFile jar = new JarFile(jarFile);
            //预处理，找到需要混淆的类和方法
            jar.stream().forEach(entry -> {
                        try {
                            if (entry.getName().endsWith(".class")) {
                                Map<String, CachedClassInfo> cache = cachedClasses.getCache();
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                try (InputStream in = jar.getInputStream(entry)) {
                                    Util.transfer(in, baos);
                                }
                                byte[] src = baos.toByteArray();
                                ClassReader classReader = new ClassReader(src);
                                ClassNode classNode = new ClassNode();
                                classReader.accept(classNode, 0);
                                if (classMethodFilter.shouldProcess(classNode)) {
                                    CachedClassInfo classInfo = new CachedClassInfo(classNode.name, classNode.name, "", cache.size(), Util.getFlag(classNode.access, Opcodes.ACC_STATIC));
                                    for (FieldNode field : classNode.fields) {
                                        boolean isStatic = Util.getFlag(field.access, Opcodes.ACC_STATIC);
                                        CachedFieldInfo cachedFieldInfo = new CachedFieldInfo(classNode.name, field.name, field.desc, isStatic);
                                        classInfo.addCachedField(cachedFieldInfo);
                                    }
                                    if (config.getOptions() != null && "true".equals(config.getOptions().getFlowObf())) {
                                        //控制流混淆
                                        Flow.transformClass(classNode);
                                    }
                                    for (MethodNode method : classNode.methods) {
                                        if (!"<clinit>".equals(method.name)) {
                                            boolean isStatic = Util.getFlag(method.access, Opcodes.ACC_STATIC);
                                            CachedMethodInfo cachedMethodInfo = new CachedMethodInfo(classNode.name, method.name, method.desc, isStatic);
                                            classInfo.addCachedMethod(cachedMethodInfo);
                                            if (config.getOptions() != null && "true".equals(config.getOptions().getFlowObf())) {
                                                //控制流混淆
                                                Flow.transformMethod(classNode, method);
                                            }
                                        }
                                    }
                                    cache.put(classNode.name, classInfo);
                                    if (config.getOptions() != null && "true".equals(config.getOptions().getFlowObf())) {
                                        ClassWriter classWriter = new ClassWriter(classReader, 0);
                                        classNode.accept(classWriter);
                                        Util.writeEntry(out, entry.getName(), classWriter.toByteArray());
                                    } else {
                                        Util.writeEntry(jar, out, entry);
                                    }
                                } else {
                                    Util.writeEntry(jar, out, entry);
                                }
                            } else {
                                Util.writeEntry(jar, out, entry);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
            jar.stream().close();
            out.closeEntry();
            out.close();
            jar.close();
            return myj2cFile;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void process(Path inputJarPath, Path myj2cJarPath, Path output, Config config, List<Path> inputLibs,
                        String plainLibName, String libUrl, boolean useAnnotations, boolean delete) throws IOException {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        List<Path> libs = new ArrayList<>(inputLibs);
        libs.add(myj2cJarPath);
        ClassMetadataReader metadataReader = new ClassMetadataReader(libs.stream().map(x -> {
            try {
                return new JarFile(x.toFile());
            } catch (IOException ex) {
                return null;
            }
        }).collect(Collectors.toList()));

        Path outputDir;
        String outputName;
        if (output.toFile().isDirectory()) {
            outputDir = output;
            outputName = inputJarPath.toFile().getName();
        } else {
            if (output.toFile().getName().contains(".jar")) {
                outputDir = output.getParent();
                outputName = output.toFile().getName();
            } else {
                outputDir = output;
                outputName = inputJarPath.toFile().getName();
            }
        }
        if (locale.getLanguage().contains("zh")) {
            System.out.println("输出位置:" + outputDir.toFile().getAbsolutePath() + File.separator + outputName);
        } else {
            System.out.println("Output location:" + outputDir.toFile().getAbsolutePath() + File.separator + outputName);
        }
        Path cppDir = outputDir.resolve("cpp");
        Files.createDirectories(cppDir);
        Path cacheDir = cppDir.resolve(".cache");
        Files.createDirectories(cacheDir);
        Path zigTempDir = cppDir.resolve(".temp");
        Files.createDirectories(zigTempDir);
        Util.copyResource("sources/jni.h", cppDir);

        Map<String, ClassNode> map = new HashMap<>();
        Map<String, String> classNameMap = new HashMap<>();
        StringBuilder instructions = new StringBuilder();
        File jarFile = myj2cJarPath.toAbsolutePath().toFile();
        JarFile inputJar = new JarFile(inputJarPath.toFile());
        //Path tempFile = temp.resolve(UUID.randomUUID() + ".data");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(outputDir.resolve(outputName)))) {
            JarFile jar = new JarFile(jarFile);
            if (locale.getLanguage().contains("zh")) {
                System.out.println("正在解析 " + inputJarPath + "...");
            } else {
                System.out.println("Parsing " + inputJarPath + "...");
            }
            nativeDir = "myj2c/" + getRandomString(6);
            bootstrapMethodsPool = new BootstrapMethodsPool(nativeDir);
            staticClassProvider = new InterfaceStaticClassProvider(nativeDir);
            methodIndex = 1;

            AtomicInteger classNumber = new AtomicInteger();
            AtomicInteger methodNumber = new AtomicInteger();

            jar.stream().forEach(entry -> {
                if (entry.getName().equals(JarFile.MANIFEST_NAME)) return;
                try {
                    if (!entry.getName().endsWith(".class")) {
                        Util.writeEntry(jar, out, entry);
                        return;
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream in = jar.getInputStream(entry)) {
                        Util.transfer(in, baos);
                    }
                    byte[] src = baos.toByteArray();
                    if (Util.byteArrayToInt(Arrays.copyOfRange(src, 0, 4)) != 0xCAFEBABE) {
                        Util.writeEntry(out, entry.getName(), src);
                        return;
                    }
                    nativeMethods = new StringBuilder();
                    ClassReader classReader = new ClassReader(src);
                    ClassNode rawClassNode = new ClassNode();
                    classReader.accept(rawClassNode, ClassReader.SKIP_DEBUG);
                    if (!classMethodFilter.shouldProcess(rawClassNode) ||
                            rawClassNode.methods.stream().noneMatch(method -> MethodProcessor.shouldProcess(method) &&
                                    classMethodFilter.shouldProcess(rawClassNode, method))) {
                        //System.out.println("Skipping " + rawClassNode.name);
                        if (useAnnotations) {
                            ClassMethodFilter.cleanAnnotations(rawClassNode);
                            ClassWriter clearedClassWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9);
                            rawClassNode.accept(clearedClassWriter);
                            Util.writeEntry(out, entry.getName(), clearedClassWriter.toByteArray());
                            return;
                        }
                        Util.writeEntry(out, entry.getName(), src);
                        return;
                    }

                    classNumber.getAndIncrement();
                    //System.out.println("<match className=\""+ rawClassNode.name +"\" />");

                   /* rawClassNode.methods.stream().filter(MethodProcessor::shouldProcess)
                            .filter(methodNode -> classMethodFilter.shouldProcess(rawClassNode, methodNode))
                    .forEach(methodNode -> Preprocessor.preprocess(rawClassNode, methodNode, platform));*/
                    //System.out.println("MethodFilter done");

                    ClassWriter preprocessorClassWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    rawClassNode.accept(preprocessorClassWriter);
                    classReader = new ClassReader(preprocessorClassWriter.toByteArray());
                    ClassNode classNode = new ClassNode();
                    classReader.accept(classNode, 0);

                    if (classNode.methods.stream().noneMatch(x -> x.name.equals("<clinit>"))) {
                        classNode.methods.add(new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, new String[0]));
                    }
                    staticClassProvider.newClass();

                    map.put(classNode.name, classNode);
                    classNameMap.put(classNode.name, classReader.getClassName());

                    instructions.append("\n//").append(classNode.name).append("\n");

                    classNode.visitMethod(Opcodes.ACC_NATIVE | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "$myj2cLoader", "()V", null, new String[0]);
                    classNode.version = 52;

                    for (int i = 0; i < classNode.methods.size(); i++) {
                        MethodNode method = classNode.methods.get(i);

                        if (!MethodProcessor.shouldProcess(method)) {
                            continue;
                        }

                        if (!classMethodFilter.shouldProcess(classNode, method) && !"<clinit>".equals(method.name)) {
                            continue;
                        }
                        //解析方法
                        MethodContext context = new MethodContext(this, method, methodIndex, classNode, currentClassId);
                        methodProcessor.processMethod(context);
                        instructions.append(context.output.toString().replace("\n", "\n    "));

                        nativeMethods.append(context.nativeMethods);

                        if ((classNode.access & Opcodes.ACC_INTERFACE) > 0) {
                            method.access &= ~Opcodes.ACC_NATIVE;
                        }
                        methodIndex++;
                        if (!"<clinit>".equals(method.name)) {
                            methodNumber.getAndIncrement();
                        }
                    }

                    if (!staticClassProvider.isEmpty()) {
                        cachedStrings.getPointer(staticClassProvider.getCurrentClassName().replace('/', '.'));
                    }

                    if (useAnnotations) {
                        ClassMethodFilter.cleanAnnotations(classNode);
                    }
                    ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    classNode.accept(classWriter);

                    //保存class文件
                    Util.writeEntry(out, entry.getName(), classWriter.toByteArray());
                    currentClassId++;
                } catch (IOException ex) {
                    ex.printStackTrace();
                    System.out.println("Error while processing " + entry.getName() + " " + ex.getMessage());
                }
            });

            Manifest mf = jar.getManifest();
            if (mf != null) {
                mf.getMainAttributes().put(new Attributes.Name("Built-By"), "un4kn0n");
                out.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
                mf.write(out);
            }
            jar.close();
            inputJar.close();

            Path lib_path = Paths.get(outputDir + separator + "build" + separator + "lib");
            if (!Files.exists(lib_path)) {
                Files.createDirectories(lib_path);
            }

            for (ClassNode ifaceStaticClass : staticClassProvider.getReadyClasses()) {
                ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                ifaceStaticClass.accept(classWriter);
                Util.writeEntry(out, ifaceStaticClass.name + ".class", classWriter.toByteArray());
            }

            out.flush();
            if (locale.getLanguage().contains("zh")) {
                System.out.println("共找到 " + classNumber.get() + " 个类文件 " + methodNumber.get() + " 个方法需要myj2c编译");
            } else {
                System.out.println("Total " + classNumber.get() + " class files and " + methodNumber.get() + " methods need compilation");
            }

            if (locale.getLanguage().contains("zh")) {
                System.out.println("正在把class文件转换成C语言代码");
            } else {
                System.out.println("Converting class file to C language code ");
            }
            genCode(cppDir, config, instructions, map, classNameMap);
            final long startTime = System.currentTimeMillis();
            List<Future<Long>> allCompileTask = new ArrayList<>();
            if (StringUtils.isEmpty(plainLibName)) {
                if (locale.getLanguage().contains("zh")) {
                    System.out.println("\n开始编译动态链接库文件");
                } else {
                    System.out.println("\nStart compiling the dynamic link library file");
                }
                List<String> libNames = new ArrayList<>();
                for (String target : config.getTargets()) {
                    String platformTypeName;
                    String osName;
                    String libName;
                    switch (target) {
                        case "WINDOWS_X86_64":
                            osName = "windows";
                            platformTypeName = "x86_64";
                            libName = "x64-windows.dll";
                            break;
                        case "MACOS_X86_64":
                            osName = "macos";
                            platformTypeName = "x86_64";
                            libName = "x64-macos.dylib";
                            break;
                        case "LINUX_X86_64":
                            osName = "linux";
                            platformTypeName = "x86_64";
                            libName = "x64-linux.so";
                            break;
                        case "WINDOWS_AARCH64":
                            osName = "windows";
                            platformTypeName = "aarch64";
                            libName = "arm64-windows.dll";
                            break;
                        case "MACOS_AARCH64":
                            osName = "macos";
                            platformTypeName = "aarch64";
                            libName = "arm64-macos.dylib";
                            break;
                        case "LINUX_AARCH64":
                            osName = "linux";
                            platformTypeName = "aarch64";
                            libName = "arm64-linux.so";
                            break;
                        default:
                            platformTypeName = "";
                            osName = "";
                            libName = "";
                            break;
                    }

                    String currentOSName = "";
                    if (SetupManager.isWindows()) {
                        currentOSName = "windows";
                    }
                    if (SetupManager.isLinux()) {
                        currentOSName = "linux";
                    }
                    if (SetupManager.isMacOS()) {
                        currentOSName = "macos";
                    }
                    String currentPlatformTypeName = "";
                    switch (System.getProperty("os.arch").toLowerCase()) {
                        case "x86_64":
                        case "amd64":
                            currentPlatformTypeName = "x86_64";
                            break;
                        case "aarch64":
                            currentPlatformTypeName = "aarch64";
                            break;
                        case "x86":
                            currentPlatformTypeName = "i386";
                            break;
                        default:
                            currentPlatformTypeName = "";
                            break;
                    }
                    if (locale.getLanguage().contains("zh")) {
                        System.out.println("开始编译:" + target);
                    } else {
                        System.out.println("Compiling:" + target);
                    }
                    String compilePath = System.getProperty("user.dir") + separator + "zig" + separator + "zig" + (SetupManager.isWindows() ? ".exe" : "");
                    if (Files.exists(Paths.get(compilePath))) {
                        Future<Long> future = zigCompile(outputDir, compilePath, platformTypeName, osName, libName, libNames, zigTempDir);
                        allCompileTask.add(future);
                    } else {
                        Path parent = Paths.get(System.getProperty("user.dir")).getParent();
                        Future<Long> future = zigCompile(outputDir, parent.toFile().getAbsolutePath() + separator + "zig-" + currentOSName + "-" + currentPlatformTypeName + "-0.14.0-dev.2435+7575f2121" + separator + "zig" + (SetupManager.isWindows() ? ".exe" : ""), platformTypeName, osName, libName, libNames, zigTempDir);
                        allCompileTask.add(future);
                    }
                }

                //获取异步Future对象
                Map<String, Long> total = new HashMap<>();
                Future<Long> future = threadPool.submit(() -> {
                    for (Future<Long> task : allCompileTask) {
                        task.get();
                    }
                    long totalTime = System.currentTimeMillis() - startTime;
                    total.put("time", totalTime);
                    return totalTime;
                });

                int max = methodNumber.get();
                max = Math.max(max, 50);
                for (int i = 0; i <= max; i++) {
                    if (total.get("time") == null) {
                        try {
                            Thread.sleep(50L * config.getTargets().size());
                            while (i >= (max * 0.97) && total.get("time") == null) {
                                Thread.sleep(100);
                            }
                            if (total.get("time") != null) {
                                System.out.printf("\r%s", progressBar(max, max));
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    System.out.printf("\r%s", progressBar(i, max));
                }
                System.out.println("\n");
                if (locale.getLanguage().contains("zh")) {
                    try {
                        System.out.printf("编译完成耗时 %dms%n", future.get());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    try {
                        System.out.printf("Compilation time %dms%n", future.get());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                if (locale.getLanguage().contains("zh")) {
                    System.out.println("正在压缩已编译的动态链接库文件");
                } else {
                    System.out.println("Compressing compiled dynamic link library files ");

                }

                DataTool.compress(outputDir + separator + "build" + separator + "lib", outputDir + separator + "data.dat", Integer.getInteger("level", Deflater.BEST_SPEED));
                //Files.deleteIfExists(tempFile);

                //Files.deleteIfExists(temp);
                if (locale.getLanguage().contains("zh")) {
                    System.out.println("正在重新打包");
                } else {
                    System.out.println("Repackaging");
                }
                Path data_path = Paths.get(outputDir + separator + "data.dat");
                Path upload_data_path = Paths.get(outputDir + separator + "upload/data.dat");
                if (StringUtils.isEmpty(libUrl)) {
                    Util.writeEntry(out, nativeDir + "/data.dat", Files.readAllBytes(data_path));
                } else {
                    Path uploadDir = outputDir.resolve("upload");
                    Files.createDirectories(uploadDir);
                    Files.deleteIfExists(upload_data_path);
                    Files.copy(data_path, upload_data_path);
                }
                try {

                    if (locale.getLanguage().contains("zh")) {
                        System.out.println("清理临时文件");
                    } else {
                        System.out.println("Clean up temporary files");
                    }
                    FileUtils.clearDirectory(outputDir + separator + "cpp");
                    FileUtils.clearDirectory(outputDir + separator + "build");
                    Files.deleteIfExists(data_path);
                } catch (Exception ignored) {
                }
            }

            addJniLoader(plainLibName, libUrl, metadataReader, out);

            //Crasher.transformOutput(out);
            out.closeEntry();
            metadataReader.close();
            if (locale.getLanguage().contains("zh")) {
                System.out.println("myj2c编译任务已成功");
            } else {
                System.out.println("myj2c compilation task succeeded");
            }
            out.close();
            if (delete) {
                Files.deleteIfExists(inputJarPath);
            }
            FileUtils.clearDirectory(temp.toString());
            Path output_path = Paths.get(outputDir + separator + outputName);
            if (!Files.exists(output_path)) {
                Files.deleteIfExists(output_path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addJniLoader(String plainLibName, String libUrl, ClassMetadataReader metadataReader, ZipOutputStream out) {
        // Use ReflectionUtil to get a list of .class resource names from the compiletime package
        List<String> classResources = ReflectionUtil.getClassResourceNamesInPackage("dev.daedalus.compiletime");

        // This map will hold mappings of original internal names to resource entries
        Map<String, String> classMap = new HashMap<>();

        // First pass: build classMap, just like we did with jar entries
        for (String resourceName : classResources) {
            if (resourceName.endsWith(".class")) {
                // The original code uses entry.getName().replace(".class", "")
                String baseName = resourceName.replace(".class", "");
                classMap.put(baseName, resourceName);
            }
        }

        // Second pass: process each class similarly to how we processed entries in the original code
        for (String resourceName : classResources) {
            try (InputStream in = MYObfuscator.class.getClassLoader().getResourceAsStream(resourceName)) {

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                if (in == null) {
                    throw new NullPointerException("in");
                }
                Util.transfer(in, baos);
                byte[] src = baos.toByteArray();

                ClassReader classReader = new ClassReader(src);
                ClassNode rawClassNode = new ClassNode();
                classReader.accept(rawClassNode, ClassReader.SKIP_DEBUG);

                ClassNode resultLoaderClass = new ClassNode();
                String originalLoaderClassName = rawClassNode.name;

                // Logic is the same as original:
                // If this is a Loader class
                if (StringUtils.contains(resourceName, "Loader")) {
                    String loaderClassName = nativeDir + "/Loader";
                    if (plainLibName != null) {
                        // LoaderPlain branch
                        if (StringUtils.contains(resourceName, "LoaderPlain")) {
                            rawClassNode.methods.forEach(method -> {
                                for (int i = 0; i < method.instructions.size(); i++) {
                                    AbstractInsnNode insnNode = method.instructions.get(i);
                                    if (insnNode instanceof LdcInsnNode && ((LdcInsnNode) insnNode).cst instanceof String &&
                                            ((LdcInsnNode) insnNode).cst.equals("%LIB_NAME%")) {
                                        ((LdcInsnNode) insnNode).cst = plainLibName;
                                    }
                                }
                            });

                            rawClassNode.accept(new ClassRemapper(resultLoaderClass, new Remapper() {
                                @Override
                                public String map(String internalName) {
                                    return internalName.equals(originalLoaderClassName)
                                            ? loaderClassName
                                            : classMap.get(internalName) != null ? nativeDir + "/" + internalName : internalName;
                                }
                            }));

                            rewriteClass(resultLoaderClass); // commented out as in original

                            ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                            for (ClassNode bootstrapClass : bootstrapMethodsPool.getClasses()) {
                                bootstrapClass.accept(classWriter);
                            }
                            resultLoaderClass.accept(classWriter);
                            Util.writeEntry(out, loaderClassName + ".class", classWriter.toByteArray());
                        }
                    } else if (StringUtils.contains(resourceName, "LoaderUnpack")) {
                        // LoaderUnpack branch
                        rawClassNode.methods.forEach(method -> {
                            for (int i = 0; i < method.instructions.size(); i++) {
                                AbstractInsnNode insnNode = method.instructions.get(i);
                                if (insnNode instanceof LdcInsnNode && ((LdcInsnNode) insnNode).cst instanceof String &&
                                        ((LdcInsnNode) insnNode).cst.equals("%LIB_URL%")) {
                                    ((LdcInsnNode) insnNode).cst = StringUtils.isNotEmpty(libUrl) ? libUrl : "";
                                }
                            }
                        });

                        rawClassNode.accept(new ClassRemapper(resultLoaderClass, new Remapper() {
                            @Override
                            public String map(String internalName) {
                                return internalName.equals(originalLoaderClassName)
                                        ? loaderClassName
                                        : classMap.get(internalName) != null ? nativeDir + "/" + internalName : internalName;
                            }
                        }));

                        rewriteClass(resultLoaderClass); // commented out as in original code

                        ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                        for (ClassNode bootstrapClass : bootstrapMethodsPool.getClasses()) {
                            bootstrapClass.accept(classWriter);
                        }
                        resultLoaderClass.accept(classWriter);
                        Util.writeEntry(out, loaderClassName + ".class", classWriter.toByteArray());
                    }
                } else if (StringUtils.isEmpty(plainLibName)) {
                    // Non-loader classes, only if plainLibName is empty
                    String loaderClassName = nativeDir + "/" + originalLoaderClassName;
                    rawClassNode.accept(new ClassRemapper(resultLoaderClass, new Remapper() {
                        @Override
                        public String map(String internalName) {
                            return internalName.equals(originalLoaderClassName)
                                    ? loaderClassName
                                    : classMap.get(internalName) != null ? nativeDir + "/" + internalName : internalName;
                        }
                    }));

                    rewriteClass(resultLoaderClass); // commented out as in original code

                    ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM9 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    resultLoaderClass.accept(classWriter);
                    Util.writeEntry(out, loaderClassName + ".class", classWriter.toByteArray());
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    public Snippets getSnippets() {
        return snippets;
    }


    public InterfaceStaticClassProvider getStaticClassProvider() {
        return staticClassProvider;
    }

    public NodeCache<String> getCachedStrings() {
        return cachedStrings;
    }

    public ClassNodeCache getCachedClasses() {
        return cachedClasses;
    }

    public MethodNodeCache getCachedMethods() {
        return cachedMethods;
    }

    public FieldNodeCache getCachedFields() {
        return cachedFields;
    }

    public String getNativeDir() {
        return nativeDir;
    }

    public BootstrapMethodsPool getBootstrapMethodsPool() {
        return bootstrapMethodsPool;
    }

    public Map<String, String> getClassMethodNameMap() {
        return classMethodNameMap;
    }


    public Map<String, String> getNoInitClassMap() {
        return noInitClassMap;
    }

    private static String getRandomString(int length) {
        //定义一个字符串（A-Z，a-z，0-9）即62位；
        String str = "zxcvbnmlkjhgfdsaqwertyuiopQWERTYUIOPASDFGHJKLZXCVBNM1234567890";
        //由Random生成随机数
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        sb.append(str.charAt(random.nextInt(26)));
        //长度为几就循环几次
        for (int i = 0; i < length - 1; ++i) {
            //产生0-61的数字
            int number = random.nextInt(62);
            //将产生的数字通过length次承载到sb中
            sb.append(str.charAt(number));
        }
        //将承载的字符转换成字符串
        return sb.toString();
    }


    private void varargsAccess(MethodNode methodNode) {
        if ((methodNode.access & Opcodes.ACC_SYNTHETIC) == 0
                && (methodNode.access & Opcodes.ACC_BRIDGE) == 0) {
            methodNode.access |= Opcodes.ACC_VARARGS;
        }
    }

    private void bridgeAccess(MethodNode methodNode) {
        if (!methodNode.name.contains("<")
                && !Modifier.isAbstract(methodNode.access)) {
            methodNode.access |= Opcodes.ACC_BRIDGE;
        }
    }

    private void syntheticAccess(ClassNode classNode) {
        classNode.access |= Opcodes.ACC_SYNTHETIC;
        classNode.fields
                .forEach(fieldNode -> fieldNode.access |= Opcodes.ACC_SYNTHETIC);
        classNode.methods
                .forEach(methodNode -> methodNode.access |= Opcodes.ACC_SYNTHETIC);
    }

    private void changeSource(ClassNode classNode) {
        classNode.sourceFile = this.getMassiveString();
        classNode.sourceDebug = this.getMassiveString();
    }

    private void changeSignature(ClassNode classNode) {
        classNode.signature = this.getMassiveString();
        classNode.fields.forEach(
                fieldNode -> fieldNode.signature = this.getMassiveString());
        classNode.methods.forEach(
                methodNode -> methodNode.signature = this.getMassiveString());
    }

    private void deprecatedAccess(ClassNode classNode) {
        classNode.access |= Opcodes.ACC_DEPRECATED;
        classNode.methods
                .forEach(methodNode -> methodNode.access |= Opcodes.ACC_DEPRECATED);
        classNode.fields
                .forEach(fieldNode -> fieldNode.access |= Opcodes.ACC_DEPRECATED);
    }

    private void transientAccess(ClassNode classNode) {
        classNode.fields
                .forEach(fieldNode -> fieldNode.access |= Opcodes.ACC_TRANSIENT);
    }

    private void removeNop(ClassNode classNode) {
        classNode.methods.parallelStream().forEach(
                methodNode -> Arrays.stream(methodNode.instructions.toArray())
                        .filter(insnNode -> insnNode.getOpcode() == Opcodes.NOP)
                        .forEach(insnNode -> {
                            methodNode.instructions.remove(insnNode);
                        }));
    }

    private String getMassiveString() {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            builder.append(" ");
        }
        return builder.toString();
    }

    private void rewriteClass(ClassNode classNode) {
        // remove unnecessary insn
        this.removeNop(classNode);
        if (!Modifier.isInterface(classNode.access)) {
            this.transientAccess(classNode);
        }
        this.deprecatedAccess(classNode);
        // bad sources
        this.changeSource(classNode);
        // bad signatures
        this.changeSignature(classNode);
        // synthetic access (most decompilers doesn't show synthetic members)
        this.syntheticAccess(classNode);
        classNode.methods.forEach(methodNode -> {
            // bridge access (almost the same than synthetic)
            this.bridgeAccess(methodNode);
            // varargs access (crashes CFR when last parameter isn't array)
            this.varargsAccess(methodNode);
        });
    }

    private static final int progressBarLength = 33;

    public static String progressBar(int currentValue, int maxValue) {
        if (progressBarLength < 9 || progressBarLength % 2 == 0) {
            throw new ArithmeticException("formattedPercent.length() = 9! + even number of chars (one for each side)");
        }
        int currentProgressBarIndex = (int) Math.ceil(((double) progressBarLength / maxValue) * currentValue);
        String formattedPercent = String.format(" %5.1f %% ", (100 * currentProgressBarIndex) / (double) progressBarLength);
        StringBuilder sb = getProcessBarString(formattedPercent, currentProgressBarIndex);
        return sb.toString();
    }

    private static StringBuilder getProcessBarString(String formattedPercent, int currentProgressBarIndex) {
        int percentStartIndex = ((progressBarLength - formattedPercent.length()) / 2);

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int progressBarIndex = 0; progressBarIndex < progressBarLength; progressBarIndex++) {
            if (progressBarIndex <= percentStartIndex - 1
                    || progressBarIndex >= percentStartIndex + formattedPercent.length()) {
                sb.append(currentProgressBarIndex <= progressBarIndex ? " " : "=");
            } else if (progressBarIndex == percentStartIndex) {
                sb.append(formattedPercent);
            }
        }
        sb.append("]");
        return sb;
    }

    private Future<Long> zigCompile(Path outputDir, String compilePath, String platformTypeName, String osName, String libName, List<String> libNames, Path zigTempDir) {
        //创建线程池
        try (ExecutorService threadPool = Executors.newCachedThreadPool()) {
            //获取异步Future对象
            return threadPool.submit(() -> {
                System.out.println("Temp Dir path: " + zigTempDir);
                ProcessHelper.ProcessResult compileRunresult = ProcessHelper.run(outputDir.toAbsolutePath(), 3000 * 1000,
                        Arrays.asList(compilePath, "cc", "-O2", "-fno-sanitize=all", "-fno-sanitize-trap=all", "-O2", "-fno-optimize-sibling-calls", "-target", platformTypeName + "-" + osName, "-std=c11", "-fPIC", "-shared", "-s", "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-I." + separator + "cpp", "-o." + separator + "build" + separator + "lib" + separator + libName, "." + separator + "cpp" + separator + "myj2c.c"));
                libNames.add(libName);
                compileRunresult.check("zig build");
                return compileRunresult.execTime;
            });
        }
    }


    public static boolean isStringObf() {
        return stringObf;
    }

    private void genCode(Path cppDir, Config config, StringBuilder instructions, Map<String, ClassNode> map, Map<String, String> classNameMap) throws IOException {
        // Retain the original `stringObf` value without forcing it
        // Assume `stringObf` is defined elsewhere in the class or passed as a parameter
        // boolean stringObf = this.stringObf; // Example if it's a class member

        BufferedWriter mainWriter = Files.newBufferedWriter(cppDir.resolve("myj2c.c").toAbsolutePath());
        mainWriter
                .append("#include <jni.h>\n" + "#include <stdatomic.h>\n" + "#include <string.h>\n" + "#include <stdbool.h>\n")
                .append(stringObf ? "#include <stdarg.h>\n" : "")
                .append("#include <math.h>\n\n");

        // Remove license-related variables and handling
        // String signCode = LicenseManager.s();
        // String sign = LicenseManager.getValue("sign");
        String appInfo = ""; // Optional: Set to relevant info if needed for professional mode

        // Retain the string obfuscation functions conditionally
        if (config.getOptions() != null && "true".equals(config.getOptions().getStringObf())) {
            mainWriter
                    .append("\nstatic inline char* myj2c_c_str_obf(char *a, char *b, const size_t len) {\n" +
                            "    volatile char c[len]; memcpy((char*) c, b, len); memcpy(b, (char*) c, len);\n" +
                            "    for(size_t i = 0; i < len; i++) a[i] ^= b[i]; return a;\n" +
                            "}\n" +
                            "\n" +
                            "static inline unsigned short* myj2c_j_str_obf(unsigned short *a, unsigned short *b, const size_t len) {\n" +
                            "    volatile unsigned short c[len]; memcpy((unsigned short*) c, b, len); memcpy(b, (unsigned short*) c, len);\n" +
                            "    for(size_t i = 0; i < len; i++) a[i] ^= b[i]; return a;\n" +
                            "}\n\n"
                    );
        }

        // Remove or bypass expiration and license checks
        // Always include the necessary structures for professional mode
        mainWriter
                .append("struct cached_system {\n" +
                        "    jclass clazz;\n" +
                        "    jfieldID id_0;\n" +
                        "};\n" +
                        "\n" +
                        "static const struct cached_system* cc_system(JNIEnv *env) {\n" +
                        "    static struct cached_system cache;\n" +
                        "    static atomic_flag lock;\n" +
                        "    if (cache.clazz) return &cache;\n" +
                        "\n" +
                        "    jclass clazz = (*env)->FindClass(env, \"java/lang/System\");\n" +
                        "    while (atomic_flag_test_and_set(&lock)) {}\n" +
                        "    if (!cache.clazz) {\n" +
                        "        cache.clazz = (*env)->NewGlobalRef(env, clazz);\n" +
                        "        cache.id_0 = (*env)->GetStaticFieldID(env, clazz, \"out\", \"Ljava/io/PrintStream;\");\n" +
                        "    }\n" +
                        "    atomic_flag_clear(&lock);\n" +
                        "    return &cache;\n" +
                        "}\n" +
                        "\n" +
                        "struct cached_print {\n" +
                        "    jclass clazz;\n" +
                        "    jmethodID method_0;\n" +
                        "};\n" +
                        "\n" +
                        "static const struct cached_print* cc_print(JNIEnv *env) {\n" +
                        "    static struct cached_print cache;\n" +
                        "    static atomic_flag lock;\n" +
                        "    if (cache.clazz) return &cache;\n" +
                        "\n" +
                        "    jclass clazz = (*env)->FindClass(env, \"java/io/PrintStream\");\n" +
                        "    while (atomic_flag_test_and_set(&lock)) {}\n" +
                        "    if (!cache.clazz) {\n" +
                        "        cache.clazz = (*env)->NewGlobalRef(env, clazz);\n" +
                        "        cache.method_0 = (*env)->GetMethodID(env, clazz, \"println\", \"(Ljava/lang/String;)V\");\n" +
                        "    }\n" +
                        "    atomic_flag_clear(&lock);\n" +
                        "    return &cache;\n" +
                        "}\n\n"
                );

        // Retain the `throw_exception` function as is
        mainWriter
                .append("void throw_exception(JNIEnv *env, const char *exception, const char *error, int line) {\n" +
                        "        jclass exception_ptr = (*env)->FindClass(env, exception);\n" +
                        "        if ((*env)->ExceptionCheck(env)) {\n" +
                        "            (*env)->ExceptionDescribe(env);\n" +
                        "            (*env)->ExceptionClear(env);\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        char str[strlen(error) + 10];\n" +
                        "        sprintf(str, \"%s on %d\", error, line);\n" +
                        "        (*env)->ThrowNew(env, exception_ptr,  str);\n" +
                        "        (*env)->DeleteLocalRef(env, exception_ptr);\n" +
                        "    }\n\n"
                );

        // Process cached classes without license or expiration conditions
        for (Map.Entry<String, CachedClassInfo> next : cachedClasses.getCache().entrySet()) {
            int id = next.getValue().getId();
            StringBuilder fieldStr = new StringBuilder();
            List<CachedFieldInfo> fields = next.getValue().getCachedFields();
            for (int i = 0; i < fields.size(); i++) {
                CachedFieldInfo fieldInfo = fields.get(i);
                if (!"<init>".equals(fieldInfo.getName())) {
                    fieldStr.append("    jfieldID id_").append(i).append(";\n");
                }
            }
            StringBuilder methodStr = new StringBuilder();
            List<CachedMethodInfo> methods = next.getValue().getCachedMethods();
            for (int i = 0; i < methods.size(); i++) {
                methodStr.append("    jmethodID method_").append(i).append(";\n");
            }
            mainWriter.append("struct cached_c_").append(String.valueOf(id)).append(" {\n").append("    jclass clazz;\n").append(String.valueOf(fieldStr)).append(String.valueOf(methodStr)).append("    jboolean initialize;\n").append("};\n\n");

            StringBuilder cachedFieldStr = new StringBuilder();

            for (int i = 0; i < fields.size(); i++) {
                CachedFieldInfo fieldInfo = fields.get(i);
                if (!"<init>".equals(fieldInfo.getName())) {
                    if (fieldInfo.isStatic()) {
                        if (stringObf) {
                            cachedFieldStr
                                    .append("        cache.id_").append(i)
                                    .append(" = (*env)->GetStaticFieldID(env, clazz, ")
                                    .append(Util.getStringObf(fieldInfo.getName())).append(", ")
                                    .append(Util.getStringObf(fieldInfo.getDesc())).append(");\n");
                        } else {
                            cachedFieldStr
                                    .append("        cache.id_").append(i)
                                    .append(" = (*env)->GetStaticFieldID(env, clazz, \"")
                                    .append(fieldInfo.getName()).append("\", \"")
                                    .append(fieldInfo.getDesc()).append("\");\n");
                        }
                    } else {
                        if (stringObf) {
                            cachedFieldStr
                                    .append("        cache.id_").append(i)
                                    .append(" = (*env)->GetFieldID(env, clazz, ")
                                    .append(Util.getStringObf(fieldInfo.getName())).append(", ")
                                    .append(Util.getStringObf(fieldInfo.getDesc())).append(");\n");
                        } else {
                            cachedFieldStr
                                    .append("        cache.id_").append(i)
                                    .append(" = (*env)->GetFieldID(env, clazz, \"")
                                    .append(fieldInfo.getName()).append("\", \"")
                                    .append(fieldInfo.getDesc()).append("\");\n");
                        }
                    }
                }
            }
            StringBuilder cachedMethodStr = new StringBuilder();
            for (int i = 0; i < methods.size(); i++) {
                CachedMethodInfo methodInfo = methods.get(i);
                if (methodInfo.isStatic()) {
                    if (stringObf) {
                        cachedMethodStr
                                .append("        cache.method_").append(i)
                                .append(" = (*env)->GetStaticMethodID(env, clazz, ")
                                .append(Util.getStringObf(methodInfo.getName())).append(", ")
                                .append(Util.getStringObf(methodInfo.getDesc())).append(");\n");
                    } else {
                        cachedMethodStr
                                .append("        cache.method_").append(i)
                                .append(" = (*env)->GetStaticMethodID(env, clazz, \"")
                                .append(methodInfo.getName()).append("\", \"")
                                .append(methodInfo.getDesc()).append("\");\n");
                    }
                } else {
                    if (stringObf) {
                        cachedMethodStr
                                .append("        cache.method_").append(i)
                                .append(" = (*env)->GetMethodID(env, clazz, ")
                                .append(Util.getStringObf(methodInfo.getName())).append(", ")
                                .append(Util.getStringObf(methodInfo.getDesc())).append(");\n");
                    } else {
                        cachedMethodStr
                                .append("        cache.method_").append(i)
                                .append(" = (*env)->GetMethodID(env, clazz, \"")
                                .append(methodInfo.getName()).append("\", \"")
                                .append(methodInfo.getDesc()).append("\");\n");
                    }
                }
            }
            String clazz = "    jclass clazz = (*env)->FindClass(env, \"" + next.getKey() + "\");\n";
            if (stringObf) {
                clazz = "    jclass clazz = (*env)->FindClass(env, " + Util.getStringObf(next.getKey()) + ");\n";
            }
            mainWriter.append("static const struct cached_c_").append(String.valueOf(id)).append("* c_").append(String.valueOf(id)).append("_(JNIEnv *env) {\n").append("    static struct cached_c_").append(String.valueOf(id)).append(" cache;\n").append("    static atomic_flag lock;\n").append("    if (cache.initialize) return &cache;\n").append("    cache.initialize = JNI_FALSE;\n").append(clazz).append("    while (atomic_flag_test_and_set(&lock)) {}\n").append("    if (!cache.initialize) {\n").append("        cache.clazz = (*env)->NewGlobalRef(env, clazz);\n").append("        if ((*env)->ExceptionCheck(env) && !clazz) {\n").append("            cache.initialize = JNI_FALSE;\n").append("            (*env)->ExceptionDescribe(env);\n").append("            (*env)->ExceptionClear(env);\n").append("            atomic_flag_clear(&lock);\n").append("            return &cache;\n").append("        }\n").append(cachedFieldStr.toString()).append(cachedMethodStr.toString()).append("        cache.initialize = JNI_TRUE;\n").append("    }\n").append("    atomic_flag_clear(&lock);\n").append("    return &cache;\n").append("}\n\n");
        }

        // Append instructions as is
        mainWriter.append(instructions);

        // Perform native registrations without any conditionals to enforce professional mode
        for (Map.Entry<String, CachedClassInfo> next : cachedClasses.getCache().entrySet()) {
            ClassNode classNode = map.get(next.getKey());
            if (classNode != null) {

                StringBuilder registrationMethods = new StringBuilder();
                int methodCount = 0;
                for (MethodNode method : classNode.methods) {
                    if (!"<init>".equals(method.name) && !"<clinit>".equals(method.name) && !"$myj2cLoader".equals(method.name)) {
                        String methodName = null;
                        if ("$myj2cClinit".equals(method.name)) {
                            methodName = getClassMethodNameMap().get(classNode.name + ".<clinit>()V");
                        } else {
                            methodName = getClassMethodNameMap().get(classNode.name + "." + method.name + method.desc);
                        }
                        if (methodName == null) {
                            continue;
                        }
                        if (stringObf) {
                            registrationMethods
                                    .append("            {")
                                    .append(Util.getStringObf(method.name)).append(", ")
                                    .append(Util.getStringObf(method.desc)).append(", (void *) &")
                                    .append(methodName).append("},\n");
                        } else {
                            registrationMethods
                                    .append("            {\"")
                                    .append(method.name).append("\", \"")
                                    .append(method.desc).append("\", (void *) &")
                                    .append(methodName).append("},\n");
                        }
                        methodCount++;
                    }
                }
                String className = classNameMap.get(classNode.name);
                if (Util.isValidJavaFullClassName(className.replaceAll("/", "."))) {
                    String methodName = NativeSignature.getJNICompatibleName(className);
                    mainWriter
                            .append("/* Native registration for <").append(className).append("> */\n")
                            .append("JNIEXPORT void JNICALL Java_").append(methodName).append("__00024myj2cLoader(JNIEnv *env, jclass clazz) {\n")
                            .append("    JNINativeMethod table[] = {\n")
                            .append(String.valueOf(registrationMethods))
                            .append("    };\n")
                            .append("\n")
                            .append("    (*env)->RegisterNatives(env, clazz, table, ").append(String.valueOf(methodCount)).append(");\n")
                            .append("}\n\n");
                }
            }
        }
        mainWriter.close();
    }

}
