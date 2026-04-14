package qupath.ext.axonpath.core;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import ai.djl.Device;
import qupath.ext.djl.DjlTools;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.processing.SimpleThresholding;
import qupath.imagej.processing.Watershed;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.Padding;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import java.awt.image.BufferedImage;

/**
 * Core processing tools for running AxonPath inference in QuPath.
 * <p>
 * The pipeline reads a BioImage.IO model bundle (weights.pt + rdf.yaml),
 * runs inference on a selected image region, and returns segmented objects
 * organised into a meaningful hierarchy:
 * <ul>
 *   <li>Electron microscopy: Fibre &gt; Inner Tongue &gt; Axon</li>
 *   <li>Brightfield: Fibre &gt; Axon</li>
 * </ul>
 * Whether inner cylinders are predicted is controlled by the {@code predict_inner_tongue}
 * flag in the model's rdf.yaml config.
 * <p>
 * Requires the DJL extension and PyTorch engine to be available in QuPath.
 * See <a href="https://qupath.readthedocs.io/en/stable/docs/deep/djl.html">the QuPath docs</a>.
 */
public class PredictionTools {
    private static final Logger logger = LoggerFactory.getLogger(PredictionTools.class);

    private PredictionTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Calculates the downsample factor needed to reach the target pixel size.
     * <p>
     * If the computed factor is within ±10% of 1.0, resampling is skipped and 1.0 is
     * returned — interpolation artefacts outweigh any benefit at such small scale
     * differences, and the model is trained with scale augmentation covering the same
     * range. Outside that band, if the factor is within 1% of an integer it is snapped
     * to that integer (avoids artefacts for near-integer ratios). Otherwise the exact
     * float is returned. Upsampling (factor &lt; 0.9) is allowed but triggers a warning.
     */
    static double calculateDownsampleFactor(ImageData<BufferedImage> imageData, double targetPixelSizeMicrons) {
        double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        double downsampleFactor = targetPixelSizeMicrons / imagePixelSizeMicrons;

        // Skip resampling for pixel-size differences within ±10%: interpolation
        // artefacts outweigh any benefit at this scale, and the model is trained
        // with scale augmentation covering the same range.
        if (downsampleFactor >= 0.9 && downsampleFactor <= 1.1) {
            if (downsampleFactor != 1.0)
                logger.debug("Pixel size difference within ±10% tolerance (factor = {}), skipping resampling.", downsampleFactor);
            return 1.0;
        }

        if (downsampleFactor < 1) {
            logger.warn("Target pixel size ({} µm) is smaller than image pixel size ({} µm). " +
                    "Upsampling will be applied (factor = {}).",
                    targetPixelSizeMicrons, imagePixelSizeMicrons, downsampleFactor);
        }

        double rounded = Math.round(downsampleFactor);
        if (GeneralTools.almostTheSame(downsampleFactor, rounded, 0.01))
            return rounded;
        return downsampleFactor;
    }

    /**
     * Extracts AxonPath-specific parameters from the model's rdf.yaml config block.
     * Expected keys: {@code pixel_size}, {@code min_diameter}, {@code predict_inner_tongue}.
     */
    public static Map<String, Object> extractParametersFromYaml(Path yamlPath) throws IOException {
        Yaml yaml = new Yaml();
        try (var inputStream = Files.newInputStream(yamlPath)) {
            Map<String, Object> yamlData = yaml.load(inputStream);
            Map<String, Object> config = (Map<String, Object>) yamlData.get("config");
            return Map.of(
                    "pixel_size", config.get("pixel_size"),
                    "min_diameter", config.get("min_diameter"),
                    "predict_inner_tongue", config.get("predict_inner_tongue")
            );
        }
    }

