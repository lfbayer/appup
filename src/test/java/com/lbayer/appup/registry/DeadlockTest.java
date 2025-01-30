package com.lbayer.appup.registry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.naming.NamingException;

import org.junit.Test;

public class DeadlockTest
{
    @Test
    public void testServiceStartDeadlock() throws Exception
    {
        // the original bug would occur if listBindings and lookup were called concurrently and
        // the following seam was hit:
        //
        // [A] a call to lookupMultiple made
        // [A] writeLock acquired
        // _B_ listBindings called
        // _B_   registrations lock acquired
        // _B_   lookupMultiple called
        // _B_     writeLock attempted (BLOCKED)
        // [A] initializeService called
        // [A]   bind called
        // [A]     registrations lock attempted (BLOCKED)

        @SuppressWarnings("resource") // we don't want to close the executor, as that would cause us to block on failure
        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 1000; i++)
        {
            AppupContext context = new AppupContext();
            Future<?> a = executor.submit(() -> {
                try
                {
                    context.listBindings(ArbitraryService.class.getName());
                }
                catch (NamingException e)
                {
                    throw new RuntimeException(e);
                }
            });
            Future<?> b = executor.submit(() -> {
                try
                {
                    context.lookup(ArbitraryService.class.getName());
                }
                catch (NamingException e)
                {
                    throw new RuntimeException(e);
                }
            });

            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
    }

    @Resource
    public static class ArbitraryService
    {
    }
}
