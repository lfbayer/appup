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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private final File destinationDir;

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

            String[] codes = nativeCode.split(",");
            for (String codeEntry : codes)
            {
                if (codeEntry.contains(OS) && codeEntry.contains(ARCH))
                {
                    LOGGER.debug("Native code matches ({})...", codeEntry);

                    String libFile = codeEntry.split(";", 2)[0];
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
}
