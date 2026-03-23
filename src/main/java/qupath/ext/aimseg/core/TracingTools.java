package qupath.ext.aimseg.core;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tools for tracing axons across z-slices of a 3D image stack.
 *
 * <p>Each fibre (with at least one axon child) is assigned an integer "Axon ID" measurement.
 * Fibres on consecutive z-slices are matched greedily by IoU of their ROIs; matched fibres
 * receive the same ID. The measurement is also propagated to all descendants of each fibre.
 */
public class TracingTools {
    private static final Logger logger = LoggerFactory.getLogger(TracingTools.class);
    private static final String AXON_ID_MEASUREMENT = "Axon ID";

    private TracingTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Assigns "Axon ID" measurements to all Fibre detections (with at least one child) across
     * z-slices, matching fibres between consecutive slices using a greedy IoU-based strategy.
     *
     * @param imageData  the current image data
     * @param minOverlap minimum IoU threshold to consider two fibres the same across slices (0–1)
     * @throws IllegalStateException if all fibres are annotations rather than detections
     */
    public static void traceAxons(ImageData<?> imageData, double minOverlap) {
        var hierarchy = imageData.getHierarchy();

        // Collect all Fibre detections with at least one child
        var allFibres = hierarchy.getFlattenedObjectList(null).stream()
                .filter(o -> o.getPathClass() == PathClass.getInstance("Fibre"))
                .filter(o -> o.isDetection())
                .filter(o -> !o.getChildObjects().isEmpty())
                .toList();

        if (allFibres.isEmpty()) {
            logger.warn("traceAxons: no Fibre detections with children found");
            return;
        }

        // Check for mixed annotations/detections and warn
        long annotationCount = hierarchy.getFlattenedObjectList(null).stream()
                .filter(o -> o.getPathClass() == PathClass.getInstance("Fibre"))
                .filter(o -> o.isAnnotation())
                .count();
        if (annotationCount > 0) {
            logger.warn("traceAxons: {} Fibre annotation(s) found and will be ignored — only detections are traced",
                    annotationCount);
        }

        // Group fibres by z-plane
        Map<Integer, List<PathObject>> byZ = new TreeMap<>();
        for (var fibre : allFibres) {
            int z = fibre.getROI().getImagePlane().getZ();
            byZ.computeIfAbsent(z, k -> new ArrayList<>()).add(fibre);
        }

        List<Integer> zSlices = new ArrayList<>(byZ.keySet());
        logger.info("traceAxons: found {} fibres across {} z-slices: {}", allFibres.size(), zSlices.size(), zSlices);

        // Map from PathObject → assigned Axon ID
        Map<PathObject, Integer> idMap = new LinkedHashMap<>();
        int nextId = 1;

        // Assign IDs on the first slice
        List<PathObject> prevSlice = byZ.get(zSlices.get(0));
        for (var fibre : prevSlice) {
            idMap.put(fibre, nextId++);
        }

        // Match consecutive slices
        for (int i = 1; i < zSlices.size(); i++) {
            List<PathObject> currSlice = byZ.get(zSlices.get(i));

            // Build all candidate pairs above threshold
            List<double[]> candidates = new ArrayList<>(); // [iou, prevIdx, currIdx]
            for (int p = 0; p < prevSlice.size(); p++) {
                Geometry gPrev = prevSlice.get(p).getROI().getGeometry();
                for (int c = 0; c < currSlice.size(); c++) {
                    Geometry gCurr = currSlice.get(c).getROI().getGeometry();
                    double iou = computeIoU(gPrev, gCurr);
                    if (iou >= minOverlap) {
                        candidates.add(new double[]{iou, p, c});
                    }
                }
            }

            // Sort by IoU descending for greedy matching
            candidates.sort(Comparator.comparingDouble((double[] row) -> row[0]).reversed());

            Set<Integer> usedPrev = new HashSet<>();
            Set<Integer> usedCurr = new HashSet<>();

            for (double[] row : candidates) {
                int p = (int) row[1];
                int c = (int) row[2];
                if (usedPrev.contains(p) || usedCurr.contains(c)) continue;
                // Match: propagate ID from prev to curr
                idMap.put(currSlice.get(c), idMap.get(prevSlice.get(p)));
                usedPrev.add(p);
                usedCurr.add(c);
            }

            // Assign new IDs to unmatched fibres on curr slice
            for (int c = 0; c < currSlice.size(); c++) {
                if (!usedCurr.contains(c)) {
                    idMap.put(currSlice.get(c), nextId++);
                }
            }

            prevSlice = currSlice;
        }

        // Write measurements to each fibre and all its descendants
        for (var entry : idMap.entrySet()) {
            PathObject fibre = entry.getKey();
            int axonId = entry.getValue();
            fibre.getMeasurementList().put(AXON_ID_MEASUREMENT, axonId);
            for (var descendant : HierarchyTools.getAllDescendants(fibre)) {
                descendant.getMeasurementList().put(AXON_ID_MEASUREMENT, axonId);
            }
        }

        logger.info("traceAxons: assigned {} unique Axon IDs across {} fibres", nextId - 1, idMap.size());
        hierarchy.fireObjectMeasurementsChangedEvent(TracingTools.class, allFibres);
    }

    private static double computeIoU(Geometry a, Geometry b) {
        if (!a.getEnvelopeInternal().intersects(b.getEnvelopeInternal())) return 0.0;
        Geometry intersection = a.intersection(b);
        double intersectionArea = intersection.getArea();
        if (intersectionArea == 0.0) return 0.0;
        double unionArea = a.getArea() + b.getArea() - intersectionArea;
        return unionArea <= 0.0 ? 0.0 : intersectionArea / unionArea;
    }
}
