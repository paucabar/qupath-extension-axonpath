package qupath.ext.aimseg.core;
/**
 * This script establishes meaningful hierarchies, recognising that a fibre can contain multiple
 * inner tongue objects, and an inner tongue object may contain multiple axon objects.
 */


/**
 * Some imports
 */


import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;



import static qupath.lib.scripting.QP.getDetectionObjects;

class HierarchyTools {

    /** Method to compute the intersection over Object2 area (IoO2A) between two object classes
     * and create hierarchical relationships
     * If IoO2A == 1, Object2 is added below Object1 immediately
     * If 0.9 < IoO2A < 1, Object2 is replaced by the intersection before being added below Object1
     */
    private static void establishHierarchyBasedOnIoO2(Collection<PathObject> objectsPrimary, Collection<PathObject> objectsSecondary) {
        // Initialise the PathObjectHierarchy
        var pathHierarchy = new PathObjectHierarchy();

        for (var parentObject: objectsPrimary) {
            Geometry parentGeometry = parentObject.getROI().getGeometry();

            for (var secondaryObject: objectsSecondary) {
                Geometry secondaryGeometry = secondaryObject.getROI().getGeometry();
                double intersectionArea = parentGeometry.intersection(secondaryGeometry).getArea();

                if (intersectionArea > 0) {
                    double parentArea = parentGeometry.getArea();
                    double secondaryArea = secondaryGeometry.getArea();
                    double ioo2 = intersectionArea / secondaryArea;

                    // Establish hierarchy directly if IoO2 == 1
                    if (ioo2 == 1) {
                        pathHierarchy.addObject(secondaryObject, false); // Add the secondary object
                        pathHierarchy.addObjectBelowParent(parentObject, secondaryObject, true);
                    }
                    // Replace secondary object with intersection and establish hierarchy if 0.9 < IoO2 < 1
                    else if (ioo2 > 0.9) {
                        Geometry intersectionGeometry = parentGeometry.intersection(secondaryGeometry);
                        ROI intersectionROI = GeometryTools.geometryToROI(intersectionGeometry, secondaryObject.getROI().getImagePlane());

                        // Create the intersection object as a detection with the same PathClass
                        PathObject intersectionObject = PathObjects.createDetectionObject(intersectionROI, secondaryObject.getPathClass());

                        // Replace the secondary object with the intersection object
                        //pathHierarchy.removeObject(secondaryObject, false) // Remove the original object
                        pathHierarchy.addObject(intersectionObject, false); // Add the intersection object

                        // Establish the hierarchy
                        pathHierarchy.addObjectBelowParent(parentObject, intersectionObject, false); // true to fireUpdate
                    }
                }
            }
        }

        QP.fireHierarchyUpdate();
    }


