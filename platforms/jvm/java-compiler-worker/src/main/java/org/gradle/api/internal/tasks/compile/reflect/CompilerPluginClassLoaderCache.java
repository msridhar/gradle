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

package org.gradle.api.internal.tasks.compile.reflect;

import org.gradle.internal.classpath.ClassPath;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.gradle.api.internal.tasks.compile.filter.AnnotationProcessorFilter.getFilteredClassLoader;

/**
 * A bounded, process-local cache for compilation-independent javac plugin classes.
 * Concurrent borrowers share a loader; leases only protect its lifetime during eviction.
 */
@NullMarked
public final class CompilerPluginClassLoaderCache implements Closeable {
    private final int maximumSize;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    public CompilerPluginClassLoaderCache(int maximumSize) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("Cache size must be positive");
        }
        this.maximumSize = maximumSize;
    }

    public static CompilerPluginClassLoaderCache shared() {
        return Shared.INSTANCE;
    }

    private static class Shared {
        private static final CompilerPluginClassLoaderCache INSTANCE = new CompilerPluginClassLoaderCache(8);

        static {
            // Prototype lifecycle: a daemon/worker-scoped service should own this in a production implementation.
            Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::close, "compiler-plugin-classloader-cache"));
        }
    }

    public Lease acquire(ClassPath classPath, ClassLoader parent) {
        Key key = new Key(classPath.getAsFiles(), fingerprint(classPath), parent);
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Classloader cache is closed");
            }
            Entry entry = entries.get(key);
            if (entry == null) {
                retireObsoleteEntries(key);
                entry = new Entry(new CachedClassLoader(classPath.getAsURLArray(), getFilteredClassLoader(parent)));
                entries.put(key, entry);
            }
            entry.borrowers++;
            evictUnusedEntries();
            return new Lease(this, entry);
        }
    }

    private void retireObsoleteEntries(Key replacement) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> candidate = iterator.next();
            Key key = candidate.getKey();
            if (key.hasChangedFiles(replacement) && candidate.getValue().borrowers == 0) {
                iterator.remove();
                // Close the old JAR handles before loading the replacement, including cached resource connections.
                candidate.getValue().loader.closeForReal();
            }
        }
    }

    private synchronized void release(Entry entry) {
        entry.borrowers--;
        if (closed && entry.borrowers == 0) {
            entry.loader.closeForReal();
        } else {
            evictUnusedEntries();
        }
    }

    private void evictUnusedEntries() {
        Iterator<Entry> iterator = entries.values().iterator();
        while (entries.size() > maximumSize && iterator.hasNext()) {
            Entry candidate = iterator.next();
            if (candidate.borrowers == 0) {
                iterator.remove();
                candidate.loader.closeForReal();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Entry entry : entries.values()) {
            if (entry.borrowers == 0) {
                entry.loader.closeForReal();
            }
        }
        entries.clear();
    }

    private static byte[][] fingerprint(ClassPath classPath) {
        try {
            List<byte[]> fingerprints = new ArrayList<>();
            byte[] buffer = new byte[8192];
            for (File file : classPath.getAsFiles()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                Path root = file.toPath();
                addName(digest, root.toAbsolutePath().toString());
                if (Files.isDirectory(root)) {
                    digest.update((byte) 'D');
                    try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                        List<Path> files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
                        for (Path child : files) {
                            addName(digest, root.relativize(child).toString());
                            addFile(digest, child, buffer);
                        }
                    }
                } else if (Files.isRegularFile(root)) {
                    digest.update((byte) 'F');
                    addFile(digest, root, buffer);
                } else {
                    digest.update((byte) 'M');
                }
                digest.update((byte) 0);
                fingerprints.add(digest.digest());
            }
            return fingerprints.toArray(new byte[0][]);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not fingerprint compiler plugin classpath", e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void addName(MessageDigest digest, String name) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void addFile(MessageDigest digest, Path file, byte[] buffer) throws IOException, NoSuchAlgorithmException {
        MessageDigest content = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                content.update(buffer, 0, count);
            }
        }
        digest.update(content.digest());
    }

    private static final class Key {
        private final List<File> classPath;
        private final byte[][] fingerprint;
        private final ClassLoader parent;

        private Key(List<File> classPath, byte[][] fingerprint, ClassLoader parent) {
            this.classPath = new ArrayList<>(classPath);
            this.fingerprint = fingerprint;
            this.parent = parent;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return parent == key.parent && classPath.equals(key.classPath) && Arrays.deepEquals(fingerprint, key.fingerprint);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * classPath.hashCode() + Arrays.deepHashCode(fingerprint)) + System.identityHashCode(parent);
        }

        private boolean hasChangedFiles(Key replacement) {
            for (int i = 0; i < classPath.size(); i++) {
                int replacementIndex = replacement.classPath.indexOf(classPath.get(i));
                if (replacementIndex >= 0 && !Arrays.equals(fingerprint[i], replacement.fingerprint[replacementIndex])) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Entry {
        private final CachedClassLoader loader;
        private int borrowers;

        private Entry(CachedClassLoader loader) {
            this.loader = loader;
        }
    }

    /** A compilation's reference to a loader; releasing it does not close a cached loader. */
    public static final class Lease implements Closeable {
        private final CompilerPluginClassLoaderCache cache;
        private final Entry entry;
        private boolean released;

        private Lease(CompilerPluginClassLoaderCache cache, Entry entry) {
            this.cache = cache;
            this.entry = entry;
        }

        public ClassLoader getClassLoader() {
            return entry.loader;
        }

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                cache.release(entry);
            }
        }
    }

    private static final class CachedClassLoader extends URLClassLoader {
        private CachedClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public void close() {
            // javac closes its processor-path loader after every compilation. The cache owns this one.
        }

        private void closeForReal() {
            try {
                super.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
