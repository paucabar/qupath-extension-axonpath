package qupath.ext.axonpath.core;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * Tools for filtering detected objects based on spatial criteria.
 */
public class FilterTools {
    private static final Logger logger = LoggerFactory.getLogger(FilterTools.class);

    private FilterTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Removes all Fibre detections (and their children) whose geometry intersects the boundary
     * of the parent object. Uses zero tolerance — only fibres that geometrically touch the
     * parent boundary are removed.
     *
     * @param parent    the parent annotation defining the region boundary
     * @param hierarchy the QuPath object hierarchy
     */
    public static void removeFibresTouchingBoundary(PathObject parent, PathObjectHierarchy hierarchy) {
        removeFibresTouchingBoundary(parent, hierarchy, 0.0);
    }

    /**
     * Removes all Fibre detections (and their children) whose geometry intersects the boundary
     * of the parent object, within the specified tolerance.
     *
     * @param parent      the parent annotation defining the region boundary
     * @param hierarchy   the QuPath object hierarchy
     * @param tolerancePx distance in pixels to expand the boundary check; use 0 for exact intersection only
     */
    public static void removeFibresTouchingBoundary(PathObject parent, PathObjectHierarchy hierarchy, double tolerancePx) {
        Geometry parentGeometry = parent.getROI().getGeometry();
        Geometry boundary = tolerancePx > 0
                ? parentGeometry.getBoundary().buffer(tolerancePx)
                : parentGeometry.getBoundary();

        var fibresToRemove = parent.getChildObjects().stream()
                .filter(o -> o.getPathClass() == PathClass.getInstance("Fibre"))
                .filter(o -> o.isDetection())
                .filter(o -> {
                    try {
                        return o.getROI().getGeometry().intersects(boundary);
                    } catch (Exception e) {
                        logger.warn("removeFibresTouchingBoundary: skipping fibre with invalid geometry: {}", e.getMessage());
                        return false;
                    }
                })
                .toList();

        if (fibresToRemove.isEmpty()) {
            logger.info("removeFibresTouchingBoundary: no edge fibres found");
            return;
        }

        logger.info("removeFibresTouchingBoundary: removing {} edge fibres (tolerance={}px)",
                fibresToRemove.size(), tolerancePx);
        hierarchy.removeObjects(fibresToRemove, false);
    }
}
