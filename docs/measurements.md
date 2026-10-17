# Measurements

AxonPath adds morphometric and intensity measurements to every fibre it keeps, and shape and
intensity measurements to the objects inside each fibre. This page lists every measurement, what it
means and where it is stored.

Measurements are computed after [segmentation](segmentation.md) and recomputed after
[Review & Edit](review-and-edit.md). They can be viewed in QuPath's measurement tables
(**Measure → Show detection measurements**) or exported with [Export measurements](export.md).

## Requirements

- **Calibrated pixel size.** Areas are reported in µm², so the image must have a pixel size set
  (Image tab → *Pixel width* / *Pixel height*).
- **Square pixels.** Pixel width and height must be equal; otherwise measurement fails with an
  error.

## Object hierarchy

Measurements depend on which objects a fibre contains, which depends on the
[Predict inner cylinder](segmentation.md#predict-inner-cylinder) setting, not on the image type:

| Predict inner cylinder | Hierarchy |
|---|---|
| On (default for the EM models) | Fibre → InnerCylinder → Axon |
| Off (default for the brightfield model) | Fibre → Axon |

A **Fibre** is the whole myelinated fibre: axon, inner tongue and myelin sheath. An
**InnerCylinder** is the region inside the myelin sheath (axon plus inner tongue). An **Axon** is
the axon itself; in brightfield images, where the inner tongue cannot be resolved, it also includes
the inner tongue. A fibre can contain more than one axon.

The setting used is stored on the parent as `AxonPath inner cylinders`, so that Recompute rebuilds
the same hierarchy (see [Review & Edit](review-and-edit.md#what-recompute-does)).

Fibres missing a required child (e.g. no axon) are removed during segmentation, so every fibre in the
results has a complete hierarchy.

## Fibre measurements

Stored on each **Fibre** object.

### Morphometry

| Measurement | Unit | Definition |
|---|---|---|
| `Fibre Area` | µm² | Area of the fibre |
| `Fibre Circularity` | – | 4π × area / perimeter². 1 for a perfect circle, lower for elongated or irregular shapes |
| `Fibre Solidity` | – | Area / convex hull area. 1 for convex shapes, lower for indented shapes |
| `Axon Area` | µm² | Total area of all axons in the fibre |
| `Axon Count` | – | Number of axons in the fibre |
| `Axon g-ratio` | – | Axon diameter / fibre diameter (see [g-ratios](#g-ratios)) |
| `InnerCylinder Area` | µm² | Total area of all inner cylinders in the fibre. **With inner cylinders only** |
| `Myelin g-ratio` | – | Inner cylinder diameter / fibre diameter (see [g-ratios](#g-ratios)). **With inner cylinders only** |

### Intensity

Intensity statistics are measured per **compartment** and per image **channel**, and all stored on
the Fibre so that a single row describes the whole fibre. Names follow the pattern
`<Compartment>: <channel>: <statistic>`, e.g. `Myelin: Channel 1: Mean`.

| Compartment | Region measured |
|---|---|
| `Fibre` | The whole fibre |
| `Myelin` | The fibre minus its inner cylinders (with inner cylinders) or minus its axons (without) |
| `InnerTongue` | The inner cylinders minus the axons. **With inner cylinders only**; absent if the inner tongue is empty |
| `Axon` | All axons in the fibre, combined |

`<channel>` is the channel name as shown in QuPath (e.g. `Channel 1`, or `Red`, `Green`, `Blue` for
RGB images). All channels are measured, not only the one used for segmentation.

Statistics: `Mean`, `Median`, `Min`, `Max`, `Std.Dev.`, `Variance`.

In brightfield images the inner tongue cannot be resolved, so it is part of the segmented axon: the
`Axon` compartment includes it, and `Myelin` is the myelin sheath only.

## Axon measurements

Stored on each **Axon** object, for per-axon analysis (useful in fibres with several axons).

| Measurement | Unit | Definition |
|---|---|---|
| `Area` | µm² | Area of the axon |
| `Circularity` | – | As for fibres |
| `Solidity` | – | As for fibres |
| `<channel>: <statistic>` | – | Intensity statistics of the axon, e.g. `Channel 1: Mean`. Same statistics as above |

## InnerCylinder measurements

Stored on each **InnerCylinder** object (with inner cylinders only).

| Measurement | Unit | Definition |
|---|---|---|
| `Area` | µm² | Area of the inner cylinder |
| `Circularity` | – | As for fibres |
| `Solidity` | – | As for fibres |

Inner cylinders have no intensity measurements of their own; see the `InnerTongue` compartment on
the Fibre.

## Linking objects: Parent Fibre ID

Each Axon and InnerCylinder stores the ID of the fibre it belongs to as **Parent Fibre ID**. When
Axon or InnerCylinder measurements are exported, this is included as a column so they can be joined
to the fibre table (matching the fibre's `Object ID`).

## 3D tracing: Axon ID

After [3D tracing](tracing.md), each traced fibre and all objects inside it get an **`Axon ID`**
measurement. Fibres on different z-slices with the same `Axon ID` belong to the same axon.

## G-ratios

G-ratios are computed from **equivalent circular diameters**: the diameter of a circle with the
same area as the object, 2 × √(area / π). This makes them robust to non-circular fibres and allows
them to be computed for fibres with several axons.

- **Axon g-ratio** = axon diameter / fibre diameter, using the total axon area of the fibre.
- **Myelin g-ratio** (with inner cylinders only) = inner cylinder diameter / fibre diameter, using
  the total inner cylinder area of the fibre. This is the ratio between the inner and outer
  boundaries of the myelin sheath. Unlike the axon g-ratio, it does not count the inner tongue as part
  of the myelin.

Without inner cylinders only the axon g-ratio is available. In brightfield images the axon includes
the inner tongue (which cannot be resolved), so there the axon g-ratio measures the same boundary as
the myelin g-ratio does in EM.

If you draw a fibre with only an axon and Recompute creates its inner cylinder (see
[Review & Edit](review-and-edit.md#drawing-a-missing-fibre)), the inner cylinder has the axon's
shape. Its inner tongue is empty, so its `Myelin g-ratio` equals its `Axon g-ratio` and it has no
`InnerTongue` intensities.

## After manual edits

Editing objects by hand makes their measurements out of date. Use **Recompute hierarchy &
measurements** (see [Review & Edit](review-and-edit.md)) to rebuild the hierarchy and recompute
every measurement on this page.

Converting and recomputing creates new objects, so:

- `Axon ID` is removed. Run [3D tracing](tracing.md) again after your last recompute.
- Object IDs change, and with them every `Parent Fibre ID`. Export measurements after your last
  recompute so the IDs in the exported tables match each other.
