/**
 * This script establishes meaningful hierarchies, recognising that a fibre can contain multiple
 * inner tongue objects, and an inner tongue object may contain multiple axon objects.
 */


/**
 * Some imports
 */

import org.locationtech.jts.geom.Geometry
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.objects.PathObjects

import static qupath.lib.gui.scripting.QPEx.*


// Method to compute the intersection over Object2 area (IoO2A) between two object classes 
// and create hierarchical relationships
// If IoO2A == 1, Object2 is added below Object1 immediately
// If 0.9 < IoO2A < 1, Object2 is replaced by the intersection before being added below Object1

def establishHierarchyBasedOnIoO2(objectsPrimary, objectsSecondary) {
    // Initialise the PathObjectHierarchy
    def pathHierarchy = new PathObjectHierarchy()

    objectsPrimary.eachWithIndex { parentObject, indexPrimary ->
        Geometry parentGeometry = parentObject.getROI().getGeometry()
        
        objectsSecondary.eachWithIndex { secondaryObject, indexSecondary ->
            Geometry secondaryGeometry = secondaryObject.getROI().getGeometry()
            float intersectionArea = parentGeometry.intersection(secondaryGeometry).getArea()
            
            if (intersectionArea > 0) {
                def parentArea = parentGeometry.getArea()
                def secondaryArea = secondaryGeometry.getArea()
                float ioo2 = intersectionArea / secondaryArea
                
                // Establish hierarchy directly if IoO2 == 1
                if (ioo2 == 1) {
                    pathHierarchy.addObjectBelowParent(parentObject, secondaryObject, true)
                }
                // Replace secondary object with intersection and establish hierarchy if 0.9 < IoO2 < 1
                else if (ioo2 > 0.9) {
                    def intersectionGeometry = parentGeometry.intersection(secondaryGeometry)
                    def intersectionROI = GeometryTools.geometryToROI(intersectionGeometry, secondaryObject.getROI().getImagePlane())
                    
                    // Create the intersection object as a detection with the same PathClass
                    def intersectionObject = PathObjects.createDetectionObject(intersectionROI, secondaryObject.getPathClass())
                    
                    // Replace the secondary object with the intersection object
                    pathHierarchy.removeObject(secondaryObject, true) // Remove the original object
                    pathHierarchy.addObject(intersectionObject, false) // Add the intersection object
                    
                    // Establish the hierarchy
                    pathHierarchy.addObjectBelowParent(parentObject, intersectionObject, true)
                }
            }
        }
    }
}

// Method to identify all the objects with no parent object

def findParentless (objects) {
    println "Checking ${objects.size()} objects"
    def parentless = objects.findAll { it.getLevel() == 1 } // level 1 because image is the 'root'
    println "Found ${parentless.size()} parentless objects"
    return parentless
}




/**
 * Pipeline to update hierarchy
 */


// Establish hierarchy
def fibre_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Fibre")) }
def axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) }
def inner_tongue_objects = getDetectionObjects().findAll {it.getPathClass() == getPathClass("Inner Tongue")}

println "Comparing ${fibre_objects.size()} fibre objects vs ${inner_tongue_objects.size()} inner tongue objects"
establishHierarchyBasedOnIoO2 (fibre_objects, inner_tongue_objects)

println "Comparing ${inner_tongue_objects.size()} inner tongue objects vs ${axon_objects.size()} axon objects"
establishHierarchyBasedOnIoO2 (inner_tongue_objects, axon_objects)

// Remove parentless pbjects
def combined_objects = axon_objects + inner_tongue_objects
parentless = findParentless (combined_objects)
removeObjects (parentless, false) // true to keep children objects