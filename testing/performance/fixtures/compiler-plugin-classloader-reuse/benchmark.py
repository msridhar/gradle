#!/usr/bin/env python3
"""Build a disposable patched distribution and benchmark real Error Prone / NullAway compilations."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[3]
EXPORTS = [
    '--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED',
    '--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED',
    '--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED',
]


def run(command, log, *, cwd=None, env=None):
    with log.open('w') as output:
        subprocess.run([str(part) for part in command], cwd=cwd, env=env,
                       stdout=output, stderr=subprocess.STDOUT, check=True)


def prepare(args, work, env):
    classes = work / 'prototype-classes'
    classes.mkdir(exist_ok=True)
    compiler_sources = REPOSITORY / 'platforms/jvm/java-compiler-worker/src/main/java'
    relative = 'org/gradle/api/internal/tasks/compile'
    sources = [compiler_sources / relative / name for name in [
        'JdkJavaCompiler.java', 'MinimalJavaCompileOptions.java',
        'reflect/GradleStandardJavaFileManager.java', 'reflect/CompilerPluginClassLoaderCache.java']]
    sources.append(REPOSITORY / 'platforms/jvm/jvm-compiler-worker/src/main/java/org/gradle/api/tasks/compile/CompileOptions.java')
    if args.base_compiler_source:
        # Apply the same one-line integration change to the wrapper release, whose Problems API
        # predates the checkout. All loader/cache and compile-option sources above are unmodified.
        adapted = work / 'adapter/org/gradle/api/internal/tasks/compile/JdkJavaCompiler.java'
        adapted.parent.mkdir(parents=True, exist_ok=True)
        original = args.base_compiler_source.read_text()
        old_call = 'DefaultClassPath.of(spec.getAnnotationProcessorPath()), hasEmptySourcepaths)'
        assert old_call in original
        adapted.write_text(original.replace(old_call,
            'DefaultClassPath.of(spec.getAnnotationProcessorPath()), hasEmptySourcepaths, compileOptions.isCompilerPluginClassLoaderReuse())'))
        sources[0] = adapted
    libraries = f'{args.gradle_home}/lib/*{os.pathsep}{args.gradle_home}/lib/plugins/*'
    run([args.java_home / 'bin/javac', '-proc:none', EXPORTS[7], '-cp', libraries,
         '-d', classes, *sources], work / 'compile-prototype.log', env=env)
    patched = work / 'gradle-patched'
    if not patched.exists():
        shutil.copytree(args.gradle_home, patched)
    for name, roots in [('jvm', ['org/gradle/api/tasks/compile']),
                        ('java', ['org/gradle/api/internal/tasks/compile'])]:
        target = next((patched / 'lib/plugins').glob(f'gradle-{name}-compiler-worker-*.jar'))
        replacements = {str(path.relative_to(classes)): path.read_bytes()
                        for root in roots for path in (classes / root).rglob('*.class')}
        temporary = target.with_suffix('.jar.tmp')
        with zipfile.ZipFile(target) as original, zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as output:
            for item in original.infolist():
                if item.filename not in replacements:
                    output.writestr(item, original.read(item.filename))
            for name, data in replacements.items():
                output.writestr(name, data)
        temporary.replace(target)
    run([args.java_home / 'bin/javac', '-proc:none', '-cp', f'{classes}{os.pathsep}{libraries}',
         '-d', classes, HERE / 'CompilerPluginBenchmark.java'], work / 'compile-benchmark.log', env=env)
    project = work / 'project'
    project.mkdir(exist_ok=True)
    (project / 'settings.gradle').write_text("rootProject.name = 'plugin-loader-benchmark'\n" +
        ''.join(f"include('module{i}')\n" for i in range(args.modules)))
    (project / 'gradle.properties').write_text(
        'org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m\n'
        f'org.gradle.workers.max={args.workers}\norg.gradle.parallel=true\n'
        'org.gradle.caching=false\norg.gradle.configuration-cache=true\n')
    (project / 'build.gradle').write_text('''
plugins {
    id 'net.ltgt.errorprone' version '5.1.1' apply false
    id 'net.ltgt.nullaway' version '3.3.0' apply false
}
abstract class CompileTimingService implements org.gradle.api.services.BuildService<org.gradle.api.services.BuildServiceParameters.None>, org.gradle.tooling.events.OperationCompletionListener {
    void onFinish(org.gradle.tooling.events.FinishEvent event) {
        if (event instanceof org.gradle.tooling.events.task.TaskFinishEvent && event.descriptor.taskPath.endsWith(':compileJava')) {
            println('BENCH_COMPILE_MS ' + event.descriptor.taskPath + ' ' + (event.result.endTime - event.result.startTime))
            def result = event.result
            def outcome = result instanceof org.gradle.tooling.events.task.TaskSuccessResult ?
                (result.fromCache ? 'FROM_CACHE' : result.upToDate ? 'UP_TO_DATE' : 'EXECUTED') :
                (result instanceof org.gradle.tooling.events.task.TaskSkippedResult ? 'SKIPPED' : 'FAILED')
            println('BENCH_COMPILE_OUTCOME ' + event.descriptor.taskPath + ' ' + outcome)
        }
    }
}
class TimingPlugin implements Plugin<Project> {
    private final org.gradle.build.event.BuildEventsListenerRegistry registry
    @javax.inject.Inject
    TimingPlugin(org.gradle.build.event.BuildEventsListenerRegistry registry) { this.registry = registry }
    void apply(Project project) {
        registry.onTaskCompletion(project.gradle.sharedServices.registerIfAbsent('compileTiming', CompileTimingService) {})
    }
}
apply plugin: TimingPlugin
subprojects {
    apply plugin: 'java'
    apply plugin: 'net.ltgt.errorprone'
    apply plugin: 'net.ltgt.nullaway'
    repositories { mavenCentral() }
    dependencies {
        errorprone 'com.google.errorprone:error_prone_core:2.50.0'
        errorprone 'com.uber.nullaway:nullaway:0.14.2'
        compileOnly 'org.jspecify:jspecify:1.0.0'
    }
    tasks.withType(JavaCompile).configureEach {
        options.fork = true
        options.forkOptions.memoryMaximumSize = '2g'
        options.incremental = false
        options.compilerPluginClassLoaderReuse = providers.gradleProperty('reuse').map { it.toBoolean() }.orElse(false)
        options.errorprone.disableAllChecks = true
        options.errorprone.nullaway {
            onlyNullMarked = true
            error()
        }
    }
}
project(':module0') {
    tasks.register('writePaths') {
        def processor = configurations.annotationProcessor
        def compile = configurations.compileClasspath
        doLast {
            rootProject.file('processor-path.txt').text = processor.asPath
            rootProject.file('compile-path.txt').text = compile.asPath
        }
    }
}
''')
    for module in range(args.modules):
        source = project / f'module{module}/src/main/java/bench'
        source.mkdir(parents=True, exist_ok=True)
        for old in source.glob('*.java'):
            old.unlink()
        (source / 'package-info.java').write_text('@org.jspecify.annotations.NullMarked\npackage bench;\n')
        for number in range(args.classes):
            methods = '\n'.join(f'''
    public String transform{method}(@org.jspecify.annotations.Nullable String input) {{
        String value = input == null ? name : input;
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(value);
        parts.add(Integer.toString({method}));
        StringBuilder result = new StringBuilder();
        for (String part : parts) {{
            if (!part.isEmpty()) {{ result.append(part.trim()); }}
        }}
        return result.toString();
    }}''' for method in range(args.methods))
            (source / f'Example{number}.java').write_text(f'''package bench;
public final class Example{number} {{
    private final String name;
    public Example{number}(String name) {{ this.name = name; }}
{methods}
}}
''')
    run([patched / 'bin/gradle', ':module0:writePaths', '--no-configuration-cache', '--console=plain'],
        work / 'resolve.log', cwd=project, env=env)
    return patched, classes, project


def compiler_benchmark(args, work, classes, project, env):
    libraries = f'{args.gradle_home}/lib/*{os.pathsep}{args.gradle_home}/lib/plugins/*'
    results = []
    for analysis in ['nullaway', 'errorprone']:
        for fork in range(args.forks):
            variants = [False, True] if fork % 2 == 0 else [True, False]
            for reuse in variants:
                print(f'Compiler: {analysis}, fork {fork + 1}/{args.forks}, reuse={reuse}', flush=True)
                log = work / f'compiler-{analysis}-{fork}-{reuse}.jsonl'
                run([args.java_home / 'bin/java', '-Xms256m', '-Xmx2g', '-XX:MaxMetaspaceSize=768m',
                     *EXPORTS, '-cp', f'{classes}{os.pathsep}{libraries}', 'CompilerPluginBenchmark',
                     project, str(reuse).lower(), analysis, args.warmups, args.iterations], log, env=env)
                for line in log.read_text().splitlines():
                    if line.startswith('{'):
                        results.append(dict(json.loads(line), fork=fork))
                (work / 'compiler-results.json').write_text(json.dumps(results, indent=2))
    for analysis in ['nullaway', 'errorprone']:
        for reuse in [False, True]:
            samples = [row for row in results if row['analysis'] == analysis and row['reuse'] == reuse and row['iteration'] >= 0]
            print(json.dumps({'analysis': analysis, 'reuse': reuse,
                              'median_wall_ms': statistics.median(row['wall_ms'] for row in samples),
                              'mean_loaded_classes': statistics.mean(row['loaded_classes'] for row in samples),
                              'mean_jit_ms': statistics.mean(row['jit_ms'] for row in samples)}), flush=True)


def gradle_benchmark(args, work, patched, project, env):
    results = []
    for iteration in range(-2, args.gradle_builds):
        for reuse in ([False, True] if iteration % 2 == 0 else [True, False]):
            print(f'Gradle: iteration {iteration}, reuse={reuse}', flush=True)
            log = work / f'gradle-{iteration}-{reuse}.log'
            start = time.perf_counter()
            run([patched / 'bin/gradle', 'compileJava', '--rerun-tasks', '--no-build-cache',
                 '--console=plain', f'-Preuse={str(reuse).lower()}'], log, cwd=project, env=env)
            results.append({'iteration': iteration, 'reuse': reuse, 'wall_seconds': time.perf_counter() - start})
            task_times = re.findall(r'BENCH_COMPILE_MS ([^ ]+) (\d+)', log.read_text())
            assert len(task_times) == args.modules, f'Expected {args.modules} executed compile tasks, found {task_times}'
            results[-1]['compile_task_ms'] = {name: int(milliseconds) for name, milliseconds in task_times}
            results[-1]['compile_task_total_seconds'] = sum(int(value) for _, value in task_times) / 1000
            (work / 'gradle-results.json').write_text(json.dumps(results, indent=2))
    for reuse in [False, True]:
        print(json.dumps({'gradle_reuse': reuse, 'median_seconds': statistics.median(
            row['wall_seconds'] for row in results if row['reuse'] == reuse and row['iteration'] >= 0)}), flush=True)


def incremental_benchmark(args, work, patched, project, env):
    """Measure real one-class edits with incremental compilation and the build cache enabled."""
    assert 1 <= args.changed_modules <= args.modules
    build_file = project / 'build.gradle'
    build = build_file.read_text()
    build = build.replace('options.incremental = false', 'options.incremental = true\n        options.listFiles = true')
    assert 'options.incremental = true' in build and 'BENCH_COMPILE_OUTCOME' in build
    build_file.write_text(build)
    properties = project / 'gradle.properties'
    properties.write_text(properties.read_text().replace('org.gradle.caching=false', 'org.gradle.caching=true'))
    command = [patched / 'bin/gradle', 'compileJava', '--build-cache', '--info', '--console=plain']
    expected_tasks = {f':module{module}:compileJava' for module in range(args.modules)}
    changed_tasks = {f':module{module}:compileJava' for module in range(args.changed_modules)}
    sources = [project / f'module{module}/src/main/java/bench/Example0.java'
               for module in range(args.changed_modules)]
    original_sources = [source.read_text() for source in sources]
    run_id = uuid.uuid4().hex

    def class_hashes():
        return {str(path.relative_to(project)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in project.glob('module*/build/classes/java/main/**/*.class')}

    def inspect(log):
        content = log.read_text()
        times = re.findall(r'BENCH_COMPILE_MS ([^ ]+) (\d+)', content)
        outcomes = dict(re.findall(r'BENCH_COMPILE_OUTCOME ([^ ]+) (\w+)', content))
        assert len(times) == args.modules and set(outcomes) == expected_tasks, log
        assert 'Build cache key for task' in content, f'Build cache was not enabled: {log}'
        return content, {name: int(value) for name, value in times}, outcomes

    run([*command, '-Preuse=false'], work / 'incremental-seed.log', cwd=project, env=env)
    controls = {}
    for reuse in [False, True]:
        log = work / f'incremental-noop-{reuse}.log'
        run([*command, f'-Preuse={str(reuse).lower()}'], log, cwd=project, env=env)
        content, times, outcomes = inspect(log)
        assert set(outcomes.values()) == {'UP_TO_DATE'}, (log, outcomes)
        assert 'Source files to be compiled:' not in content, log
        controls[str(reuse).lower()] = outcomes

    results = []
    try:
        for iteration in range(-args.incremental_warmups, args.incremental_builds):
            for reuse in ([False, True] if iteration % 2 == 0 else [True, False]):
                print(f'Incremental: iteration {iteration}, reuse={reuse}, changed modules={args.changed_modules}', flush=True)
                marker = f'{run_id}-{iteration}-{str(reuse).lower()}'
                for module, (source, original) in enumerate(zip(sources, original_sources)):
                    old = 'parts.add(Integer.toString(0));'
                    assert old in original
                    source.write_text(original.replace(old, f'parts.add("{marker}-module{module}");', 1))
                before = class_hashes()
                log = work / f'incremental-{iteration}-{reuse}.log'
                start = time.perf_counter()
                run([*command, f'-Preuse={str(reuse).lower()}'], log, cwd=project, env=env)
                elapsed = time.perf_counter() - start
                content, times, outcomes = inspect(log)
                expected_outcomes = {name: 'EXECUTED' if name in changed_tasks else 'UP_TO_DATE'
                                     for name in expected_tasks}
                assert outcomes == expected_outcomes, (log, outcomes)
                for name in changed_tasks:
                    assert f"Build cache key for task '{name}' is " in content, (log, name)
                    assert f"Stored cache entry for task '{name}' with cache key " in content, (log, name)
                counts = [int(count) for count in re.findall(r'Incremental compilation of (\d+) classes completed', content)]
                assert counts == [1] * args.changed_modules, (log, counts)
                assert 'Full recompilation is required' not in content, log
                listed_sources = re.findall(r'^(/[^\n]+\.java)\s*$', content, re.MULTILINE)
                assert sorted(listed_sources) == sorted(str(source) for source in sources), (log, listed_sources)
                after = class_hashes()
                changed_outputs = sorted(name for name in set(before) | set(after) if before.get(name) != after.get(name))
                expected_outputs = sorted(f'module{module}/build/classes/java/main/bench/Example0.class'
                                          for module in range(args.changed_modules))
                assert changed_outputs == expected_outputs, (log, changed_outputs)
                results.append({'iteration': iteration, 'reuse': reuse, 'edit_marker': marker,
                                'wall_seconds': elapsed, 'compile_task_ms': times, 'task_outcomes': outcomes,
                                'executed_compile_task_total_seconds': sum(times[name] for name in changed_tasks) / 1000,
                                'incremental_class_counts': counts, 'compiled_sources': listed_sources,
                                'changed_class_outputs': changed_outputs,
                                'configuration_cache_reused': 'Reusing configuration cache.' in content})
                (work / 'incremental-results.json').write_text(json.dumps({
                    'run_id': run_id, 'build_cache_enabled': True, 'incremental_enabled': True,
                    'changed_modules': args.changed_modules, 'noop_task_outcomes': controls,
                    'samples': results}, indent=2))
    finally:
        for source, original in zip(sources, original_sources):
            source.write_text(original)
    for reuse in [False, True]:
        samples = [row for row in results if row['reuse'] == reuse and row['iteration'] >= 0]
        print(json.dumps({'incremental_reuse': reuse, 'median_wall_seconds': statistics.median(
            row['wall_seconds'] for row in samples), 'median_executed_task_seconds': statistics.median(
            row['executed_compile_task_total_seconds'] for row in samples)}), flush=True)


def verify_analysis(work, patched, project, env):
    source = project / 'module0/src/main/java/bench/NullAwayFailure.java'
    source.write_text('package bench; public class NullAwayFailure { public String fail() { return null; } }\n')
    command = [str(patched / 'bin/gradle'), ':module0:compileJava', '--rerun-tasks', '--console=plain', '-Preuse=true']
    try:
        result = subprocess.run(command, cwd=project, env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (work / 'verify-expected-failure.log').write_text(result.stdout)
        assert result.returncode != 0 and '[NullAway]' in result.stdout, result.stdout
    finally:
        source.unlink()
    run(command, work / 'verify-recovery.log', cwd=project, env=env)
    (work / 'verification.json').write_text(json.dumps({'nullaway_detected_error': True, 'recovery_succeeded': True}, indent=2))
    print('Verified NullAway reports a real error and compilation recovers with reuse enabled.', flush=True)


def verify_incremental_analysis(work, patched, project, env):
    source = project / 'module0/src/main/java/bench/Example0.java'
    original = source.read_text()
    assert 'options.incremental = true' in (project / 'build.gradle').read_text()
    verification = {}
    try:
        for reuse in [False, True]:
            command = [str(patched / 'bin/gradle'), 'compileJava', '--build-cache', '--info',
                       '--console=plain', f'-Preuse={str(reuse).lower()}']
            source.write_text(original.replace('return result.toString();', 'return null;', 1))
            failure = subprocess.run(command, cwd=project, env=env, text=True,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (work / f'incremental-expected-failure-{reuse}.log').write_text(failure.stdout)
            assert failure.returncode != 0 and '[NullAway]' in failure.stdout, failure.stdout
            assert re.findall(r'Incremental compilation of (\d+) classes completed', failure.stdout) == ['1']
            assert 'Full recompilation is required' not in failure.stdout
            marker = uuid.uuid4().hex
            source.write_text(original.replace('parts.add(Integer.toString(0));', f'parts.add("{marker}");', 1))
            recovery_log = work / f'incremental-recovery-{reuse}.log'
            run(command, recovery_log, cwd=project, env=env)
            recovery = recovery_log.read_text()
            assert re.findall(r'Incremental compilation of (\d+) classes completed', recovery) == ['1']
            assert 'BENCH_COMPILE_OUTCOME :module0:compileJava EXECUTED' in recovery
            verification[str(reuse).lower()] = {'nullaway_detected_error': True,
                'incremental_failure_class_count': 1, 'incremental_recovery_class_count': 1,
                'recovery_executed': True}
    finally:
        source.write_text(original)
    (work / 'incremental-verification.json').write_text(json.dumps(verification, indent=2))
    print('Verified a real incremental NullAway error and recovery in both reuse modes, with the build cache enabled.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--base-compiler-source', type=Path)
    parser.add_argument('--user-home', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--phase', choices=['prepare', 'compiler', 'gradle', 'incremental', 'verify', 'verify-incremental', 'all'], default='all')
    parser.add_argument('--forks', type=int, default=4)
    parser.add_argument('--iterations', type=int, default=16)
    parser.add_argument('--warmups', type=int, default=6)
    parser.add_argument('--classes', type=int, default=20)
    parser.add_argument('--methods', type=int, default=10)
    parser.add_argument('--modules', type=int, default=16)
    parser.add_argument('--workers', type=int, default=1)
    parser.add_argument('--gradle-builds', type=int, default=6)
    parser.add_argument('--incremental-builds', type=int, default=16)
    parser.add_argument('--incremental-warmups', type=int, default=6)
    parser.add_argument('--changed-modules', type=int, default=1)
    args = parser.parse_args()
    args.gradle_home = args.gradle_home.resolve()
    args.java_home = args.java_home.resolve()
    args.user_home = args.user_home.resolve()
    if args.base_compiler_source:
        args.base_compiler_source = args.base_compiler_source.resolve()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home), GRADLE_USER_HOME=str(args.user_home))
    (work / f'parameters-{args.phase}.json').write_text(json.dumps({key: str(value) if isinstance(value, Path) else value
        for key, value in vars(args).items()}, indent=2))
    if args.phase in ['prepare', 'all']:
        patched, classes, project = prepare(args, work, env)
    else:
        patched, classes, project = work / 'gradle-patched', work / 'prototype-classes', work / 'project'
    if args.phase in ['compiler', 'all']:
        compiler_benchmark(args, work, classes, project, env)
    if args.phase in ['gradle', 'all']:
        gradle_benchmark(args, work, patched, project, env)
    if args.phase == 'incremental':
        incremental_benchmark(args, work, patched, project, env)
    if args.phase in ['verify', 'all']:
        verify_analysis(work, patched, project, env)
    if args.phase == 'verify-incremental':
        verify_incremental_analysis(work, patched, project, env)


if __name__ == '__main__':
    main()
