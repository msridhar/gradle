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

package org.gradle.api.internal.tasks.compile.reflect

import org.gradle.internal.classpath.DefaultClassPath
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class CompilerPluginClassLoaderCacheTest extends Specification {
    @TempDir
    Path temporaryDirectory
    def cache = new CompilerPluginClassLoaderCache(1)
    def parent = getClass().classLoader

    def cleanup() {
        cache.close()
    }

    def "javac closing a cached loader does not close its resources"() {
        given:
        def path = classPath(jar("plugin.jar", "first"))
        def first = cache.acquire(path, parent)
        def loader = first.classLoader

        when:
        loader.close()
        first.close()
        def second = cache.acquire(path, parent)

        then:
        second.classLoader.is(loader)
        resource(second.classLoader) == "first"

        cleanup:
        second?.close()
    }

    def "invalidates replaced jars even when size and modification time are unchanged"() {
        given:
        def file = jar("plugin.jar", "first")
        def timestamp = Files.getLastModifiedTime(file)
        def size = Files.size(file)
        def first = cache.acquire(classPath(file), parent)
        def loader = first.classLoader
        first.close()

        when:
        jar("plugin.jar", "other")
        Files.setLastModifiedTime(file, timestamp)
        def second = cache.acquire(classPath(file), parent)

        then:
        !second.classLoader.is(loader)
        Files.size(file) == size
        resource(second.classLoader) == "other"

        cleanup:
        second?.close()
    }

    def "invalidates directory resources"() {
        given:
        def directory = Files.createDirectory(temporaryDirectory.resolve("classes"))
        Files.write(directory.resolve("marker.txt"), "first".bytes)
        def first = cache.acquire(classPath(directory), parent)
        def loader = first.classLoader
        first.close()

        when:
        Files.write(directory.resolve("marker.txt"), "other".bytes)
        def second = cache.acquire(classPath(directory), parent)

        then:
        !second.classLoader.is(loader)
        resource(second.classLoader) == "other"

        cleanup:
        second?.close()
    }

    def "replacing a jar retires its old idle loader below capacity (different classpath=#differentClasspath)"() {
        given:
        cache.close()
        cache = new CompilerPluginClassLoaderCache(8)
        def file = jar("plugin.jar", "first")
        def dependency = jar("dependency.jar", "dependency")
        def first = cache.acquire(classPath(file, dependency), parent)
        resource(first.classLoader) == "first"
        first.close()

        when:
        Files.move(jar("replacement.jar", "other"), file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        def nextDependency = differentClasspath ? jar("another-dependency.jar", "another") : dependency
        def second = cache.acquire(classPath(file, nextDependency), parent)

        then:
        resource(second.classLoader) == "other"
        first.classLoader.getResource("marker.txt") == null

        cleanup:
        second?.close()

        where:
        differentClasspath << [false, true]
    }

    def "classpath order and parent identity are part of the key"() {
        given:
        def a = jar("a.jar", "first")
        def b = jar("b.jar", "other")
        def first = cache.acquire(classPath(a, b), parent)
        def reversed = cache.acquire(classPath(b, a), parent)
        def differentParent = cache.acquire(classPath(a, b), new URLClassLoader(new URL[0], parent))

        expect:
        !first.classLoader.is(reversed.classLoader)
        !first.classLoader.is(differentParent.classLoader)
        resource(first.classLoader) == "first"
        resource(reversed.classLoader) == "other"

        cleanup:
        first?.close()
        reversed?.close()
        differentParent?.close()
    }

    def "eviction keeps active loaders open and closes unused loaders"() {
        given:
        def first = cache.acquire(classPath(jar("a.jar", "first")), parent)
        def second = cache.acquire(classPath(jar("b.jar", "other")), parent)

        expect:
        resource(first.classLoader) == "first"
        resource(second.classLoader) == "other"

        when:
        first.close()

        then:
        first.classLoader.getResource("marker.txt") == null
        resource(second.classLoader) == "other"

        cleanup:
        first?.close()
        second?.close()
    }

    def "concurrent compilations share exactly the same loader"() {
        given:
        def path = classPath(jar("plugin.jar", "first"))
        def threads = Executors.newFixedThreadPool(8)
        def barrier = new CyclicBarrier(8)

        when:
        def futures = (1..8).collect {
            threads.submit({
                def lease = cache.acquire(path, parent)
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                    return lease.classLoader
                } finally {
                    lease.close()
                }
            } as Callable<ClassLoader>)
        }
        def loaders = futures.collect { it.get(15, TimeUnit.SECONDS) }

        then:
        loaders.every { it.is(loaders.first()) }

        cleanup:
        threads.shutdownNow()
    }

    def "closing cache defers closing a loader until its last borrower releases it"() {
        given:
        def path = classPath(jar("plugin.jar", "first"))
        def first = cache.acquire(path, parent)
        def second = cache.acquire(path, parent)

        when:
        cache.close()
        first.close()

        then:
        resource(second.classLoader) == "first"

        when:
        second.close()

        then:
        second.classLoader.getResource("marker.txt") == null
    }

    private Path jar(String name, String value) {
        def file = temporaryDirectory.resolve(name)
        new JarOutputStream(Files.newOutputStream(file)).withCloseable { output ->
            output.putNextEntry(new JarEntry("marker.txt"))
            output.write(value.bytes)
            output.closeEntry()
        }
        return file
    }

    private static def classPath(Path... paths) {
        DefaultClassPath.of(paths.collect { it.toFile() })
    }

    private static String resource(ClassLoader loader) {
        loader.getResourceAsStream("marker.txt").withCloseable { it.getText("UTF-8") }
    }
}
