package org.osgi.framework;

import java.io.File;
import java.net.URL;
import java.util.Dictionary;

public interface Bundle
{
    String getSymbolicName();
    Dictionary<?, ?> getHeaders();
    Iterable<String> findPaths(String path, String filePattern, boolean recurse);
    URL getEntry(String entry);
    File getEntryFile(String entry);
    URL getResource(String entry);
    String getFile(String entry);
    File getLocation();
    <T> T loadClass(String classname) throws ClassNotFoundException;
}
