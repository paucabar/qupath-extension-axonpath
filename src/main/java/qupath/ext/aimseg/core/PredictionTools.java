package qupath.ext.aimseg.core;

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
import java.nio.file.Paths;
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
import qupath.ext.djl.DjlTools;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.processing.SimpleThresholding;
import qupath.imagej.processing.Watershed;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.servers.PixelType;
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
 * This script demonstrates how to run an AimSeg model in QuPath,
 * including the basic functions for model inference and post-processing in QuPath.
 * The output is stored as three distinct classes of segmented objects: axon, inner tongue, and fibre.
 * <p>
 *
 * Prior to running this script, ensure that the DJL extension is installed in QuPath
 * and PyTorch has been downloaded – see <a href="https://qupath.readthedocs.io/en/stable/docs/deep/djl.html">the QuPath docs</a>.
 */
public class PredictionTools {
    private static final Logger logger = LoggerFactory.getLogger(PredictionTools.class);

    private PredictionTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Function to calculate the downsample factor based on target pixel size
     */
    static double calculateDownsampleFactor(ImageData<BufferedImage> imageData, double targetPixelSizeMicrons, boolean allowUpscaling) {
        double pixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        double downsampleFactor = targetPixelSizeMicrons / pixelSizeMicrons;

        if (!allowUpscaling && downsampleFactor < 1) {
            logger.warn("Target pixel size ({} µm) is smaller than the image pixel size ({} µm). Downsample will not be applied (factor set to 1).", targetPixelSizeMicrons, pixelSizeMicrons);
            return 1;
        }

        return Math.round(downsampleFactor);
    }

