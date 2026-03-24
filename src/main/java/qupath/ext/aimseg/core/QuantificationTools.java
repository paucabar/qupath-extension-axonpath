package qupath.ext.aimseg.core;

import java.util.Collection;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.RoiTools;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;

/**
 * Computes morphometric features from myelinated fibre objects organised in a hierarchy.
 * Depending on the hierarchy structure, either Fibre > Inner Tongue > Axon (EM)
 * or Fibre > Axon (brightfield) metrics are calculated and assigned to each fibre.
 */
public class QuantificationTools {

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
            boolean withInnerTongues = fibre.getChildObjects().stream()
                    .anyMatch(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"));

            double fibreArea = fibre.getROI().getScaledArea(pixelWidth, pixelHeight);
            double fibreCircularity = RoiTools.getCircularity(fibre.getROI());
            double fibreSolidity = fibre.getROI().getSolidity();

            double axonArea = 0;
            double innerTongueArea = 0;
            int axonCount = 0;

            if (withInnerTongues) {
                // Fibre > Inner Tongue > Axon
                for (var innerTongue : fibre.getChildObjects()) {
                    if (innerTongue.getPathClass() != PathClass.getInstance("Inner Tongue"))
                        throw new IllegalStateException(
                            "Expected Inner Tongue child of Fibre, got: " + innerTongue.getPathClass());
                    innerTongueArea += innerTongue.getROI().getScaledArea(pixelWidth, pixelHeight);
                    for (var axon : innerTongue.getChildObjects()) {
                        if (axon.getPathClass() != PathClass.getInstance("Axon"))
                            throw new IllegalStateException(
                                "Expected Axon child of Inner Tongue, got: " + axon.getPathClass());
                        axonArea += axon.getROI().getScaledArea(pixelWidth, pixelHeight);
                        axonCount++;
                    }
                }
            } else {
                // Fibre > Axon
                for (var axon : fibre.getChildObjects()) {
                    if (axon.getPathClass() != PathClass.getInstance("Axon"))
                        throw new IllegalStateException(
                            "Expected Axon child of Fibre, got: " + axon.getPathClass());
                    axonArea += axon.getROI().getScaledArea(pixelWidth, pixelHeight);
                    axonCount++;
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

                if (withInnerTongues) {
                    double innerTongueDiameter = 2 * Math.sqrt(innerTongueArea / Math.PI);
                    double myelinGratio = innerTongueDiameter / fibreDiameter;
                    ml.put("Inner Tongue Area", innerTongueArea);
                    ml.put("Myelin g-ratio", myelinGratio);
                }
            }
        }
    }
}