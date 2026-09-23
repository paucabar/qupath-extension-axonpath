# AxonPath for QuPath

A [QuPath](https://qupath.github.io) extension for deep learning segmentation of myelinated nerve
fibres in **electron microscopy** and **brightfield** images.

AxonPath runs trained [AxonPath](https://github.com/paucabar/aimseg-dl) models on regions you
select and turns the predictions into a QuPath object hierarchy:

- **Electron microscopy:** Fibre → InnerCylinder → Axon
- **Brightfield:** Fibre → Axon

Every fibre gets morphometric measurements (areas, g-ratios, circularity, solidity, axon count)
and per-compartment intensity statistics. The results can be corrected by hand and re-measured
without re-running the model.

> **Status:** first release in preparation. Full documentation is coming; this page covers the
> essentials.

## Requirements

- QuPath **0.7.0** or later
- An internet connection the first time a model runs: QuPath's Deep Java Library extension
  downloads the PyTorch engine on first use
- A GPU is optional; models run on CPU too

## Installation

AxonPath is installed from its extension catalog, through QuPath's Extension Manager:

1. In QuPath, open **Extensions → Manage extensions**.
2. Click **Manage extension catalogs**, paste
   `https://github.com/paucabar/qupath-catalog-axonpath` and click **Add**.
3. Install **AxonPath** from the extension list and restart QuPath if prompted.

The extension then appears under **Extensions → AxonPath**.

## Models

AxonPath models are distributed in [BioImage.IO](https://bioimage.io) format: one folder per
model, containing `weights.pt` and `rdf.yaml`. The `rdf.yaml` sets the model's defaults (target
pixel size, minimum object diameter, whether inner cylinders are predicted).

Models for EM (central and peripheral nervous system) and brightfield images will be published
alongside the first release. Put the model folders inside one directory and select that
directory in the AxonPath panel.

## Basic workflow

1. Open an image with pixel size calibration set.
2. Select the annotations to process, or click **Annotate whole image** (one annotation per
   z-slice for z-stacks).
3. Choose a model, input channel and preferred device, check the model parameters, and click
   **Run**.
4. To correct results by hand (**Review & Edit**): **Convert detections to annotations**, edit
   the objects with QuPath's tools, then **Recompute hierarchy & measurements**.
5. **Data Export:** export measurements as TSV, or export annotations as training tiles or
   full-resolution images with GeoJSON.

For z-stacks, **3D Tracing → Trace Axons** links fibres across consecutive slices by shape
overlap.

## Citation

A preprint describing AxonPath is in preparation. Citation details will be added here.

## License

Apache License 2.0; see [LICENSE](LICENSE).
