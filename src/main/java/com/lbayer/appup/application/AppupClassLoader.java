/*
 * Copyright (C) 2015 Leo Bayer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lbayer.appup.application;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import com.lbayer.appup.registry.IContribRegistry;

class AppupClassLoader extends URLClassLoader implements IAppupRuntime
{
    private static final Logger LOGGER = Logger.getLogger(AppupClassLoader.class.getName());

    private static final String OS;
    private static final String ARCH;

    private static final String LIB_PATH = "java.library.path";

    private static File libsDir;

    private Map<File, BundleHandle> bundles;
    
    static
    {
        String os = System.getProperty("os.name");
        if (os.startsWith("Mac"))
        {
            OS = "macosx";
        }
        else if (os.startsWith("Windows"))
        {
            OS = "win32";
        }
        else if (os.toLowerCase().startsWith("linux"))
        {
            OS = "linux";
        }
        else
        {
            OS = os;
        }

        String arch = System.getProperty("os.arch");
        ARCH = arch;

        System.setProperty("osgi.os", OS);
        System.setProperty("osgi.arch", ARCH);
    }

    public AppupClassLoader(ClassLoader classLoader)
    {
        super(new URL[0], classLoader);

        bundles = new HashMap<>();

        libsDir = new File(System.getProperty("java.io.tmpdir"), "libs");
        if (!libsDir.mkdirs())
        {
            LOGGER.fine("Error creating libs dir!");
            return;
        }

        System.setProperty(LIB_PATH, libsDir.getPath() + File.pathSeparator + System.getProperty(LIB_PATH));
    }

    private void addSource(File file)
    {
        try
        {
            addURL(file.toURI().toURL());
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void install(File file)
    {
        try
        {
            LOGGER.info("Installing bundle: " + file.getName());

            loadLibs(file).forEach(this::addSource);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to read file: " + file, e);
        }
    }

    private List<File> loadLibs(File file) throws IOException
    {
        List<File> urls = new ArrayList<>();
        urls.add(file);

        BundleHandle bundle = new BundleHandle();
        bundle.location = file;
        bundles.put(file, bundle);

        LOGGER.fine(String.format("Loading libs (%s)...", file.getPath()));
        if (file.isDirectory())
        {
            File mfFile = new File(file, "META-INF/MANIFEST.MF");
            try (InputStream in = new FileInputStream(mfFile))
            {
                bundle.manifest = new Manifest(in);
            }

            Attributes attrs = bundle.manifest.getMainAttributes();

            String cp = attrs.getValue("Bundle-ClassPath");
            if (cp != null)
            {
                Arrays.stream(cp.split(","))
                    .filter((c) -> !c.equals("."))
                    .map((c) -> new File(file, c))
                    .forEach(urls::add);
            }

            File plugin = new File(file, "plugin.xml");
            if (plugin.isFile())
            {
                try (InputStream in = new FileInputStream(plugin))
                {
                    getContribRegistry().register(bundle, in);
                }
            }
        }
        else if (file.isFile())
        {
            try (JarFile jf = new JarFile(file))
            {
                bundle.manifest = jf.getManifest();
                Attributes attrs = bundle.manifest.getMainAttributes();

                String nativeCode = attrs.getValue("Bundle-NativeCode");
                if (nativeCode != null)
                {
                    LOGGER.fine(String.format("Loading native code (%s)...", file.getPath()));

                    String[] codes = nativeCode.split(",");
                    for (String codeEntry : codes)
                    {
                        if (codeEntry.contains(OS) && codeEntry.contains(ARCH))
                        {
                            LOGGER.fine(String.format("Native code matches (%s)...", codeEntry));

                            String libFile = codeEntry.split(";", 2)[0];
                            LOGGER.fine(String.format("Extracting library (%s)...", libFile));
                            ZipEntry libEntry = jf.getEntry(libFile);

                            File tmpLibFile = new File(libsDir, new File(libEntry.getName()).getName());
                            tmpLibFile.deleteOnExit();

                            copy(jf, libEntry, tmpLibFile);
                        }
                    }
                }

                String cp = attrs.getValue("Bundle-ClassPath");
                if (cp != null && !cp.equals("."))
                {
                    String[] codes = cp.split(",");
                    for (String codeEntry : codes)
                    {
                        if (codeEntry.equals("."))
                        {
                            continue;
                        }

                        LOGGER.fine(String.format("Extracting jar (%s)...", codeEntry));

                        ZipEntry jarEntry = jf.getEntry(codeEntry);
                        if (jarEntry == null)
                        {
                            LOGGER.fine("Missing entry: " + codeEntry);
                            continue;
                        }

                        File tmpJarFile = File.createTempFile("code", ".jar");
                        tmpJarFile.deleteOnExit();

                        copy(jf, jarEntry, tmpJarFile);

                        urls.add(tmpJarFile);
                    }
                }

                ZipEntry pluginEntry = jf.getEntry("plugin.xml");
                if (pluginEntry != null)
                {
                    try (InputStream in = jf.getInputStream(pluginEntry))
                    {
                        getContribRegistry().register(bundle, in);
                    }
                }
            }
        }

        Attributes mainAttrs = bundle.manifest.getMainAttributes();

        String activator = mainAttrs.getValue("Bundle-Activator");
        if (activator != null)
        {
            bundle.activatorClassname = activator;
        }

        String name = mainAttrs.getValue("Bundle-SymbolicName");
        if (name != null)
        {
            int colonOffset = name.indexOf(';');
            if (colonOffset >= 0)
            {
                name = name.substring(0, colonOffset);
            }

            bundle.symbolicName = name;
        }

        if (bundle.symbolicName == null)
        {
            Logger.getLogger(getClass().getName()).warning("Bundle is missing symbolic name: " + file);
        }

        return urls;
    }

    private IContribRegistry getContribRegistry()
    {
        try
        {
            return InitialContext.doLookup(IContribRegistry.class.getName());
        }
        catch (NamingException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void stop(File file) throws Exception
    {
        BundleHandle bundle = bundles.get(file);
        if (bundle == null)
        {
            return;
//            throw new IllegalArgumentException("No bundle for file: " + file);
        }

        if (bundle.activator == null)
        {
            return;
//            throw new IllegalStateException("Bundle not started: " + file);
        }

        Logger.getLogger(getClass().getName()).info("Stopping " + bundle.activatorClassname);
        bundle.activator.stop(bundle);
    }

    public void start(File file)
    {
        BundleHandle bundle = bundles.get(file);
        if (bundle == null)
        {
            throw new IllegalArgumentException("No bundle for file: " + file);
        }

        if (bundle.activator != null)
        {
            Logger.getLogger(getClass().getName()).warning("Bundle already started: " + file);
            return;
        }

        if (bundle.activatorClassname != null)
        {
            try
            {
                Class<?> clazz = loadClass(bundle.activatorClassname);
                BundleActivator instance = (BundleActivator) clazz.newInstance();

                Logger.getLogger(getClass().getName()).info("Starting " + bundle.activatorClassname);
                instance.start(bundle);
                bundle.activator = instance;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static void copy(JarFile jf, ZipEntry entry, File output) throws IOException
    {
        try (InputStream in = jf.getInputStream(entry); OutputStream out = new FileOutputStream(output))
        {
            byte[] buf = new byte[8192];
            int len = 0;
            while ((len = in.read(buf)) > 0)
            {
                out.write(buf, 0, len);
            }
        }
    }

    /**
     * Bundle reference;
     */
    private class BundleHandle implements Bundle, BundleContext
    {
        private Manifest manifest;
        private String activatorClassname;
        private String symbolicName;
        private BundleActivator activator;
        private File location;

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof BundleHandle && Objects.equals(location, ((BundleHandle) obj).location);
        }

        @Override
        public int hashCode()
        {
            return location.hashCode();
        }

        @Override
        public String toString()
        {
            return "bundle: " + symbolicName;
        }

        public Bundle getBundle()
        {
            return this;
        }

        public String getSymbolicName()
        {
            return symbolicName;
        }

        @Override
        public File getLocation()
        {
            return location;
        }
        @Override
        public String getFile(String entry)
        {
            return getEntryFile(entry).getAbsolutePath();
        }

        public File getEntryFile(String entry)
        {
            return new File(location, entry);
        }

        @Override
        public URL getEntry(String entry)
        {
            try
            {
                if (location.isFile())
                {
                    try (JarFile jarFile = new JarFile(location))
                    {
                        JarEntry jarEntry = jarFile.getJarEntry(entry);
                        if (jarEntry != null)
                        {
                            return new URL("jar:" + location.toURI().toURL() + "!/" + entry);
                        }

                        return null;
                    }
                }

                File file = new File(location, entry);
                if (file.exists())
                {
                    return file.toURI().toURL();
                }

                return null;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public URL getResource(String entry)
        {
            return getEntry(entry);
        }

        public Bundle[] getBundles()
        {
            return bundles.values().toArray(new Bundle[0]);
        }

        @Override
        public Dictionary<?, ?> getHeaders()
        {
            Hashtable<Object, Object> result = new Hashtable<>();
            manifest.getMainAttributes().entrySet().forEach((e) ->
                result.put(e.getKey().toString(), e.getValue())
            );

            return result;
        }

        @Override
        public Iterable<String> findPaths(String path, String filePattern, boolean recurse)
        {
            List<String> result = new ArrayList<>();
            String endsWith = filePattern.replaceAll("\\*", "");

            if (location.isFile())
            {
                try (JarFile jarFile = new JarFile(location))
                {
                    Enumeration<JarEntry> enumeration = jarFile.entries();
                    while (enumeration.hasMoreElements())
                    {
                        JarEntry entry = enumeration.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(path) && name.endsWith(endsWith))
                        {
                            result.add(name);
                        }
                    }

                    return result;
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }

            File start = new File(location, path);

            Queue<File> stack = new LinkedList<>();
            stack.add(start);

            File dir;
            while ((dir = stack.poll()) != null)
            {
                File[] files = dir.listFiles();
                if (files == null)
                {
                    continue;
                }

                for (File file : files)
                {
                    if (file.isFile())
                    {
                        if (file.getName().endsWith(endsWith))
                        {
                            result.add(file.getAbsolutePath().substring(location.getAbsolutePath().length() + 1));
                        }
                    }
                    else if (recurse && file.isDirectory())
                    {
                        stack.add(file);
                    }
                }
            }

            return result;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T loadClass(String classname) throws ClassNotFoundException
        {
            return (T) AppupClassLoader.this.loadClass(classname);
        }

        @Override
        public String getProperty(String name)
        {
            return System.getProperty(name);
        }

        @Override
        public ServiceRegistration registerService(String name, Object service, Dictionary<?, ?> props)
        {
            try
            {
                InitialContext context = new InitialContext();
                context.bind(name, service);
            }
            catch (NamingException e)
            {
                throw new RuntimeException(e);
            }

            return new ServiceRegistration()
            {
                @Override
                public void unregister()
                {
                }
            };
        }
    }
}
