package qupath.ext.axonpath.core;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.engine.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Helper class to manage access to PyTorch via Deep Java Library.
 */
public class PytorchManager {

    private static final Logger logger = LoggerFactory.getLogger(PytorchManager.class);

    private PytorchManager() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Get the PyTorch engine, downloading if necessary.
     * @return the engine if available, or null if this failed
     */
    public static Engine getEngineOnline() {
        try {
            return callOnline(() -> Engine.getEngine("PyTorch"));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get the available devices for PyTorch, as DJL device names: {@code "gpu0"}, {@code "gpu1"}, ...
     * for each GPU, {@code "mps"} on Apple Silicon, and always {@code "cpu"}.
     * @return Only "cpu" if no local engine is found.
     */
    public static Collection<String> getAvailableDevices() {
        try {
            Set<String> availableDevices = new LinkedHashSet<>();

            var engine = getEngineOffline();
            if (engine == null) {
                return List.of("cpu");
            }
            // This is expected to return GPUs if available, or CPU otherwise.
            // GPUs are named with their index (e.g. "gpu0"), which is the form Device.fromName() accepts.
            for (var device : engine.getDevices()) {
                String type = device.getDeviceType();
                availableDevices.add(Device.Type.GPU.equals(type) ? type + device.getDeviceId() : type);
            }
            // MPS needs an arm64 Mac (and an arm64 Java); Intel Macs have no MPS support in PyTorch
            if (GeneralTools.isMac() && "aarch64".equals(System.getProperty("os.arch"))) {
                availableDevices.add("mps");
            }
            // CPU should always be available
            availableDevices.add("cpu");

            return availableDevices;
        } catch (EngineException e) {
            logger.error("Unable to fetch engine", e);
            return List.of("cpu");
        }
    }

    /**
     * Query if the PyTorch engine is already available, without a need to download.
     * @return whether PyTorch is available.
     */
    public static boolean hasPyTorchEngine() {
        return getEngineOffline() != null;
    }

    /**
     * Get the PyTorch engine, without automatically downloading it if it isn't available.
     * @return the engine if available, or null otherwise
     */
    static Engine getEngineOffline() {
        try {
            return callOffline(() -> Engine.getEngine("PyTorch"));
        } catch (Exception e) {
            logger.debug("Failed to fetch offline engine", e);
            return null;
        }
    }

    /**
     * Call a function with the "offline" property set to true (to block automatic downloads).
     * @param callable The function to be called.
     * @return The return value of the callable.
     * @param <T> The return type of the callable.
     * @throws Exception If the callable does.
     */
    private static <T> T callOffline(Callable<T> callable) throws Exception {
        return callWithTempProperty("ai.djl.offline", "true", callable);
    }

    /**
     * Call a function with the "offline" property set to false (to allow automatic downloads).
     * @param callable The function to be called.
     * @return The return value of the callable.
     * @param <T> The return type of the callable.
     * @throws Exception If the callable does.
     */
    private static <T> T callOnline(Callable<T> callable) throws Exception {
        return callWithTempProperty("ai.djl.offline", "false", callable);
    }


    private static <T> T callWithTempProperty(String property, String value, Callable<T> callable) throws Exception {
        String oldValue = System.getProperty(property);
        System.setProperty(property, value);
        try {
            return callable.call();
        } finally {
            if (oldValue == null)
                System.clearProperty(property);
            else
                System.setProperty(property, oldValue);
        }
    }


}