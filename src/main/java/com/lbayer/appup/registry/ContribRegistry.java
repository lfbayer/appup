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

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;



public class ContribRegistry implements IContribRegistry
{
    private List<Document> doms;
    private ClassLoader classLoader;

    public ContribRegistry(ClassLoader classLoader)
    {
        doms = new ArrayList<>();
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
        List<IContribElement> results = new ArrayList<>();
        for (Document dom : doms)
        {
            String owner = (String) dom.getUserData("appup.contrib.owner");
            URL ownerURL = (URL) dom.getUserData("appup.contrib.ownerURL");

            NodeList extensions = dom.getElementsByTagName("extension"); // .getChildNodes();
            for (int j = 0; j < extensions.getLength(); j++)
            {
                Node extension = extensions.item(j);
                Node point = extension.getAttributes().getNamedItem("point");
                if (point != null && point.getNodeValue().equals(contribTypeId))
                {
                    NodeList children = extension.getChildNodes();
                    for (int k = 0; k < children.getLength(); k++)
                    {
                        Node elem = children.item(k);
                        if (elem.getNodeType() == Node.ELEMENT_NODE)
                        {
                            results.add(new ContribElement(ownerURL, owner, elem));
                        }
                    }
                }
            }
        }

        return results.toArray(new IContribElement[results.size()]);
    }

    public void register(URL ownerURL, String owner, InputStream pluginConfig)
    {
        try
        {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document dom = db.parse(pluginConfig);
            dom.setUserData("appup.contrib.owner", owner, null);
            dom.setUserData("appup.contrib.ownerURL", ownerURL, null);
            doms.add(dom);
        }
        catch (ParserConfigurationException | SAXException | IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private class ContribElement implements IContribElement
    {
        private final Node node;
        private final String owner;
        private final URL ownerURL;

        public ContribElement(URL ownerURL, String owner, Node node)
        {
            this.ownerURL = ownerURL;
            this.owner = owner;
            this.node = node;
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
            NamedNodeMap attrs = node.getAttributes();
            if (attrs == null)
            {
                return null;
            }

            Node item = attrs.getNamedItem(name);
            if (item != null)
            {
                return item.getNodeValue();
            }

            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T createInstance(String name) throws ContribException
        {
            try
            {
                return (T) createClass(name).newInstance();
            }
            catch (InstantiationException | IllegalAccessException e)
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
            return node.getNodeName();
        }

        @Override
        public IContribElement[] getChildren(String string)
        {
            List<IContribElement> results = new ArrayList<>();

            NodeList children = node.getChildNodes();
            for (int j = 0; j < children.getLength(); j++)
            {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE)
                {
                    if (string == null || child.getNodeName().equals(string))
                    {
                        results.add(new ContribElement(ownerURL, owner, children.item(j)));
                    }
                }
            }

            return results.toArray(new IContribElement[results.size()]);
        }

        @Override
        public String toString()
        {
            return getName() + " : " + getOwnerURL();
        }
    }
}
