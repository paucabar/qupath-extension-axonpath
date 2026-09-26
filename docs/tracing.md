# 3D tracing

For z-stacks (e.g. serial-section or FIB-SEM data), **3D Tracing** links fibres across consecutive
z-slices, so the same axon can be followed through the volume.

<!-- screenshot: 3D Tracing section and traced fibres coloured by Axon ID -->

## Before tracing

Segment every z-slice you want to trace. **Annotate whole image** creates one annotation per
z-slice; select them all and click **Run** (see [Segmentation](segmentation.md#6-select-the-regions-to-segment)).

If you corrected results by hand, click **Recompute hierarchy & measurements** first (see
[Review & Edit](review-and-edit.md)). Tracing only uses fibres that are detections; fibres that are
still annotations are ignored.

## Running

1. Open the **3D Tracing** section of the AxonPath panel.
2. Set **Min overlap (IoU)** (default 0.5).
3. Click **Trace Axons**.

Tracing runs on all fibres in the image; no selection is needed.

## How it works

Fibres on each slice are compared with fibres on the next slice by their **overlap**, measured as
intersection over union (IoU): the area they share divided by the area they cover together. IoU is 1
for identical shapes and 0 for shapes that do not touch.

Starting from the first slice, each fibre gets a number. On each following slice, pairs of fibres
are matched from the highest overlap downwards, each fibre matching at most one fibre on the
neighbouring slice. Matched fibres get the same number; unmatched fibres get a new one.

The number is stored as the **`Axon ID`** measurement on each fibre and all objects inside it, and
each ID gets its own colour, so traced axons can be followed visually.

## Choosing the minimum overlap

Pairs with an overlap below **Min overlap (IoU)** are never matched.

- **Raise it** if different axons are being linked together, e.g. in densely packed tissue.
- **Lower it** if the same axon is split into several IDs, e.g. when axons shift a lot between
  slices (thick sections, large z-steps, oblique axons).

The value is remembered.

## Limitations

- Only **consecutive** slices are compared. If a fibre is missing on one slice (not segmented, or
  removed as incomplete), it gets a new ID after the gap.
- Tracing uses fibre outlines only; it does not use axon shape or intensity.
- Tracing works across **z-slices**. AxonPath is designed for fixed tissue, so images are not expected
  to have timepoints. If your stack shows a time slider instead of a z slider, its dimensions were
  read incorrectly. Fix them before adding the image to QuPath, e.g. in Fiji by swapping frames and
  slices in *Image → Properties* and saving the stack again.
- `Axon ID` is removed when results are converted to annotations and recomputed. Trace again after
  your last recompute.

`Axon ID` is included in [measurement exports](export.md), so traced axons can be analysed outside
QuPath, e.g. to follow g-ratio along an axon.
