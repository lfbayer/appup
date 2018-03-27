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

import com.lbayer.appup.internal.InjectionElf;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.annotation.PostConstruct;
import javax.naming.NamingException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

public class ContribRegistry implements IContribRegistry
{
    private ClassLoader classLoader;

    public ContribRegistry(ClassLoader classLoader)
    {
        this.classLoader = classLoader;
    }

    public void initializeFromClassLoader() throws IOException
    {
        initializeResources("META-INF/plugin.xml");
        initializeResources("plugin.xml");
    }

    private void initializeResources(String name) throws IOException
    {
        Enumeration<URL> urls = classLoader.getResources(name);
        while (urls.hasMoreElements())
        {
            URL url = urls.nextElement();
            try (InputStream in = url.openStream())
            {
                URLConnection conn = url.openConnection();
                if (conn instanceof JarURLConnection)
                {
                    URL jarUrl = ((JarURLConnection) conn).getJarFileURL();
                    register(jarUrl, jarUrl.toString(), in);
                }
            }
        }
    }

    @Override
    public IContribElement[] getContribElementsFor(String contribTypeId)
    {
        List<ContribElement> results = contribs.get(contribTypeId);
        if (results == null)
        {
            return new IContribElement[0];
        }

        return results.toArray(new IContribElement[results.size()]);
    }

    private Map<String, List<ContribElement>> contribs = new HashMap<>();

    private List<ContribElement> createElements(URL ownerURL, String owner, Node extension)
    {
        List<ContribElement> result = new ArrayList<>();

        NodeList children = extension.getChildNodes();
        for (int k = 0; k < children.getLength(); k++)
        {
            Node elem = children.item(k);
            if (elem.getNodeType() == Node.ELEMENT_NODE)
            {
                List<ContribElement> childContribElements = null;
                if (elem.hasChildNodes())
                {
                    childContribElements = createElements(ownerURL, owner, elem);
                }

                NamedNodeMap attrs = elem.getAttributes();
                Map<String, String> attrsMap = new HashMap<>();
                for (int i = 0; i < attrs.getLength(); i++)
                {
                    Node attr = attrs.item(i);
                    attrsMap.put(attr.getNodeName(), attr.getNodeValue());
                }

                result.add(new ContribElement(ownerURL, owner, elem.getNodeName(), attrsMap, childContribElements));
            }
        }

        return result;
    }

    public void register(URL ownerURL, String owner, InputStream pluginConfig)
    {
        try
        {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document dom = db.parse(pluginConfig);

            NodeList extensions = dom.getElementsByTagName("extension"); // .getChildNodes();
            for (int j = 0; j < extensions.getLength(); j++)
            {
                Node extension = extensions.item(j);
                Node point = extension.getAttributes().getNamedItem("point");
                if (point == null)
                {
                    continue;
                }

                String contribTypeId = point.getNodeValue();

                List<ContribElement> contribElems = contribs.computeIfAbsent(contribTypeId, k -> new ArrayList<>());
                contribElems.addAll(createElements(ownerURL, owner, extension));
            }
        }
        catch (ParserConfigurationException | SAXException | IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private class ContribElement implements IContribElement
    {
        private final Map<String, String> attrs;
        private final String name;
        private final String owner;
        private final URL ownerURL;
        private final List<ContribElement> children;

        private ContribElement(URL ownerURL, String owner, String name, Map<String, String> attrs, List<ContribElement> children)
        {
            this.ownerURL = ownerURL;
            this.owner = owner;
            this.name = name;
            this.attrs = attrs;
            this.children = children;
        }

        @Override
        public URL getOwnerURL()
        {
            return ownerURL;
        }

        @Override
        public String getOwner()
        {
            return owner;
        }

        @Override
        public String getAttribute(String name)
        {
            return attrs.get(name);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T createInstance(String name) throws ContribException
        {
            try
            {
                T instance = (T) createClass(name).newInstance();
                InjectionElf.injectResources(instance);
                InjectionElf.invokeMethodsWithAnnotation(PostConstruct.class, instance);
                return instance;
            }
            catch (InstantiationException | IllegalAccessException | InvocationTargetException | NamingException e)
            {
                throw new ContribException(e);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Class<T> createClass(String name) throws ContribException
        {
            String classname = getAttribute(name);
            if (classname == null)
            {
                throw new ContribException("No such attribute: " + name);
            }

            if (classname.isEmpty())
            {
                throw new ContribException("Attribute is empty: " + name);
            }

            try
            {
                return (Class<T>) classLoader.loadClass(classname);
            }
            catch (ClassNotFoundException e)
            {
                throw new ContribException(e);
            }
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public IContribElement[] getChildren(String string)
        {
            return children.stream()
                    .filter((it) -> it.name.equals(string))
                    .toArray(IContribElement[]::new);
        }

        @Override
        public String toString()
        {
            return getName() + " : " + getOwnerURL();
        }
    }
}
