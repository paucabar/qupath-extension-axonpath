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
import qupath.lib.objects.PathObjects

import static qupath.lib.gui.scripting.QPEx.*


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
            float intersectionArea = parentGeometry.intersection(secondaryGeometry).getArea()
            
            if (intersectionArea > 0) {
                def parentArea = parentGeometry.getArea()
                def secondaryArea = secondaryGeometry.getArea()
                float ioo2 = intersectionArea / secondaryArea
                
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
 * It identifies child objects up to two levels deep, ensuring that first-level children belong to the "Inner Tongue"
 * class and second-level children belong to the "Axon" class. Fibre objects that lack at least one "Inner Tongue"
 * child and one "Axon" descendant are removed from the hierarchy, along with their associated child objects.
 * 
 * The function retains only valid Fibre objects with meaningful relationships and removes invalid ones,
 * ensuring accurate and biologically relevant data organisation.
 */
 
Collection<PathObject> removeChildless() {
    // Get all Fibre objects
    def fibre_objects = getDetectionObjects().findAll { it.getPathClass() == getPathClass("Fibre") }
    
    // Create a map to store Fibre objects and their child objects
    def validFibreToChildrenMap = [:]
    
    // Iterate through each Fibre object
    fibre_objects.each { fibre ->
        // Get the first level of child objects (filter by "Inner Tongue" class)
        def firstLevelChildren = fibre.getChildObjects().findAll { it.getPathClass() == getPathClass("Inner Tongue") }
    
        // Get the second level of child objects from first-level children (filter by "Axon" class)
        def secondLevelChildren = firstLevelChildren.collectMany { it.getChildObjects() }
                                                     .findAll { it.getPathClass() == getPathClass("Axon") }
    
        // Check if the Fibre object has at least one "Inner Tongue" child and one "Axon" child
        if (firstLevelChildren && secondLevelChildren) {
            // Combine the valid first and second-level children into a single list
            def allValidChildren = firstLevelChildren + secondLevelChildren
    
            // Store the Fibre object and its valid children in the map
            validFibreToChildrenMap[fibre] = allValidChildren
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
    removeObjects(invalidFibreObjects, false) // true to remove chilfren
    
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

// Set microscopy data
def micData = "EM" // "EM or BF"

// Establish hierarchy
Collection<PathObject> fibre_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Fibre")) }
Collection<PathObject> axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) }
Collection<PathObject> inner_tongue_objects
if (micData == "EM") {
    inner_tongue_objects = getDetectionObjects().findAll {it.getPathClass() == getPathClass("Inner Tongue")}
    println inner_tongue_objects
}

if (micData == "EM") {
    println "Comparing ${fibre_objects.size()} fibre objects vs ${inner_tongue_objects.size()} inner tongue objects"
    removeObjects(inner_tongue_objects, true)
    establishHierarchyBasedOnIoO2 (fibre_objects, inner_tongue_objects)
    inner_tongue_objects = getDetectionObjects().findAll {it.getPathClass() == getPathClass("Inner Tongue")} // updated collection
    
    println "Comparing ${inner_tongue_objects.size()} inner tongue objects vs ${axon_objects.size()} axon objects"
    removeObjects(axon_objects, true)
    establishHierarchyBasedOnIoO2 (inner_tongue_objects, axon_objects)
    axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) } // update collection
} else if (micData == "BF") {
    println "Comparing ${fibre_objects.size()} fibre objects vs ${axon_objects.size()} axon objects"
    removeObjects(axon_objects, true)
    establishHierarchyBasedOnIoO2 (fibre_objects, axon_objects)
    axon_objects = getDetectionObjects().findAll{(it.getPathClass() == getPathClass("Axon")) } // update collection
}

// Remove objects with an invalid hierarchy
if (micData == "EM") {
    Collection<PathObject> combined_objects = axon_objects + inner_tongue_objects
    Collection<PathObject> invalidParentlessObjects = removeParentless (combined_objects) // Storing invalid objects, could be useful for semi-automated annotation
} else if (micData == "BF") {
    Collection<PathObject> invalidParentlessObjects = removeParentless (axon_objects) // Storing invalid objects, could be useful for semi-automated annotation
}


 // Remove objects invalid for quantification
Collection<PathObject> invalidFibreObjects = removeChildless() // Storing invalid objects, could be useful for semi-automated annotation

return   