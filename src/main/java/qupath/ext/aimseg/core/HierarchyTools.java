package qupath.ext.aimseg.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

public class HierarchyTools {
    private static final Logger logger = LoggerFactory.getLogger(HierarchyTools.class);

    private HierarchyTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Assigns child objects to parents based on the intersection over child area (IoC).
     * If IoC > 0.9999, the child is added directly.
     * If IoC > 0.9, the child is clipped to the parent boundary before being added.
     */
    private static void assignChildrenToParents(Collection<PathObject> parents, Collection<PathObject> children) {
        for (var parent : parents) {
            Geometry parentShape = parent.getROI().getGeometry();

            for (var child : children) {
                Geometry childShape = child.getROI().getGeometry();
                Geometry overlap = parentShape.intersection(childShape);

                if (overlap.isEmpty()) continue;

                double overlapRatio = overlap.getArea() / childShape.getArea();

                if (overlapRatio > 0.9999) {
                    parent.addChildObject(child);
                } else if (overlapRatio > 0.9) {
                    Geometry clipped = GeometryTools.homogenizeGeometryCollection(overlap);
                    ROI clippedROI = GeometryTools.geometryToROI(clipped, child.getROI().getImagePlane());
                    PathObject clippedChild = PathObjects.createAnnotationObject(clippedROI, child.getPathClass());
                    parent.addChildObject(clippedChild);
                }
            }
        }
    }

    /**
     * Builds the object hierarchy after AimSeg inference.
     * If inner tongues are present, the expected structure is Fibre > Inner Tongue > Axon.
     * Otherwise, the expected structure is Fibre > Axon.
     * Orphaned objects and fibres missing expected children are removed.
     */
    static void updateHierarchy(PathObjectHierarchy hierarchy,
                                PathObject rootObject,
                                Collection<PathObject> fibres,
                                Collection<PathObject> axons,
                                Collection<PathObject> innerTongues) {

        boolean withInnerTongues = !innerTongues.isEmpty();

        if (withInnerTongues) {
            logger.info("Assigning {} inner tongues to {} fibres", innerTongues.size(), fibres.size());
            assignChildrenToParents(fibres, innerTongues);

            Collection<PathObject> assignedInnerTongues = fibres.stream()
                    .flatMap(f -> f.getChildObjects().stream())
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();

            logger.info("Assigning {} axons to {} inner tongues", axons.size(), assignedInnerTongues.size());
            assignChildrenToParents(assignedInnerTongues, axons);

            Collection<PathObject> orphans = Stream.concat(
                    innerTongues.stream().filter(it -> it.getLevel() == 0),
                    axons.stream().filter(it -> it.getLevel() == 0)
            ).toList();
            logger.info("Removing {} orphaned objects", orphans.size());
            hierarchy.removeObjects(orphans, false);

        } else {
            logger.info("Assigning {} axons to {} fibres", axons.size(), fibres.size());
            assignChildrenToParents(fibres, axons);

            Collection<PathObject> orphanedAxons = axons.stream()
                    .filter(it -> it.getLevel() == 0)
                    .toList();
            logger.info("Removing {} orphaned axons", orphanedAxons.size());
            hierarchy.removeObjects(orphanedAxons, false);
        }

        Collection<PathObject> incompleteFibres = findIncompleteFibres(fibres, withInnerTongues);
        logger.info("Removing {} incomplete fibres", incompleteFibres.size());

        Collection<PathObject> validFibres = fibres.stream()
                .filter(f -> !incompleteFibres.contains(f))
                .collect(toCollection(ArrayList::new));
        rootObject.addChildObjects(validFibres);
    }

    /**
     * Returns fibres that are missing their expected children.
     * With inner tongues: a valid fibre needs at least one inner tongue containing at least one axon.
     * Without inner tongues: a valid fibre needs at least one axon.
     */
    private static Collection<PathObject> findIncompleteFibres(Collection<PathObject> fibres, boolean withInnerTongues) {
        Set<PathObject> incomplete = new HashSet<>();

        for (var fibre : fibres) {
            if (withInnerTongues) {
                boolean hasPopulatedInnerTongue = fibre.getChildObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                        .anyMatch(it -> !it.getChildObjects().isEmpty());
                if (!hasPopulatedInnerTongue) {
                    incomplete.add(fibre);
                }
            } else {
                boolean hasAxon = fibre.getChildObjects().stream()
                        .anyMatch(it -> it.getPathClass() == PathClass.getInstance("Axon"));
                if (!hasAxon) {
                    incomplete.add(fibre);
                }
            }
        }

        return incomplete;
    }
}