package com.lbayer.appup.registry;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ContribRegistryTest
{
    private URL getPluginURL()
    {
        return getClass().getResource("/plugin.xml");
    }

    private ContribRegistry loadRegistry() throws IOException
    {
        ContribRegistry registry = new ContribRegistry(getClass().getClassLoader());
        registry.initializeFromClassLoader();

        URL resource = getPluginURL();
        try (InputStream in = resource.openStream())
        {
            registry.register(resource, "test", in);
        }

        return registry;
    }

    @Test
    public void testObject() throws Throwable
    {
        ContribRegistry registry = loadRegistry();
        IContribElement[] elements = registry.getContribElementsFor("object");
        Assert.assertEquals(1, elements.length);
        Assert.assertEquals(Object.class, elements[0].createClass("class"));

        Object obj = elements[0].createInstance("class");
        Assert.assertNotNull(obj);
        Assert.assertEquals(Object.class, obj.getClass());
    }

    @Test(expected = ContribException.class)
    public void testBadClass() throws ContribException, IOException
    {
        ContribRegistry registry = loadRegistry();
        IContribElement[] elements = registry.getContribElementsFor("bad-object");
        Assert.assertEquals(1, elements.length);

        elements[0].createInstance("class");
    }

    @Test
    public void testNone() throws Throwable
    {
        ContribRegistry registry = loadRegistry();
        IContribElement[] elements = registry.getContribElementsFor("none");
        Assert.assertEquals(0, elements.length);
    }

    @Test
    public void testNested() throws Throwable
    {
        ContribRegistry registry = loadRegistry();

        IContribElement[] nested = registry.getContribElementsFor("nested");
        Assert.assertEquals(1, nested.length);

        IContribElement top = nested[0];
        Assert.assertEquals("top-value", top.getAttribute("value"));
        Assert.assertEquals("top", top.getName());
        Assert.assertEquals("test", top.getOwner());
        Assert.assertEquals(getPluginURL(), top.getOwnerURL());

        IContribElement[] empty = top.getChildren("empty");
        Assert.assertEquals(1, empty.length);

        IContribElement[] middle = top.getChildren("middle");
        Assert.assertEquals(1, middle.length);
        Assert.assertEquals("middle-value", middle[0].getAttribute("value"));
        Assert.assertEquals("middle", middle[0].getName());
        Assert.assertEquals(getPluginURL(), middle[0].getOwnerURL());

        IContribElement[] bottom = middle[0].getChildren("bottom");
        Assert.assertEquals(1, bottom.length);
        Assert.assertEquals("bottom-value", bottom[0].getAttribute("value"));
        Assert.assertEquals("bottom", bottom[0].getName());
    }

    @Test
    public void testConcurrent() throws Throwable
    {
        ContribRegistry registry = loadRegistry();

        runAndWait(20, () -> {
            IContribElement[] elems = registry.getContribElementsFor("org.ziptie.server.core.jobs.backupComplete");
            Assert.assertEquals(1, elems.length);
            Assert.assertEquals("org.ziptie.server.job.backup.BackupResultsTrapSender", elems[0].getAttribute("class"));
        });
    }

    private void runAndWait(int concurrency, Runnable task) throws Throwable
    {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++)
        {
            futures.add(pool.submit(task));
        }

        for (Future<?> future : futures)
        {
            try
            {
                future.get();
            }
            catch (ExecutionException e)
            {
                throw e.getCause();
            }
        }

        pool.shutdown();
    }
}