    /**
     * This function processes Fibre objects in QuPath by verifying and filtering their hierarchical relationships.
     * Depending on the data type ("EM" or "BF"), it enforces specific hierarchical relationships:
     * <p>
     * - For "EM": Fibre > Inner Tongue > Axon (at least one Inner Tongue and one Axon descendant required).
     * - For "BF": Fibre > Axon (at least one Axon required; Inner Tongue is ignored).
     * <p>
     * Invalid Fibre objects (those not meeting the hierarchy criteria) are removed from the hierarchy,
     * along with their associated child objects.
     *
     * @param dataType A string ("EM" or "BF") specifying the type of data and hierarchy logic to apply.
     * @return A collection of invalid Fibre objects that were removed.
     */
    static Collection<PathObject> removeChildless(String dataType) {
        // Get all Fibre objects
        Collection<PathObject> fibreObjects = getDetectionObjects().stream()
                .filter(po -> po.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();

        // Create a map to store Fibre objects and their valid child objects
        Map<PathObject, Collection<PathObject>> validFibreToChildrenMap = new HashMap<>();

        // Iterate through each Fibre object
        for (var fibre: fibreObjects) {
            if (dataType.equals("EM")) {
                // For EM: Fibre > Inner Tongue > Axon hierarchy
                Collection<PathObject> firstLevelChildren = fibre.getChildObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                        .toList();
                Collection<PathObject> secondLevelChildren = firstLevelChildren.stream()
                        .flatMap(pathObject -> pathObject.getChildObjects().stream())
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                        .toList();
                // Check if the Fibre object has at least one Inner Tongue and one Axon
                if (!firstLevelChildren.isEmpty() && !secondLevelChildren.isEmpty()) {
                    firstLevelChildren.addAll(secondLevelChildren);
                    validFibreToChildrenMap.put(fibre, firstLevelChildren);
                }
            } else if (dataType.equals("BF")) {
                // For BF: Fibre > Axon hierarchy
                Collection<PathObject> firstLevelChildren = fibre.getChildObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                        .toList();
                // Check if the Fibre object has at least one Axon
                if (!firstLevelChildren.isEmpty()) {
                    validFibreToChildrenMap.put(fibre, firstLevelChildren);
                }
            }
        }

        // Output the results for debugging or further processing
        System.out.println("Valid Fibre objects and their children:");
        validFibreToChildrenMap.entrySet().forEach(es -> {
            var fibre = es.getKey();
            var children = es.getValue();
            System.out.println("Fibre: ${fibre.getID()} has ${children.size()} valid child objects ${children}");
        });


        // Remove invalid Fibre objects and their children
        Set<PathObject> invalidFibreObjects = new HashSet<>(fibreObjects);
        invalidFibreObjects.removeAll(validFibreToChildrenMap.keySet());
        System.out.println("Removing ${invalidFibreObjects.size()} invalid Fibre objects...");
        QP.removeObjects(invalidFibreObjects); // true to remove children

        return invalidFibreObjects;
    }


    /**
     * Identify all the objects with no parent object
     */
    static Collection<PathObject> removeParentless(Collection<PathObject> objects) {
        System.out.println("Checking ${objects.size()} objects");
        // level 1 because image is the 'root'
        var parentless = objects.stream().filter(it -> it.getLevel() == 1).toList();
        System.out.println("Found ${parentless.size()} parentless objects");
        QP.removeObjects(parentless); // true to keep children objects
        return parentless;
    }


    /**
     * Pipeline to update hierarchy
     */
    static void updateHierarchy() {
        // Set microscopy data
        var dataType = "EM"; // "EM or BF"

        // Establish hierarchy
        Collection<PathObject> fibreObjects = getDetectionObjects().stream()
                .filter(it -> (it.getPathClass() == PathClass.getInstance("Fibre")))
                .toList();
        Collection<PathObject> axonObjects = getDetectionObjects().stream()
                .filter(it -> (it.getPathClass() == PathClass.getInstance("Axon")))
                .toList();
        if (dataType.equals("EM")) {
            Collection<PathObject> innerTongueObjects;
            innerTongueObjects = getDetectionObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();
            System.out.println("Comparing ${fibreObjects.size()} fibre objects vs ${innerTongueObjects.size()} inner tongue objects");
            QP.removeObjects(innerTongueObjects);
            establishHierarchyBasedOnIoO2(fibreObjects, innerTongueObjects);
            innerTongueObjects = getDetectionObjects()
                    .stream().filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();

            System.out.println("Comparing ${innerTongueObjects.size()} inner tongue objects vs ${axonObjects.size()} axon objects");
            QP.removeObjects(axonObjects);
            establishHierarchyBasedOnIoO2(innerTongueObjects, axonObjects);
            axonObjects = getDetectionObjects().stream()
                    .filter(it -> (it.getPathClass() == PathClass.getInstance("Axon")))
                    .toList();

            // Remove objects with an invalid hierarchy
            Collection<PathObject> combined_objects = Stream.concat(axonObjects.stream(), innerTongueObjects.stream()).toList();
            Collection<PathObject> invalidParentlessObjects = removeParentless(combined_objects); // Storing invalid objects, could be useful for semi-automated annotation

        } else if (dataType.equals("BF")) {
            System.out.println("Comparing ${fibreObjects.size()} fibre objects vs ${axonObjects.size()} axon objects");
            QP.removeObjects(axonObjects);
            establishHierarchyBasedOnIoO2(fibreObjects, axonObjects);
            axonObjects = getDetectionObjects()
                    .stream().filter(it -> (it.getPathClass() == PathClass.getInstance("Axon")))
                    .toList();
            // Remove objects with an invalid hierarchy
            Collection<PathObject> invalidParentlessObjects = removeParentless(axonObjects); // Storing invalid objects, could be useful for semi-automated annotation

        }

        // Remove objects invalid for quantification
        Collection<PathObject> invalidFibreObjects = removeChildless(dataType); // Storing invalid objects, could be useful for semi-automated annotation
    }

    enum DataType {
        BRIGHTFIELD,
        ELECTRON_MICROSCOPY;
    }

}