package wemiplugin.jvmhotswap.agent;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * Entry point for the hotswap agent.
 *
 * Should not import anything outside JVM and this plugin.
 */
public class AgentMain {

    private static final String HOTSWAP_ITERATION_PROPERTY = "wemi.hotswap.iteration";

    /**
     * Called as an entry-point by JVM when attached at startup, with -javaagent switch.
     */
    public static void premain(String agentArgs, Instrumentation inst){
        initialize(agentArgs, inst);
    }

    /**
     * Called as an entry-point by JVM when attached to already running JVM.
     * NOTE: This mode is currently not used by the plugin.
     */
    public static void agentmain(String agentArgs, Instrumentation inst){
        initialize(agentArgs, inst);
    }

    static void log(String message) {
        System.err.println("[Wemi Hotswap Agent] "+message);
    }

    static void log(String message, Throwable e) {
        System.err.println("[Wemi Hotswap Agent] "+message);
        e.printStackTrace(System.err);
    }

    private static void initialize(String argumentArgs, final Instrumentation inst){
        final int port = Integer.parseInt(argumentArgs);
        System.setProperty(HOTSWAP_ITERATION_PROPERTY, "0");

        final Thread changeThread = new Thread("Hotswap listening thread"){
            @Override
            public void run() {
                try (final Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    log("Attached and listening on port "+port);

                    final DataInputStream in = new DataInputStream(socket.getInputStream());

                    ArrayList<ClassDefinition> pendingChanges = new ArrayList<>();
                    int iteration = 0;

                    while(socket.isConnected() && !socket.isClosed()){
                        final String fileString;
                        try {
                            fileString = in.readUTF();
                        } /*catch (EOFException e) { //TODO Check what happens on exit
                            break;
                        } */catch (IOException e) {
                            log("Failed to read next packet", e);
                            break;
                        }

                        if (!fileString.isEmpty()) {
                            final Path file = Paths.get(fileString);
                            final ClassDefinition definition = createReDefinition(file);
                            if(definition == null){
                                log("Failed to create class redefinition for "+file.toAbsolutePath());
                            }else{
                                pendingChanges.add(definition);
                            }
                        } else {
                            try {
                                inst.redefineClasses(pendingChanges.toArray(new ClassDefinition[0]));
                                System.setProperty(HOTSWAP_ITERATION_PROPERTY, Integer.toString(++iteration));
                            } catch (ClassNotFoundException e) {
                                log("Class for redefinition not found", e);
                            } catch (UnmodifiableClassException e) {
                                log("Class for redefinition not modifiable", e);
                            } catch (UnsupportedOperationException e){
                                log("Failed to redefine class", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    log("Hotswap listening thread crashed", e);
                }
            }
        };
        changeThread.setDaemon(true);
        changeThread.start();
    }

    private static ClassDefinition createReDefinition(Path from){
        try {
            final byte[] bytes = Files.readAllBytes(from);
            final String className = BytecodeUtil.javaClassName(bytes);
            if (className == null) {
                // Already logged
                return null;
            }

            final Class clazz = Class.forName(BytecodeUtil.bytecodeToNormalClassName(className));
            return new ClassDefinition(clazz, bytes);
        } catch (Exception e) {
            log("Failed to create definition for " + from.toAbsolutePath(), e);
            return null;
        }
    }
}
