package wemi.util;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * ClassLoader that first checks own URLs before asking parent.
 *
 * This creates an enclave of classes that exist only inside this class loader.
 *
 * Some (not yet loaded!) classes of the parent class loader will probably want to be loaded using this
 * class loader, to force any classes used by them to be loaded by this class loader.
 * Common prefixes of names of those classes should be passed in [entryPoints].
 *
 * This is useful for injecting dynamically loaded libraries which are compiled in as "provided".
 */
public class EnclaveClassLoader extends URLClassLoader {

	private final ClassLoader parent;
	private final String[] entryPoints;

	/**
	 * @param entryPoints patterns that either specifies a class and its inner classes (e.g. `java.lang.String`)
	 *                    or pattern that specifies all classes in package (and its subpackages) (e.g. `java.lang.`)
	 */
	public EnclaveClassLoader(URL[] urls, ClassLoader parent, String ... entryPoints) {
		super(urls, parent);
		this.parent = parent;
		this.entryPoints = entryPoints;
	}


	private Class<?> forceLoadClass(String name) throws ClassNotFoundException {
		final String resourceName = name.replace('.', '/') + ".class";
		try (final InputStream stream = getResourceAsStream(resourceName)) {
			if (stream == null) {
				throw new ClassNotFoundException(name);
			}

			byte[] buffer = new byte[2048];

			int offset = 0;
			while (true) {
				int read = stream.read(buffer, offset, buffer.length - offset);
				if (read <= 0) break;
				offset += read;
				if (offset == buffer.length) {
					buffer = Arrays.copyOf(buffer, buffer.length * 2);
				}
			}

			return defineClass(name, buffer, 0, offset);
		} catch (IOException e) {
			throw new ClassNotFoundException("Could not force-load class " + name, e);
		}
	}

	private boolean isEntryPoint(String name) {
		for (String pattern : entryPoints) {
			if (pattern.equals(name)) {
				return true;
			}
			if (name.startsWith(pattern) && (name.charAt(pattern.length()) == '$' || pattern.charAt(pattern.length() - 1) == '.')) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		/* Follows the default implementation closely, but tries to load the class in itself first */
		synchronized (getClassLoadingLock(name)) {
			// First, check if the class has already been loaded
			Class<?> c = findLoadedClass(name);
			if (c == null) {
				if (isEntryPoint(name)) {
					c = forceLoadClass(name);
				} else {
					try {
						c = findClass(name);
					} catch (ClassNotFoundException e) {
						c = parent.loadClass(name);
					}
				}
			}
			if (resolve) {
				resolveClass(c);
			}
			return c;
		}
	}

	@Nullable
	@Override
	public URL getResource(String name) {
		/* Follows the default implementation closely, but tries to load the resource in itself first */
		URL url = findResource(name);
		if (url == null) {
			url = parent.getResource(name);
		}
		return url;
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		/* Follows the default implementation closely, but tries to load the resource in itself first */
		return new CompoundEnumeration<>(findResources(name), parent.getResources(name));
	}

	/** Same functionality as sun.misc.CompoundEnumeration */
	private static class CompoundEnumeration<E> implements Enumeration<E> {
		private final Enumeration<E>[] enumerations;
		private int index = 0;

		@SafeVarargs
		CompoundEnumeration(Enumeration<E>...enumerations) {
			this.enumerations = enumerations;
		}

		public boolean hasMoreElements() {
			while(this.index < this.enumerations.length) {
				if (this.enumerations[this.index] != null && this.enumerations[this.index].hasMoreElements()) {
					return true;
				}

				++this.index;
			}

			return false;
		}

		public E nextElement() {
			if (!this.hasMoreElements()) {
				throw new NoSuchElementException();
			}
			return this.enumerations[this.index].nextElement();
		}
	}
}
