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
package com.lbayer.appup.registry;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.ConfigurationException;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.event.EventContext;
import javax.naming.event.NamingEvent;
import javax.naming.event.NamingListener;
import javax.naming.event.ObjectChangeListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.lbayer.appup.internal.InjectionElf.injectResources;
import static com.lbayer.appup.internal.InjectionElf.invokeMethodsWithAnnotation;

class AppupContext implements Context, EventContext
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AppupContext.class);

    private final Map<String, List<AppupContext.Registration>> registrations;
    private final Map<String, List<ObjectChangeListener>> listeners;

    /** Lock that only allows a single service to be instantiated and initialized at a time */
    private final ReentrantLock writeLock = new ReentrantLock();

    private final ThreadLocal<Set<String>> currentLookups = ThreadLocal.withInitial(LinkedHashSet::new);

    AppupContext()
    {
        registrations = new HashMap<>();
        listeners = new HashMap<>();
    }

    @Override
    public void addNamingListener(Name target, int scope, NamingListener l)
    {
        addNamingListener(target.toString(), scope, l);
    }

    @Override
    public void addNamingListener(String target, int scope, NamingListener l)
    {
        if (!(l instanceof ObjectChangeListener))
        {
            throw new UnsupportedOperationException("Unsupported listener type");
        }

        synchronized (listeners)
        {
            List<ObjectChangeListener> list = listeners.computeIfAbsent(target, k -> new ArrayList<>());

            list.add((ObjectChangeListener) l);
        }
    }

    @Override
    public void removeNamingListener(NamingListener l)
    {
        synchronized (listeners)
        {
            for (List<ObjectChangeListener> entries : listeners.values())
            {
                Iterator<ObjectChangeListener> iter = entries.iterator();
                while (iter.hasNext())
                {
                    if (iter.next() == l)
                    {
                        iter.remove();
                        return;
                    }
                }
            }
        }
    }

    @Override
    public boolean targetMustExist()
    {
        return false;
    }

    @Override
    public void close()
    {
        writeLock.lock();
        try
        {
            synchronized (registrations)
            {
                LOGGER.debug("AppupContext closing");
                registrations.clear();
            }
        }
        finally
        {
            writeLock.unlock();
        }
    }

    @Override
    public Object lookup(Name name) throws NamingException
    {
        String key;

        int last = name.size() - 1;
        if (name.getPrefix(last).toString().equals("java:comp/env"))
        {
            key = name.get(last);

            LOGGER.debug("Name translated for lookup: {} -> {}", name, key);
        }
        else
        {
            key = name.toString();
        }

        return lookup(key);
    }

    private List<Object> getRegisteredObjects(String name)
    {
        synchronized (registrations)
        {
            List<AppupContext.Registration> result = registrations.get(name);
            if (result != null && !result.isEmpty())
            {
                List<Object> objects = new ArrayList<>(result.size());
                for (AppupContext.Registration registration : result)
                {
                    objects.add(registration.object);
                }

                return objects;
            }
        }

        return null;
    }

    @Override
    public Object lookup(String name) throws NamingException
    {
        return lookupMultiple(name).getFirst();
    }

    private void initializeService(String name, Object service) throws NamingException
    {
        try
        {
            injectResources(service);
            invokeMethodsWithAnnotation(PostConstruct.class, service);
        }
        catch (IllegalAccessException | InvocationTargetException e)
        {
            ConfigurationException exception = new ConfigurationException("Unable to inject resources into instance: " + service);
            exception.setRootCause(e);
            throw exception;
        }

        bind(name, service);
    }

    private List<Object> lookupMultiple(String name) throws NamingException
    {
        LOGGER.debug("Looking up {}", name);

        List<Object> registeredObjects = getRegisteredObjects(name);
        if (registeredObjects != null)
        {
            return registeredObjects;
        }

        // we add the current name to the ThreadLocal of currentLookups so that we can detect recursive calls to lookup for the same resource.
        if (!currentLookups.get().add(name))
        {
            // If we get inside here it indicates a dependency cycle in the Resource injections.
            throw new ConfigurationException("Resource dependency cycle detected for object: " + name + "\n"
                                                     + String.join("->", currentLookups.get()));
        }

        try
        {
            writeLock.lock();
            try
            {
                // check again, since it might have been added before we acquired this lock.
                registeredObjects = getRegisteredObjects(name);
                if (registeredObjects != null)
                {
                    return registeredObjects;
                }

                Class<?> clazz = Class.forName(name, true, Thread.currentThread().getContextClassLoader());

                ServiceLoader<?> services = ServiceLoader.load(clazz);
                Iterator<?> iter = services.iterator();
                if (iter.hasNext())
                {
                    LOGGER.debug("Creating service from SPI: {}", name);

                    List<Object> result = new ArrayList<>();
                    do
                    {
                        Object service = iter.next();
                        initializeService(name, service);
                        result.add(service);
                    } while (iter.hasNext());

                    return result;
                }
                else if (!clazz.isInterface())
                {
                    Resource resource = clazz.getAnnotation(Resource.class);
                    if (resource == null)
                    {
                        throw new NameNotFoundException(name);
                    }

                    try
                    {
                        LOGGER.debug("Creating class from class annotation: {}", name);
                        Object service = clazz.getConstructor().newInstance();

                        initializeService(name, service);

                        return Collections.singletonList(service);
                    }
                    catch (ReflectiveOperationException e)
                    {
                        ConfigurationException exception = new ConfigurationException("Unable to create service instance: " + name);
                        exception.setRootCause(e);
                        throw exception;
                    }
                }
                else
                {
                    throw new NameNotFoundException(name);
                }
            }
            finally
            {
                writeLock.unlock();
            }
        }
        catch (ClassNotFoundException e)
        {
            NameNotFoundException exception = new NameNotFoundException(name);
            exception.setRootCause(e);
            throw exception;
        }
        finally
        {
            currentLookups.get().remove(name);
        }
    }

    @Override
    public void bind(Name name, Object obj)
    {
        bind(name.toString(), obj);
    }

    @Override
    public void bind(String name, Object obj)
    {
        LOGGER.debug("Binding {}", name);

        Registration registration;

        writeLock.lock();
        try
        {
            synchronized (registrations)
            {
                List<AppupContext.Registration> result = registrations.computeIfAbsent(name, k -> new ArrayList<>());
                registration = new Registration(name, obj);
                result.add(registration);
            }
        }
        finally
        {
            writeLock.unlock();
        }

        synchronized (listeners)
        {
            List<ObjectChangeListener> l = listeners.get(name);
            if (l != null)
            {
                for (ObjectChangeListener listener : l)
                {
                    listener.objectChanged(new NamingEvent(this, NamingEvent.OBJECT_ADDED, registration.toBinding(), null, null));
                }
            }
        }
    }

    @Override
    public void unbind(Name name)
    {
        unbind(name.toString());
    }

    @Override
    public void unbind(String name)
    {
        Registration registration;

        writeLock.lock();
        try
        {
            synchronized (registrations)
            {
                List<AppupContext.Registration> result = registrations.remove(name);
                if (result == null || result.isEmpty())
                {
                    return;
                }

                if (result.size() > 1)
                {
                    LOGGER.warn("More than one registration for this name: {}", name);
                }

                registration = result.getFirst();
            }
        }
        finally
        {
            writeLock.unlock();
        }

        synchronized (listeners)
        {
            List<ObjectChangeListener> l = listeners.get(name);
            if (l != null)
            {
                for (ObjectChangeListener listener : l)
                {
                    listener.objectChanged(new NamingEvent(this, NamingEvent.OBJECT_REMOVED, null, registration.toBinding(), null));
                }
            }
        }
    }

    @Override
    public NamingEnumeration<Binding> listBindings(Name name) throws NamingException
    {
        return listBindings(name.toString());
    }

    @Override
    public NamingEnumeration<Binding> listBindings(String name) throws NamingException
    {
        synchronized (registrations)
        {
            // force lookup
            lookupMultiple(name);

            List<AppupContext.Registration> regs = registrations.get(name);
            if (regs == null || regs.isEmpty())
            {
                throw new NameNotFoundException(name);
            }

            return new RegistrationEnumeration(new ArrayList<>(regs).iterator());
        }
    }

    @Override
    public NameParser getNameParser(Name name)
    {
        return AppupNameParser.NAME_PARSER;
    }

    @Override
    public NameParser getNameParser(String name)
    {
        return AppupNameParser.NAME_PARSER;
    }

    @Override
    public void rebind(Name name, Object obj)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rebind(String name, Object obj)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rename(Name oldName, Name newName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rename(String oldName, String newName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamingEnumeration<NameClassPair> list(Name name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamingEnumeration<NameClassPair> list(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroySubcontext(Name name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroySubcontext(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createSubcontext(Name name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createSubcontext(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object lookupLink(Name name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object lookupLink(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Name composeName(Name name, Name prefix)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String composeName(String name, String prefix)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object addToEnvironment(String propName, Object propVal)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object removeFromEnvironment(String propName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Hashtable<?, ?> getEnvironment()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNameInNamespace()
    {
        throw new UnsupportedOperationException();
    }

    private static class RegistrationEnumeration implements NamingEnumeration<Binding>
    {
        Iterator<Registration> iterator;

        public RegistrationEnumeration(Iterator<Registration> iterator)
        {
            this.iterator = iterator;
        }

        @Override
        public Binding nextElement()
        {
            return iterator.next().toBinding();
        }

        @Override
        public Binding next()
        {
            return nextElement();
        }

        @Override
        public boolean hasMoreElements()
        {
            return iterator.hasNext();
        }

        @Override
        public boolean hasMore()
        {
            return hasMoreElements();
        }

        @Override
        public void close()
        {
        }
    }

    private static class AppupNameParser implements NameParser
    {
        private static final AppupNameParser NAME_PARSER = new AppupNameParser();

        @Override
        public Name parse(String name) throws NamingException
        {
            return new CompositeName(name);
        }
    }

    private record Registration(String name, Object object)
    {
        public Binding toBinding()
        {
            return new Binding(name, object.getClass().getName(), object);
        }

        @Override
        public String toString()
        {
            return name + "(" + object.getClass().getName() + ")";
        }
    }
}
