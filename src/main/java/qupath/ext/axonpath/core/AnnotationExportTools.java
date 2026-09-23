package qupath.ext.axonpath.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Tools for exporting AxonPath annotation data, either as training-ready image/label tiles
 * or as full-resolution images with GeoJSON annotation files.
 * <p>
 * Both methods handle z-stacks and time-lapse data: output filenames include a {@code _z{n}}
 * suffix for z-stacks, {@code _t{n}} for time-lapse, or both for 3D time-series.
 * Single-plane images use no suffix, preserving the existing 2D naming convention.
 */
public class AnnotationExportTools {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationExportTools.class);

    private AnnotationExportTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Exports downsampled image tiles, semantic masks, and fibre instance labels
     * for all provided images into a structured output directory.
     * <p>
     * Output structure:
     * <pre>
     *   outputDir/images/  — raw image tiles at the given downsample
     *   outputDir/masks/   — semantic labels (0=bg, 1=Fibre, 2=InnerCylinder, 3=Axon)
     *   outputDir/labels/  — fibre instance labels (unique integer per Fibre)
     * </pre>
     * ROI convention: unclassified annotation objects define export regions.
     * If none are present, the whole image is exported — but only for the z-slices/timepoints
     * that actually contain a classified (Fibre/InnerCylinder/Axon) annotation; planes with no
     * classified annotations are skipped entirely.
     * The {@code _roi<n>} suffix is always written regardless of the number of ROIs.
     * <p>
     * Images with no classified annotations are still exported (masks and labels
     * will be all-zero); the image name is reported via {@code onWarn}.
     *
     * @param images     project entries to export
     * @param outputDir  destination directory; subdirs are created automatically
     * @param downsample downsample factor applied to all exported tiles
     * @param onProgress callback receiving (imageName, fraction 0..1); may be null
     * @param onWarn     callback receiving imageName when no classified annotations are found; may be null
     * @throws IOException if a directory cannot be created or a file cannot be written
     */
    public static void exportTraining(
            List<ProjectImageEntry<BufferedImage>> images,
            File outputDir,
            double downsample,
            BiConsumer<String, Double> onProgress,
            Consumer<String> onWarn) throws IOException {

        var imageDir  = new File(outputDir, "images");
        var masksDir  = new File(outputDir, "masks");
        var labelsDir = new File(outputDir, "labels");
        mkdirs(imageDir);
        mkdirs(masksDir);
        mkdirs(labelsDir);

        var fibreClass         = PathClass.getInstance("Fibre");
        var innerCylinderClass = PathClass.getInstance("InnerCylinder");
        var axonClass          = PathClass.getInstance("Axon");

        int n = images.size();
        for (int i = 0; i < n; i++) {
            var entry   = images.get(i);
            String name = entry.getImageName();
            if (onProgress != null) onProgress.accept(name, (double) i / n);

            try (var imageData = entry.readImageData()) {
                if (imageData == null) {
                    logger.warn("Could not read image data for '{}'", name);
                    continue;
                }
                var server    = imageData.getServer();
                var hierarchy = imageData.getHierarchy();
                String base   = baseName(server.getMetadata().getName());

                int nZ = server.nZSlices();
                int nT = server.nTimepoints();
                boolean multiZ = nZ > 1;
                boolean multiT = nT > 1;

                // AxonPath objects may be annotations (mid manual-edit) or detections (the normal
                // post-inference/post-recompute state) — classification is what matters here, not
                // object type, so both are scanned.
                boolean hasClassified = hierarchy.getFlattenedObjectList(null).stream()
                        .filter(o -> o.isAnnotation() || o.isDetection())
                        .anyMatch(a -> a.getPathClass() == fibreClass
                                    || a.getPathClass() == innerCylinderClass
                                    || a.getPathClass() == axonClass);
                if (!hasClassified && onWarn != null)
                    onWarn.accept(name);

                var rois = hierarchy.getAnnotationObjects().stream()
                        .filter(a -> a.getPathClass() == null)
                        .toList();

                if (!rois.isEmpty()) {
                    // ROI-based: one tile per ROI; plane determined by the ROI's own image plane
                    int numRois = rois.size();
                    for (int j = 0; j < numRois; j++) {
                        var roi   = rois.get(j).getROI();
                        int z     = roi.getImagePlane().getZ();
                        int t     = roi.getImagePlane().getT();
                        String suf = base + "_roi" + j + planeSuffix(z, t, multiZ, multiT) + ".tif";

                        try (var semanticServer = new LabeledImageServer.Builder(imageData)
                                .backgroundLabel(0, ColorTools.BLACK)
                                .downsample(downsample)
                                .useAnnotations()
                                .useDetections()
                                .addLabel("Fibre",         1)
                                .addLabel("InnerCylinder", 2)
                                .addLabel("Axon",          3)
                                .multichannelOutput(false)
                                .useFilter(p -> p.getROI().getImagePlane().getZ() == z
                                        && p.getROI().getImagePlane().getT() == t)
                                .build();
                             var instanceServer = new LabeledImageServer.Builder(imageData)
                                .backgroundLabel(0, ColorTools.BLACK)
                                .downsample(downsample)
                                .useAnnotations()
                                .useDetections()
                                .useInstanceLabels()
                                .useFilter(p -> p.getPathClass() == fibreClass
                                        && p.getROI().getImagePlane().getZ() == z
                                        && p.getROI().getImagePlane().getT() == t)
                                .multichannelOutput(false)
                                .build()) {

                            ImageWriterTools.writeImageRegion(server,
                                    RegionRequest.createInstance(server.getPath(), downsample, roi),
                                    new File(imageDir,  suf).getAbsolutePath());
                            ImageWriterTools.writeImageRegion(semanticServer,
                                    RegionRequest.createInstance(semanticServer.getPath(), downsample, roi),
                                    new File(masksDir,  suf).getAbsolutePath());
                            ImageWriterTools.writeImageRegion(instanceServer,
                                    RegionRequest.createInstance(instanceServer.getPath(), downsample, roi),
                                    new File(labelsDir, suf).getAbsolutePath());
                        }
                        if (onProgress != null)
                            onProgress.accept(name, (i + (j + 1.0) / numRois) / n);
                    }
                } else {
                    // Whole-image fallback: export only planes that have classified AxonPath
                    // objects — annotation or detection, classification is what matters here.
                    var classifiedPlanes = hierarchy.getFlattenedObjectList(null).stream()
                            .filter(o -> o.isAnnotation() || o.isDetection())
                            .filter(a -> a.getPathClass() == fibreClass
                                      || a.getPathClass() == innerCylinderClass
                                      || a.getPathClass() == axonClass)
                            .map(a -> a.getROI().getImagePlane())
                            .distinct()
                            .toList();

                    int numPlanes = classifiedPlanes.size();
                    for (int j = 0; j < numPlanes; j++) {
                        var plane = classifiedPlanes.get(j);
                        int z = plane.getZ();
                        int t = plane.getT();
                        String suf = base + "_roi0" + planeSuffix(z, t, multiZ, multiT) + ".tif";

                        try (var semanticServer = new LabeledImageServer.Builder(imageData)
                                .backgroundLabel(0, ColorTools.BLACK)
                                .downsample(downsample)
                                .useAnnotations()
                                .useDetections()
                                .addLabel("Fibre",         1)
                                .addLabel("InnerCylinder", 2)
                                .addLabel("Axon",          3)
                                .multichannelOutput(false)
                                .useFilter(p -> p.getROI().getImagePlane().getZ() == z
                                        && p.getROI().getImagePlane().getT() == t)
                                .build();
                             var instanceServer = new LabeledImageServer.Builder(imageData)
                                .backgroundLabel(0, ColorTools.BLACK)
                                .downsample(downsample)
                                .useAnnotations()
                                .useDetections()
                                .useInstanceLabels()
                                .useFilter(p -> p.getPathClass() == fibreClass
                                        && p.getROI().getImagePlane().getZ() == z
                                        && p.getROI().getImagePlane().getT() == t)
                                .multichannelOutput(false)
                                .build()) {

                            ImageWriterTools.writeImageRegion(server,
                                    RegionRequest.createInstance(server.getPath(), downsample,
                                            0, 0, server.getWidth(), server.getHeight(), z, t),
                                    new File(imageDir,  suf).getAbsolutePath());
                            ImageWriterTools.writeImageRegion(semanticServer,
                                    RegionRequest.createInstance(semanticServer.getPath(), downsample,
                                            0, 0, server.getWidth(), server.getHeight(), z, t),
                                    new File(masksDir,  suf).getAbsolutePath());
                            ImageWriterTools.writeImageRegion(instanceServer,
                                    RegionRequest.createInstance(instanceServer.getPath(), downsample,
                                            0, 0, server.getWidth(), server.getHeight(), z, t),
                                    new File(labelsDir, suf).getAbsolutePath());
                        }
                        if (onProgress != null)
                            onProgress.accept(name, (i + (j + 1.0) / numPlanes) / n);
                    }
                }

            } catch (Exception e) {
                logger.warn("exportTraining: could not process '{}': {}", name, e.getMessage(), e);
            }
        }
        if (onProgress != null) onProgress.accept("Done", 1.0);
    }

    /**
     * Exports full-resolution raw images and GeoJSON annotation files for all provided images.
     * <p>
     * Output structure:
     * <pre>
     *   outputDir/images/      — full-resolution TIF images, one per annotated plane
     *   outputDir/annotations/ — GeoJSON files, one per annotated plane (matching image names)
     * </pre>
     * Only planes that contain at least one annotation object are exported; empty planes
     * are skipped. Each GeoJSON contains only the annotations from its paired plane.
     * All annotation objects are exported regardless of class, making the output
     * suitable for backup or redistribution as a general-purpose annotated dataset.
     *
     * @param images     project entries to export
     * @param outputDir  destination directory; subdirs are created automatically
     * @param onProgress callback receiving (imageName, fraction 0..1); may be null
     * @throws IOException if a directory cannot be created or a file cannot be written
     */
    public static void exportRaw(
            List<ProjectImageEntry<BufferedImage>> images,
            File outputDir,
            BiConsumer<String, Double> onProgress) throws IOException {

        var imageDir       = new File(outputDir, "images");
        var annotationsDir = new File(outputDir, "annotations");
        mkdirs(imageDir);
        mkdirs(annotationsDir);

        int n = images.size();
        for (int i = 0; i < n; i++) {
            var entry   = images.get(i);
            String name = entry.getImageName();
            if (onProgress != null) onProgress.accept(name, (double) i / n);

            try (var imageData = entry.readImageData()) {
                if (imageData == null) {
                    logger.warn("Could not read image data for '{}'", name);
                    continue;
                }
                var server  = imageData.getServer();
                String base = baseName(server.getMetadata().getName());

                boolean multiZ = server.nZSlices() > 1;
                boolean multiT = server.nTimepoints() > 1;

                // One TIF per annotated plane — skip planes with no annotations
                var annotatedPlanes = imageData.getHierarchy().getAnnotationObjects().stream()
                        .map(a -> a.getROI().getImagePlane())
                        .distinct()
                        .toList();

                int numPlanes = annotatedPlanes.size();
                for (int j = 0; j < numPlanes; j++) {
                    var plane = annotatedPlanes.get(j);
                    int z = plane.getZ();
                    int t = plane.getT();
                    String suf = base + planeSuffix(z, t, multiZ, multiT);

                    ImageWriterTools.writeImageRegion(server,
                            RegionRequest.createInstance(server.getPath(), 1.0,
                                    0, 0, server.getWidth(), server.getHeight(), z, t),
                            new File(imageDir, suf + ".tif").getAbsolutePath());

                    // Remap annotations to the default plane (z=0, t=0) so the GeoJSON
                    // loads correctly when the paired TIF is opened as a 2D image in QuPath
                    var defaultPlane = ImagePlane.getDefaultPlane();
                    var planeAnnotations = imageData.getHierarchy().getAnnotationObjects().stream()
                            .filter(a -> a.getROI().getImagePlane().equals(plane))
                            .map(a -> PathObjects.createAnnotationObject(
                                    a.getROI().updatePlane(defaultPlane),
                                    a.getPathClass()))
                            .toList();
                    PathIO.exportObjectsAsGeoJSON(
                            new File(annotationsDir, suf + ".geojson"),
                            planeAnnotations,
                            PathIO.GeoJsonExportOptions.FEATURE_COLLECTION);

                    if (onProgress != null)
                        onProgress.accept(name, (i + (j + 1.0) / numPlanes) / n);
                }

            } catch (Exception e) {
                logger.warn("exportRaw: could not process '{}': {}", name, e.getMessage(), e);
            }
        }
        if (onProgress != null) onProgress.accept("Done", 1.0);
    }

    /**
     * Returns a filename suffix encoding the z-slice and/or timepoint when relevant.
     * Returns an empty string for single-plane images (no suffix needed).
     */
    private static String planeSuffix(int z, int t, boolean multiZ, boolean multiT) {
        String s = "";
        if (multiZ) s += "_z" + z;
        if (multiT) s += "_t" + t;
        return s;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
    }
}
