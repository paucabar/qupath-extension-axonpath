package qupath.ext.axonpath;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.axonpath.core.AxonPathClasses;
import qupath.ext.axonpath.core.PredictionTools;
import qupath.ext.axonpath.core.PytorchManager;
import qupath.fx.utils.FXUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

/**
 * Scripting entry point for AxonPath segmentation.
 * <p>
 * Runs the same pipeline as the Run button in the AxonPath panel (inference, hierarchy building,
 * measurements) on a list of parent objects, configured through a builder:
 * <pre>{@code
 * def segmenter = AxonPathSegmenter.builder("/path/to/model")
 *         .channel("DAPI")
 *         .build()
 * segmenter.detectObjects(getCurrentImageData(), getAnnotationObjects())
 * }</pre>
 * Pixel size, minimum diameter and inner cylinder prediction default to the values in the model's
 * {@code rdf.yaml}, as in the GUI.
 * <p>
 * If a non-CPU device is requested but is not available, or inference fails on it, the segmenter
 * logs a warning and falls back to CPU instead of failing.
 */
public class AxonPathSegmenter {
    private static final Logger logger = LoggerFactory.getLogger(AxonPathSegmenter.class);

    // Thresholds used by the GUI; not exposed because they are tied to how the models are trained
    private static final double SDT_MIN_THRESHOLD = 0.5;
    private static final double SDT_MAX_THRESHOLD = 1;

    private static final String CPU = "cpu";

    private static final Set<PathClass> AXONPATH_CLASSES = Set.of(
            PathClass.getInstance(AxonPathClasses.FIBRE),
            PathClass.getInstance(AxonPathClasses.INNER_CYLINDER),
            PathClass.getInstance(AxonPathClasses.AXON));

    private final Path modelPath;
    private final Integer channelIndex;  // 0-based; null if a channel name was given
    private final String channelName;
    private final String device;
    private final double pixelSize;
    private final double minDiameter;
    private final boolean predictInnerCylinder;
    private final boolean removeEdgeFibres;

    private AxonPathSegmenter(Builder builder, double pixelSize, double minDiameter, boolean predictInnerCylinder) {
        this.modelPath = builder.modelPath;
        this.channelIndex = builder.channelIndex;
        this.channelName = builder.channelName;
        this.device = builder.device;
        this.pixelSize = pixelSize;
        this.minDiameter = minDiameter;
        this.predictInnerCylinder = predictInnerCylinder;
        this.removeEdgeFibres = builder.removeEdgeFibres;
    }

    /**
     * Create a builder for a segmenter using the model in the given directory.
     *
     * @param modelPath path to a BioImage.IO model directory containing {@code weights.pt} and {@code rdf.yaml}
     */
    public static Builder builder(String modelPath) {
        return new Builder(Path.of(modelPath));
    }

    /**
     * Create a builder for a segmenter using the model in the given directory.
     *
     * @param modelPath path to a BioImage.IO model directory containing {@code weights.pt} and {@code rdf.yaml}
     */
    public static Builder builder(Path modelPath) {
        return new Builder(modelPath);
    }

    /**
     * Segment fibres, inner cylinders and axons inside each parent object.
     * <p>
     * Any AxonPath objects already under a parent are removed first, so re-running replaces
     * previous results (including manual corrections). Parents that are themselves AxonPath
     * objects (Fibre, InnerCylinder, Axon) are skipped. Each processed parent is locked.
     *
     * @param imageData the image to process; its pixel size must be calibrated
     * @param parents   the objects (usually annotations) defining the regions to segment
     * @return all AxonPath objects created, across all parents
     * @throws IOException              if the model cannot be read
     * @throws IllegalArgumentException if the image has no pixel size or the channel cannot be found
     */
    public Collection<PathObject> detectObjects(ImageData<BufferedImage> imageData,
                                                Collection<? extends PathObject> parents) throws IOException {
        if (!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons())
            throw new IllegalArgumentException("AxonPath requires a calibrated pixel size (µm), but "
                    + imageData.getServer().getMetadata().getName() + " has none");

        int channel = resolveChannel(imageData);  // 1-based, as used by PredictionTools
        ensureClassesAvailable();
        String activeDevice = checkDevice(device);

        List<PathObject> validParents = new ArrayList<>();
        for (var parent : parents) {
            if (isAxonPathClass(parent.getPathClass()))
                logger.warn("Skipping parent {}: AxonPath objects cannot be used as parents", parent);
            else
                validParents.add(parent);
        }

        List<PathObject> results = new ArrayList<>();
        for (int i = 0; i < validParents.size(); i++) {
            var parent = validParents.get(i);
            logger.info("AxonPath: processing parent {}/{}", i + 1, validParents.size());
            try {
                results.addAll(run(imageData, parent, channel, activeDevice));
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                if (CPU.equals(activeDevice))
                    throw e;
                // Inference runs before any objects are added, so retrying on CPU is safe.
                // Stay on CPU for the remaining parents instead of failing on each one.
                logger.warn("Inference failed on device '{}', retrying on CPU for this and all remaining parents",
                        activeDevice, e);
                activeDevice = CPU;
                results.addAll(run(imageData, parent, channel, activeDevice));
            }
        }
        logger.info("AxonPath: {} objects created in {} parent(s)", results.size(), validParents.size());
        return results;
    }

    /**
     * Whether the class is one AxonPath assigns to its output (Fibre, InnerCylinder, Axon).
     * Unclassified objects have a null class and are not AxonPath objects; the null check is
     * required because {@code Set.of(...).contains(null)} throws.
     */
    static boolean isAxonPathClass(PathClass pathClass) {
        return pathClass != null && AXONPATH_CLASSES.contains(pathClass);
    }

