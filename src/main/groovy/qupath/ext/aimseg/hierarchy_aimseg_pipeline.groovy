/**
 * This script establishes meaningful hierarchies, recognising that a fibre can contain multiple
 * inner tongue objects, and an inner tongue object may contain multiple axon objects.
 */


/**
 * Some imports
 */


import org.locationtech.jts.geom.Geometry
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.PathObjectHierarchy

import static qupath.lib.scripting.QP.*


// Method to compute the intersection over Object2 area (IoO2A) between two object classes 
// and create hierarchical relationships
// If IoO2A == 1, Object2 is added below Object1 immediately
// If 0.9 < IoO2A < 1, Object2 is replaced by the intersection before being added below Object1

def establishHierarchyBasedOnIoO2(objectsPrimary, objectsSecondary) {
    // Initialise the PathObjectHierarchy
    def pathHierarchy = new PathObjectHierarchy()

    objectsPrimary.each { parentObject ->
        Geometry parentGeometry = parentObject.getROI().getGeometry()
        
        objectsSecondary.each { secondaryObject ->
            Geometry secondaryGeometry = secondaryObject.getROI().getGeometry()
            double intersectionArea = parentGeometry.intersection(secondaryGeometry).getArea()
            
            if (intersectionArea > 0) {
                def parentArea = parentGeometry.getArea()
                def secondaryArea = secondaryGeometry.getArea()
                double ioo2 = intersectionArea / secondaryArea
                
                // Establish hierarchy directly if IoO2 == 1
                if (ioo2 == 1) {
                    pathHierarchy.addObject(secondaryObject, false) // Add the secondary object
                    pathHierarchy.addObjectBelowParent(parentObject, secondaryObject, true)
                }
                // Replace secondary object with intersection and establish hierarchy if 0.9 < IoO2 < 1
                else if (ioo2 > 0.9) {
                    def intersectionGeometry = parentGeometry.intersection(secondaryGeometry)
                    def intersectionROI = GeometryTools.geometryToROI(intersectionGeometry, secondaryObject.getROI().getImagePlane())
                    
                    // Create the intersection object as a detection with the same PathClass
                    def intersectionObject = PathObjects.createDetectionObject(intersectionROI, secondaryObject.getPathClass())
                    
                    // Replace the secondary object with the intersection object
                    //pathHierarchy.removeObject(secondaryObject, false) // Remove the original object
                    pathHierarchy.addObject(intersectionObject, false) // Add the intersection object
                    
                    // Establish the hierarchy
                    pathHierarchy.addObjectBelowParent(parentObject, intersectionObject, false) // true to fireUpdate
                }
            }
        }
    }
    
    fireHierarchyUpdate()
}


/**
 * This function processes Fibre objects in QuPath by verifying and filtering their hierarchical relationships.
 * Depending on the data type ("EM" or "BF"), it enforces specific hierarchical relationships:
 * 
 * - For "EM": Fibre > Inner Tongue > Axon (at least one Inner Tongue and one Axon descendant required).
 * - For "BF": Fibre > Axon (at least one Axon required; Inner Tongue is ignored).
 * 
 * Invalid Fibre objects (those not meeting the hierarchy criteria) are removed from the hierarchy,
 * along with their associated child objects.
 * 
 * @param dataType A string ("EM" or "BF") specifying the type of data and hierarchy logic to apply.
 * @return A collection of invalid Fibre objects that were removed.
 */