    /**
     * Runs the AxonPath DNN model on a region of the image and returns the
     * raw prediction as an ImageJ ImagePlus.
     * <p>
     * The image is normalised to [0, 1] using its min/max pixel values before
     * being passed to the model.
     */
    static ImagePlus modelInference(URI modelUri, String layout, int inputWidth, int inputHeight,
                                    Padding padding, int[] inputShape,
                                    ImageData<BufferedImage> imageData, ImageServer<BufferedImage> server,
                                    RegionRequest request, int channel, String device) throws IOException {
        // Compute min/max stats from the correct channel using QuPath's raster directly
        double min, max;
        try {
            var channelPixels = server.readRegion(request);
            var raster = channelPixels.getRaster();
            int channelIdx = channel - 1;
            double[] pixels = raster.getSamples(0, 0, raster.getWidth(), raster.getHeight(), channelIdx, (double[]) null);
            min = Double.MAX_VALUE;
            max = -Double.MAX_VALUE;
            for (double p : pixels) {
                if (p < min) min = p;
                if (p > max) max = p;
            }
            logger.info("Channel {} stats: min={}, max={}", channel, min, max);
        } catch (Exception e) {
            logger.warn("Could not compute channel stats, using defaults 0-1", e);
            min = 0;
            max = 1;
        }

        DjlTools.setOverrideDevice("PyTorch", Device.fromName(device));
        ImagePlus prediction;
        try (var dnn = DjlTools.createDnnModel(modelUri, layout, inputShape)) {
            var op = ImageOps.buildImageDataOp()
                    .appendOps(
                            ImageOps.Channels.extract(channel - 1),
                            ImageOps.Core.ensureType(PixelType.FLOAT32),
                            ImageOps.Core.subtract(min),
                            ImageOps.Core.divide(max - min),
                            ImageOps.ML.dnn(dnn, inputWidth, inputHeight, padding)
                    );
            var mat = op.apply(imageData, request);
            prediction = OpenCVTools.matToImagePlus("Prediction", mat);
            mat.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return prediction;
    }

    /**
     * Returns a new collection where every object's ROI has its holes filled.
     * Creates new detection objects; original objects are not modified.
     */
    private static Collection<PathObject> fillHoles(Collection<PathObject> objects) {
        return objects.stream()
                .map(o -> PathObjects.createDetectionObject(
                        RoiTools.fillHoles(o.getROI()), o.getPathClass()))
                .toList();
    }

    /**
     * Runs ImageJ's Particle Analyzer on a binary image.
     * Always returns an output ImagePlus with a standard (non-inverted) LUT.
     */
    static ImagePlus analyzeParticles(ImagePlus imp, int options, int measurements,
                                      double minSize, double maxSize,
                                      double minCircularity, double maxCircularity) {
        var rt = new ResultsTable();
        var pa = new ParticleAnalyzer(options, measurements, rt, minSize, maxSize, minCircularity, maxCircularity);
        ImageProcessor ip = imp.getProcessor();
        ip.setBinaryThreshold();
        pa.setHideOutputImage(true);
        pa.analyze(imp, ip);
        ImagePlus output = pa.getOutputImage();
        if (output.isInvertedLut()) {
            IJ.run(output, "Grays", "");
        }
        return output;
    }

    /**
     * Performs instance segmentation on a Signed Distance Transform (SDT) channel
     * of the model prediction.
     * <p>
     * The pipeline is:
     * <ol>
     *   <li>Threshold the SDT channel to get seed regions</li>
     *   <li>Add seeds to the hierarchy and render them as a 16-bit label image
     *       via a {@link LabeledImageServer}</li>
     *   <li>Apply a 2D watershed using the SDT channel as intensity guidance</li>
     *   <li>Convert the resulting label image back to QuPath annotation objects</li>
     * </ol>
     * Seeds smaller than 30% of the expected minimum object diameter are filtered out.
     */
    static Collection<PathObject> processSDT(ImagePlus prediction,
                                             PathObjectHierarchy hierarchy,
                                             String className,
                                             int channel,
                                             double minThreshold, double maxThreshold,
                                             double downsample,
                                             double minDiameterPixels,
                                             ImageData<BufferedImage> imageData,
                                             RegionRequest request,
                                             double translateX, double translateY) throws IOException {
        // Threshold the SDT channel to obtain seed regions
        prediction.setC(channel);
        ImageProcessor sdtChannel = prediction.getProcessor().duplicate();
        sdtChannel.setThreshold(minThreshold, maxThreshold, ImageProcessor.NO_LUT_UPDATE);

        var thresholdedROI = SimpleThresholding.thresholdToROI(sdtChannel, request);
        if (thresholdedROI == null) {
            logger.warn("processSDT ({}): no regions found above threshold", className);
            return java.util.Collections.emptySet();
        }
        var seedROIs = RoiTools.splitROI(thresholdedROI);
        var seedObjects = seedROIs.stream()
                .map(roi -> PathObjects.createAnnotationObject(roi, PathClass.getInstance("Seed")))
                .toList();
        hierarchy.addObjects(seedObjects);

        // Filter seeds smaller than 30% of the minimum expected object diameter.
        // minSeedArea must be in full-resolution pixels² because p.getROI().getArea()
        // always returns area in full-res coords — scale the radius by downsample.
        double minSeedRadius = 0.3 * minDiameterPixels / 2;
        double minSeedArea = Math.PI * Math.pow(minSeedRadius * downsample, 2);

        // Render seeds as a 16-bit instance label image using LabeledImageServer.
        // A seed-specific RegionRequest is required so the server path matches correctly.
        var seedServer = new LabeledImageServer.Builder(imageData)
                .backgroundLabel(0, ColorTools.BLACK)
                .downsample(downsample)
                .useAnnotations()
                .useInstanceLabels()
                .useFilter(p -> p.isAnnotation()
                        && p.getPathClass() == PathClass.getInstance("Seed")
                        && p.getROI().getArea() > minSeedArea)
                .multichannelOutput(false)
                .build();

        RegionRequest seedRequest = RegionRequest.createInstance(seedServer.getPath(), request);
        ImagePlus labelImage = IJTools.convertToImagePlus(seedServer, seedRequest).getImage();

        // Convert to 16-bit so the label image is compatible with the watershed and RoiLabeling
        IJ.run(labelImage, "16-bit", "");
        ImageProcessor labelProcessor = labelImage.getProcessor();

        // Clean up seed objects from the hierarchy
        hierarchy.removeObjects(
                hierarchy.getAnnotationObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Seed"))
                        .toList(),
                false);

        // Watershed: expand seed labels into the thresholded SDT region.
        // sdtChannel (model output) and labelProcessor (LabeledImageServer rendering) may differ
        // by 1 pixel due to rounding at non-integer downsample factors. A mismatch causes an
        // ArrayIndexOutOfBoundsException inside Watershed. Resize sdtChannel to match if needed.
        if (sdtChannel.getWidth() != labelProcessor.getWidth() ||
                sdtChannel.getHeight() != labelProcessor.getHeight()) {
            logger.warn("processSDT ({}): SDT size {}×{} ≠ label size {}×{}, resizing SDT to match",
                    className, sdtChannel.getWidth(), sdtChannel.getHeight(),
                    labelProcessor.getWidth(), labelProcessor.getHeight());
            sdtChannel.setInterpolationMethod(ImageProcessor.BILINEAR);
            sdtChannel = sdtChannel.resize(labelProcessor.getWidth(), labelProcessor.getHeight(), true);
        }
        try {
            Watershed.doWatershed(sdtChannel, labelProcessor, 0, true);
        } catch (ArrayIndexOutOfBoundsException e) {
            logger.warn("processSDT ({}): watershed failed due to image boundary condition ({}x{}), returning empty result",
                    className, sdtChannel.getWidth(), sdtChannel.getHeight());
            return java.util.Collections.emptySet();
        }

        // Convert the label image back to QuPath annotation objects
        var detectedROIs = RoiLabeling.labelsToFilledRoiList(labelProcessor, true);
        ImagePlane plane = request.getImagePlane();
        Calibration calibration = prediction.getCalibration();

        logger.info("processSDT ({}): {} seeds → {} detected objects", className, seedObjects.size(), detectedROIs.size());

        return detectedROIs.stream()
                .map(roiIJ -> {
                    var roi = IJTools.convertToROI(roiIJ, calibration, downsample, plane);
                    return PathObjects.createDetectionObject(
                            roi.translate(translateX, translateY),
                            PathClass.getInstance(className));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Performs semantic segmentation on a single channel of the model prediction,
     * returning one annotation object per connected region with the given label value.
     * Used for InnerCylinder prediction, which does not require watershed separation.
     */
    static Collection<PathObject> processSemantic(ImagePlus prediction, String className,
                                                  int channel, int labelValue,
                                                  double downsample,
                                                  double minDiameterPixels,
                                                  double translateX, double translateY,
                                                  ImagePlane plane) {
        prediction.setC(channel);
        ImageProcessor ip = prediction.getProcessor();
        ip.setThreshold(labelValue, labelValue, ImageProcessor.NO_LUT_UPDATE);

        // minSize is in inference pixels² (binaryMask is at inference resolution, default calibration)
        double minSize = Math.PI * Math.pow(minDiameterPixels / 2.0, 2);
        ImagePlus binaryMask = new ImagePlus("Binary Mask", ip.createMask());
        int options = ParticleAnalyzer.SHOW_MASKS + ParticleAnalyzer.ADD_TO_MANAGER + ParticleAnalyzer.COMPOSITE_ROIS;
        analyzeParticles(binaryMask, options, Measurements.AREA, minSize, Double.POSITIVE_INFINITY, 0, 1);

        RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            logger.info("processSemantic ({}): 0 detected objects", className);
            return java.util.Collections.emptySet();
        }
        rm.setVisible(false);
        var roiList = rm.getRoisAsArray();
        rm.close();

        Calibration calibration = prediction.getCalibration();

        logger.info("processSemantic ({}): {} detected objects", className, roiList.length);

        return Arrays.stream(roiList)
                .map(roiIJ -> {
                    var roi = IJTools.convertToROI(roiIJ, calibration, downsample, plane);
                    return PathObjects.createDetectionObject(
                            roi.translate(translateX, translateY),
                            PathClass.getInstance(className));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Convenience overload that reads {@code pixel_size}, {@code min_diameter}, and
     * {@code predict_inner_tongue} from the model's rdf.yaml and delegates to the
     * full overload.
     */
    public static Collection<PathObject> runAxonPath(Path modelPath,
                                                   ImageData<BufferedImage> imageData,
                                                   PathObject parentObject,
                                                   double minThreshold,
                                                   double maxThreshold,
                                                   int channel,
                                                   String device) throws IOException {
        Map<String, Object> parameters = extractParametersFromYaml(modelPath.resolve("rdf.yaml"));
        return runAxonPath(modelPath, imageData, parentObject, minThreshold, maxThreshold, channel,
                (double) parameters.get("pixel_size"),
                (double) parameters.get("min_diameter"),
                (boolean) parameters.get("predict_inner_tongue"),
                false,
                device);
    }

    /**
     * Main AxonPath segmentation pipeline.
     * <p>
     * Runs inference on the region covered by {@code parentObject}, post-processes
     * the prediction into QuPath detection objects, builds the object hierarchy, and
     * computes morphometric features on the resulting fibres.
     *
     * @param modelPath          path to the BioImage.IO model directory (must contain weights.pt and rdf.yaml)
     * @param imageData          the current QuPath image data
     * @param parentObject       the annotation defining the region to process
     * @param minThreshold       minimum threshold for SDT-based instance segmentation
     * @param maxThreshold       maximum threshold for SDT-based instance segmentation
     * @param channel            image channel to use for inference (1-based)
     * @param pixelSize          target pixel size in microns
     * @param minDiameter        minimum object diameter in pixels for seed filtering
     * @param predictInnerCylinder whether to predict inner cylinder structures
     * @param removeEdgeFibres     whether to remove fibres touching the parent boundary after hierarchy is built
     * @return all objects created by the pipeline (fibres, axons, and optionally inner cylinders)
     */
    public static Collection<PathObject> runAxonPath(Path modelPath,
                                                   ImageData<BufferedImage> imageData,
                                                   PathObject parentObject,
                                                   double minThreshold,
                                                   double maxThreshold,
                                                   int channel,
                                                   double pixelSize,
                                                   double minDiameter,
                                                   boolean predictInnerCylinder,
                                                   boolean removeEdgeFibres,
                                                   String device) throws IOException {
        var hierarchy = imageData.getHierarchy();
        if (!parentObject.getChildObjects().isEmpty()) {
            var allDescendants = HierarchyTools.getAllDescendants(parentObject);
            hierarchy.removeObjects(allDescendants, false);
        }

        double targetPixelSizeMicrons = pixelSize;
        double minDiameterPixels = minDiameter;
        boolean predictInnerCylinderFlag = predictInnerCylinder;

        logger.info("Model parameters: pixel_size={} µm, min_diameter={} px, predict_inner_tongue={}",
                targetPixelSizeMicrons, minDiameterPixels, predictInnerCylinderFlag);

        double downsample = calculateDownsampleFactor(imageData, targetPixelSizeMicrons);
        logger.info("Downsample factor: {}", downsample);

        // Define the region to process based on the parent annotation and selected channel
        var server = imageData.getServer();
        var parentROI = parentObject.getROI();
        double translateX = parentROI.getBoundsX();
        double translateY = parentROI.getBoundsY();
        RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, parentROI);

        logger.info("Processing region: x={}, y={}, w={}, h={}",
                parentROI.getBoundsX(), parentROI.getBoundsY(),
                parentROI.getBoundsWidth(), parentROI.getBoundsHeight());

        // Run model inference
        int[] inputShape = new int[]{1, 1, 512, 512};
        ImagePlus prediction = modelInference(
                modelPath.resolve("weights.pt").toUri(),
                "NCHW", 512, 512, Padding.symmetric(32), inputShape,
                imageData, server, request, channel, device);

        // Post-process prediction channels into QuPath objects
        var fibres = processSDT(prediction, imageData.getHierarchy(), "Fibre", 2,
                minThreshold, maxThreshold, downsample, minDiameterPixels,
                imageData, request, translateX, translateY);

        var axons = processSDT(prediction, imageData.getHierarchy(), "Axon", 3,
                minThreshold, maxThreshold, downsample, minDiameterPixels,
                imageData, request, translateX, translateY);

        Collection<PathObject> innerCylinders = List.of();
        if (predictInnerCylinderFlag) {
            innerCylinders = processSemantic(prediction, "InnerCylinder", 1, 2,
                    downsample, minDiameterPixels, translateX, translateY, request.getImagePlane());
        }

        logger.info("Detected: {} fibres, {} axons, {} inner cylinders",
                fibres.size(), axons.size(), innerCylinders.size());

        // Fill holes in all detected ROIs before building the hierarchy
        fibres = fillHoles(fibres);
        axons = fillHoles(axons);
        innerCylinders = fillHoles(innerCylinders);

        // Build hierarchy and optionally remove edge fibres before quantification
        HierarchyTools.updateHierarchy(imageData.getHierarchy(), parentObject, fibres, axons, innerCylinders);

        if (removeEdgeFibres) {
            FilterTools.removeFibresTouchingBoundary(parentObject, imageData.getHierarchy());
        }

        var validFibres = parentObject.getChildObjects().stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();
        QuantificationTools.computeFeatures(imageData, validFibres);
        QuantificationTools.computeIntensityFeatures(imageData, validFibres);

        if (!parentObject.isLocked()) {
            parentObject.setLocked(true);
        }

        return Stream.of(fibres.stream(), axons.stream(), innerCylinders.stream())
                .flatMap(s -> s)
                .collect(Collectors.toSet());
    }
}