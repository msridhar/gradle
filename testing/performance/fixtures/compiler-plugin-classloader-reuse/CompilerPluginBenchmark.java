/*
 * Copyright 2026 Gradle and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.api.internal.tasks.compile.reflect.GradleStandardJavaFileManager;
import org.gradle.internal.classpath.DefaultClassPath;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.StringWriter;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Measures repeated compiler invocations in one JVM, using the actual modified Gradle file manager. */
public class CompilerPluginBenchmark {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        boolean reuse = Boolean.parseBoolean(args[1]);
        String analysis = args[2];
        int warmups = Integer.parseInt(args[3]);
        int iterations = Integer.parseInt(args[4]);
        List<File> processorPath = Arrays.stream(new String(Files.readAllBytes(project.resolve("processor-path.txt"))).trim().split(File.pathSeparator))
            .map(File::new).collect(Collectors.toList());
        String compilePath = new String(Files.readAllBytes(project.resolve("compile-path.txt"))).trim();
        List<File> sources;
        try (Stream<Path> files = Files.walk(project.resolve("module0/src/main/java"))) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().map(Path::toFile).collect(Collectors.toList());
        }
        Path output = Files.createDirectories(project.resolve("compiler-output"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ClassLoadingMXBean loading = ManagementFactory.getClassLoadingMXBean();
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        com.sun.management.OperatingSystemMXBean operatingSystem =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        List<String> options = new ArrayList<>(Arrays.asList("-classpath", compilePath,
            "-processorpath", processorPath.stream().map(File::getPath).collect(Collectors.joining(File.pathSeparator)),
            "-d", output.toString(), "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW",
            "-XDaddTypeAnnotationsToSymbol=true"));
        if (analysis.equals("nullaway")) {
            options.add("-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true");
        } else {
            options.add("-Xplugin:ErrorProne -Xep:NullAway:OFF");
        }
        for (int iteration = -warmups; iteration < iterations; iteration++) {
            long loaded = loading.getTotalLoadedClassCount();
            long unloaded = loading.getUnloadedClassCount();
            long jitTime = jit.getTotalCompilationTime();
            long cpuTime = operatingSystem.getProcessCpuTime();
            long start = System.nanoTime();
            StringWriter diagnostics = new StringWriter();
            try (StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
                 JavaFileManager manager = GradleStandardJavaFileManager.wrap(standard, DefaultClassPath.of(processorPath), false, reuse)) {
                JavaCompiler.CompilationTask task = compiler.getTask(diagnostics, manager, null, options, null,
                    standard.getJavaFileObjectsFromFiles(sources));
                task.setProcessors(Collections.emptyList());
                if (!task.call()) {
                    throw new AssertionError(diagnostics.toString());
                }
            }
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionTime()).sum();
            long metaspace = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().contains("Metaspace")).mapToLong(pool -> pool.getUsage().getUsed()).sum();
            System.out.printf(java.util.Locale.ROOT,
                "{\"iteration\":%d,\"reuse\":%s,\"analysis\":\"%s\",\"wall_ms\":%.3f,\"cpu_ms\":%.3f,\"loaded_classes\":%d,\"unloaded_classes\":%d,\"jit_ms\":%d,\"gc_total_ms\":%d,\"metaspace_bytes\":%d}%n",
                iteration, reuse, analysis, elapsedMs, (operatingSystem.getProcessCpuTime() - cpuTime) / 1_000_000.0,
                loading.getTotalLoadedClassCount() - loaded, loading.getUnloadedClassCount() - unloaded,
                jit.getTotalCompilationTime() - jitTime, gcTime, metaspace);
        }
    }
}
