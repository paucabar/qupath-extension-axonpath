# Segmentation

This page explains how to segment myelinated fibres with the AxonPath panel. To process many images
at once, see [Scripting and batch processing](scripting.md).

Open the panel from **Extensions → AxonPath → AxonPath**.

<!-- screenshot: AxonPath panel, main section -->

## 1. Prepare the image

- **Set the pixel size.** AxonPath rescales images to the resolution the model was trained on, and
  reports measurements in µm, so the pixel size must be calibrated. Check it in the Image tab
  (*Pixel width* / *Pixel height*) and double-click a value to set it. Pixel width and height must be
  equal.
- **Use a project** if you want to export measurements or annotations later. Export works on project
  images.

## 2. Choose a model

The first time, click **Choose model directory** and select the folder that contains your model
folders (see [Models](models.md)). AxonPath remembers it. The folder name is shown next to
*Models:*; double-click it to open the folder on your computer.

Then pick a model from **Select a model**. Only folders containing both `weights.pt` and `rdf.yaml`
are listed. The info button next to the list summarises this.

Choose the model that matches your images:

| Model | Images | Model pixel size |
|---|---|---|
| `em_cns-0.1.0` | Electron microscopy, central nervous system | 0.008 µm |
| `em_pns-0.1.0` | Electron microscopy, peripheral nervous system | 0.03 µm |
| `brightfield-0.1.0` | Brightfield | 0.071 µm |

## 3. Check the model parameters

Selecting a model fills in its default parameters, read from its `rdf.yaml`. The defaults are
usually right; change them only for a reason. The reset button restores the model defaults.

### Pixel size (µm)

The pixel size the image is rescaled to before segmentation. It should match the resolution the
model was trained at, so leave it at the model default unless you have a reason to change it.

The number next to it shows how the image will be rescaled: **(×4)** means the image is downsampled
4 times. A factor below 1 (highlighted as a warning) means the image is **upsampled**: its pixels are larger than
the model's, so the model sees structures larger than it was trained on. Expect poorer results, and
consider a model trained at a resolution closer to your images.

When the image and model pixel sizes differ by less than 10%, the image is not rescaled at all.

### Min diameter (px)

The smallest object kept, as a diameter in pixels at the model's pixel size. Smaller objects are
discarded as noise. The equivalent size in µm is shown next to it.

Lower it if small fibres or axons are missed; raise it if small spurious objects appear.

### Predict inner cylinder

Segment inner cylinders (axon plus inner tongue). This gives the full EM hierarchy, Fibre →
InnerCylinder → Axon, and the EM-only measurements (`InnerCylinder Area`, `Myelin g-ratio`,
`InnerTongue` intensities; see [Measurements](measurements.md)).

On by default for EM models and off for brightfield, where the inner tongue cannot be resolved.

### Remove edge fibres

Remove fibres touching the boundary of the region being processed. Fibres cut by the region edge have
incomplete shapes, which biases measurements such as area and g-ratio. Off by default.

## 4. Choose the input channel

AxonPath models take a single channel. Pick it from **Input channel**. The list shows channels as
C1, C2, and so on, with their names.

Single-channel (greyscale) images, e.g. 8-bit EM or brightfield images, have only C1. For images
with several channels, choose the one showing the fibres.

## 5. Choose the device

Open the **Hardware** section to choose the **Preferred device**:

- **cpu** always works.
- **gpu** appears if PyTorch detects a supported NVIDIA GPU.
- **mps** appears on Macs, for Apple Silicon GPUs.

GPU options are only listed once the PyTorch engine has been downloaded (see
[Installation](installation.md#installing-pytorch)). If in doubt, use CPU. The choice is remembered.

## 6. Select the regions to segment

AxonPath segments inside **parent objects**: usually annotations drawn around the tissue of
interest. Select one or more before running:

- Draw and select annotations with QuPath's tools, or
- Use **Select all → Annotations** (or **Detections**) to select all of them in the image, or
- Click **Annotate whole image** to create and select an annotation covering the whole image. For
  z-stacks it creates one annotation per z-slice.

AxonPath objects (Fibre, InnerCylinder, Axon) cannot be used as parents.

## 7. Run

Click **Run**. The parents are processed one at a time, with progress shown below the button.

For each parent, AxonPath:

1. Rescales the region to the model's pixel size and runs the model on it, in tiles.
2. Turns the model output into fibres, axons and (optionally) inner cylinders.
3. Keeps only objects inside the parent, clipping those crossing its boundary.
4. Builds the hierarchy: each inner cylinder is assigned to the fibre containing it, and each axon to
   the inner cylinder (EM) or fibre (brightfield) containing it.
5. Removes incomplete objects: fibres without an axon (or, in EM, without an inner cylinder containing
   an axon), and axons or inner cylinders not inside a fibre.
6. Computes [measurements](measurements.md) for every fibre and its contents.
7. Locks the parent, so it is not moved or edited by accident.

**Running again on the same parent replaces its previous results**, including manual corrections.
Other annotations inside the parent are kept. Avoid running both a parent and an annotation nested
inside it: their results overlap in the nested region.

If no complete fibres are found, a notification says so. Check the pixel size, the channel and the
model.

## Results

The results are detection objects, organised under each parent:

| Imaging mode | Hierarchy |
|---|---|
| Electron microscopy (inner cylinder prediction on) | Fibre → InnerCylinder → Axon |
| Brightfield (inner cylinder prediction off) | Fibre → Axon |

View them in the Hierarchy tab, and their measurements with **Measure → Show detection
measurements**. See [Measurements](measurements.md) for what each measurement means.

<!-- screenshot: segmented EM image with the hierarchy -->

## Next steps

- Correct errors by hand: [Review & Edit](review-and-edit.md)
- Link fibres across z-slices: [3D tracing](tracing.md)
- Export results: [Export](export.md)
