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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Native;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NativeCodeManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeCodeManager.class);

    public static final String OS;
    public static final String ARCH;
    private static final String OS_NAME;
    private static final String ARCH_NAME;

    private final File destinationDir;

    static
    {
        OS_NAME = System.getProperty("os.name").toLowerCase();
        if (OS_NAME.startsWith("mac"))
        {
            OS = "macosx";
        }
        else if (OS_NAME.startsWith("windows"))
        {
            OS = "win32";
        }
        else if (OS_NAME.startsWith("linux"))
        {
            OS = "linux";
        }
        else
        {
            OS = OS_NAME;
        }

        ARCH_NAME = System.getProperty("os.arch").toLowerCase();
        if (ARCH_NAME.equals("amd64"))
        {
            ARCH = "x86_64";
        }
        else
        {
            ARCH = ARCH_NAME;
        }
    }

    public NativeCodeManager(File destinationDir)
    {
        this.destinationDir = destinationDir;
    }

    public void initialize(URL[] urls) throws IOException
    {
        for (URL url : urls)
        {
            if (url.getPath().endsWith(".jar"))
            {
                try
                {
                    Path path = Paths.get(url.toURI());
                    try (JarFile jarFile = new JarFile(path.toFile()))
                    {
                        copyLibs(jarFile);
                    }
                }
                catch (URISyntaxException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void copyLibs(JarFile jf) throws IOException
    {
        Manifest manifest = jf.getManifest();
        if (manifest == null)
        {
            LOGGER.debug("Bundle has no manifest: {}", jf.getName());
            return;
        }

        Attributes attrs = manifest.getMainAttributes();
        String nativeCode = attrs.getValue("Bundle-NativeCode");
        if (nativeCode != null)
        {
            LOGGER.debug("Loading native code ({})...", jf.getName());

            importNativeCodeEntries(jf, nativeCode);
        }
    }

    private void importNativeCodeEntries(JarFile jf, String nativeCode) throws IOException
    {
        String[] codes = nativeCode.split(",");
        for (String codeEntry : codes)
        {
            String[] libAndExpression = codeEntry.split(";", 2);
            String libFile = libAndExpression[0];

            if (libAndExpression.length < 2 || NativeCodeRestriction.matches(libAndExpression[1]))
            {
                importLibrary(jf, libFile);
            }
        }
    }

    private void importLibrary(JarFile jf, String libFile) throws IOException
    {
        LOGGER.debug("Extracting library ({})...", libFile);

        ZipEntry libEntry = jf.getEntry(libFile);
        if (libEntry == null)
        {
            throw new RuntimeException(String.format("File '%s' was not found in '%s'", libFile, jf.getName()));
        }

        File tmpLibFile = new File(destinationDir, new File(libEntry.getName()).getName());
        tmpLibFile.deleteOnExit();

        copy(jf, libEntry, tmpLibFile);
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

    private static boolean matchesEitherRestriction(Set<String> restriction, String match, String match2)
    {
        return restriction.isEmpty() || restriction.contains(match) || restriction.contains(match2);
    }

    public static class NativeCodeRestriction
    {
        public static boolean matches(String clauseString)
        {
            Set<String> archRestrictions = new HashSet<>();
            Set<String> osRestrictions = new HashSet<>();

            // parse the restrictions
            for (String restriction : clauseString.split(";"))
            {
                String[] keyValue = restriction.split("=", 2);
                if (keyValue.length != 2)
                {
                    continue;
                }

                String key = keyValue[0].trim();
                String value = keyValue[1].trim().toLowerCase();
                if (value.startsWith("\""))
                {
                    value = value.substring(1);
                }

                if (value.endsWith("\""))
                {
                    value = value.substring(0, value.length() - 1);
                }

                switch (key)
                {
                case "osname":
                    osRestrictions.add(value);
                    break;

                case "processor":
                    archRestrictions.add(value);
                    break;

                default:
                    break;
                }
            }

            return matchesEitherRestriction(osRestrictions, OS, OS_NAME) && matchesEitherRestriction(archRestrictions, ARCH, ARCH_NAME);
        }
    }
}
