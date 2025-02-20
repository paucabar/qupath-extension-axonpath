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
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.Padding;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.scripting.QP;
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
    /**
     * Function to calculate the downsample factor based on target pixel size
     */
    static double calculateDownsampleFactor(ImageData<BufferedImage> imageData, double targetPixelSizeMicrons, boolean allowUpscaling) {
        // Get the current pixel size from image metadata
        double pixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons(); // maybe getPixelHeight() and getPixelWidth()

        // Calculate the downsample factor
        double downsampleFactor = targetPixelSizeMicrons / pixelSizeMicrons;

        // Handle upscaling based on the user's preference
        if (!allowUpscaling && downsampleFactor < 1) {
            throw new IllegalArgumentException("Target pixel size is smaller than the current pixel size. Upscaling is disabled.");
        }

        // Ensure the downsample factor is at least 1 if upscaling is not allowed
        if (!allowUpscaling) {
            downsampleFactor = Math.max(downsampleFactor, 1);
        }

        return Math.round(downsampleFactor);
    }

    /**
     * This function runs an AimSeg model using DJL in QuPath
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
    static ImagePlus analyzeParticles(ImagePlus imp, int options, int measurements, double minSize, double maxSize, double minCirc, double maxCirc) {
        var rt = new ResultsTable();
        var pa = new ParticleAnalyzer(options, measurements, rt, minSize, maxSize, minCirc, maxCirc);
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
    static Collection<PathObject> processSDT(ImagePlus imp, String dataType, double targetPixelSizeMicrons, double minDiameterMicrons,
                                             String className, int channel, double min_threshold, double max_threshold, double downsample,
                                             ImageData<BufferedImage> imageData, RegionRequest request, double translateX, double translateY) throws IOException {
        // Create ROIs from thresholds
        imp.setC(channel); // Set the channel index (1-based)
        ImageProcessor ip = imp.getProcessor(); // Get the ImageProcessor of the specified channel
        ip.setThreshold(min_threshold, max_threshold, ImageProcessor.NO_LUT_UPDATE);
        var multipartRoi = SimpleThresholding.thresholdToROI(ip, request); // generates a multi-part ROI including all the thresholded regions
        var roiList = RoiTools.splitROI(multipartRoi); // split the multi-part ROI into separate ROIs

        // Convert QuPath ROIs to objects
        var pathObjects = roiList.stream().map(
                roi -> PathObjects.createAnnotationObject(roi, PathClass.getInstance("Seed")))
                .toList();
        QP.addObjects(pathObjects);

        // Create an ImageServer for seed instances
        double minAreaMicrons = Math.PI * Math.pow(minDiameterMicrons / 2, 2);
        double minAreaPixels = minAreaMicrons / Math.pow(targetPixelSizeMicrons, 2) * downsample;
        double minSizePixels = switch (dataType) {
            case "EM" -> minAreaPixels * 0.4;
            case "BF" -> minAreaPixels * 0.2;
            default -> throw new IllegalArgumentException("Unknown datatype: " + dataType);
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
        var seeds = QP.getCurrentImageData()
                .getHierarchy()
                .getAnnotationObjects().stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("Seed"))
                .toList();
        QP.removeObjects(seeds);

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

        // Convert ImageJ ROIs to QuPath detections
        return roiDetected.stream().map(
                roiIJ -> {
                    var roi = IJTools.convertToROI(roiIJ, cal, downsample, plane);
                    return PathObjects.createDetectionObject(roi.translate(translateX, translateY), PathClass.getInstance(className));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Method to get a semantic channel from an image plus and return an instance segmentation in the
     * form of QuPath objects.
     */
    static Collection<PathObject> processSemantic(ImagePlus imp, String className, int channel, int label, double downsample,
                                                  ImageData<BufferedImage> imageData, RegionRequest request, double translateX, double translateY) {
        // Create ROIs from thresholds
        imp.setC(channel); // Set the channel index (1-based)
        ImageProcessor ip = imp.getProcessor(); // Get the ImageProcessor of the specified channel
        ip.setThreshold(label, label, ImageProcessor.NO_LUT_UPDATE);
        ImageProcessor ip_mask = ip.createMask(); // image processor
        ImagePlus imp_mask = new ImagePlus("Binary Mask", ip_mask); // image processor to image plus

        int options_add_manager = ParticleAnalyzer.SHOW_MASKS + ParticleAnalyzer.ADD_TO_MANAGER + ParticleAnalyzer.COMPOSITE_ROIS;
        int measurements_area = Measurements.AREA;
        ImagePlus binaryMask = analyzeParticles(imp_mask, options_add_manager, measurements_area, 0, Double.POSITIVE_INFINITY, 0, 1);
        RoiManager rm = RoiManager.getInstance();
        rm.setVisible(false);
        var roiList = rm.getRoisAsArray();
        rm.close();

        // Convert ImageJ ROIs to QuPath detections
        ImagePlane plane = ImagePlane.getDefaultPlane();
        Calibration cal = imp.getCalibration();

        return Arrays.stream(roiList)
                .map(roi -> {
                    var roiIJ = IJTools.convertToROI(roi, cal, downsample, plane);
                    return PathObjects.createDetectionObject(roiIJ.translate(translateX, translateY), PathClass.getInstance(className));
                })
                .collect(Collectors.toSet());
    }


    /**
     * Segmentation pipeline
     */
    public static Collection<PathObject> runAimSeg(Path modelPath) throws IOException {
        //Some parameters

        // temporary path, use weights_tem.pt or weights_brightfield.pt model
        var uri = modelPath.toUri();
        var dataType = "EM"; // "EM or BF"

        // Image data
        ImageData<BufferedImage> imageData = QP.getCurrentImageData();

        // Model parameters
        int inputWidth = 512;
        int inputHeight = inputWidth;
        int nChannels = 1;
        var padding = Padding.symmetric(32);
        var layout = "NCHW";
        int[] inputShape = new int[] {1, nChannels, inputHeight, inputWidth};

        // Image parameters
        double targetPixelSizeMicrons = dataType.equals("EM") ? 0.008 : 0.07;
        double minDiameterMicrons = dataType.equals("BF") ? 0.2 : 1.0;
        double downsample = calculateDownsampleFactor(imageData, targetPixelSizeMicrons, true);

        // Post-processing parameters
        double min_threshold = 0.7;
        double max_threshold = 1;

        // Get an ImageJ representation of the output
        ImagePlus impOutput;

        // Use a selected annotation if we have one, otherwise request pixels for the full image
        double translateX = 0.0;
        double translateY = 0.0;

        var selectedObject = QP.getSelectedObject();
        RegionRequest request;
        var server = imageData.getServer();
        if (selectedObject != null && selectedObject.isAnnotation()) {
            var roi = selectedObject.getROI();
            translateX = roi.getBoundsX();
            translateY = roi.getBoundsY();
            request = RegionRequest.createInstance(server.getPath(), downsample, roi);
        } else {
            request = RegionRequest.createInstance(server, downsample);
        }

        // Run model on the specified image region
        impOutput = modelInference(uri, layout, inputWidth, inputHeight, padding, inputShape, imageData, server, request);

        // Instance segmentation on model prediction
        var fibres = processSDT(impOutput, dataType, targetPixelSizeMicrons, minDiameterMicrons, "Fibre", 2, min_threshold, max_threshold, downsample, imageData, request, translateX, translateY);
        QP.addObjects(fibres);
        var axons = processSDT(impOutput, dataType, targetPixelSizeMicrons, minDiameterMicrons, "Axon", 3, min_threshold, max_threshold, downsample, imageData, request, translateX, translateY);
        QP.addObjects(axons);
        Collection<PathObject> tongues = List.of();
        if (dataType.equals("EM")) {
            tongues = processSemantic(impOutput, "Inner Tongue", 1, 2, downsample, imageData, request, translateX, translateY);
            QP.addObjects(tongues);
        }

        HierarchyTools.updateHierarchy(fibres, axons, tongues, dataType);

        // lock selected annotation
        if (selectedObject != null && !selectedObject.isLocked()) {
            selectedObject.setLocked(true);
        }
        return Stream.of(fibres.stream(), axons.stream(), tongues.stream()).flatMap(s -> s).collect(Collectors.toSet());
    }


}