package qupath.ext.aimseg.core;
/*
 * This script is designed to extract morphometric features from myelinated fibre objects,
 * assigning the features directly to the parent "Fibre" objects to enable efficient
 * generation of a result table. It assumes the image has been processed with AimSeg 
 * detections, where objects are already organised into meaningful hierarchical relationships.
 * As part of the process, the script validates the detected objects, ensuring that 
 * only biologically relevant data is retained by removing invalid objects prior to quantification.
 */




import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.RoiTools;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import qupath.lib.scripting.QP;


/**
 * Define some methods
 */

class QuantificationTools {

    /**
     * This function calculates myelin metrics from QuPath objects organised in a hierarchical structure.
     * The assumed hierarchy is as follows: Fibre > Inner Tongue > Axon.
     * This means that each Fibre object contains one or more Inner Tongue objects,
     * and each Inner Tongue object contains one or more Axon objects.
     *
     * The function sums the areas of all Axon and Inner Tongue objects within a Fibre object
     * to produce a single area measurement for each Fibre.
     * All area calculations take into account the image calibration, specifically the pixel size
     * in square microns, ensuring accurate and meaningful results.
     */

    static void computeFeatures(ImageData<BufferedImage> imageData, String dataType) {
        // Get calibration
        double pixelHeightMicrons = imageData.getServer().getPixelCalibration().getPixelHeightMicrons();
        double pixelWidthMicrons = imageData.getServer().getPixelCalibration().getPixelWidthMicrons();

        // Check if the height and width are the same
        if (pixelHeightMicrons != pixelWidthMicrons) {
            throw new IllegalArgumentException("The pixel calibration is not isotropic (Pixel Height: ${pixelHeightMicrons}, Pixel Width: ${pixelWidthMicrons}). This method requires isotropic XY pixels.");
        }

        // Use the pixel size (assuming isotropic calibration)
        double pixelSizeSquaredMicrons = pixelHeightMicrons * pixelWidthMicrons;

        var parentDetections = QP.getDetectionObjects().stream().filter(it -> it.getPathClass() == PathClass.getInstance("Fibre")).toList();

        for (var parent: parentDetections) {
            // define metrics
            double axonArea = 0;
            double fibreArea = 0;
            double axonGratio = 0;
            int axonObjects = 0;
            double fibreCircularity = 0;
            double fibreSolidity = 0;
            double innerRegionArea = 0;
            double myelinGratio = 0;

            // get object areas
            if (dataType.equals("EM")) {
                var childDetections = parent.getChildObjects();
                for (var child: childDetections) {
                    innerRegionArea += child.getROI().getArea() * pixelSizeSquaredMicrons;
                    var grandchildDetections = child.getChildObjects();
                    axonObjects += grandchildDetections.size();

                    for (var grandchild: grandchildDetections) {
                        axonArea += grandchild.getROI().getArea() * pixelSizeSquaredMicrons;
                    }
                }
            } else if (dataType.equals("BF")) {
                var childDetections = parent.getChildObjects();
                for (var child: childDetections) {
                    axonArea += child.getROI().getArea() * pixelSizeSquaredMicrons;
                    axonObjects = childDetections.size();
                }
            }

            // fibre metrics
            fibreArea = parent.getROI().getArea() * pixelSizeSquaredMicrons;
            fibreCircularity = RoiTools.getCircularity(parent.getROI());
            fibreSolidity = parent.getROI().getSolidity();

            // g-ratio netrics
            double fibreDiameter = 2 * Math.sqrt(fibreArea / Math.PI);
            double axonDiameter = 2 * Math.sqrt(axonArea / Math.PI);

            axonGratio = axonDiameter / fibreDiameter;
            if (dataType.equals("EM")) {
                double inregDiameter = 2 * Math.sqrt(innerRegionArea / Math.PI);
                myelinGratio = inregDiameter / fibreDiameter;
            }

            // add measurements
            parent.getMeasurementList().put("Axon Area", axonArea);
            parent.getMeasurementList().put("Fibre Area", fibreArea);
            parent.getMeasurementList().put("Axon g-ratio", axonGratio);
            parent.getMeasurementList().put("Axon Objects", axonObjects);
            parent.getMeasurementList().put("Fibre Circularity", fibreCircularity);
            parent.getMeasurementList().put("Fibre Solidity", fibreSolidity);
            if (dataType.equals("EM")) {
                parent.getMeasurementList().put("Inner Region Area", innerRegionArea);
                parent.getMeasurementList().put("Myelin g-ratio", myelinGratio);
            }

        }
    }


    /**
     * Quantification pipeline
     */
    static void runQuantification(String dataType) {
        // Feature extraction
        computeFeatures(QP.getCurrentImageData(), dataType);
    }

}