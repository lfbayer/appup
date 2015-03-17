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
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.lbayer.appup.registry.AppupInitialContextFactory;
import com.lbayer.appup.registry.ContribRegistry;
import com.lbayer.appup.registry.IContribRegistry;

public class AppupLauncher
{
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
            launcher.launch(configFile);
        }
        catch (Throwable t)
        {
            System.err.println("Error starting application.");
            t.printStackTrace();
        }
    }

    public void launch(File configFile) throws Exception
    {
        System.setProperty("appup.startTime", Long.toString(System.currentTimeMillis()));

        if (configFile != null)
        {
            try (InputStream in = new FileInputStream(configFile))
            {
                System.getProperties().load(in);
            }
        }

        uriify("osgi.configuration.area");
        uriify("osgi.install.area");

        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, AppupInitialContextFactory.class.getName());

        if (Boolean.parseBoolean(System.getProperty("appup.clearTmp", "true")))
        {
            clearTmpDir();
        }

        AppupClassLoader runtime = new AppupClassLoader(AppupClassLoader.class.getClassLoader());

        Thread.currentThread().setContextClassLoader(runtime);

        InitialContext ctxt = new InitialContext();
        ctxt.bind(IContribRegistry.class.getName(), new ContribRegistry(runtime));
        ctxt.bind(IAppupRuntime.class.getName(), runtime);

        String cp = System.getProperty("appup.cp");
        if (cp != null)
        {
            List<File> toStart = new ArrayList<>();
            for (String entry : cp.split(","))
            {
                String[] s = entry.split("@");
                File file = new File(s[0]);
                if (s.length > 1)
                {
                    toStart.add(file);
                }

                runtime.install(file);
            }

            toStart.stream().forEachOrdered(runtime::start);
        }

        List<IApplication> applications = new ArrayList<>();
        ServiceLoader.load(IApplication.class, runtime).forEach(applications::add);

        for (IApplication application : applications)
        {
            application.start(runtime);
        }

        for (IApplication application : applications)
        {
            application.stop();
        }
    }

    private static void uriify(String prop)
    {
        try
        {
            String orig = System.getProperty(prop);
            File file = new File(orig);
            String value = file.getCanonicalFile().toURI().toString();
            System.setProperty(prop, value);

            System.err.println(String.format("Overwrote property %s: %s -> %s", prop, orig, value));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Delete all of the immediate children of java.io.tmpdir
     */
    private static void clearTmpDir()
    {
        String tmpDir = System.getProperty("java.io.tmpdir");
        if (tmpDir != null)
        {
            File file = new File(tmpDir);
            File[] listFiles = file.listFiles();
            if (listFiles != null)
            {
                for (File tmp : listFiles)
                {
                    try
                    {
                        tmp.delete();
                    }
                    catch (Throwable t)
                    {
                        // do nothing.
                    }
                }
            }
        }
    }
}
