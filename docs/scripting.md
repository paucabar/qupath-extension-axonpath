# Scripting and batch processing

AxonPath can be run from a Groovy script, the same way as the **Run** button in the AxonPath panel.
This is the way to process many images: write the script once and use QuPath's **Run for project**.

## The script template

Open the template from **Extensions → AxonPath → Segmentation script template**. It opens in
QuPath's script editor as a new, unsaved script.

<!-- screenshot: script editor with the template open -->

Edit the configuration block at the top:

```groovy
// Path to the model directory (the folder containing weights.pt and rdf.yaml)
def modelPath = "YOUR_MODEL_PATH"

// Parent annotations to segment inside: class name, null (all), or "" (unclassified)
def targetClassName = "YOUR_CLASS_NAME"
```

- **`modelPath`** is the folder of one model, e.g. `"D:/models/em_cns-0.1.0"`. Forward slashes
  work on every operating system, including Windows.
- **`targetClassName`** selects the annotations to segment inside:

  | Value | Annotations processed |
  |---|---|
  | `"Tissue"` (any class name) | Annotations of that class |
  | `null` | All annotations |
  | `""` | Unclassified annotations |

  AxonPath objects (Fibre, InnerCylinder, Axon) are never used as parents, whatever the value, so
  annotations created with *Convert detections to annotations* are not segmented again.

Then adjust the segmentation options in the builder (see [Options](#options)) and run the script.

### Running on a whole project

1. Save the script.
2. In the script editor, choose **Run → Run for project** and select the images to process.

Images without annotations of the chosen class are skipped, with a message in the log. To segment
whole images, create full-image annotations first, e.g. with **Objects → Annotations → Create full
image annotation**, or add `createFullImageAnnotation(true)` before the parent selection in the
script.

### Before running

- Each image needs a **calibrated pixel size**; images without one fail with an error.
- Re-running removes any AxonPath objects already inside a parent annotation, **including manual
  corrections**. Keep a copy of the project if you have corrected results you need.
- Processed parent annotations are locked, as they are when using the panel.

## Options

The template creates an `AxonPathSegmenter` with a builder. Every option is optional:

```groovy
def segmenter = AxonPathSegmenter
    .builder(modelPath)
    .channel(0)
    .device("cpu")
    .removeEdgeFibres(false)
    .build()
```

| Option | Default | Description |
|---|---|---|
| `.channel(int)` | `0` | Channel to segment, by **0-based** index: the first channel is `0`. The AxonPath panel shows channels starting at C1, so C1 is `0`, C2 is `1` and so on |
| `.channel(String)` | – | Channel to segment, by name, e.g. `.channel("DAPI")`. Looked up separately in each image, so it works when channel order differs between images. An unknown name fails with an error listing the available channels |
| `.device(String)` | `"cpu"` | `"cpu"`, `"gpu"` (NVIDIA; `"gpu:1"` for a second GPU) or `"mps"` (Apple Silicon). If the device is not available, or the model fails on it, AxonPath logs a warning and continues on CPU |
| `.removeEdgeFibres(boolean)` | `false` | Remove fibres touching the boundary of the parent annotation. Useful when fibres cut by the annotation edge would bias measurements |
| `.pixelSize(double)` | from `rdf.yaml` | Pixel size in µm the image is rescaled to before segmentation |
| `.minDiameter(double)` | from `rdf.yaml` | Minimum object diameter in pixels, at the model's pixel size |
| `.predictInnerCylinder(boolean)` | from `rdf.yaml` | Segment inner cylinders (EM models) |

The last three override the model's defaults and are the same as the *Model parameters* in the
panel; see [Segmentation](segmentation.md#3-check-the-model-parameters) before changing them.

`build()` checks the model folder and fails straight away if `weights.pt` or `rdf.yaml` is missing or
invalid, so a wrong path is reported before any image is processed.

## Running on selected annotations only

While testing settings, it is often easier to process only the selected annotations. Uncomment this
line in the template:

```groovy
parents = getSelectedObjects()
```

## Writing your own script

The template is a starting point; the API can be used in any script:

```groovy
import qupath.ext.axonpath.AxonPathSegmenter

def segmenter = AxonPathSegmenter.builder("D:/models/em_cns-0.1.0")
        .channel("DAPI")
        .build()

def parents = getAnnotationObjects().findAll { it.getPathClass() == getPathClass("Tissue") }
def objects = segmenter.detectObjects(getCurrentImageData(), parents)
println "Created ${objects.size()} objects"
```

`detectObjects(imageData, parents)` segments inside each parent and returns all AxonPath objects
created (fibres, inner cylinders and axons). Hierarchy and [measurements](measurements.md) are
computed as they are when using the panel.

A segmenter can be reused for any number of images: build it once, then call `detectObjects` for
each image.

## Other steps

Only segmentation is available through scripting for now. [Review & Edit](review-and-edit.md),
[3D tracing](tracing.md) and [export](export.md) are available from the AxonPath panel.
