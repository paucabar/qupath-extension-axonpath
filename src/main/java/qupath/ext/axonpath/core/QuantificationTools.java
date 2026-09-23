package qupath.ext.axonpath.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.analysis.features.ObjectMeasurements.Compartments;
import qupath.lib.analysis.features.ObjectMeasurements.Measurements;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Computes morphometric and intensity-based features from myelinated fibre objects organised
 * in a hierarchy. Two public methods are provided:
 * <ul>
 *   <li>{@link #computeFeatures} — shape metrics (area, circularity, solidity, g-ratio)</li>
 *   <li>{@link #computeIntensityFeatures} — per-compartment intensity statistics</li>
 * </ul>
 * Both methods support either Fibre &gt; InnerCylinder &gt; Axon (EM) or Fibre &gt; Axon
 * (brightfield) hierarchy structures.
 */
public class QuantificationTools {

    private static final Logger logger = LoggerFactory.getLogger(QuantificationTools.class);

    private static final Collection<Measurements> ALL_INTENSITY_MEASUREMENTS = EnumSet.allOf(Measurements.class);
    private static final double INTENSITY_DOWNSAMPLE = 1.0;

    private QuantificationTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    public static void computeFeatures(ImageData<BufferedImage> imageData, Collection<PathObject> fibres) {
        double pixelHeight = imageData.getServer().getPixelCalibration().getPixelHeightMicrons();
        double pixelWidth = imageData.getServer().getPixelCalibration().getPixelWidthMicrons();

        if (pixelHeight != pixelWidth) {
            throw new IllegalArgumentException(
                "Pixel calibration is not isotropic (height: " + pixelHeight + ", width: " + pixelWidth + "). Isotropic XY pixels are required.");
        }

        for (var fibre : fibres) {
            boolean withInnerCylinders = fibre.getChildObjects().stream()
                    .anyMatch(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"));

            double fibreArea = fibre.getROI().getScaledArea(pixelWidth, pixelHeight);
            double fibreCircularity = RoiTools.getCircularity(fibre.getROI());
            double fibreSolidity = fibre.getROI().getSolidity();

            double axonArea = 0;
            double innerCylinderArea = 0;
            int axonCount = 0;

            String fibreId = fibre.getID().toString();
            if (withInnerCylinders) {
                // Fibre > InnerCylinder > Axon
                for (var innerCylinder : fibre.getChildObjects()) {
                    if (innerCylinder.getPathClass() != PathClass.getInstance("InnerCylinder"))
                        throw new IllegalStateException(
                            "Expected InnerCylinder child of Fibre, got: " + innerCylinder.getPathClass());
                    double icArea = innerCylinder.getROI().getScaledArea(pixelWidth, pixelHeight);
                    innerCylinderArea += icArea;
                    try (var ml = innerCylinder.getMeasurementList()) {
                        ml.put("Area", icArea);
                        ml.put("Circularity", RoiTools.getCircularity(innerCylinder.getROI()));
                        ml.put("Solidity", innerCylinder.getROI().getSolidity());
                    }
                    innerCylinder.getMetadata().put("Parent Fibre ID", fibreId);
                    for (var axon : innerCylinder.getChildObjects()) {
                        if (axon.getPathClass() != PathClass.getInstance("Axon"))
                            throw new IllegalStateException(
                                "Expected Axon child of InnerCylinder, got: " + axon.getPathClass());
                        double aArea = axon.getROI().getScaledArea(pixelWidth, pixelHeight);
                        axonArea += aArea;
                        axonCount++;
                        try (var ml = axon.getMeasurementList()) {
                            ml.put("Area", aArea);
                            ml.put("Circularity", RoiTools.getCircularity(axon.getROI()));
                            ml.put("Solidity", axon.getROI().getSolidity());
                        }
                        axon.getMetadata().put("Parent Fibre ID", fibreId);
                    }
                }
            } else {
                // Fibre > Axon
                for (var axon : fibre.getChildObjects()) {
                    if (axon.getPathClass() != PathClass.getInstance("Axon"))
                        throw new IllegalStateException(
                            "Expected Axon child of Fibre, got: " + axon.getPathClass());
                    double aArea = axon.getROI().getScaledArea(pixelWidth, pixelHeight);
                    axonArea += aArea;
                    axonCount++;
                    try (var ml = axon.getMeasurementList()) {
                        ml.put("Area", aArea);
                        ml.put("Circularity", RoiTools.getCircularity(axon.getROI()));
                        ml.put("Solidity", axon.getROI().getSolidity());
                    }
                    axon.getMetadata().put("Parent Fibre ID", fibreId);
                }
            }

            // G-ratio calculations
            double fibreDiameter = 2 * Math.sqrt(fibreArea / Math.PI);
            double axonDiameter = 2 * Math.sqrt(axonArea / Math.PI);
            double axonGratio = axonDiameter / fibreDiameter;

            // Store measurements
            try (var ml = fibre.getMeasurementList()) {
                ml.put("Fibre Area", fibreArea);
                ml.put("Fibre Circularity", fibreCircularity);
                ml.put("Fibre Solidity", fibreSolidity);
                ml.put("Axon Area", axonArea);
                ml.put("Axon Count", axonCount);
                ml.put("Axon g-ratio", axonGratio);

                if (withInnerCylinders) {
                    double innerCylinderDiameter = 2 * Math.sqrt(innerCylinderArea / Math.PI);
                    double myelinGratio = innerCylinderDiameter / fibreDiameter;
                    ml.put("InnerCylinder Area", innerCylinderArea);
                    ml.put("Myelin g-ratio", myelinGratio);
                }
            }
        }
    }

    /**
     * Computes per-compartment intensity measurements for each fibre and its children.
     * <p>
     * All compartment measurements are stored on the Fibre object for unified export:
     * <ul>
     *   <li>{@code "Fibre: <channel>: <stat>"} — whole fibre ROI</li>
     *   <li>{@code "Myelin: <channel>: <stat>"} — fibre minus union of InnerCylinders (EM) or Axons (brightfield)</li>
     *   <li>{@code "InnerTongue: <channel>: <stat>"} — union of InnerCylinders minus union of Axons (EM only)</li>
     *   <li>{@code "Axon: <channel>: <stat>"} — union of all Axon ROIs</li>
     * </ul>
     * Individual Axon objects also receive direct measurements ({@code "<channel>: <stat>"})
     * for per-axon analysis in multi-axon fibres.
     * <p>
     * Measurements are computed at full resolution (downsample = 1.0). Failures for individual
     * fibres are logged as warnings and do not abort the batch.
     *
     * @param imageData the image data providing the pixel server
     * @param fibres    the Fibre objects to measure
     */
    public static void computeIntensityFeatures(ImageData<BufferedImage> imageData, Collection<PathObject> fibres) {
        var server = imageData.getServer();
        for (var fibre : fibres) {
            try {
                computeIntensityFeaturesForFibre(server, fibre);
            } catch (IOException e) {
                logger.warn("Could not compute intensity features for fibre {}: {}", fibre.getID(), e.getMessage());
            }
        }
    }

    private static void computeIntensityFeaturesForFibre(ImageServer<BufferedImage> server, PathObject fibre) throws IOException {
        boolean withInnerCylinders = fibre.getChildObjects().stream()
                .anyMatch(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"));

        ROI fibreROI = fibre.getROI();

        if (withInnerCylinders) {
            // EM mode: Fibre > InnerCylinder > Axon
            List<ROI> icROIs = fibre.getChildObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"))
                    .map(PathObject::getROI)
                    .collect(Collectors.toList());

            List<ROI> axonROIs = fibre.getChildObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"))
                    .flatMap(ic -> ic.getChildObjects().stream())
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                    .map(PathObject::getROI)
                    .collect(Collectors.toList());

            if (icROIs.isEmpty() || axonROIs.isEmpty()) {
                logger.debug("Skipping intensity features for fibre {} - missing InnerCylinder or Axon children", fibre.getID());
                return;
            }

            ROI unionIC = RoiTools.union(icROIs);
            ROI unionAxon = RoiTools.union(axonROIs);
            ROI myelinROI = RoiTools.combineROIs(fibreROI, unionIC, RoiTools.CombineOp.SUBTRACT);
            ROI innerTongueROI = RoiTools.combineROIs(unionIC, unionAxon, RoiTools.CombineOp.SUBTRACT);

            // All compartments stored on the Fibre object
            measureWithPrefix(server, fibreROI, fibre, "Fibre: ");
            measureWithPrefix(server, myelinROI, fibre, "Myelin: ");
            measureWithPrefix(server, innerTongueROI, fibre, "InnerTongue: ");
            measureWithPrefix(server, unionAxon, fibre, "Axon: ");

            // Individual Axon measurements stored on each Axon object
            for (var ic : fibre.getChildObjects()) {
                if (ic.getPathClass() != PathClass.getInstance("InnerCylinder")) continue;
                for (var axon : ic.getChildObjects()) {
                    if (axon.getPathClass() != PathClass.getInstance("Axon")) continue;
                    ObjectMeasurements.addIntensityMeasurements(server, axon, INTENSITY_DOWNSAMPLE,
                            ALL_INTENSITY_MEASUREMENTS, List.of(Compartments.CELL));
                }
            }

        } else {
            // Brightfield mode: Fibre > Axon
            List<ROI> axonROIs = fibre.getChildObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                    .map(PathObject::getROI)
                    .collect(Collectors.toList());

            if (axonROIs.isEmpty()) {
                logger.debug("Skipping intensity features for fibre {} - no Axon children", fibre.getID());
                return;
            }

            ROI unionAxon = RoiTools.union(axonROIs);
            ROI myelinROI = RoiTools.combineROIs(fibreROI, unionAxon, RoiTools.CombineOp.SUBTRACT);

            // All compartments stored on the Fibre object
            measureWithPrefix(server, fibreROI, fibre, "Fibre: ");
            measureWithPrefix(server, myelinROI, fibre, "Myelin: ");
            measureWithPrefix(server, unionAxon, fibre, "Axon: ");

            // Individual Axon measurements stored on each Axon object
            for (var axon : fibre.getChildObjects()) {
                if (axon.getPathClass() != PathClass.getInstance("Axon")) continue;
                ObjectMeasurements.addIntensityMeasurements(server, axon, INTENSITY_DOWNSAMPLE,
                        ALL_INTENSITY_MEASUREMENTS, List.of(Compartments.CELL));
            }
        }
    }

    /**
     * Measures a ROI using all intensity statistics and copies the results to {@code target}'s
     * measurement list, prefixing each name with {@code prefix}.
     * Skips silently if the ROI is null or has no area (e.g. an empty subtraction result).
     */
    private static void measureWithPrefix(ImageServer<BufferedImage> server, ROI roi, PathObject target, String prefix) throws IOException {
        if (roi == null || roi.getArea() <= 0) return;
        var tempObject = PathObjects.createDetectionObject(roi);
        ObjectMeasurements.addIntensityMeasurements(server, tempObject, INTENSITY_DOWNSAMPLE,
                ALL_INTENSITY_MEASUREMENTS, List.of(Compartments.CELL));
        try (var ml = target.getMeasurementList()) {
            for (var name : tempObject.getMeasurementList().getMeasurementNames()) {
                ml.put(prefix + name, tempObject.getMeasurementList().get(name));
            }
        }
    }
}
