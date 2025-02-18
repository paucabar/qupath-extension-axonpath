/*
 * This script is designed to extract morphometric features from myelinated fibre objects,
 * assigning the features directly to the parent "Fibre" objects to enable efficient
 * generation of a result table. It assumes the image has been processed with AimSeg 
 * detections, where objects are already organised into meaningful hierarchical relationships.
 * As part of the process, the script validates the detected objects, ensuring that 
 * only biologically relevant data is retained by removing invalid objects prior to quantification.
 */


/**
 * Some imports
 */


import qupath.lib.roi.RoiTools

import static qupath.lib.scripting.QP.*

/**
 * Define some methods
 */



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

void computeFeatures(ImageData imageData, String dataType) {
    // Get calibration
    def pixelHeightMicrons = imageData.getServer().getPixelCalibration().getPixelHeightMicrons()
    def pixelWidthMicrons = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
    
    // Check if the height and width are the same
    if (pixelHeightMicrons != pixelWidthMicrons) {
        throw new IllegalArgumentException("The pixel calibration is not isotropic (Pixel Height: ${pixelHeightMicrons}, Pixel Width: ${pixelWidthMicrons}). This method requires isotropic XY pixels.")
    }
    
    // Use the pixel size (assuming isotropic calibration)
    def pixelSizeSquaredMicrons = pixelHeightMicrons * pixelWidthMicrons
    
    parentDetections = getDetectionObjects().findAll{it.getPathClass() == getPathClass("Fibre")}
    def roiTools = new RoiTools()
    
    parentDetections.each { parent ->
    
        // define metrics
        double axon_area = 0
        double fibre_area = 0
        double axon_gratio = 0
        int axon_objects = 0
        double fibre_circularity = 0
        double fibre_solidity = 0
        double inner_region_area = 0
        double myelin_gratio = 0
    
        // get object areas
        if (dataType == "EM") {
            childDetections = parent.getChildObjects()
            childDetections.each { child ->
                inner_region_area += child.getROI().getArea() * pixelSizeSquaredMicrons
                grandchildDetections = child.getChildObjects()
                axon_objects += grandchildDetections.size()
                
                grandchildDetections.each { grandchild ->
                    axon_area += grandchild.getROI().getArea() * pixelSizeSquaredMicrons
                }
            }
        } else if (dataType == "BF") {
            childDetections = parent.getChildObjects()
            childDetections.each { child ->
                axon_area += child.getROI().getArea() * pixelSizeSquaredMicrons
                axon_objects = childDetections.size()
            }
        }
                
        // fibre metrics
        fibre_area = parent.getROI().getArea() * pixelSizeSquaredMicrons
        fibre_circularity = roiTools.getCircularity(parent.getROI())
        fibre_solidity = parent.getROI().getSolidity()
        
        // g-ratio netrics
        double fibre_diameter = 2 * Math.sqrt(fibre_area / Math.PI)
        double axon_diameter = 2 * Math.sqrt(axon_area / Math.PI)
        
        axon_gratio = axon_diameter / fibre_diameter
        if (dataType == "EM") {
            double inreg_diameter = 2 * Math.sqrt(inner_region_area / Math.PI)
            myelin_gratio = inreg_diameter / fibre_diameter
        }
        
        // add measurements
        parent.getMeasurementList().putMeasurement("Axon Area", axon_area)
        parent.getMeasurementList().putMeasurement("Fibre Area", fibre_area)
        parent.getMeasurementList().putMeasurement("Axon g-ratio", axon_gratio)
        parent.getMeasurementList().putMeasurement("Axon Objects", axon_objects)
        parent.getMeasurementList().putMeasurement("Fibre Circularity", fibre_circularity)
        parent.getMeasurementList().putMeasurement("Fibre Solidity", fibre_solidity)
        if (dataType == "EM") {
            parent.getMeasurementList().putMeasurement("Inner Region Area", inner_region_area)
            parent.getMeasurementList().putMeasurement("Myelin g-ratio", myelin_gratio)
        }

    }
}




/**
 * Quantification pipeline
 */
 
// get image data
String dataType = "EM" // EM or BF
ImageData imageData = getCurrentImageData()

// Feature extraction
computeFeatures(imageData, dataType)
