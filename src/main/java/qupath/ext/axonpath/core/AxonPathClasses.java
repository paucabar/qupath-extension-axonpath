package qupath.ext.axonpath.core;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.objects.classes.PathClass;

/**
 * Names and default colours of the classes AxonPath assigns to its output objects, plus a helper
 * to make sure they appear in QuPath's class list (so users can pick them when correcting
 * results manually).
 * <p>
 * Default colours: a light blue for fibres, chosen to stay visible on both dark myelin and grey
 * background, and the viridis stops of the AxonPath benchmark overlay figures (axonpath training
 * repo, {@code plot_prediction_overlays.py}) for inner cylinders and axons.
 */
public class AxonPathClasses {
    private static final Logger logger = LoggerFactory.getLogger(AxonPathClasses.class);

    public static final String FIBRE = "Fibre";
    public static final String INNER_CYLINDER = "InnerCylinder";
    public static final String AXON = "Axon";

    private static final int FIBRE_COLOR = ColorTools.packRGB(30, 144, 255);          // light blue
    private static final int INNER_CYLINDER_COLOR = ColorTools.packRGB(53, 183, 120); // viridis(2/3)
    private static final int AXON_COLOR = ColorTools.packRGB(253, 231, 36);           // viridis(1.0)

    private AxonPathClasses() {
        throw new UnsupportedOperationException("Do not instantiate this class");
    }

    /**
     * Returns whether a class is one of the AxonPath output classes (Fibre, InnerCylinder, Axon).
     *
     * @param pathClass the class to check; {@code null} (unclassified) returns false
     * @return true for an AxonPath class
     */
    public static boolean isAxonPathClass(PathClass pathClass) {
        return pathClass != null && (pathClass == PathClass.getInstance(FIBRE)
                || pathClass == PathClass.getInstance(INNER_CYLINDER)
                || pathClass == PathClass.getInstance(AXON));
    }

    /**
     * Appends any missing AxonPath classes (Fibre, InnerCylinder, Axon) to the given class list.
     * Existing entries are never removed, reordered or recoloured; a default colour is applied
     * only to a class that is being added.
     * <p>
     * Intended to be called with {@code QuPathGUI.getAvailablePathClasses()}. QuPath replaces
     * that list whenever a project is opened, so this should be called at the point of use
     * (opening the AxonPath window, running actions) rather than once at startup.
     *
     * @param availableClasses the class list to update in place
     * @return true if any class was added
     */
    public static boolean ensureClassesAvailable(List<PathClass> availableClasses) {
        boolean changed = false;
        changed |= addIfMissing(availableClasses, FIBRE, FIBRE_COLOR);
        changed |= addIfMissing(availableClasses, INNER_CYLINDER, INNER_CYLINDER_COLOR);
        changed |= addIfMissing(availableClasses, AXON, AXON_COLOR);
        return changed;
    }

    private static boolean addIfMissing(List<PathClass> availableClasses, String name, int color) {
        // PathClass instances are cached singletons, so contains() is an identity check by name
        var pathClass = PathClass.getInstance(name);
        if (availableClasses.contains(pathClass))
            return false;
        pathClass.setColor(color);
        availableClasses.add(pathClass);
        logger.info("Added missing class '{}' to the available classes", name);
        return true;
    }
}
