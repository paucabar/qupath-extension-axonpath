package qupath.ext.aimseg.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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
     * Returns all descendants of the given object (children, grandchildren, etc.).
     */
    public static List<PathObject> getAllDescendants(PathObject parent) {
        var result = new ArrayList<PathObject>();
        for (var child : parent.getChildObjects()) {
            result.add(child);
            result.addAll(getAllDescendants(child));
        }
        return result;
    }

    /**
     * Clips fibres to the actual parent annotation boundary.
     * Fibres fully inside are kept as-is; fibres partially overlapping are clipped to the boundary.
     * Only fibres with no overlap at all are discarded.
     */
    private static Collection<PathObject> clipToParentBoundary(PathObject rootObject, Collection<PathObject> fibres) {
        Geometry parentShape = rootObject.getROI().getGeometry();
        List<PathObject> result = new ArrayList<>();
        for (var fibre : fibres) {
            Geometry fibreShape = fibre.getROI().getGeometry();
            Geometry overlap = parentShape.intersection(fibreShape);
            if (overlap.isEmpty()) continue;
            double overlapRatio = overlap.getArea() / fibreShape.getArea();
            if (overlapRatio > 0.9999) {
                result.add(fibre);
            } else {
                Geometry clipped = GeometryTools.homogenizeGeometryCollection(overlap);
                ROI clippedROI = GeometryTools.geometryToROI(clipped, fibre.getROI().getImagePlane());
                result.add(PathObjects.createDetectionObject(clippedROI, fibre.getPathClass()));
            }
        }
        return result;
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
                    PathObject clippedChild = PathObjects.createDetectionObject(clippedROI, child.getPathClass());
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
    public static void updateHierarchy(PathObjectHierarchy hierarchy,
                                PathObject rootObject,
                                Collection<PathObject> fibres,
                                Collection<PathObject> axons,
                                Collection<PathObject> innerTongues) {

        // Clip all objects to the actual parent annotation shape (bounding box may be larger)
        Collection<PathObject> boundedFibres = clipToParentBoundary(rootObject, fibres);
        Collection<PathObject> boundedAxons = clipToParentBoundary(rootObject, axons);
        Collection<PathObject> boundedInnerTongues = clipToParentBoundary(rootObject, innerTongues);
        logger.info("Clipped to parent boundary: fibres {} → {}, axons {} → {}, inner tongues {} → {}",
                fibres.size(), boundedFibres.size(), axons.size(), boundedAxons.size(),
                innerTongues.size(), boundedInnerTongues.size());

        boolean withInnerTongues = !boundedInnerTongues.isEmpty();

        if (withInnerTongues) {
            logger.info("Assigning {} inner tongues to {} fibres", boundedInnerTongues.size(), boundedFibres.size());
            assignChildrenToParents(boundedFibres, boundedInnerTongues);

            Collection<PathObject> assignedInnerTongues = boundedFibres.stream()
                    .flatMap(f -> f.getChildObjects().stream())
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();

            logger.info("Assigning {} axons to {} inner tongues", boundedAxons.size(), assignedInnerTongues.size());
            assignChildrenToParents(assignedInnerTongues, boundedAxons);

            Collection<PathObject> orphans = Stream.concat(
                    boundedInnerTongues.stream().filter(it -> it.getLevel() == 0),
                    boundedAxons.stream().filter(it -> it.getLevel() == 0)
            ).toList();
            logger.info("Removing {} orphaned objects", orphans.size());
            hierarchy.removeObjects(orphans, false);

        } else {
            logger.info("Assigning {} axons to {} fibres", boundedAxons.size(), boundedFibres.size());
            assignChildrenToParents(boundedFibres, boundedAxons);

            Collection<PathObject> orphanedAxons = boundedAxons.stream()
                    .filter(it -> it.getLevel() == 0)
                    .toList();
            logger.info("Removing {} orphaned axons", orphanedAxons.size());
            hierarchy.removeObjects(orphanedAxons, false);
        }

        Collection<PathObject> incompleteFibres = findIncompleteFibres(boundedFibres, withInnerTongues);
        logger.info("Removing {} incomplete fibres", incompleteFibres.size());

        Collection<PathObject> validFibres = boundedFibres.stream()
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