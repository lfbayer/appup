package org.osgi.framework;

import java.util.Dictionary;

public interface BundleContext
{
    Bundle getBundle();
    Bundle[] getBundles();
    String getProperty(String name);

    ServiceRegistration registerService(String name, Object service, Dictionary<?, ?> props);
}
