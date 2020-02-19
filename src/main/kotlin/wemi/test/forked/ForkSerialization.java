package wemi.test.forked;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serialization utilities for communication with forked processes.
 * Pure Java with no dependencies.
 */
public final class ForkSerialization {

	public static void writeTo(DataOutputStream out, List<String> list) throws IOException {
		out.writeInt(list.size());
		for (String s : list) {
			out.writeUTF(s);
		}
	}

	public static void readFrom(DataInputStream in, List<String> list) throws IOException {
		list.clear();
		final int length = in.readInt();
		if (list instanceof ArrayList) {
			((ArrayList<String>) list).ensureCapacity(length);
		}
		for (int i = 0; i < length; i++) {
			list.add(in.readUTF());
		}
	}

	public static void writeTo(DataOutputStream out, Set<String> list) throws IOException {
		out.writeInt(list.size());
		for (String s : list) {
			out.writeUTF(s);
		}
	}

	public static void readFrom(DataInputStream in, Set<String> list) throws IOException {
		list.clear();
		final int length = in.readInt();
		for (int i = 0; i < length; i++) {
			list.add(in.readUTF());
		}
	}

	public static void writeTo(DataOutputStream out, Map<String, String> map) throws IOException {
		out.writeInt(map.size());
		for (Map.Entry<String, String> entry : map.entrySet()) {
			out.writeUTF(entry.getKey());
			out.writeUTF(entry.getValue());
		}
	}

	public static void readFrom(DataInputStream in, Map<String, String> map) throws IOException {
		map.clear();
		final int length = in.readInt();
		for (int i = 0; i < length; i++) {
			final String key = in.readUTF();
			final String value = in.readUTF();
			map.put(key, value);
		}
	}
}
