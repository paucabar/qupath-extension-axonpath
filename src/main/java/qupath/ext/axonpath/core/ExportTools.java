package qupath.ext.axonpath.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.ObjDoubleConsumer;

/**
 * Tools for exporting AxonPath measurement data to TSV files.
 */
public class ExportTools {

    private static final Logger logger = LoggerFactory.getLogger(ExportTools.class);

    public static final String COL_IMAGE = "Image";
    public static final String COL_OBJECT_ID = "Object ID";
    public static final String COL_CLASSIFICATION = "Classification";
    public static final String COL_PARENT_FIBRE_ID = "Parent Fibre ID";

    private ExportTools() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Scans objects of the given class across all provided images and returns all
     * available columns in order: Image, Object ID, Classification, measurement names,
     * and (for Axon/InnerCylinder) Parent Fibre ID.
     * Intended to be called off the FX thread.
     *
     * @param images      project entries to scan
     * @param targetClass the PathClass to filter by
     * @return ordered, deduplicated list of column names
     */
    public static List<String> populateColumns(
            List<ProjectImageEntry<BufferedImage>> images,
            PathClass targetClass) {
        var axonClass = PathClass.getInstance("Axon");
        var innerCylinderClass = PathClass.getInstance("InnerCylinder");

        var columns = new ArrayList<String>();
        columns.add(COL_IMAGE);
        columns.add(COL_OBJECT_ID);
        columns.add(COL_CLASSIFICATION);

        var measurementSet = new LinkedHashSet<String>();
        for (var entry : images) {
            try (var imageData = entry.readImageData()) {
                if (imageData == null) continue;
                imageData.getHierarchy().getFlattenedObjectList(null).stream()
                        .filter(o -> o.getPathClass() == targetClass)
                        .forEach(o -> measurementSet.addAll(o.getMeasurementList().getNames()));
            } catch (Exception e) {
                logger.warn("populateColumns: could not read '{}': {}", entry.getImageName(), e.getMessage());
            }
        }
        columns.addAll(measurementSet);

        if (targetClass == axonClass || targetClass == innerCylinderClass)
            columns.add(COL_PARENT_FIBRE_ID);

        return columns;
    }

    /**
     * Exports objects of the given class from the provided images to a delimited file.
     * <p>
     * The {@code columns} list controls exactly which columns appear and in what order.
     * Special column names {@code "Image"}, {@code "Object ID"}, {@code "Classification"},
     * and {@code "Parent Fibre ID"} are resolved from object metadata; all other names
     * are looked up in the object's measurement list (empty string if absent).
     * Pass an empty list to export all columns discovered via {@link #populateColumns}.
     *
     * @param images      project entries to export
     * @param targetClass the PathClass to filter by
     * @param columns     columns to include; pass an empty list to include all
     * @param separator   column separator string (e.g. {@code "\t"})
     * @param outputFile  destination file
     * @param onProgress  optional callback receiving (imageName, fraction 0..1); may be null
     * @throws IOException if writing the output file fails
     */
    public static void exportMeasurements(
            List<ProjectImageEntry<BufferedImage>> images,
            PathClass targetClass,
            List<String> columns,
            String separator,
            File outputFile,
            ObjDoubleConsumer<String> onProgress) throws IOException {

        List<String> effectiveColumns = columns.isEmpty()
                ? populateColumns(images, targetClass)
                : columns;

        try (var writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {

            writer.println(String.join(separator, effectiveColumns));

            int n = images.size();
            for (int i = 0; i < n; i++) {
                var entry = images.get(i);
                if (onProgress != null)
                    onProgress.accept(entry.getImageName(), (double) i / n);

                try (var imageData = entry.readImageData()) {
                    if (imageData == null) continue;
                    var objects = imageData.getHierarchy().getFlattenedObjectList(null).stream()
                            .filter(o -> o.getPathClass() == targetClass)
                            .toList();
                    for (var obj : objects) {
                        var ml = obj.getMeasurementList();
                        var row = new ArrayList<String>();
                        for (var col : effectiveColumns) {
                            row.add(switch (col) {
                                case COL_IMAGE -> entry.getImageName();
                                case COL_OBJECT_ID -> obj.getID().toString();
                                case COL_CLASSIFICATION -> targetClass.getName();
                                case COL_PARENT_FIBRE_ID -> findParentFibreId(obj);
                                default -> {
                                    double val = ml.getOrDefault(col, Double.NaN);
                                    yield Double.isNaN(val) ? "" : String.valueOf(val);
                                }
                            });
                        }
                        writer.println(String.join(separator, row));
                    }
                } catch (Exception e) {
                    logger.warn("exportMeasurements: could not read '{}': {}", entry.getImageName(), e.getMessage());
                }
            }
            if (onProgress != null) onProgress.accept("Done", 1.0);
        }
    }

    /**
     * Walks the parent chain of an object until a Fibre ancestor is found.
     * Returns the Fibre's UUID string, or an empty string if not found.
     */
    private static String findParentFibreId(PathObject obj) {
        var fibreClass = PathClass.getInstance("Fibre");
        PathObject current = obj.getParent();
        while (current != null) {
            if (current.getPathClass() == fibreClass)
                return current.getID().toString();
            current = current.getParent();
        }
        return "";
    }
}