Collection<PathObject> removeChildless(String dataType) {
    // Get all Fibre objects
    Collection<PathObject> fibre_objects = getDetectionObjects().findAll { it.getPathClass() == getPathClass("Fibre") }
    
    // Create a map to store Fibre objects and their valid child objects
    Map<PathObject, Collection<PathObject>> validFibreToChildrenMap = [:]
    
    // Iterate through each Fibre object
    fibre_objects.each { fibre ->
        if (dataType == "EM") {
            // For EM: Fibre > Inner Tongue > Axon hierarchy
            Collection<PathObject> firstLevelChildren = fibre.getChildObjects().findAll { it.getPathClass() == getPathClass("Inner Tongue") }
            Collection<PathObject> secondLevelChildren = firstLevelChildren.collectMany { it.getChildObjects() }
                                                         .findAll { it.getPathClass() == getPathClass("Axon") }
            // Check if the Fibre object has at least one Inner Tongue and one Axon
            if (firstLevelChildren && secondLevelChildren) {
                validFibreToChildrenMap[fibre] = firstLevelChildren + secondLevelChildren
            }
        } else if (dataType == "BF") {
            // For BF: Fibre > Axon hierarchy
            Collection<PathObject> firstLevelChildren = fibre.getChildObjects().findAll { it.getPathClass() == getPathClass("Axon") }
            // Check if the Fibre object has at least one Axon
            if (firstLevelChildren) {
                validFibreToChildrenMap[fibre] = firstLevelChildren
            }
        }
    }
    
    // Output the results for debugging or further processing
    println "Valid Fibre objects and their children:"
    validFibreToChildrenMap.each { fibre, children ->
        println "Fibre: ${fibre.getID()} has ${children.size()} valid child objects ${children}"
    }
    
    // Remove invalid Fibre objects and their children
    def invalidFibreObjects = fibre_objects - validFibreToChildrenMap.keySet()
    println "Removing ${invalidFibreObjects.size()} invalid Fibre objects..."
    removeObjects(invalidFibreObjects, false) // true to remove children
    
    return invalidFibreObjects
}


// Method to identify all the objects with no parent object

Collection<PathObject> removeParentless (objects) {
    println "Checking ${objects.size()} objects"
    def parentless = objects.findAll { it.getLevel() == 1 } // level 1 because image is the 'root'
    println "Found ${parentless.size()} parentless objects"
    removeObjects (parentless, false) // true to keep children objects
    return parentless
}




/**
 * Pipeline to update hierarchy
 */
static void updateHierarchy() {
    // Set microscopy data
    def dataType = "EM" // "EM or BF"

    // Establish hierarchy
    Collection<PathObject> fibre_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Fibre")) }
    Collection<PathObject> axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) }
    Collection<PathObject> inner_tongue_objects
    if (dataType == "EM") {
        inner_tongue_objects = getDetectionObjects().findAll {it.getPathClass() == getPathClass("Inner Tongue")}
        println inner_tongue_objects
    }

    if (dataType == "EM") {
        println "Comparing ${fibre_objects.size()} fibre objects vs ${inner_tongue_objects.size()} inner tongue objects"
        removeObjects(inner_tongue_objects)
        establishHierarchyBasedOnIoO2 (fibre_objects, inner_tongue_objects)
        inner_tongue_objects = getDetectionObjects().findAll {it.getPathClass() == getPathClass("Inner Tongue")} // updated collection

        println "Comparing ${inner_tongue_objects.size()} inner tongue objects vs ${axon_objects.size()} axon objects"
        removeObjects(axon_objects)
        establishHierarchyBasedOnIoO2 (inner_tongue_objects, axon_objects)
        axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) } // update collection
    } else if (dataType == "BF") {
        println "Comparing ${fibre_objects.size()} fibre objects vs ${axon_objects.size()} axon objects"
        removeObjects(axon_objects)
        establishHierarchyBasedOnIoO2 (fibre_objects, axon_objects)
        axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) } // update collection
    }

// Remove objects with an invalid hierarchy
    if (dataType == "EM") {
        Collection<PathObject> combined_objects = axon_objects + inner_tongue_objects
        Collection<PathObject> invalidParentlessObjects = removeParentless (combined_objects) // Storing invalid objects, could be useful for semi-automated annotation
    } else if (dataType == "BF") {
        Collection<PathObject> invalidParentlessObjects = removeParentless (axon_objects) // Storing invalid objects, could be useful for semi-automated annotation
    }

    // Remove objects invalid for quantification
    Collection<PathObject> invalidFibreObjects = removeChildless(dataType) // Storing invalid objects, could be useful for semi-automated annotation
}

