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

// ── Configuration ────────────────────────────────────────────────────────────
// Path to the model directory (the folder containing weights.pt and rdf.yaml)
def modelPath = "YOUR_MODEL_PATH"

// Parent annotations to segment inside: class name, null (all), or "" (unclassified)
// AxonPath annotations (Fibre, InnerCylinder, Axon) are never used as parents.
def targetClassName = "YOUR_CLASS_NAME"
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
def parents = getAnnotationObjects().findAll { !(it.getPathClass() in axonPathClasses) }
if (targetClassName == "")
    parents = parents.findAll { it.getPathClass() == null }
else if (targetClassName != null)
    parents = parents.findAll { it.getPathClass() == getPathClass(targetClassName) }
//parents = getSelectedObjects()  // To process only the selected annotations, useful while testing

if (parents.isEmpty()) {
    println "No parent annotations found for class: ${targetClassName == null ? 'all' : targetClassName == '' ? 'unclassified' : targetClassName}"
    return
}

segmenter.detectObjects(getCurrentImageData(), parents)
println "AxonPath segmentation done: ${parents.size()} annotation(s) processed"