    /**
     * Method to extract parameters from rdf.yaml file
     */
    static Map<String, Object> extractParametersFromYaml(Path yamlPath) throws IOException {
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
     * Method to run an AimSeg model using DJL in QuPath
     */
    static ImagePlus modelInference(URI uri, String layout, int inputWidth, int inputHeight, Padding padding, int[] inputShape,
                                    ImageData<BufferedImage> imageData, ImageServer<BufferedImage> server, RegionRequest request) throws IOException {
        ImagePlus imp = IJTools.convertToImagePlus(server, request).getImage();

        // Get the statistics of the image to get the minimum and maximum pixel values
        ImageStatistics stats = imp.getStatistics();
        double min = stats.min;
        double max = stats.max;
        double difference = max - min;
        ImagePlus impOutput;
        // Apply prediction
        try (var dnn = DjlTools.createDnnModel(uri, layout, inputShape)) {
            var op = ImageOps.buildImageDataOp()
                    .appendOps(
                            ImageOps.Core.ensureType(PixelType.FLOAT32),
                            //ImageOps.Normalize.percentile(0.1, 99.9),
                            ImageOps.Core.subtract(min),
                            ImageOps.Core.divide(difference),
                            ImageOps.ML.dnn(dnn, inputWidth, inputHeight, padding)
                    );

            // Run the prediction, getting an OpenCV Mat as output
            var mat = op.apply(imageData, request);

            // Convert to an ImageJ ImagePlus
            impOutput = OpenCVTools.matToImagePlus("Prediction", mat);
            mat.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        //impOutput.show()

        return impOutput;
    }

    /**
     * Implements ImageJ's Particle Analyzer
     * The method will always return an ImagePlus
     * options is defined as an integer using ParticleAnalyzer fields
     * options is defined as an integer using Interface Measurements fields
     * results table is not given as an argument because the method is never used to measure
     */
    static ImagePlus analyzeParticles(ImagePlus imp, int options, int measurements, double minSize, double maxSize, double minCircularity, double maxCircularity) {
        var rt = new ResultsTable();
        var pa = new ParticleAnalyzer(options, measurements, rt, minSize, maxSize, minCircularity, maxCircularity);
        ImageProcessor ip = imp.getProcessor();
        ip.setBinaryThreshold();
        pa.setHideOutputImage(true);
        pa.analyze(imp, ip);
        ImagePlus impOutput = pa.getOutputImage();
        if (impOutput.isInvertedLut()) {
            IJ.run(impOutput, "Grays", ""); // get the non-inverted LUT
        }
        return impOutput;
    }

    /**
     * Method to get an SDT channel from an image plus and return an instance segmentation in the
     * form of QuPath objects.
     */
    static Collection<PathObject> processSDT(ImagePlus imp,
                                             PathObjectHierarchy hierarchy,
                                             DataType dataType,
                                             double targetPixelSizeMicrons,
                                             double minDiameterMicrons,
                                             String className,
                                             int channel,
                                             double minThreshold,
                                             double maxThreshold,
                                             double downsample,
                                             ImageData<BufferedImage> imageData,
                                             RegionRequest request,
                                             double translateX,
                                             double translateY) throws IOException {
        // Create ROIs from thresholds
        imp.setC(channel); // Set the channel index (1-based)
        ImageProcessor ip = imp.getProcessor(); // Get the ImageProcessor of the specified channel
        ip.setThreshold(minThreshold, maxThreshold, ImageProcessor.NO_LUT_UPDATE);
        var multipartRoi = SimpleThresholding.thresholdToROI(ip, request); // generates a multi-part ROI including all the thresholded regions
        var roiList = RoiTools.splitROI(multipartRoi); // split the multi-part ROI into separate ROIs

        // Convert QuPath ROIs to objects
        var pathObjects = roiList.stream().map(
                roi -> PathObjects.createAnnotationObject(roi, PathClass.getInstance("Seed")))
                .toList();
        hierarchy.addObjects(pathObjects);

        // Create an ImageServer for seed instances
        double minAreaMicrons = Math.PI * Math.pow(minDiameterMicrons / 2, 2);
        double minAreaPixels = minAreaMicrons / Math.pow(targetPixelSizeMicrons, 2) * downsample;
        double minSizePixels = switch (dataType) {
            case ELECTRON_MICROSCOPY -> minAreaPixels * 0.4;
            case BRIGHTFIELD -> minAreaPixels * 0.2;
        };

        var seedServer = new LabeledImageServer.Builder(imageData)
                .backgroundLabel(0, ColorTools.BLACK) // Specify background label (usually 0 or 255)
                .downsample(downsample)    // Choose server resolution; this should match the resolution at which tiles are exported
                .useAnnotations()
                .useInstanceLabels()
                .useFilter(p -> p.isAnnotation() && p.getPathClass() == PathClass.getInstance("Seed") && p.getROI().getArea() > minSizePixels)
                .multichannelOutput(false) // If true, each label refers to the channel of a multichannel binary image (required for multiclass probability)
                .build();

        // Uncomment if you want to export the label image
        //def name = GeneralTools.stripExtension(imageData.getServer().getMetadata().getName()) // get image name to export annotations
        //def pathLabel = buildFilePath(labelDir, name + ".tif") // Define instance output file paths
        //writeImage(seedServer, pathLabel) // write the image

        // Open the seed labels with ImageJ
        ImagePlus impLabels = IJTools.convertToImagePlus(seedServer, request).getImage();
        ImageProcessor ipLabels = impLabels.getProcessor();

        // Delete all existing objects
        var seeds = hierarchy
                .getAnnotationObjects().stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("Seed"))
                .toList();
        hierarchy.removeObjects(seeds, false);

        // Apply a 2D watershed transform, constraining region growing using an intensity threshold.
        // Parameters:
        // ip - image containing intensity information
        // ipLabels - image containing starting labels; these will be modified
        // minIntensity - minimum threshold; labels will not expand into pixels with values below the threshold
        // conn8 - true if 8-connectivity should be used; alternative is 4-connectivity

        // Use QuPath's ImageJ-friendly Watershed class (not the general Watershed class for SimpleImage inputs)
        double minIntensity = 0;
        boolean conn8 = true;
        Watershed.doWatershed(ip, ipLabels, minIntensity, conn8);

        // Create detection objects from label image
        var roiDetected = RoiLabeling.labelsToFilledRoiList(ipLabels, conn8);

        // Convert ImageJ ROIs to QuPath ROIs
        ImagePlane plane = ImagePlane.getDefaultPlane();
        Calibration cal = imp.getCalibration();

        // Convert ImageJ ROIs to QuPath annotations
        return roiDetected.stream().map(
                roiIJ -> {
                    var roi = IJTools.convertToROI(roiIJ, cal, downsample, plane);
                    return PathObjects.createAnnotationObject(roi.translate(translateX, translateY), PathClass.getInstance(className));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Method to get a semantic channel from an image plus and return an instance segmentation in the
     * form of QuPath objects.
     */
    static Collection<PathObject> processSemantic(ImagePlus imp, String className, int channel, int label, double downsample,
                                                  double translateX, double translateY) {
        // Create ROIs from thresholds
        imp.setC(channel); // Set the channel index (1-based)
        ImageProcessor ip = imp.getProcessor(); // Get the ImageProcessor of the specified channel
        ip.setThreshold(label, label, ImageProcessor.NO_LUT_UPDATE);
        ImageProcessor ipMask = ip.createMask(); // image processor
        ImagePlus impMask = new ImagePlus("Binary Mask", ipMask); // image processor to image plus

        int optionsAddManager = ParticleAnalyzer.SHOW_MASKS + ParticleAnalyzer.ADD_TO_MANAGER + ParticleAnalyzer.COMPOSITE_ROIS;
        int measurementsArea = Measurements.AREA;
        ImagePlus binaryMask = analyzeParticles(impMask, optionsAddManager, measurementsArea, 0, Double.POSITIVE_INFINITY, 0, 1);
        RoiManager rm = RoiManager.getInstance();
        rm.setVisible(false);
        var roiList = rm.getRoisAsArray();
        rm.close();

        // Convert ImageJ ROIs to QuPath annotations
        ImagePlane plane = ImagePlane.getDefaultPlane();
        Calibration cal = imp.getCalibration();

        return Arrays.stream(roiList)
                .map(roi -> {
                    var roiIJ = IJTools.convertToROI(roi, cal, downsample, plane);
                    return PathObjects.createAnnotationObject(roiIJ.translate(translateX, translateY), PathClass.getInstance(className));
                })
                .collect(Collectors.toSet());
    }


    /**
     * Segmentation pipeline
     */
    public static Collection<PathObject> runAimSeg(Path modelPath,
                                                   ImageData<BufferedImage> imageData,
                                                   PathObject parentObject,
                                                   double minThreshold,
                                                   double maxThreshold) throws IOException {
        // Ensure we're not creating duplicates etc
        parentObject.getChildObjects().clear();

        // Find the weights and YAML files
        Path weightsPath = modelPath.resolve("weights.pt");
        Path yamlPath = modelPath.resolve("rdf.yaml");

        // Extract parameters from the YAML file
        Map<String, Object> parameters = extractParametersFromYaml(yamlPath);
        double targetPixelSizeMicrons = (double) parameters.get("pixel_size");
        double minDiameterPixels = (double) parameters.get("min_diameter");
        boolean predictInnerTongue = (boolean) parameters.get("predict_inner_tongue");

       // Calculate downsample factor
        double downsample = calculateDownsampleFactor(imageData, targetPixelSizeMicrons, false);

        // Model parameters
        int inputWidth = 512;
        int inputHeight = inputWidth;
        int nChannels = 1;
        var padding = Padding.symmetric(32);
        var layout = "NCHW";
        int[] inputShape = new int[] {1, nChannels, inputHeight, inputWidth};


        // Get an ImageJ representation of the output
        ImagePlus impOutput;

        // Get roi selection instance
        var server = imageData.getServer();
        var roi = parentObject.getROI();
        double translateX = roi.getBoundsX();
        double translateY = roi.getBoundsY();
        RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, roi);

        // Run model on the specified image region
        impOutput = modelInference(weightsPath.toUri(), layout, inputWidth, inputHeight, padding, inputShape, imageData, server, request);

        // Instance segmentation on model prediction
        var fibres = processSDT(impOutput, imageData.getHierarchy(), DataType.ELECTRON_MICROSCOPY, targetPixelSizeMicrons, minDiameterPixels,
                "Fibre", 2, minThreshold, maxThreshold, downsample, imageData, request, translateX, translateY);
        var axons = processSDT(impOutput, imageData.getHierarchy(), DataType.ELECTRON_MICROSCOPY, targetPixelSizeMicrons, minDiameterPixels,
                "Axon", 3, minThreshold, maxThreshold, downsample, imageData, request, translateX, translateY);
        Collection<PathObject> tongues = List.of();
        
        if (predictInnerTongue) {
            tongues = processSemantic(impOutput, "Inner Tongue", 1, 2, downsample, translateX, translateY);
        }

        HierarchyTools.updateHierarchy(imageData.getHierarchy(), parentObject, fibres, axons, tongues, DataType.ELECTRON_MICROSCOPY);

        // Lock selected annotation
        if (!parentObject.isLocked()) {
            parentObject.setLocked(true);
        }
        
        return Stream.of(fibres.stream(), axons.stream(), tongues.stream())
                .flatMap(s -> s) // Flattening multiple collections into one
                .collect(Collectors.toSet());
    }

    public enum DataType {
        BRIGHTFIELD,
        ELECTRON_MICROSCOPY
    }

    public enum TissueType {
        CENTRAL_NERVOUS_SYSTEM,
        PERIPHERAL_NERVOUS_SYSTEM
    }
}
