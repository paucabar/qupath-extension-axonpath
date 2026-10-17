package qupath.ext.axonpath.core;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

/**
 * Settings that AxonPath stores on a parent object, so that later steps can reuse them.
 * <p>
 * Run stores whether inner cylinders were predicted for the parent, and Recompute reads it back to
 * rebuild the same hierarchy (Fibre &gt; InnerCylinder &gt; Axon, or Fibre &gt; Axon). The value
 * is the inner cylinder setting itself, not the model or image type: EM models enable inner
 * cylinders by default and brightfield models don't, but the setting can be changed either way.
 * <p>
 * The value is kept in the object's metadata, which QuPath saves in the project (.qpdata) and in
 * GeoJSON, and shows as a column in the measurement tables. It is lost if the parent is merged or
 * recreated; Recompute then falls back to guessing from the objects drawn. To change it without
 * re-running, use a script, e.g.
 * {@code getSelectedObjects().each { it.getMetadata().put("AxonPath inner cylinders", "false") }}.
 */
public final class AxonPathMetadata {
    private static final Logger logger = LoggerFactory.getLogger(AxonPathMetadata.class);

    /** Metadata key holding {@code "true"} or {@code "false"}. */
    public static final String INNER_CYLINDERS_KEY = "AxonPath inner cylinders";

    private AxonPathMetadata() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /** Stores whether the parent's results were built with inner cylinders. */
    public static void storeInnerCylinderMode(PathObject parent, boolean withInnerCylinders) {
        parent.getMetadata().put(INNER_CYLINDERS_KEY, Boolean.toString(withInnerCylinders));
    }

    /**
     * Reads the stored inner cylinder setting.
     * <p>
     * Only {@code "true"} and {@code "false"} are accepted (ignoring case and surrounding spaces),
     * so a hand-edited typo is not silently
     * read as {@code false}. Any other value is treated as absent, with a warning.
     *
     * @return the stored setting, or empty if none is stored (e.g. projects from v0.1.0/0.1.1,
     *         objects drawn from scratch) or the value is invalid
     */
    public static Optional<Boolean> readInnerCylinderMode(PathObject parent) {
        // hasMetadata() avoids creating an empty metadata map on objects that have none
        if (!parent.hasMetadata())
            return Optional.empty();
        String value = parent.getMetadata().get(INNER_CYLINDERS_KEY);
        if (value == null)
            return Optional.empty();
        return switch (value.trim().toLowerCase()) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> {
                logger.warn("Ignoring invalid '{}' value '{}' on {}: expected true or false",
                        INNER_CYLINDERS_KEY, value, parent);
                yield Optional.empty();
            }
        };
    }
}
