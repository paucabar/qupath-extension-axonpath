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
     * Removes all Fibre detections (and their children) whose geometry is within 1 pixel of
     * the parent boundary. The 1-pixel default absorbs floating point precision errors from
     * JTS clipping while being too small to affect genuinely interior fibres.
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
        Geometry boundary = parent.getROI().getGeometry().getBoundary();
        double threshold = tolerancePx > 0 ? tolerancePx : 1.0;

        var fibresToRemove = parent.getChildObjects().stream()
                .filter(o -> o.getPathClass() == PathClass.getInstance("Fibre"))
                .filter(o -> o.isDetection())
                .filter(o -> {
                    try {
                        return o.getROI().getGeometry().distance(boundary) < threshold;
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
