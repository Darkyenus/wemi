package wemiplugin.jvmhotswap.agent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static wemiplugin.jvmhotswap.agent.AgentMain.log;

/**
 * Utilities for dealing with Java class file format.
 *
 * See <a href="https://docs.oracle.com/javase/specs/jvms/se10/html/jvms-4.html">class file specification</a>.
 */
public class BytecodeUtil {

    /**
     * @param data data of .class file
     * @return Class name, defined by the parameter class file, or null if fails to parse.
     * Returns in internal format: com/example/MyClass$Inner (see {@link #bytecodeToNormalClassName(String)} to convert).
     */
    public static String javaClassName(byte[] data) {
        try (DataInputStream cIn = new DataInputStream(new ByteArrayInputStream(data))) {
            final int magic = cIn.readInt();
            if (magic != 0xCAFEBABE) {
                log("Unrecognized .class header: "+magic);
                return null;
            }
            final int minor = cIn.readUnsignedShort();
            final int major = cIn.readUnsignedShort();
            if (major > 54 || (major == 54 && minor > 0)) {
                log("Unsupported .class version "+major+"."+minor+", name detection may fail");
            }

            final int constantPoolCount = cIn.readUnsignedShort();
            final Map<Integer, Integer> poolClassInfo = new HashMap<>();
            final Map<Integer, String> poolUtf8 = new HashMap<>();

            // The constant_pool table is indexed from 1 to constant_pool_count - 1.
            int constantIndex = 0;
            while (++constantIndex < constantPoolCount) {
                final int constantType = cIn.readUnsignedByte();
                switch (constantType) {
                    case 7:{ //Class
                        poolClassInfo.put(constantIndex, cIn.readUnsignedShort());
                        break;
                    }
                    case 9: //FieldRef
                    case 10: //MethodRef
                    case 11: { //InterfaceMethodRef
                        cIn.skipBytes(4);
                        break;
                    }
                    case 8:{ //String
                        cIn.skipBytes(2);
                        break;
                    }
                    case 3://Integer
                    case 4:{//Float
                        cIn.skipBytes(4);
                        break;
                    }
                    case 5: //Long
                    case 6:{//Double
                        cIn.skipBytes(8);
                        //TODO Valid?
                        constantIndex++;
                        break;
                    }
                    case 12:{//NameAndType
                        cIn.skipBytes(4);
                        break;
                    }
                    case 1:{//Utf8
                        //TODO Valid?
                        poolUtf8.put(constantIndex, cIn.readUTF());
                        break;
                    }
                    case 15:{//MethodHandle
                        cIn.skipBytes(3);
                        break;
                    }
                    case 16:{//MethodType
                        cIn.skipBytes(2);
                        break;
                    }
                    case 18:{//InvokeDynamic
                        cIn.skipBytes(4);
                        break;
                    }
                    case 19://Module
                    case 20:{//Package
                        cIn.skipBytes(2);
                        break;
                    }
                }
            }

            cIn.skipBytes(2);//Access flags
            final int thisClass = cIn.readUnsignedShort();
            final Integer thisClassNameIndex = poolClassInfo.get(thisClass);
            if (thisClassNameIndex == null) {
                log("Failed to read class file, no ClassInfo at constant pool index "+thisClass);
                return null;
            }
            final String className = poolUtf8.get(thisClassNameIndex);
            if (className == null) {
                log("Failed to read class file, no Utf8 at constant pool index "+thisClassNameIndex);
                return null;
            }
            return className;
        } catch (IOException e) {
            log("Failed to read class file", e);
            return null;
        }
    }

    /**
     * Converts internal class name representation (e.g. com/example/MyClass$Inner) to
     * representation used by {@link Class#forName(String)}.
     */
    public static String bytecodeToNormalClassName(String raw) {
        return raw.replace('/', '.');
    }

}
