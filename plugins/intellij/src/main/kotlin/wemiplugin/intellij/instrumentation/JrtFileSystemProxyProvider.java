package wemiplugin.intellij.instrumentation;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * The FileSystemProvider API does not allow to add more filesystem providers lazily,
 * so we have to eagerly put in a shim that does the lazy loading.
 */
public class JrtFileSystemProxyProvider extends FileSystemProvider {

	@Override
	public String getScheme() {
		return "jrt";
	}

	private FileSystemProvider targetCache = null;

	private FileSystemProvider target() {
		if (targetCache == null) {
			try {
				targetCache = (FileSystemProvider) Class.forName("jdk.internal.jrtfs.JrtFileSystemProvider").newInstance();
			} catch (Exception e) {
				throw new RuntimeException("JrtFileSystemProvider not found");
			}
		}
		return targetCache;
	}

	@Override
	public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
		return target().newFileSystem(uri, env);
	}

	@Override
	public FileSystem getFileSystem(URI uri) {
		return target().getFileSystem(uri);
	}

	@NotNull
	@Override
	public Path getPath(@NotNull URI uri) {
		return target().getPath(uri);
	}

	@Override
	public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
		return target().newByteChannel(path, options, attrs);
	}

	@Override
	public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
		return target().newDirectoryStream(dir, filter);
	}

	@Override
	public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
		target().createDirectory(dir, attrs);
	}

	@Override
	public void delete(Path path) throws IOException {
		target().delete(path);
	}

	@Override
	public void copy(Path source, Path target, CopyOption... options) throws IOException {
		target().copy(source, target, options);
	}

	@Override
	public void move(Path source, Path target, CopyOption... options) throws IOException {
		target().move(source, target, options);
	}

	@Override
	public boolean isSameFile(Path path, Path path2) throws IOException {
		return target().isSameFile(path, path2);
	}

	@Override
	public boolean isHidden(Path path) throws IOException {
		return target().isHidden(path);
	}

	@Override
	public FileStore getFileStore(Path path) throws IOException {
		return target().getFileStore(path);
	}

	@Override
	public void checkAccess(Path path, AccessMode... modes) throws IOException {
		target().checkAccess(path, modes);
	}

	@Override
	public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
		return target().getFileAttributeView(path, type, options);
	}

	@Override
	public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
		return target().readAttributes(path, type, options);
	}

	@Override
	public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
		return target().readAttributes(path, attributes, options);
	}

	@Override
	public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
		target().setAttribute(path, attribute, value, options);
	}
}
