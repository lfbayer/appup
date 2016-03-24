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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Easy little functions for handling dependency injection.
 */
public final class InjectionElf
{
    private static final Logger LOGGER = Logger.getLogger(InjectionElf.class.getName());

    private InjectionElf()
    {

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
        LOGGER.fine("Injecting resources into instance: " + instance);
        for (Method method : instance.getClass().getMethods())
        {
            Class<?>[] types = method.getParameterTypes();
            Resource resource = method.getAnnotation(Resource.class);
            if (resource != null && types.length == 1)
            {
                String resourceName = resource.name();
                if (resourceName == null || resourceName.isEmpty())
                {
                    Class<?> type = resource.type();
                    if (type != null && type != Object.class)
                    {
                        resourceName = type.getName();
                    }
                    else
                    {
                        resourceName = types[0].getName();
                    }
                }

                LOGGER.fine(String.format("Injecting resource: %s#%s(%s)", instance.getClass().getName(), method.getName(), resourceName));

                Object value = InitialContext.doLookup(resourceName);
                if (value == null)
                {
                    throw new IllegalStateException(String.format("Injection resource missing for: %s#%s(%s)", instance.getClass().getName(), method.getName(), resourceName));
                }

                method.invoke(instance, value);
            }
        }
    }
}
