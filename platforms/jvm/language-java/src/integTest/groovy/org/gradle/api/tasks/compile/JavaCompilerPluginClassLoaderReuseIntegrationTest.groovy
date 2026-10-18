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

package org.gradle.api.tasks.compile

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.precondition.Requires
import org.gradle.test.preconditions.TestExecutionPreconditions

@Requires(value = TestExecutionPreconditions.NotEmbeddedExecutor, reason = "requires a persistent compiler JVM across builds")
class JavaCompilerPluginClassLoaderReuseIntegrationTest extends AbstractIntegrationSpec {
    def "plugin loader reuse is opt-in and leaves annotation processors fresh (reuse=#reuse, fork=#fork)"() {
        given:
        executer.requireDaemon().requireIsolatedDaemons()
        executer.withWorkerDaemonsExpirationDisabled()
        settingsFile << "include 'plugins'"
        file('plugins/build.gradle') << "plugins { id 'java' }"
        file('plugins/src/main/java/probe/ProbePlugin.java') << '''
            package probe;
            import com.sun.source.util.JavacTask;
            import com.sun.source.util.Plugin;
            public class ProbePlugin implements Plugin {
                private static final String TOKEN = java.util.UUID.randomUUID().toString();
                public String getName() { return "Probe"; }
                public void init(JavacTask task, String... args) {
                    System.out.println("plugin-loader=" + TOKEN);
                }
            }
        '''
        file('plugins/src/main/java/probe/ProbeProcessor.java') << '''
            package probe;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            @SupportedAnnotationTypes("*")
            public class ProbeProcessor extends AbstractProcessor {
                private static final String TOKEN = java.util.UUID.randomUUID().toString();
                public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                public synchronized void init(ProcessingEnvironment environment) {
                    super.init(environment);
                    System.out.println("processor-loader=" + TOKEN);
                }
                public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    return false;
                }
            }
        '''
        file('plugins/src/main/resources/META-INF/services/com.sun.source.util.Plugin') << 'probe.ProbePlugin'
        file('plugins/src/main/resources/META-INF/services/javax.annotation.processing.Processor') << 'probe.ProbeProcessor'
        file('src/main/java/Example.java') << 'class Example {}'
        buildFile << """
            plugins { id 'java' }
            dependencies { annotationProcessor project(':plugins') }
            tasks.withType(JavaCompile).configureEach {
                options.fork = $fork
                options.compilerPluginClassLoaderReuse = $reuse
                options.compilerArgs.add('-Xplugin:Probe')
            }
            tasks.register('compileOther', JavaCompile) {
                dependsOn compileJava
                source = sourceSets.main.java
                classpath = sourceSets.main.compileClasspath
                destinationDirectory = layout.buildDirectory.dir('other')
                options.annotationProcessorPath = configurations.annotationProcessor
            }
        """

        when:
        succeeds 'compileOther'

        then:
        def plugins = tokens('plugin-loader')
        plugins.size() == 2
        (plugins[0] == plugins[1]) == reuse
        def processors = tokens('processor-loader')
        processors.size() == 2
        processors[0] != processors[1]
        file('build/classes/java/main/Example.class').exists()
        file('build/other/Example.class').exists()

        when:
        file('src/main/java/Example.java').text = 'class Example { int changed; }'
        executer.withWorkerDaemonsExpirationDisabled()
        succeeds 'compileOther'

        then:
        def nextPlugins = tokens('plugin-loader')
        nextPlugins.size() == 2
        (nextPlugins[0] == nextPlugins[1]) == reuse
        (nextPlugins[0] == plugins[0]) == reuse
        def nextProcessors = tokens('processor-loader')
        nextProcessors.size() == 2
        (processors + nextProcessors).unique().size() == 4

        where:
        reuse | fork
        false | false
        true  | false
        false | true
        true  | true
    }

    private List<String> tokens(String prefix) {
        (output =~ /${prefix}=([a-f0-9-]+)/).collect { it[1] }
    }
}
