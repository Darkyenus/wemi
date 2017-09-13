package wemi.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

/**
 * Class loader which forces classes to be loaded using this class loader if they match some prefix.
 *
 * This is useful for injecting dynamically loaded libraries which are compiled in as "provided".
 */
public final class ForceClassLoader extends URLClassLoader {

    private final String[] forceLoadedClassPrefixes;

    public ForceClassLoader(URL[] urls, ClassLoader parent, String ... forceLoadedClassPrefixes) {
        super(urls, parent);
        this.forceLoadedClassPrefixes = forceLoadedClassPrefixes;
    }

    private Class<?> forceLoadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> result = findLoadedClass(name);
        if (result == null) {
            final String resourceName = name.replace('.', '/').concat(".class");
            try (InputStream stream = getResourceAsStream(resourceName)) {
                byte[] buffer = new byte[2048];
                int offset = 0;
                while (true) {
                    final int read = stream.read(buffer, offset, buffer.length - offset);
                    if (read <= 0) break;
                    offset += read;
                    if (offset == buffer.length) {
                        buffer = Arrays.copyOf(buffer, buffer.length * 2);
                    }
                }

                result = defineClass(name, buffer, 0, offset);
            } catch (Exception ex) {
                throw new ClassNotFoundException("Could not force-load class " + name, ex);
            }
        }

        if (resolve) {
            resolveClass(result);
        }

        return result;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            for (String forceLoadedClassPrefix : forceLoadedClassPrefixes) {
                if (name.startsWith(forceLoadedClassPrefix)) {
                    return forceLoadClass(name, resolve);
                }
            }

            return super.loadClass(name, resolve);
        }
    }
}
