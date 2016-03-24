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
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.Binding;
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

import com.lbayer.appup.internal.InjectionElf;

class AppupContext implements Context, EventContext
{
    private static final Logger LOGGER = Logger.getLogger(AppupContext.class.getName());

    private Map<String, List<AppupContext.Registration>> registrations;
    private Map<String, List<ObjectChangeListener>> listeners;

    public AppupContext()
    {
        registrations = new HashMap<>();
        listeners = new HashMap<>();
    }

    @Override
    public void addNamingListener(Name target, int scope, NamingListener l) throws NamingException
    {
        addNamingListener(target.toString(), scope, l);
    }

    @Override
    public void addNamingListener(String target, int scope, NamingListener l) throws NamingException
    {
        if (!(l instanceof ObjectChangeListener))
        {
            throw new UnsupportedOperationException("Unsupported listener type");
        }

        synchronized (listeners)
        {
            List<ObjectChangeListener> list = listeners.get(target);
            if (list == null)
            {
                list = new ArrayList<>();
                listeners.put(target, list);
            }

            list.add((ObjectChangeListener) l);
        }
    }

    @Override
    public void removeNamingListener(NamingListener l) throws NamingException
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
    public boolean targetMustExist() throws NamingException
    {
        return false;
    }

    @Override
    public void close() throws NamingException
    {
        synchronized (registrations)
        {
            registrations.clear();
        }
    }

    @Override
    public Object lookup(Name name) throws NamingException
    {
        return lookup(name.toString());
    }

    @Override
    public Object lookup(String name) throws NamingException
    {
        LOGGER.fine("Looking up " + name);
        synchronized (registrations)
        {
            List<AppupContext.Registration> result = registrations.get(name);
            if (result != null && !result.isEmpty())
            {
                return result.get(0).object;
            }
        }

        try
        {
            Class<?> clazz = Class.forName(name, true, Thread.currentThread().getContextClassLoader());
            ServiceLoader<?> services = ServiceLoader.load(clazz);
            Iterator<?> iter = services.iterator();
            if (iter.hasNext())
            {
                Object service = iter.next();

                // TODO: check or handle cyclic dependencies here.
                try
                {
                    InjectionElf.injectResources(service);
                }
                catch (IllegalAccessException | InvocationTargetException e)
                {
                    LOGGER.log(Level.WARNING, "Can't initialize instance for class: " + name, e);
                    throw new ConfigurationException("Unable to inject resources into instance: " + service);
                }

                bind(name, service);
                return service;
            }
        }
        catch (ClassNotFoundException e)
        {
            LOGGER.log(Level.WARNING, "Can't load class: " + name, e);
            throw new NameNotFoundException(name);
        }

        throw new NameNotFoundException(name);
    }

    @Override
    public void bind(Name name, Object obj) throws NamingException
    {
        bind(name.toString(), obj);
    }

    @Override
    public void bind(String name, Object obj) throws NamingException
    {
        LOGGER.fine("Binding " + name);

        Registration registration;

        synchronized (registrations)
        {
            List<AppupContext.Registration> result = registrations.get(name);
            if (result == null)
            {
                result = new ArrayList<>();
                registrations.put(name, result);
            }

            registration = new Registration(name, obj);
            result.add(registration);
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
    public void unbind(Name name) throws NamingException
    {
        unbind(name.toString());
    }

    @Override
    public void unbind(String name) throws NamingException
    {
        Registration registration;
        synchronized (registrations)
        {
            List<AppupContext.Registration> result = registrations.remove(name);
            if (result == null || result.isEmpty())
            {
                return;
            }

            if (result.size() > 1)
            {
                LOGGER.warning("More than one registration for this name: " + name);
            }

            registration = result.get(0);
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
            List<AppupContext.Registration> regs = registrations.get(name);
            if (regs == null || regs.isEmpty())
            {
                throw new NameNotFoundException(name);
            }

            return new RegistrationEnumeration(new ArrayList<>(regs).iterator());
        }
    }

    @Override
    public void rebind(Name name, Object obj) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rebind(String name, Object obj) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rename(Name oldName, Name newName) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rename(String oldName, String newName) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamingEnumeration<NameClassPair> list(Name name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamingEnumeration<NameClassPair> list(String name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroySubcontext(Name name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroySubcontext(String name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createSubcontext(Name name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context createSubcontext(String name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object lookupLink(Name name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object lookupLink(String name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NameParser getNameParser(Name name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NameParser getNameParser(String name) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Name composeName(Name name, Name prefix) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String composeName(String name, String prefix) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object addToEnvironment(String propName, Object propVal) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object removeFromEnvironment(String propName) throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Hashtable<?, ?> getEnvironment() throws NamingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNameInNamespace() throws NamingException
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
        public Binding next() throws NamingException
        {
            return nextElement();
        }

        @Override
        public boolean hasMoreElements()
        {
            return iterator.hasNext();
        }

        @Override
        public boolean hasMore() throws NamingException
        {
            return hasMoreElements();
        }

        @Override
        public void close() throws NamingException
        {
        }
    }

    private static class Registration
    {
        Object object;
        private String name;

        public Registration(String name, Object object)
        {
            this.name = name;
            this.object = object;
        }

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