    private Collection<PathObject> run(ImageData<BufferedImage> imageData, PathObject parent,
                                       int channel, String device) throws IOException {
        return PredictionTools.runAxonPath(modelPath, imageData, parent,
                SDT_MIN_THRESHOLD, SDT_MAX_THRESHOLD, channel,
                pixelSize, minDiameter, predictInnerCylinder, removeEdgeFibres, device);
    }

    /**
     * Returns the 1-based channel number used internally by PredictionTools.
     */
    private int resolveChannel(ImageData<BufferedImage> imageData) {
        List<ImageChannel> channels = imageData.getServer().getMetadata().getChannels();
        if (channelName != null) {
            for (int i = 0; i < channels.size(); i++) {
                if (channelName.equals(channels.get(i).getName()))
                    return i + 1;
            }
            throw new IllegalArgumentException("Channel '" + channelName + "' not found. Available channels: "
                    + channels.stream().map(ImageChannel::getName).toList());
        }
        if (channelIndex < 0 || channelIndex >= channels.size())
            throw new IllegalArgumentException("Channel index " + channelIndex + " is out of range: the image has "
                    + channels.size() + " channel(s), so valid indices are 0 to " + (channels.size() - 1));
        return channelIndex + 1;
    }

    /**
     * Returns the requested device if PyTorch reports it as available, otherwise CPU.
     * If the PyTorch engine has not been downloaded yet, availability cannot be checked
     * and the requested device is returned unchanged (the runtime fallback still applies).
     */
    private static String checkDevice(String requested) {
        if (CPU.equals(requested) || !PytorchManager.hasPyTorchEngine())
            return requested;
        // Device names can include an index (e.g. "gpu:1"); availability is reported per type
        String type = requested.split(":")[0];
        if (PytorchManager.getAvailableDevices().contains(type))
            return requested;
        logger.warn("Device '{}' is not available (available: {}), using CPU instead",
                requested, PytorchManager.getAvailableDevices());
        return CPU;
    }

    /**
     * Add the AxonPath classes to QuPath's class list when running with the GUI.
     * The list is observed by the UI, so it must be modified on the application thread.
     */
    private static void ensureClassesAvailable() {
        var gui = QuPathGUI.getInstance();
        if (gui == null)
            return;
        FXUtils.runOnApplicationThread(() -> AxonPathClasses.ensureClassesAvailable(gui.getAvailablePathClasses()));
    }

    /**
     * Builder for {@link AxonPathSegmenter}. Unset parameters default to the model's {@code rdf.yaml}
     * values (pixel size, minimum diameter, inner cylinder prediction), the first channel, CPU, and
     * keeping edge fibres.
     */
    public static class Builder {
        private final Path modelPath;
        private Integer channelIndex = 0;
        private String channelName;
        private String device = CPU;
        private Double pixelSize;
        private Double minDiameter;
        private Boolean predictInnerCylinder;
        private boolean removeEdgeFibres = false;

        private Builder(Path modelPath) {
            this.modelPath = modelPath;
        }

        /**
         * Channel to segment, by 0-based index (the first channel is 0, shown as C1 in the AxonPath panel).
         */
        public Builder channel(int index) {
            this.channelIndex = index;
            this.channelName = null;
            return this;
        }

        /**
         * Channel to segment, by name. Resolved separately for each image, so it works across
         * images whose channels are in different orders.
         */
        public Builder channel(String name) {
            this.channelName = name;
            this.channelIndex = null;
            return this;
        }

        /**
         * PyTorch device: {@code "cpu"} (default), {@code "gpu"} (or {@code "gpu:1"} etc.), or {@code "mps"}
         * (Apple Silicon). Falls back to CPU if the device is not available.
         */
        public Builder device(String device) {
            this.device = device.toLowerCase(Locale.ROOT);
            return this;
        }

        /**
         * Target pixel size in µm the image is resampled to before inference. Overrides {@code rdf.yaml}.
         */
        public Builder pixelSize(double pixelSize) {
            this.pixelSize = pixelSize;
            return this;
        }

        /**
         * Minimum object diameter in pixels at the target pixel size. Overrides {@code rdf.yaml}.
         */
        public Builder minDiameter(double minDiameter) {
            this.minDiameter = minDiameter;
            return this;
        }

        /**
         * Whether to segment inner cylinders (electron microscopy models). Overrides {@code rdf.yaml}.
         */
        public Builder predictInnerCylinder(boolean predict) {
            this.predictInnerCylinder = predict;
            return this;
        }

        /**
         * Whether to remove fibres touching the parent object's boundary. Default: false.
         */
        public Builder removeEdgeFibres(boolean remove) {
            this.removeEdgeFibres = remove;
            return this;
        }

        /**
         * Build the segmenter, reading defaults from the model's {@code rdf.yaml}.
         *
         * @throws IOException if the model directory, {@code weights.pt} or {@code rdf.yaml} is missing,
         *                     or {@code rdf.yaml} lacks a required config key
         */
        public AxonPathSegmenter build() throws IOException {
            if (!Files.isDirectory(modelPath))
                throw new IOException("Model directory not found: " + modelPath);
            if (!Files.exists(modelPath.resolve("weights.pt")))
                throw new IOException("weights.pt not found in model directory: " + modelPath);
            var params = PredictionTools.extractParametersFromYaml(modelPath.resolve("rdf.yaml"));
            return new AxonPathSegmenter(this,
                    pixelSize != null ? pixelSize : (double) params.get("pixel_size"),
                    minDiameter != null ? minDiameter : (double) params.get("min_diameter"),
                    predictInnerCylinder != null ? predictInnerCylinder : (boolean) params.get("predict_inner_cylinder"));
        }
    }
}
