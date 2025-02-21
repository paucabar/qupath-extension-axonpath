package qupath.ext.aimseg.core;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;


import static java.util.stream.Collectors.toCollection;
import static qupath.ext.aimseg.core.PredictionTools.DataType.BRIGHTFIELD;
import static qupath.ext.aimseg.core.PredictionTools.DataType.ELECTRON_MICROSCOPY;

/**
 * This class provides tools to establish meaningful hierarchies, recognising that a fibre can contain multiple
 * inner tongue objects, and an inner tongue object may contain multiple axon objects.
 */
public class HierarchyTools {
    private static final Logger logger = LoggerFactory.getLogger(HierarchyTools.class);

    private HierarchyTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /** Compute the intersection over Object2 area (IoO2A) between two object classes
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
                        Geometry intersectionGeometry2 = GeometryTools.homogenizeGeometryCollection(intersectionGeometry);
                        ROI intersectionROI = GeometryTools.geometryToROI(intersectionGeometry2, secondaryObject.getROI().getImagePlane());

                        // Create the intersection object as an annotation with the same PathClass
                        PathObject intersectionObject = PathObjects.createAnnotationObject(intersectionROI, secondaryObject.getPathClass());

                        // Replace the secondary object with the intersection object
                        //pathHierarchy.removeObject(secondaryObject, false) // Remove the original object
                        pathHierarchy.addObject(intersectionObject, false); // Add the intersection object

                        // Establish the hierarchy
                        pathHierarchy.addObjectBelowParent(parentObject, intersectionObject, false); // true to fireUpdate
                    }
                }
            }
        }

        pathHierarchy.fireHierarchyChangedEvent(HierarchyTools.class);
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
    static Collection<PathObject> removeChildless(PathObjectHierarchy hierarchy, PredictionTools.DataType dataType) {
        // Get all Fibre objects
        Collection<PathObject> fibreObjects = hierarchy.getAnnotationObjects().stream()
                .filter(po -> po.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();

        // Create a map to store Fibre objects and their valid child objects
        Map<PathObject, Collection<PathObject>> validFibreToChildrenMap = new HashMap<>();

        // Iterate through each Fibre object
        for (var fibre: fibreObjects) {
            if (dataType == ELECTRON_MICROSCOPY) {
                // For EM: Fibre > Inner Tongue > Axon hierarchy
                Collection<PathObject> firstLevelChildren = fibre.getChildObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                        .collect(toCollection(ArrayList::new)); // toList makes an immutable list by default
                Collection<PathObject> secondLevelChildren = firstLevelChildren.stream()
                        .flatMap(pathObject -> pathObject.getChildObjects().stream()) // flatmap lets us merge lists of children into one list
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                        .toList();
                // Check if the Fibre object has at least one Inner Tongue and one Axon
                if (!firstLevelChildren.isEmpty() && !secondLevelChildren.isEmpty()) {
                    firstLevelChildren.addAll(secondLevelChildren);
                    validFibreToChildrenMap.put(fibre, firstLevelChildren);
                }
            } else if (dataType == BRIGHTFIELD) {
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
        if (logger.isDebugEnabled()) {
            logger.debug("Valid Fibre objects and their children:");
            for (var es: validFibreToChildrenMap.entrySet()) {
                var fibre = es.getKey();
                var children = es.getValue();
                logger.debug("Fibre: {} has {} valid child objects {}", fibre.getID(), children.size(), children);
            }
        }

        // Remove invalid Fibre objects and their children
        Set<PathObject> invalidFibreObjects = new HashSet<>(fibreObjects);
        invalidFibreObjects.removeAll(validFibreToChildrenMap.keySet());
        logger.info("Removing {} invalid Fibre objects...", invalidFibreObjects.size());
        hierarchy.removeObjects(invalidFibreObjects, false);

        return invalidFibreObjects;
    }


    /**
     * Identify all the objects with no parent object
     */
    static Collection<PathObject> removeParentless(Collection<PathObject> objects, PathObjectHierarchy hierarchy) {
        logger.info("Checking {} objects", objects.size());
        // level 1 because image is the 'root'
        var parentless = objects.stream().filter(it -> it.getLevel() == 1).toList();
        logger.info("Found {} parentless objects", parentless.size());
        hierarchy.removeObjects(parentless, false);
        return parentless;
    }

    static void updateHierarchy(PredictionTools.DataType dataType, PathObjectHierarchy hierarchy) {
        Collection<PathObject> fibreObjects = hierarchy.getAnnotationObjects().stream()
                .filter(it -> (it.getPathClass() == PathClass.getInstance("Fibre")))
                .toList();
        Collection<PathObject> axonObjects = hierarchy.getAnnotationObjects().stream()
                .filter(it -> (it.getPathClass() == PathClass.getInstance("Axon")))
                .toList();
        Collection<PathObject> innerTongueObjects;
        if (dataType == ELECTRON_MICROSCOPY) {
            innerTongueObjects = hierarchy.getAnnotationObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();

        } else {
            innerTongueObjects = List.of();
        }
        updateHierarchy(hierarchy, fibreObjects, axonObjects, innerTongueObjects, dataType);
    }


    /**
     * Pipeline to update hierarchy
     */
    static void updateHierarchy(
            PathObjectHierarchy hierarchy,
            Collection<PathObject> fibreObjects,
            Collection<PathObject> axonObjects,
            Collection<PathObject> innerTongueObjects,
            PredictionTools.DataType dataType) {

        if (dataType == ELECTRON_MICROSCOPY) {
            logger.info("Comparing {} fibre objects to {} inner tongue objects", fibreObjects.size(), innerTongueObjects.size());
            hierarchy.removeObjects(innerTongueObjects, true);
            establishHierarchyBasedOnIoO2(fibreObjects, innerTongueObjects);
            innerTongueObjects = hierarchy.getAnnotationObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();

            logger.info("Comparing {} inner tongue objects to {} axon objects", innerTongueObjects.size(), axonObjects.size());
            hierarchy.removeObjects(axonObjects, true);
            establishHierarchyBasedOnIoO2(innerTongueObjects, axonObjects);
            // Remove objects with an invalid hierarchy
            axonObjects = hierarchy.getAnnotationObjects().stream()
                    .filter(it -> (it.getPathClass() == PathClass.getInstance("Axon")))
                    .toList();
            Collection<PathObject> combined_objects = Stream.concat(axonObjects.stream(), innerTongueObjects.stream()).toList();
            Collection<PathObject> invalidParentlessObjects = removeParentless(combined_objects, hierarchy); // Storing invalid objects, could be useful for semi-automated annotation
        } else if (dataType == BRIGHTFIELD) {
            logger.info("Comparing {} fibre objects to {} axon objects", fibreObjects.size(), axonObjects.size());
            hierarchy.removeObjects(axonObjects, true);
            establishHierarchyBasedOnIoO2(fibreObjects, axonObjects);

            // Remove objects with an invalid hierarchy
            Collection<PathObject> invalidParentlessObjects = removeParentless(axonObjects, hierarchy); // Storing invalid objects, could be useful for semi-automated annotation
        }

        // Remove objects invalid for quantification
        Collection<PathObject> invalidFibreObjects = removeChildless(hierarchy, dataType); // Storing invalid objects, could be useful for semi-automated annotation
    }

}