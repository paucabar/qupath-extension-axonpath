/**
 * AxonPath segmentation script template
 *
 * Segments myelinated fibres (Fibre > InnerCylinder > Axon) inside annotations, using the same
 * pipeline as the Run button in the AxonPath panel.
 *
 * Batch processing: open this script from a project and use Run > Run for project.
 *
 * Notes:
 *  - The image pixel size must be calibrated (Image tab > Pixel width/height).
 *  - Re-running removes any AxonPath objects already inside a parent annotation,
 *    including manual corrections.
 *  - Processed parent annotations are locked.
 */

import qupath.ext.axonpath.AxonPathSegmenter
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.roi.RectangleROI

// ── Configuration ────────────────────────────────────────────────────────────
// Path to the model directory (the folder containing weights.pt and rdf.yaml)
def modelPath = "YOUR_MODEL_PATH"

// Parent annotations to segment inside: class name, null (all), or "" (unclassified)
// AxonPath annotations (Fibre, InnerCylinder, Axon) are never used as parents.
def targetClassName = "YOUR_CLASS_NAME"

// Segment whole images instead (e.g. EM images without background); targetClassName is then ignored.
// Uses one full-image annotation per z-slice and timepoint: an existing one is reused, whatever its
// class, otherwise an unclassified one is created. Planes with other annotations are skipped.
def segmentFullImage = false
// ─────────────────────────────────────────────────────────────────────────────

def segmenter = AxonPathSegmenter
    .builder(modelPath)
    .channel(0)                   // Channel by 0-based index (first channel = 0, shown as C1 in the AxonPath panel)
//  .channel("YOUR_CHANNEL_NAME") // Or by name, safer if channel order differs between images
    .device("cpu")                // "cpu", "gpu" or "mps" (Apple Silicon); falls back to CPU if unavailable
    .removeEdgeFibres(false)      // Remove fibres touching the parent annotation boundary
//  .pixelSize(0.02)              // Target pixel size in µm (default: from the model's rdf.yaml)
//  .minDiameter(5)               // Minimum object diameter in pixels at the target pixel size (default: from rdf.yaml)
//  .predictInnerCylinder(false)  // Segment inner cylinders, EM models only (default: from rdf.yaml)
    .build()

// Select parent annotations, excluding AxonPath objects
def axonPathClasses = ["Fibre", "InnerCylinder", "Axon"].collect { getPathClass(it) }
def annotations = getAnnotationObjects().findAll { !(it.getPathClass() in axonPathClasses) }
def parents = []

if (segmentFullImage) {
    def server = getCurrentServer()
    def isFullImage = { roi ->
        roi instanceof RectangleROI && roi.getBoundsX() == 0 && roi.getBoundsY() == 0
                && roi.getBoundsWidth() == server.getWidth() && roi.getBoundsHeight() == server.getHeight()
    }
    for (int t = 0; t < server.nTimepoints(); t++) {
        for (int z = 0; z < server.nZSlices(); z++) {
            def onPlane = annotations.findAll { it.getROI().getZ() == z && it.getROI().getT() == t }
            def fullImage = onPlane.findAll { isFullImage(it.getROI()) }
            // A full-image annotation would contain any other annotation on the plane, and segmenting
            // both would give overlapping results, so such planes are left for manual handling
            def others = onPlane - fullImage
            if (!others.isEmpty()) {
                println "Skipping z=${z}, t=${t}: it has ${others.size()} other annotation(s)"
                continue
            }
            if (fullImage.size() > 1)
                println "Warning: ${fullImage.size()} full-image annotations on z=${z}, t=${t}; using the first, delete the others"
            if (fullImage.isEmpty()) {
                def roi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), ImagePlane.getPlane(z, t))
                def annotation = PathObjects.createAnnotationObject(roi)
                addObject(annotation)
                fullImage = [annotation]
            }
            parents << fullImage[0]
        }
    }
} else {
    parents = annotations
    if (targetClassName == "")
        parents = parents.findAll { it.getPathClass() == null }
    else if (targetClassName != null)
        parents = parents.findAll { it.getPathClass() == getPathClass(targetClassName) }
}
//parents = getSelectedObjects()  // To process only the selected annotations, useful while testing

if (parents.isEmpty()) {
    if (segmentFullImage)
        println "No planes to segment: every plane has other annotations"
    else
        println "No parent annotations found for class: ${targetClassName == null ? 'all' : targetClassName == '' ? 'unclassified' : targetClassName}"
    return
}

segmenter.detectObjects(getCurrentImageData(), parents)
println "AxonPath segmentation done: ${parents.size()} annotation(s) processed"
