package qupath.ext.axonpath.core;

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

    /** Intersection over child area above which a child is assigned to a parent unchanged. */
    static final double IOC_ASSIGN = 0.9999;
    /** Intersection over child area above which a child is clipped to the parent and assigned. */
    static final double IOC_CLIP = 0.9;
    /** Fraction of an AxonPath annotation's area inside a parent above which it belongs to that parent. */
    static final double IOC_BELONGS = 0.5;

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
        Geometry parentShapeBuffered = parentShape.buffer(0.5);
        for (var fibre : fibres) {
            Geometry fibreShape = fibre.getROI().getGeometry();
            if (parentShapeBuffered.covers(fibreShape)) {
                result.add(fibre);
            } else {
                Geometry overlap = parentShape.intersection(fibreShape);
                if (overlap.isEmpty()) continue;
                Geometry clipped = GeometryTools.homogenizeGeometryCollection(overlap);
                ROI clippedROI = GeometryTools.geometryToROI(clipped, fibre.getROI().getImagePlane());
                result.add(PathObjects.createDetectionObject(clippedROI, fibre.getPathClass()));
            }
        }
        return result;
    }

    /**
     * Fraction of the child's area that lies inside the parent (intersection over child, IoC):
     * 1 when the child is fully inside, 0 when they don't overlap.
     */
    static double intersectionOverChild(Geometry parentShape, Geometry childShape) {
        if (childShape.getArea() <= 0 || !parentShape.intersects(childShape))
            return 0;
        return parentShape.intersection(childShape).getArea() / childShape.getArea();
    }

    /**
     * Assigns child objects to parents based on the intersection over child area (IoC).
     * If IoC > {@link #IOC_ASSIGN}, the child is added directly.
     * If IoC > {@link #IOC_CLIP}, the child is clipped to the parent boundary before being added.
     */
    private static void assignChildrenToParents(Collection<PathObject> parents, Collection<PathObject> children) {
        for (var parent : parents) {
            Geometry parentShape = parent.getROI().getGeometry();

            for (var child : children) {
                Geometry childShape = child.getROI().getGeometry();
                Geometry overlap = parentShape.intersection(childShape);

                if (overlap.isEmpty()) continue;

                double overlapRatio = overlap.getArea() / childShape.getArea();

                if (overlapRatio > IOC_ASSIGN) {
                    parent.addChildObject(child);
                } else if (overlapRatio > IOC_CLIP) {
                    Geometry clipped = GeometryTools.homogenizeGeometryCollection(overlap);
                    ROI clippedROI = GeometryTools.geometryToROI(clipped, child.getROI().getImagePlane());
                    PathObject clippedChild = PathObjects.createDetectionObject(clippedROI, child.getPathClass());
                    parent.addChildObject(clippedChild);
                }
            }
        }
    }

    /**
     * Finds the AxonPath annotations (Fibre, InnerCylinder, Axon converted for editing, or drawn by
     * hand) that belong to a parent: on the parent's z-slice and timepoint, and more than half
     * ({@link #IOC_BELONGS}) inside the parent, either themselves or through an AxonPath ancestor.
     * <p>
     * This is the single rule used by Run's clean-up, Recompute, Lock/Unlock and the Review &amp;
     * Edit buttons, so they all agree on what belongs to which region:
     * <ul>
     *   <li>Converted annotations are added to the hierarchy by shape, not under the parent, so they
     *       are usually not its children; that is why they are looked up by geometry.</li>
     *   <li>Each object belongs to the region holding most of it. A neighbouring region's fibre
     *       that only touches this region's boundary is left alone, while a hand-drawn fibre that
     *       sticks out a little is still collected (and later clipped).</li>
     *   <li>Geometry tests are 2D, so the plane check keeps other z-slices out.</li>
     * </ul>
     */
    public static List<PathObject> findAxonPathAnnotations(PathObjectHierarchy hierarchy, PathObject parent) {
        return axonPathAnnotations(hierarchy, parent).toList();
    }

    /** Whether the parent has any AxonPath annotation, as {@link #findAxonPathAnnotations} defines it. */
    public static boolean hasAxonPathAnnotations(PathObjectHierarchy hierarchy, PathObject parent) {
        return axonPathAnnotations(hierarchy, parent).findAny().isPresent();
    }

    private static Stream<PathObject> axonPathAnnotations(PathObjectHierarchy hierarchy, PathObject parent) {
        var plane = parent.getROI().getImagePlane();
        Geometry parentShape = parent.getROI().getGeometry();
        return hierarchy.getAnnotationObjects().stream()
                .filter(it -> AxonPathClasses.isAxonPathClass(it.getPathClass()))
                .filter(it -> it.getROI().getImagePlane().getZ() == plane.getZ()
                        && it.getROI().getImagePlane().getT() == plane.getT())
                .filter(it -> {
                    for (PathObject o = it; o != null && AxonPathClasses.isAxonPathClass(o.getPathClass()); o = o.getParent()) {
                        try {
                            if (intersectionOverChild(parentShape, o.getROI().getGeometry()) > IOC_BELONGS)
                                return true;
                        } catch (RuntimeException e) {
                            // e.g. a JTS TopologyException for an invalid hand-drawn shape
                            logger.warn("Skipping {}: could not compare its shape with the parent ({})", o, e.getMessage());
                        }
                    }
                    return false;
                });
    }

    /**
     * Guesses whether a parent's objects should be built with inner cylinders: true if any inner
     * cylinder overlaps the parent. Used when no setting is stored on the parent
     * (see {@link AxonPathMetadata}), e.g. for objects drawn from scratch or older projects.
     */
    public static boolean guessInnerCylinderMode(PathObject rootObject, Collection<PathObject> innerCylinders) {
        return !clipToParentBoundary(rootObject, innerCylinders).isEmpty();
    }

    /**
     * Builds the object hierarchy, guessing the structure from the objects:
     * with inner cylinders if any overlaps the parent (see {@link #guessInnerCylinderMode}).
     * Kept for scripts that call it directly; AxonPath itself uses the explicit overload.
     */
    public static void updateHierarchy(PathObjectHierarchy hierarchy,
                                PathObject rootObject,
                                Collection<PathObject> fibres,
                                Collection<PathObject> axons,
                                Collection<PathObject> innerCylinders) {
        updateHierarchy(hierarchy, rootObject, fibres, axons, innerCylinders,
                guessInnerCylinderMode(rootObject, innerCylinders));
    }

    /**
     * Builds the object hierarchy under {@code rootObject}.
     * <ul>
     *   <li>With inner cylinders: Fibre &gt; InnerCylinder &gt; Axon. A fibre needs an inner
     *       cylinder containing an axon, and axons outside any inner cylinder are removed.</li>
     *   <li>Without inner cylinders: Fibre &gt; Axon. Any inner cylinders passed in are ignored.</li>
     * </ul>
     * Orphaned objects and fibres missing expected children are removed.
     *
     * @param withInnerCylinders the inner cylinder setting to build with, normally the one stored on
     *                           the parent by Run (see {@link AxonPathMetadata})
     */
    public static void updateHierarchy(PathObjectHierarchy hierarchy,
                                PathObject rootObject,
                                Collection<PathObject> fibres,
                                Collection<PathObject> axons,
                                Collection<PathObject> innerCylinders,
                                boolean withInnerCylinders) {

        // Clip all objects to the actual parent annotation shape (bounding box may be larger)
        Collection<PathObject> boundedFibres = clipToParentBoundary(rootObject, fibres);
        Collection<PathObject> boundedAxons = clipToParentBoundary(rootObject, axons);
        Collection<PathObject> boundedInnerCylinders = withInnerCylinders
                ? clipToParentBoundary(rootObject, innerCylinders) : List.of();
        logger.info("Clipped to parent boundary: fibres {} -> {}, axons {} -> {}, inner cylinders {} -> {}",
                fibres.size(), boundedFibres.size(), axons.size(), boundedAxons.size(),
                innerCylinders.size(), boundedInnerCylinders.size());
        if (!withInnerCylinders && !innerCylinders.isEmpty())
            logger.info("Building without inner cylinders: ignoring {} inner cylinder(s)", innerCylinders.size());

        if (withInnerCylinders) {
            logger.info("Assigning {} inner cylinders to {} fibres", boundedInnerCylinders.size(), boundedFibres.size());
            assignChildrenToParents(boundedFibres, boundedInnerCylinders);

            Collection<PathObject> assignedInnerCylinders = boundedFibres.stream()
                    .flatMap(f -> f.getChildObjects().stream())
                    .filter(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"))
                    .toList();

            logger.info("Assigning {} axons to {} inner cylinders", boundedAxons.size(), assignedInnerCylinders.size());
            assignChildrenToParents(assignedInnerCylinders, boundedAxons);

            Collection<PathObject> orphans = Stream.concat(
                    boundedInnerCylinders.stream().filter(it -> it.getLevel() == 0),
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

        Collection<PathObject> incompleteFibres = findIncompleteFibres(boundedFibres, withInnerCylinders);
        logger.info("Removing {} incomplete fibres", incompleteFibres.size());

        Collection<PathObject> validFibres = boundedFibres.stream()
                .filter(f -> !incompleteFibres.contains(f))
                .collect(toCollection(ArrayList::new));
        rootObject.addChildObjects(validFibres);
    }

    /**
     * Creates an inner cylinder for each axon that lies inside a fibre but touches no inner
     * cylinder at all, with the same shape as the axon. Used by Recompute with inner cylinders, so
     * that a fibre drawn with only an axon is kept instead of being removed.
     * <p>
     * For such a fibre the inner tongue is empty, so its myelin g-ratio equals its axon g-ratio.
     * Axons partly inside an inner cylinder get none and are handled as before (clipped and
     * assigned above {@link #IOC_CLIP}, removed otherwise), and axons outside every fibre are left
     * to be removed as orphans.
     *
     * @return the new InnerCylinder detections, not yet added to the hierarchy
     */
    public static List<PathObject> createMissingInnerCylinders(Collection<PathObject> fibres,
                                                               Collection<PathObject> axons,
                                                               Collection<PathObject> innerCylinders) {
        var fibreShapes = fibres.stream().map(f -> f.getROI().getGeometry()).toList();
        var innerCylinderShapes = innerCylinders.stream().map(ic -> ic.getROI().getGeometry()).toList();
        List<PathObject> created = new ArrayList<>();
        for (var axon : axons) {
            Geometry axonShape = axon.getROI().getGeometry();
            boolean insideFibre = fibreShapes.stream()
                    .anyMatch(f -> intersectionOverChild(f, axonShape) > IOC_CLIP);
            boolean touchesInnerCylinder = innerCylinderShapes.stream()
                    .anyMatch(ic -> intersectionOverChild(ic, axonShape) > 0);
            if (insideFibre && !touchesInnerCylinder)
                created.add(PathObjects.createDetectionObject(axon.getROI(), PathClass.getInstance("InnerCylinder")));
        }
        return created;
    }

    /**
     * Returns fibres that are missing their expected children.
     * With inner cylinders: a valid fibre needs at least one inner cylinder containing at least one axon.
     * Without inner cylinders: a valid fibre needs at least one axon.
     */
    private static Collection<PathObject> findIncompleteFibres(Collection<PathObject> fibres, boolean withInnerCylinders) {
        Set<PathObject> incomplete = new HashSet<>();

        for (var fibre : fibres) {
            if (withInnerCylinders) {
                boolean hasPopulatedInnerCylinder = fibre.getChildObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"))
                        .anyMatch(it -> !it.getChildObjects().isEmpty());
                if (!hasPopulatedInnerCylinder) {
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