package qupath.ext.axonpath.core;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Device names and device selection for PyTorch inference, shared by the AxonPath panel and
 * the scripting API so that both accept the same names and fall back to CPU the same way.
 * <p>
 * Device names follow Deep Java Library (DJL): {@code "cpu"}, {@code "gpu0"}, {@code "gpu1"}, ...
 * and {@code "mps"} (Apple Silicon).
 */
public final class DeviceTools {
    private static final Logger logger = LoggerFactory.getLogger(DeviceTools.class);

    public static final String CPU = "cpu";

    // "gpu", "gpu1" or the older "gpu:1" form (documented in AxonPath 0.1.0/0.1.1 scripts)
    private static final Pattern GPU_NAME = Pattern.compile("gpu:?(\\d*)");

    private DeviceTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Converts a device name to the form DJL accepts.
     * <ul>
     *     <li>{@code null} or blank: {@code "cpu"}</li>
     *     <li>{@code "gpu"}: {@code "gpu0"}, the first GPU</li>
     *     <li>{@code "gpu:1"}: {@code "gpu1"}; DJL rejects the colon form</li>
     * </ul>
     * Names are lower-cased; anything else is returned as it is.
     */
    public static String normalise(String device) {
        if (device == null || device.isBlank())
            return CPU;
        String name = device.trim().toLowerCase(Locale.ROOT);
        var matcher = GPU_NAME.matcher(name);
        if (matcher.matches())
            return "gpu" + (matcher.group(1).isEmpty() ? "0" : Integer.parseInt(matcher.group(1)));
        return name;
    }

    /**
     * Returns the normalised device if it is in {@code available}, otherwise CPU (with a warning).
     *
     * @param requested the device asked for, in any form accepted by {@link #normalise(String)}
     * @param available the device names PyTorch reports, e.g. from {@link PytorchManager#getAvailableDevices()}
     */
    public static String resolve(String requested, Collection<String> available) {
        String device = normalise(requested);
        if (isCpu(device) || available.contains(device))
            return device;
        logger.warn("Device '{}' is not available (available: {}), using CPU instead", requested, available);
        return CPU;
    }

    /**
     * Checks a requested device against the devices of the installed PyTorch engine.
     * If the engine has not been downloaded yet, availability cannot be checked and the normalised
     * device is returned unchanged; inference then falls back to CPU if it fails.
     */
    public static String checkAvailable(String requested) {
        String device = normalise(requested);
        if (isCpu(device) || !PytorchManager.hasPyTorchEngine())
            return device;
        return resolve(device, PytorchManager.getAvailableDevices());
    }

    public static boolean isCpu(String device) {
        return CPU.equals(normalise(device));
    }
}
