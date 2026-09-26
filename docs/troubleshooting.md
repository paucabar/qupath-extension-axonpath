# Troubleshooting

Most problems are reported in QuPath's log: **View → Show log**. Include the log when asking for help.

## The model fails to run the first time

The PyTorch engine is probably not installed. Download it with **Extensions → Deep Java Library →
Manage DJL engines** (see [Installation](installation.md#installing-pytorch)).

## The model list is empty

The model directory must contain one folder per model, each with `weights.pt` and `rdf.yaml`. Select
the directory containing the model folders, not a model folder itself. See
[Models](models.md#setting-up-the-model-directory).

## The model parameters are greyed out after selecting a model

The model's `rdf.yaml` could not be read, or is missing one of the required `config` keys
(`pixel_size`, `min_diameter`, `predict_inner_cylinder`). The log says which. See
[Models](models.md#model-format).

## "No valid fibres were detected in the selected region"

The model ran, but no complete fibre was found. Check that:

- the **pixel size** of the image is set correctly (Image tab);
- the **model** matches the image type (EM CNS, EM PNS or brightfield);
- the right **input channel** is selected;
- the region contains myelinated fibres.

## Results are poor

- **Check the rescaling factor** next to *Pixel size* in the panel. If it is below 1 (upsampling), the
  image has a lower resolution than the model was trained on. See
  [Segmentation](segmentation.md#pixel-size-µm).
- **Check the pixel size** of the image. A wrong value makes the model see structures at the wrong
  size.
- **Adjust Min diameter** if small objects are missed or spurious ones appear.
- Correct the remaining errors by hand with [Review & Edit](review-and-edit.md).

## "Pixel calibration is not isotropic"

Pixel width and height differ. AxonPath requires square pixels; set both to the same value in the
Image tab.

## The GPU is not listed, or not used

- GPU options appear only once the PyTorch engine is installed (see
  [Installation](installation.md#installing-pytorch)).
- NVIDIA GPUs need a compatible CUDA installation. **Extensions → Deep Java Library → Manage DJL
  engines** shows whether CUDA was found.
- In scripts, a device that is not available is replaced with CPU, with a warning in the log.

## Buttons in Review & Edit are disabled

They act on a selected **parent** annotation, not on the fibres themselves:

- **Convert detections to annotations** needs AxonPath detections under the selected parent.
- **Recompute**, **lock** and **unlock** need AxonPath annotations under the selected parent.

## "No Fibre detections with children found — run AxonPath first"

3D tracing uses fibres that are detections. Run segmentation first, and if you converted results to
annotations, click **Recompute hierarchy & measurements** before tracing.

## Script errors

- **`Channel 'X' not found`:** the channel name does not exist in that image; the error lists the
  available names. Channel names are case-sensitive.
- **`Channel index N is out of range`:** channel indices start at 0 in scripts. See
  [Scripting](scripting.md#options).
- **`AxonPath requires a calibrated pixel size`:** set the pixel size of that image.
- **`Model directory not found`** or **`weights.pt not found`:** check `modelPath`. It must point to
  one model folder.

## Getting help

Ask on the [image.sc forum](https://forum.image.sc/tag/qupath) (tag `qupath`), or open an issue on
[GitHub](https://github.com/paucabar/qupath-extension-axonpath/issues).
