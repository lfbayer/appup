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
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

import javax.naming.Context;
import javax.naming.InitialContext;

import com.lbayer.appup.registry.AppupInitialContextFactory;
import com.lbayer.appup.registry.ContribRegistry;
import com.lbayer.appup.registry.IContribRegistry;

public class AppupLauncher
{
    private static final String LIB_PATH = "java.library.path";

    private Semaphore exitSemaphore = new Semaphore(0);

    public static void main(String[] args)
    {
        int i = 0;
        File configFile = null;
        while (i < args.length)
        {
            switch (args[i])
            {
            case "-c":
                i++;
                if (i >= args.length)
                {
                    System.err.println("Invalid arguments");
                    System.exit(10);
                }

                configFile = new File(args[i]);
                if (!configFile.isFile())
                {
                    System.err.println("No such file: " + configFile);
                    System.exit(10);
                }
                break;
            default:
                break;
            }

            i++;
        }

        AppupLauncher launcher = new AppupLauncher();
        try
        {
            int result = launcher.launch(configFile, args);
//            System.exit(result);
        }
        catch (Throwable t)
        {
            System.err.println("Error starting application.");
            t.printStackTrace();
//            System.exit(1);
        }
    }

    public int launch(File configFile, String[] arguments) throws Exception
    {
        System.setProperty(IAppupRuntime.PROP_STARTTIME, Long.toString(System.currentTimeMillis()));

        if (configFile != null)
        {
            try (InputStream in = new FileInputStream(configFile))
            {
                System.getProperties().load(in);
            }
        }

        File confDir = new File(System.getProperty(IAppupRuntime.PROP_CONFDIR, "config"));
        System.setProperty(IAppupRuntime.PROP_CONFDIR, confDir.getAbsolutePath());
        System.setProperty("osgi.configuration.area", confDir.getCanonicalFile().toURI().toString());

        File installArea = new File(System.getProperty("osgi.install.area", "."));
        System.setProperty("osgi.install.area", installArea.getCanonicalFile().toURI().toString());

        if (System.getProperty(Context.INITIAL_CONTEXT_FACTORY) == null)
        {
            System.setProperty(Context.INITIAL_CONTEXT_FACTORY, AppupInitialContextFactory.class.getName());
        }

        importProperties();

        URL[] urls = ((URLClassLoader) getClass().getClassLoader()).getURLs();

        File libsDir = new File(System.getProperty(IAppupRuntime.PROP_LIBDIR, ".lib"));
        libsDir.mkdirs();
        System.setProperty(LIB_PATH, libsDir.getPath() + File.pathSeparator + System.getProperty(LIB_PATH));

        NativeCodeManager nativeCodeManager = new NativeCodeManager(libsDir);
        nativeCodeManager.initialize(urls);

        System.setProperty("osgi.os", NativeCodeManager.OS);
        System.setProperty("osgi.arch", NativeCodeManager.ARCH);

        ContribRegistry contribRegistry = new ContribRegistry(getClass().getClassLoader());
        contribRegistry.initializeFromClassLoader();

        InitialContext ctxt = new InitialContext();
        ctxt.bind(IContribRegistry.class.getName(), contribRegistry);

        String[] classnames = getInterpolatedSystemProperty(IAppupRuntime.PROP_STARTCLASSES).split(",");
        AppupLifecycle lifecycle = new AppupLifecycle(getClass().getClassLoader(), Arrays.asList(classnames));
        lifecycle.setErrorHandler((name, t) -> {
            System.err.println("Error in " + name);
            t.printStackTrace();
        });

        try
        {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try
                {
                    // hold shutdown until the main thread has exited
                    exitSemaphore.acquire();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }));

            lifecycle.start();
            lifecycle.stop();

            return 0;
        }
        finally
        {
            exitSemaphore.release();
        }
    }

    private void importProperties() throws IOException
    {
        String propertyFiles = System.getProperty("appup.propertiesFiles");
        if (propertyFiles != null)
        {
            for (String entry : propertyFiles.split(","))
            {
                File file = new File(interpolateString(entry));
                try (InputStream in = new FileInputStream(file))
                {
                    System.getProperties().load(in);
                }
            }
        }
    }

    private static String getInterpolatedSystemProperty(String prop)
    {
        return interpolateString(System.getProperty(prop, ""));
    }

    private static String interpolateString(String input)
    {
        StringBuilder result = new StringBuilder();

        int index = 0;
        int start;

        while ((start = input.indexOf('{', index)) >= 0)
        {
            result.append(input.substring(index, start));

            int end = input.indexOf('}', start);
            if (end <= start)
            {
                throw new IllegalArgumentException("Bad configuration string: " + input);
            }

            String prop = input.substring(start + 1, end);

            result.append(System.getProperty(prop, ""));

            index = end + 1;
        }

        result.append(input.substring(index));

        return result.toString();
    }
}
