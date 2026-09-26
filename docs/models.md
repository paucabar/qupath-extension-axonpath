# Models

AxonPath runs deep learning models trained with [aimseg-dl](https://github.com/paucabar/aimseg-dl).
Each model is trained for one type of image at a fixed resolution.

## Available models

| Model | Images | Pixel size | Hierarchy |
|---|---|---|---|
| `em_cns-0.1.0` | Electron microscopy, central nervous system | 0.008 µm | Fibre → InnerCylinder → Axon |
| `em_pns-0.1.0` | Electron microscopy, peripheral nervous system | 0.03 µm | Fibre → InnerCylinder → Axon |
| `brightfield-0.1.0` | Brightfield | 0.071 µm | Fibre → Axon |

**Pixel size** is the resolution the model was trained at. Images at a different resolution are
rescaled automatically, but work best when their pixel size is the same or smaller (finer) than the
model's. See [Segmentation](segmentation.md#pixel-size-µm).

## Download

<!-- TODO: Zenodo DOI link -->

Models are distributed as `.zip` files. Download the ones you need and unzip each into its own
folder.

## Setting up the model directory

Put all model folders inside one directory:

```
AxonPath models/
├── em_cns-0.1.0/
│   ├── weights.pt
│   ├── rdf.yaml
│   └── ...
├── em_pns-0.1.0/
└── brightfield-0.1.0/
```

In the AxonPath panel, click **Choose model directory** and select the top directory
(`AxonPath models/` above), not an individual model folder. Every subfolder containing `weights.pt`
and `rdf.yaml` appears in the model list.

## Model format

Models use the [BioImage.IO](https://bioimage.io) format: a folder with the PyTorch weights
(`weights.pt`) and a description file (`rdf.yaml`). AxonPath reads three settings from the `config`
section of `rdf.yaml`, which become the defaults in the panel:

| Key | Type | Meaning |
|---|---|---|
| `pixel_size` | number | Pixel size in µm the model was trained at |
| `min_diameter` | number | Minimum object diameter in pixels, at the model's pixel size |
| `predict_inner_cylinder` | `true` / `false` | Whether the model segments inner cylinders (EM) |

```yaml
config:
  pixel_size: 0.008
  min_diameter: 20.0
  predict_inner_cylinder: true
```

A model without these keys cannot be used, and selecting it logs an error.

## Training your own model

Models can be trained or fine-tuned on your own data with
[aimseg-dl](https://github.com/paucabar/aimseg-dl). Training data can be exported from QuPath with
[Export annotations → Training](export.md#training-mode).
