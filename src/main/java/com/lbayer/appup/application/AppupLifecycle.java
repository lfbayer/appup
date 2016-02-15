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

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.function.BiConsumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppupLifecycle
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AppupLifecycle.class);

    private final List<String> lifecycleNames;
    private final ClassLoader classLoader;

    private List<Object> lifecycleInstances;
    private List<Object> startedInstances;
    private BiConsumer<String, Throwable> errorHandler;

    public AppupLifecycle(ClassLoader classLoader, List<String> lifecycleNames)
    {
        this.classLoader = classLoader;
        this.lifecycleNames = lifecycleNames;
    }

    /**
     * Instantiate all lifecycles and then execute all {@link PostConstruct} methods on each instance.
     *
     * @return <code>true</code> if all lifecycle classes started successful, <code>false</code> if there were any failures
     */
    public boolean start()
    {
        if (startedInstances != null)
        {
            throw new IllegalStateException("Lifecycle already started");
        }

        boolean success = true;

        startedInstances = new ArrayList<>();

        if (lifecycleInstances == null)
        {
            lifecycleInstances = new ArrayList<>();
            for (String lifecycleName : lifecycleNames)
            {
                if (lifecycleName == null || lifecycleName.isEmpty())
                {
                    throw new RuntimeException("Empty lifecycleName");
                }

                String[] props = lifecycleName.split(";", 2);
                String className = props[0];
                if (props.length > 1 && !NativeCodeManager.NativeCodeRestriction.matches(props[1]))
                {
                    LOGGER.debug("Skipping lifecycle: {}", lifecycleName);
                    continue;
                }

                try
                {
                    Class<?> clazz = classLoader.loadClass(className);
                    Object instance = clazz.newInstance();
                    lifecycleInstances.add(instance);
                    LOGGER.debug("Added lifecycle: {}", lifecycleName);
                }
                catch (Throwable t)
                {
                    success = false;

                    if (errorHandler != null)
                    {
                        errorHandler.accept(lifecycleName, t);
                    }
                    else
                    {
                        throw new RuntimeException("Unable to load lifecycle class: " + lifecycleName, t);
                    }
                }
            }
        }

        for (Object lifecycleInstance : lifecycleInstances)
        {
            try
            {
                invokeMethodsWithAnnotation(PostConstruct.class, lifecycleInstance);
                startedInstances.add(lifecycleInstance);
            }
            catch (Throwable t)
            {
                success = false;
                if (errorHandler != null)
                {
                    errorHandler.accept(lifecycleInstance.getClass().getName(), t);
                }
                else
                {
                    LOGGER.error("Error starting lifecycle instance " + lifecycleInstance.getClass().getName(), t);
                }
            }
        }

        return success;
    }

    public void setErrorHandler(BiConsumer<String, Throwable> errorHandler)
    {
        this.errorHandler = errorHandler;
    }

    /**
     * Execute the {@link PreDestroy} methods on the lifecycle instances that were successfully started using the {@link #start()} method.
     *
     * Lifecycles instances are stopped in reverse order.
     */
    public void stop()
    {
        if (startedInstances == null)
        {
            throw new IllegalStateException("Lifecycle never started");
        }

        ListIterator<Object> iter = startedInstances.listIterator(startedInstances.size());
        while (iter.hasPrevious())
        {
            Object object = iter.previous();
            try
            {
                invokeMethodsWithAnnotation(PreDestroy.class, object);
            }
            catch (Throwable t)
            {
                if (errorHandler != null)
                {
                    errorHandler.accept(object.getClass().getName(), t);
                }
                else
                {
                    LOGGER.error("Error stopping lifecycle instance " + object.getClass().getName(), t);
                }
            }
        }
    }

    private static void invokeMethodsWithAnnotation(Class<? extends Annotation> annotation, Object object) throws InvocationTargetException, IllegalAccessException
    {
        for (Method m : object.getClass().getMethods())
        {
            if (m.getAnnotation(annotation) != null)
            {
                m.invoke(object);
            }
        }
    }
}
