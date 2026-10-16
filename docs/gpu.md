# GPU setup (optional)

**You don't need a GPU to use AxonPath.** Models run on the CPU of any computer, and nothing on
this page is required. A GPU makes segmentation faster, mostly on large images or many regions.

How to use a GPU depends on your computer:

- [Apple Silicon Macs](#apple-silicon-macs): nothing to install.
- [NVIDIA GPUs](#nvidia-gpus-windows-and-linux) on Windows or Linux: a one-time setup.

Then [choose the GPU in AxonPath](#choosing-the-gpu-in-axonpath).

## Apple Silicon Macs

Apple Silicon (M1 or later) Macs can use their GPU through **MPS**, with the Apple Silicon version
of QuPath. Nothing needs to be installed: choose `mps` as the device in AxonPath.

PyTorch's MPS support is more limited than CUDA's, so a model may not run on it. In that case
AxonPath shows a warning and continues on CPU.

## NVIDIA GPUs (Windows and Linux)

This is a one-time setup in three parts. It follows QuPath's own
[GPU support](https://qupath.readthedocs.io/en/stable/docs/deep/gpu.html) page, which explains the
background and other options.

You need:

- an NVIDIA GPU with a recent driver;
- [conda](https://conda-forge.org/download/) (e.g. Miniforge or Anaconda);
- about 11 GB of free disk space.

In short: a conda environment provides CUDA, the GPU toolkit PyTorch needs (version 12.8 for
QuPath 0.7). A launcher starts QuPath with that environment, and QuPath then downloads its GPU
version of PyTorch.

### Part 1: create the conda environment

Open a terminal (on Windows, *Miniforge Prompt* or *Anaconda Prompt*) and run these three
commands, one at a time:

```
conda create -n qupath-pytorch python=3.13
conda activate qupath-pytorch
pip install torch==2.7.1 torchvision==0.22.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cu128
```

The last command downloads about 3 GB. Then run `conda env list` and note the folder of
`qupath-pytorch`, e.g. `C:\Users\<you>\miniforge3\envs\qupath-pytorch`.

### Part 2: create the QuPath launcher

1. Start QuPath as usual and open **Extensions → Deep Java Library → Create launch script**.
2. Fill in:
   - **Conda environment:** the folder you noted in part 1;
   - **PyTorch version:** `2.7.1`. Type it exactly, with no spaces around it.

   Leave the other fields as they are (**PyTorch path** must stay empty).
3. Save the launcher somewhere easy to find, e.g. the Desktop, and close QuPath.

**From now on, start QuPath with this launcher** to use the GPU: on Windows, double-click the
`.bat` file; on Linux, run `bash /path/to/launcher.sh`. QuPath started the usual way still works,
on CPU only.

### Part 3: download PyTorch for the GPU

1. Start QuPath with the launcher.
2. Open **Extensions → Deep Java Library → Manage DJL engines**. It should show a **CUDA version**
   and your GPU. PyTorch shows version **?**, because its GPU version is not downloaded yet.
3. Click **Check/Download** next to PyTorch and wait for the download (about 4.5 GB) to finish.

### Check the setup

Open **Automate → Script editor** and run:

```groovy
println ai.djl.engine.Engine.getEngine("PyTorch")
```

If the GPU is set up, the output lists `CUDA` and `CUDNN` among the capabilities. AxonPath then
lists the GPU, see below.

## Choosing the GPU in AxonPath

In the AxonPath panel, open **Hardware** and choose the **Preferred device**:

- `gpu0` is the first NVIDIA GPU (`gpu1` the second, and so on);
- `mps` is the GPU of an Apple Silicon Mac.

The choice is remembered. If the model can't run on the chosen device, AxonPath shows a warning
and continues on CPU, so you can always fall back to `cpu`.

In scripts, use `.device("gpu")` (or `"gpu1"`, `"mps"`); see [Scripting](scripting.md).

## If the GPU is not listed

- **NVIDIA:** make sure QuPath was started with the launcher, then run the check above. If it
  shows an error:
  - *Offline mode is enabled*: PyTorch for the GPU is not downloaded yet; do
    [part 3](#part-3-download-pytorch-for-the-gpu);
  - *Illegal char*: the PyTorch version in the launcher has extra spaces; create the launcher
    again ([part 2](#part-2-create-the-qupath-launcher));
  - if there is no error but `CUDA` is not listed, see QuPath's
    [GPU support](https://qupath.readthedocs.io/en/stable/docs/deep/gpu.html) page.
- **Mac:** `mps` is listed only with the Apple Silicon version of QuPath, not the Intel version.
- The device list updates when you open **Hardware** and when you click **Run**.
