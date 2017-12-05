/*
 * Copyright (C) 2016 Leo Bayer
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
package com.lbayer.appup.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.annotation.Resource;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Easy little functions for handling dependency injection.
 */
public final class InjectionElf
{
    private static final Logger LOGGER = LoggerFactory.getLogger(InjectionElf.class);

    private InjectionElf()
    {

    }

    public static void invokeMethodsWithAnnotation(Class<? extends Annotation> annotation, Object object) throws InvocationTargetException, IllegalAccessException
    {
        for (Method m : object.getClass().getMethods())
        {
            if (m.getAnnotation(annotation) != null)
            {
                m.invoke(object);
            }
        }
    }

    /**
     * Finds methods on the given instance that are marked with the {@link Resource} annotation, and calls those methods by looking up the type in the {@link InitialContext}.
     * @param instance The instance to inject resources into
     * @throws NamingException If the context lookup fails for a resource
     * @throws IllegalAccessException If a resource method is no accessable
     * @throws InvocationTargetException On an exception invoking the resource methods
     */
    public static void injectResources(Object instance) throws NamingException, IllegalAccessException, InvocationTargetException
    {
        LOGGER.trace("Injecting resources into instance: {}", instance);

        injectResourcesForClass(instance, instance.getClass());
    }

    private static void injectResourcesForClass(Object instance, Class<?> clazz) throws NamingException, IllegalAccessException, InvocationTargetException
    {
        // inject into the super class first
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null)
        {
            injectResourcesForClass(instance, superclass);
        }
        else
        {
            return;
        }

        for (Field field : clazz.getDeclaredFields())
        {
            Resource resource = field.getAnnotation(Resource.class);
            if (resource != null)
            {
                String resourceName = getResourceName(resource, field.getType());

                LOGGER.debug("Injecting resource in field: {}#{}({})", instance.getClass().getName(), field.getName(), resourceName);

                Object value;
                try
                {
                    value = InitialContext.doLookup(resourceName);
                }
                catch (NamingException e)
                {
                    throw new IllegalStateException(String.format("Injection resource missing for field: %s#%s(%s)", instance.getClass().getName(), field.getName(), resourceName), e);
                }

                if (!field.isAccessible())
                {
                    field.setAccessible(true);
                }

                field.set(instance, value);
            }
        }

        for (Method method : clazz.getDeclaredMethods())
        {
            Class<?>[] types = method.getParameterTypes();
            Resource resource = method.getAnnotation(Resource.class);
            if (resource != null && types.length == 1)
            {
                String resourceName = getResourceName(resource, types[0]);

                LOGGER.debug("Injecting resource: {}#{}({})", instance.getClass().getName(), method.getName(), resourceName);

                Object value;
                try
                {
                    value = InitialContext.doLookup(resourceName);
                }
                catch (NamingException e)
                {
                    throw new IllegalStateException(String.format("Injection resource missing for: %s#%s(%s)", instance.getClass().getName(), method.getName(), resourceName), e);
                }

                if (!method.isAccessible())
                {
                    method.setAccessible(true);
                }

                method.invoke(instance, value);
            }
        }
    }

    private static String getResourceName(Resource resource, Class<?> parameterType)
    {
        String resourceName = resource.name();
        if (resourceName.isEmpty())
        {
            Class<?> type = resource.type();
            if (type != Object.class)
            {
                resourceName = type.getName();
            }
            else
            {
                resourceName = parameterType.getName();
            }
        }

        return resourceName;
    }
}
