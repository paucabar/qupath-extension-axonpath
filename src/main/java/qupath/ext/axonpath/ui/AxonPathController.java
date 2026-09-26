package qupath.ext.axonpath.ui;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.util.StringConverter;
import org.controlsfx.control.SearchableComboBox;
import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.axonpath.core.AxonPathClasses;
import qupath.ext.axonpath.core.HierarchyTools;
import qupath.ext.axonpath.core.PredictionTools;
import qupath.ext.axonpath.core.PytorchManager;
import qupath.ext.axonpath.core.QuantificationTools;
import qupath.ext.axonpath.core.TracingTools;
import qupath.ext.axonpath.ui.ExportDialog;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.common.GeneralTools;
import qupath.lib.scripting.QP;
import javafx.fxml.FXMLLoader;

public class AxonPathController extends BorderPane {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.axonpath.ui.strings");
    private static final Logger logger = LoggerFactory.getLogger(AxonPathController.class);
    private boolean isRunning = false;

    @FXML
    private SearchableComboBox<Path> modelChoiceBox;
    @FXML
    private ChoiceBox<String> deviceChoiceBox;
    @FXML
    private ChoiceBox<String> channelChoiceBox;
    @FXML
    private Label modelDirLabel;
    @FXML
    private Button convertToAnnotationsButton;
    @FXML
    private Button recomputeButton;
    @FXML
    private ToggleButton lockAnnotationsButton;
    @FXML
    private ToggleButton unlockAnnotationsButton;
    @FXML
    private TextField pixelSizeField;
    @FXML
    private TextField minDiameterField;
    @FXML
    private CheckBox predictInnerCylinderCheckBox;
    @FXML
    private CheckBox removeEdgeFibresCheckBox;
    @FXML
    private Label minDiameterMicronsLabel;
    @FXML
    private Label downsampleLabel;
    @FXML
    private Button resetParamsButton;
    @FXML
    private Label labelMessage;
    @FXML
    private Spinner<Double> minOverlapSpinner;
    @FXML
    private Button traceAxonsButton;
    @FXML
    private Button exportMeasurementsButton;
    @FXML
    private Button exportAnnotationsButton;

    private double defaultPixelSize;
    private double defaultMinDiameter;
    private boolean defaultPredictInnerCylinder;

    // Pixel size and channel name edits (Image tab or scripts) keep the same ImageData and only
    // fire a "serverMetadata" property change, so the channel list and downsample label must
    // listen for it. Also fires on channel colour changes. May fire off the FX thread.
    private final PropertyChangeListener serverMetadataListener = evt -> {
        if ("serverMetadata".equals(evt.getPropertyName()))
            Platform.runLater(() -> {
                refreshChannels(true);
                updateDownsampleDisplay();
            });
    };

    private final StringProperty modelDir = PathPrefs.createPersistentPreference("axonpath.model.dir", null);
    private final StringProperty preferredDevice = PathPrefs.createPersistentPreference("axonpath.inference.device", null);
    private final StringProperty minOverlapPref = PathPrefs.createPersistentPreference("axonpath.tracing.min.overlap", "0.5");

    public static AxonPathController createInstance() throws IOException {
        return new AxonPathController();
    }

    private AxonPathController() throws IOException {
        var url = AxonPathController.class.getResource("axonpath.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        configureDevices();
        configureMinOverlapSpinner();
        modelChoiceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Path path) {
                return path == null ? "" : path.getFileName().toString();
            }
            @Override
            public Path fromString(String s) {
                return null;
            }
        });
        refreshModels(modelDir.get());

        // Update µm display whenever pixel size or min diameter changes
        pixelSizeField.textProperty().addListener((obs, oldVal, newVal) -> updateMinDiameterMicrons());
        minDiameterField.textProperty().addListener((obs, oldVal, newVal) -> updateMinDiameterMicrons());

        // Update downsample display whenever pixel size changes
        pixelSizeField.textProperty().addListener((obs, oldVal, newVal) -> updateDownsampleDisplay());

        // Refresh model params when selection changes
        modelChoiceBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> refreshModelParams(newVal));

        // Enable export buttons only when a project is open
        var noProject = QuPathGUI.getInstance().projectProperty().isNull();
        exportMeasurementsButton.disableProperty().bind(noProject);
        exportAnnotationsButton.disableProperty().bind(noProject);

        // Refresh channels and post-processing buttons whenever the image changes
        QuPathGUI.getInstance().imageDataProperty().addListener(
                (obs, oldImage, newImage) -> {
                    if (oldImage != null) oldImage.removePropertyChangeListener(serverMetadataListener);
                    if (newImage != null) newImage.addPropertyChangeListener(serverMetadataListener);
                    refreshChannels(false);
                    refreshPostProcessingButtons();
                    updateDownsampleDisplay();
                });
        var currentImage = QuPathGUI.getInstance().getImageData();
        if (currentImage != null) currentImage.addPropertyChangeListener(serverMetadataListener);
        refreshChannels(false);
        updateDownsampleDisplay();

        // Enable/disable post-processing buttons based on selection
        QuPathGUI.getInstance().getViewer().addViewerListener(new qupath.lib.gui.viewer.QuPathViewerListener() {
            @Override
            public void selectedObjectChanged(qupath.lib.gui.viewer.QuPathViewer viewer, PathObject pathObjectSelected) {
                refreshPostProcessingButtons();
                refreshStatusLabel();
            }
            @Override
            public void visibleRegionChanged(qupath.lib.gui.viewer.QuPathViewer viewer, java.awt.Shape shape) {}
            @Override
            public void imageDataChanged(qupath.lib.gui.viewer.QuPathViewer viewer, qupath.lib.images.ImageData imageDataOld, qupath.lib.images.ImageData imageDataNew) {}
            @Override
            public void viewerClosed(qupath.lib.gui.viewer.QuPathViewer viewer) {}
        });
        refreshPostProcessingButtons();
        refreshStatusLabel();
    }

    @FXML
    private void run() {
        Path modelPath = modelChoiceBox.getSelectionModel().getSelectedItem();
        if (modelPath == null) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.no-model"));
            return;
        }
        if (!Files.exists(modelPath)) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.model-not-downloaded"));
            return;
        }
        if (QP.getCurrentImageData() == null) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.no-image"));
            return;
        }
        var selectedObjects = new ArrayList<>(QP.getSelectedObjects());
        if (selectedObjects.isEmpty()) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.no-selection"));
            return;
        }
        if (selectedObjects.stream().anyMatch(this::isAxonPathClass)) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.axonpath-selection"));
            return;
        }

        double pixelSize, minDiameter;
        try {
            pixelSize = Double.parseDouble(pixelSizeField.getText());
            minDiameter = Double.parseDouble(minDiameterField.getText());
        } catch (NumberFormatException e) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.invalid-params"));
            return;
        }
        boolean predictInnerCylinder = predictInnerCylinderCheckBox.isSelected();
        boolean removeEdgeFibres = removeEdgeFibresCheckBox.isSelected();
        final double finalPixelSize = pixelSize;
        final double finalMinDiameter = minDiameter;

        ensureAxonPathClasses();
        isRunning = true;
        setStatusLabel(MessageFormat.format(
                resources.getString("ui.run.progress"), 1, selectedObjects.size()));
        Platform.runLater(() -> runInferenceStep(selectedObjects, 0, modelPath, finalPixelSize, finalMinDiameter,
                predictInnerCylinder, removeEdgeFibres, new int[]{0}));
    }

    /**
     * Processes one parent object per FX pulse, yielding between each so the status
     * label repaints visibly. All hierarchy modifications stay on the FX thread.
     */
    private void runInferenceStep(List<PathObject> parents, int index,
                                  Path modelPath, double pixelSize, double minDiameter,
                                  boolean predictInnerCylinder, boolean removeEdgeFibres, int[] totalObjects) {
        Platform.runLater(() -> {
            try {
                var result = PredictionTools.runAxonPath(
                        modelPath, QP.getCurrentImageData(), parents.get(index), 0.5, 1,
                        getSelectedChannel(), pixelSize, minDiameter, predictInnerCylinder, removeEdgeFibres, getDevice());
                totalObjects[0] += result.size();
            } catch (IOException e) {
                logger.error("AxonPath inference failed for parent {}", index, e);
                Dialogs.showErrorMessage("AxonPath extension", e.getMessage());
                onInferenceComplete(parents, totalObjects);
                return;
            }
            int next = index + 1;
            if (next < parents.size()) {
                setStatusLabel(MessageFormat.format(
                        resources.getString("ui.run.progress"), next + 1, parents.size()));
                runInferenceStep(parents, next, modelPath, pixelSize, minDiameter,
                        predictInnerCylinder, removeEdgeFibres, totalObjects);
            } else {
                onInferenceComplete(parents, totalObjects);
            }
        });
    }

    private void onInferenceComplete(List<PathObject> parents, int[] totalObjects) {
        isRunning = false;
        logger.info("{} total objects created by AxonPath", totalObjects[0]);
        var allFibres = parents.stream()
                .flatMap(p -> p.getChildObjects().stream())
                .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();
        if (allFibres.isEmpty())
            Dialogs.showWarningNotification("AxonPath extension", resources.getString("ui.error.no-valid-fibres"));
        refreshPostProcessingButtons();
        refreshStatusLabel();
    }

    @FXML
    private void convertToAnnotations() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();

        var detections = QP.getSelectedObjects().stream()
                .filter(p -> !isAxonPathClass(p))
                .flatMap(p -> HierarchyTools.getAllDescendants(p).stream())
                .filter(it -> isAxonPathClass(it) && it.isDetection())
                .toList();

        if (detections.isEmpty()) return;

        ensureAxonPathClasses();
        var annotations = detections.stream()
                .map(d -> {
                    var ann = PathObjects.createAnnotationObject(d.getROI(), d.getPathClass());
                    ann.setLocked(true);
                    return ann;
                })
                .toList();

        hierarchy.removeObjects(detections, false);
        hierarchy.addObjects(annotations);
        hierarchy.fireHierarchyChangedEvent(this);

        refreshPostProcessingButtons();
    }

    /**
     * Adds any missing AxonPath classes to QuPath's class list. Called before each action
     * because opening a project (possibly while this window is open) replaces that list.
     */
    private void ensureAxonPathClasses() {
        AxonPathClasses.ensureClassesAvailable(QuPathGUI.getInstance().getAvailablePathClasses());
    }

    @FXML
    private void lockAnnotations() {
        setAxonPathAnnotationsLocked(true);
        lockAnnotationsButton.setSelected(false);
    }

    @FXML
    private void unlockAnnotations() {
        setAxonPathAnnotationsLocked(false);
        unlockAnnotationsButton.setSelected(false);
    }

    private void setAxonPathAnnotationsLocked(boolean locked) {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();
        var allAxonPathAnnotations = hierarchy.getFlattenedObjectList(null).stream()
                .filter(it -> isAxonPathClass(it) && it.isAnnotation())
                .toList();
        var toUpdate = QP.getSelectedObjects().stream()
                .filter(p -> !isAxonPathClass(p))
                .flatMap(p -> {
                    var parentGeom = p.getROI().getGeometry();
                    var parentPlane = p.getROI().getImagePlane();
                    // Restricted to the parent's own z/t plane: without this, on a z-stack where
                    // fibres occupy similar XY footprints across slices, a selected parent on one
                    // slice would lock/unlock annotations belonging to other slices too — the
                    // geometry intersection check below is 2D (X,Y) only and ignores Z entirely.
                    // Same root cause as recomputeForParent()'s z/t fix.
                    return allAxonPathAnnotations.stream()
                            .filter(it -> it.getROI().getImagePlane().getZ() == parentPlane.getZ()
                                    && it.getROI().getImagePlane().getT() == parentPlane.getT())
                            .filter(it -> {
                                try {
                                    if (parentGeom.intersects(it.getROI().getGeometry())) return true;
                                    PathObject ancestor = it.getParent();
                                    while (ancestor != null && isAxonPathClass(ancestor)) {
                                        if (parentGeom.intersects(ancestor.getROI().getGeometry())) return true;
                                        ancestor = ancestor.getParent();
                                    }
                                    return false;
                                } catch (Exception e) {
                                    return false;
                                }
                            });
                })
                .distinct()
                .toList();
        if (toUpdate.isEmpty()) return;
        toUpdate.forEach(it -> it.setLocked(locked));
        hierarchy.fireHierarchyChangedEvent(this);
    }

    @FXML
    private void recompute() {
        if (QP.getCurrentImageData() == null) return;

        var parents = QP.getSelectedObjects().stream()
                .filter(p -> {
                    if (isAxonPathClass(p)) {
                        logger.info("Skipping AxonPath-classed object as parent: {}", p);
                        return false;
                    }
                    return true;
                })
                .toList();
        if (parents.isEmpty()) return;

        ensureAxonPathClasses();
        isRunning = true;
        setStatusLabel(MessageFormat.format(
                resources.getString("ui.run.progress"), 1, parents.size()));
        Platform.runLater(() -> recomputeStep(parents, 0));
    }

    /**
     * Processes one parent object per FX pulse, mirroring {@link #runInferenceStep}: yields
     * between each so the status label repaints and the UI stays responsive. A fully-selected
     * 3D z-stack can mean recomputing dozens of parents at once — without yielding, the whole
     * batch would run as a single unbroken block on the FX thread.
     */
    private void recomputeStep(List<PathObject> parents, int index) {
        Platform.runLater(() -> {
            recomputeForParent(parents.get(index));

            int next = index + 1;
            if (next < parents.size()) {
                setStatusLabel(MessageFormat.format(
                        resources.getString("ui.run.progress"), next + 1, parents.size()));
                recomputeStep(parents, next);
            } else {
                isRunning = false;
                logger.info("Hierarchy and measurements recomputed");
                refreshPostProcessingButtons();
            }
        });
    }

    private void recomputeForParent(PathObject parentObject) {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();
        var parentROI = parentObject.getROI();
        var parentPlane = parentROI.getImagePlane();

        // Collect all AxonPath annotations that either directly intersect the parent, or
        // have a QuPath ancestor (fibre) that intersects the parent. The ancestor check handles
        // annotations created from scratch where QuPath places the axon as a child of the fibre
        // in the hierarchy, even when the axon lies outside the parent boundary.
        //
        // Restricted to the parent's own z/t plane: geometry intersection tests below are 2D
        // (X,Y) only, so on a z-stack where fibres occupy similar XY footprints across slices,
        // omitting this check pulls in every other slice's objects as candidates too. That
        // blew up the cost of HierarchyTools.assignChildrenToParents()'s O(parents x children)
        // matching below roughly with (z-slices)^2, which is what made recompute() effectively
        // freeze on 3D data.
        var parentGeom = parentROI.getGeometry();
        var allAxonPath = hierarchy.getFlattenedObjectList(null).stream()
                .filter(it -> isAxonPathClass(it) && it.isAnnotation())
                .filter(it -> it.getROI().getImagePlane().getZ() == parentPlane.getZ()
                        && it.getROI().getImagePlane().getT() == parentPlane.getT())
                .filter(it -> {
                    try {
                        if (parentGeom.intersects(it.getROI().getGeometry())) return true;
                        PathObject ancestor = it.getParent();
                        while (ancestor != null && isAxonPathClass(ancestor)) {
                            if (parentGeom.intersects(ancestor.getROI().getGeometry())) return true;
                            ancestor = ancestor.getParent();
                        }
                        return false;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();

        if (allAxonPath.isEmpty()) {
            logger.info("No AxonPath objects found within selected parent, skipping");
            return;
        }

        // Convert annotations to detections
        var detections = allAxonPath.stream()
                .map(d -> PathObjects.createDetectionObject(d.getROI(), d.getPathClass()))
                .toList();
        hierarchy.removeObjects(allAxonPath, false);

        var fibres = detections.stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();
        var axons = detections.stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                .toList();
        var innerCylinders = detections.stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("InnerCylinder"))
                .toList();

        if (fibres.isEmpty()) {
            logger.info("No Fibre objects found within selected parent, skipping");
            return;
        }

        HierarchyTools.updateHierarchy(hierarchy, parentObject, fibres, axons, innerCylinders);

        var validFibres = parentObject.getChildObjects().stream()
                .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();
        QuantificationTools.computeFeatures(imageData, validFibres);
        QuantificationTools.computeIntensityFeatures(imageData, validFibres);
    }

    @FXML
    private void resetParams() {
        applyDefaultParams();
    }

    @FXML
    private void showModelDirInfo() {
        Dialogs.showMessageDialog("Model directory",
                "AxonPath expects each model to be a folder containing:\n" +
                "  - weights.pt  (PyTorch model weights)\n" +
                "  - rdf.yaml    (BioImage.IO model config)\n\n" +
                "Point the model directory to the folder containing these subfolders.");
    }

    @FXML
    private void selectAllAnnotations() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();
        var toSelect = hierarchy.getAnnotationObjects().stream()
                .filter(a -> !isAxonPathClass(a))
                .toList();
        hierarchy.getSelectionModel().setSelectedObjects(toSelect, toSelect.isEmpty() ? null : toSelect.get(0));
    }

    @FXML
    private void selectAllDetections() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();
        var toSelect = hierarchy.getDetectionObjects().stream()
                .filter(d -> !isAxonPathClass(d))
                .toList();
        hierarchy.getSelectionModel().setSelectedObjects(toSelect, toSelect.isEmpty() ? null : toSelect.get(0));
    }

    @FXML
    private void annotateWholeImage() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var server = imageData.getServer();
        var hierarchy = imageData.getHierarchy();
        int nZ = server.nZSlices();
        var annotations = new ArrayList<PathObject>();
        for (int z = 0; z < nZ; z++) {
            var roi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
                    ImagePlane.getPlane(z, 0));
            annotations.add(PathObjects.createAnnotationObject(roi));
        }
        hierarchy.addObjects(annotations);
        hierarchy.getSelectionModel().setSelectedObjects(annotations, annotations.get(0));
    }

    @FXML
    private void chooseModel() {
        File dir = FileChoosers.promptForDirectory();
        if (dir == null) return;
        modelDir.set(dir.toString());
        refreshModels(modelDir.get());
    }

    @FXML
    private void traceAxons() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.no-image"));
            return;
        }
        boolean hasTraceableFibres = imageData.getHierarchy().getFlattenedObjectList(null).stream()
                .anyMatch(o -> o.getPathClass() == PathClass.getInstance("Fibre")
                        && o.isDetection()
                        && !o.getChildObjects().isEmpty());
        if (!hasTraceableFibres) {
            Dialogs.showErrorMessage("AxonPath extension", resources.getString("ui.error.no-traceable-fibres"));
            return;
        }
        double minOverlap = minOverlapSpinner.getValue();

        var task = new Task<Void>() {
            @Override
            protected Void call() {
                TracingTools.traceAxons(imageData, minOverlap,
                        p -> updateProgress(p, 1.0),
                        this::updateMessage);
                return null;
            }
        };

        var dialog = new ProgressDialog(task);
        dialog.setTitle("AxonPath");
        dialog.setHeaderText(resources.getString("ui.tracing.progress.header"));
        dialog.setOnShown(e -> dialog.getDialogPane().setGraphic(null));

        task.setOnSucceeded(e -> dialog.close());
        task.setOnFailed(e -> {
            logger.error("Axon tracing failed", task.getException());
            Dialogs.showErrorMessage("AxonPath extension",
                    task.getException() != null ? task.getException().getMessage() : "Unexpected error");
            dialog.close();
        });

        Thread thread = new Thread(task, "axonpath-tracing");
        thread.setDaemon(true);
        thread.start();
        dialog.showAndWait();
    }

    private void configureMinOverlapSpinner() {
        double initial;
        try {
            initial = Double.parseDouble(minOverlapPref.get());
        } catch (NumberFormatException e) {
            initial = 0.5;
        }
        var valueFactory = new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1.0, initial, 0.05);
        minOverlapSpinner.setValueFactory(valueFactory);
        minOverlapSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
                minOverlapPref.set(String.valueOf(newVal)));
    }

    private void configureDevices() {
        deviceChoiceBox.getItems().addAll(PytorchManager.getAvailableDevices());
        deviceChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                preferredDevice.set(newVal));
        if (preferredDevice.get() != null) {
            deviceChoiceBox.setValue(preferredDevice.get());
        }
    }

    private String getDevice() {
        String device = deviceChoiceBox.getValue();
        return (device == null || device.isBlank()) ? "cpu" : device;
    }

    private void refreshModels(String pathString) {
        if (pathString == null) return;
        var path = Path.of(pathString);
        if (!Files.exists(path)) return;

        try (var pathStream = Files.list(path)) {
            var models = pathStream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("weights.pt")) && Files.exists(p.resolve("rdf.yaml")))
                    .toList();
            modelChoiceBox.getItems().setAll(models);
            modelDirLabel.setText(path.getFileName().toString());
            modelDirLabel.getStyleClass().remove("warning-message");
            if (modelDirLabel.getTooltip() != null)
                modelDirLabel.getTooltip().setText(pathString);
        } catch (IOException e) {
            logger.error("Error listing model directory: {}", pathString, e);
        }
    }

    private void updateDownsampleDisplay() {
        try {
            double targetPixelSize = Double.parseDouble(pixelSizeField.getText());
            var imageData = QP.getCurrentImageData();
            if (imageData == null) { downsampleLabel.setText(""); return; }
            double imagePixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            if (!Double.isFinite(imagePixelSize) || imagePixelSize <= 0) { downsampleLabel.setText(""); return; }

            double raw = targetPixelSize / imagePixelSize;
            double downsample;
            if (raw >= 0.9 && raw <= 1.1) {
                downsample = 1.0;
            } else {
                double rounded = Math.round(raw);
                downsample = GeneralTools.almostTheSame(raw, rounded, 0.01) ? rounded : raw;
            }

            String text = (downsample == Math.floor(downsample))
                    ? "(\u00d7" + (long) downsample + ")"
                    : String.format("(\u00d7%.2f)", downsample);
            downsampleLabel.setText(text);

            if (downsample < 1.0) {
                if (!downsampleLabel.getStyleClass().contains("downsample-warning"))
                    downsampleLabel.getStyleClass().add("downsample-warning");
            } else {
                downsampleLabel.getStyleClass().remove("downsample-warning");
            }
        } catch (NumberFormatException e) {
            downsampleLabel.setText("");
            downsampleLabel.getStyleClass().remove("downsample-warning");
        }
    }

    private void updateMinDiameterMicrons() {
        try {
            double px = Double.parseDouble(minDiameterField.getText());
            double microns = Double.parseDouble(pixelSizeField.getText());
            minDiameterMicronsLabel.setText(String.format("(%.2f \u00b5m)", px * microns));
        } catch (NumberFormatException e) {
            minDiameterMicronsLabel.setText("");
        }
    }

    private void refreshModelParams(Path modelPath) {
        if (modelPath == null || !Files.exists(modelPath.resolve("rdf.yaml"))) {
            pixelSizeField.setDisable(true);
            minDiameterField.setDisable(true);
            predictInnerCylinderCheckBox.setDisable(true);
            resetParamsButton.setDisable(true);
            pixelSizeField.setText("");
            minDiameterField.setText("");
            predictInnerCylinderCheckBox.setSelected(false);
            return;
        }
        try {
            var params = PredictionTools.extractParametersFromYaml(modelPath.resolve("rdf.yaml"));
            defaultPixelSize = (double) params.get("pixel_size");
            defaultMinDiameter = (double) params.get("min_diameter");
            defaultPredictInnerCylinder = (boolean) params.get("predict_inner_cylinder");
            applyDefaultParams();
            pixelSizeField.setDisable(false);
            minDiameterField.setDisable(false);
            predictInnerCylinderCheckBox.setDisable(false);
            resetParamsButton.setDisable(false);
        } catch (Exception e) {
            logger.error("Could not read model parameters from rdf.yaml", e);
        }
    }

    private void applyDefaultParams() {
        pixelSizeField.setText(String.valueOf(defaultPixelSize));
        minDiameterField.setText(String.valueOf(defaultMinDiameter));
        predictInnerCylinderCheckBox.setSelected(defaultPredictInnerCylinder);
    }

    /**
     * Rebuild the channel list from the current image's metadata.
     *
     * @param keepSelection if true, reselect the previously selected channel index (when still valid)
     *                      instead of resetting to C1. Use only for metadata updates on the same image
     *                      (e.g. channel renames), not when switching images.
     */
    private void refreshChannels(boolean keepSelection) {
        int previousIndex = channelChoiceBox.getSelectionModel().getSelectedIndex();
        channelChoiceBox.getItems().clear();
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;

        var server = imageData.getServer();
        var channels = server.getMetadata().getChannels();
        for (int i = 0; i < channels.size(); i++) {
            String name = "C" + (i + 1) + " - " + channels.get(i).getName();
            channelChoiceBox.getItems().add(name);
        }
        if (keepSelection && previousIndex >= 0 && previousIndex < channelChoiceBox.getItems().size()) {
            channelChoiceBox.getSelectionModel().select(previousIndex);
        } else if (!channelChoiceBox.getItems().isEmpty()) {
            channelChoiceBox.getSelectionModel().selectFirst();
        }
    }

    private boolean isAxonPathClass(PathObject obj) {
        return obj.getPathClass() == PathClass.getInstance("Fibre")
                || obj.getPathClass() == PathClass.getInstance("Axon")
                || obj.getPathClass() == PathClass.getInstance("InnerCylinder");
    }

    private void refreshPostProcessingButtons() {
        var selected = QP.getSelectedObjects();

        boolean hasAxonPathAnnotations = selected != null && !selected.isEmpty()
                && selected.stream().noneMatch(this::isAxonPathClass)
                && QP.getCurrentImageData() != null
                && QP.getCurrentImageData().getHierarchy().getFlattenedObjectList(null).stream()
                .filter(it -> isAxonPathClass(it) && it.isAnnotation())
                .anyMatch(it -> selected.stream()
                        .anyMatch(p -> {
                            try {
                                return p.getROI().getGeometry().intersects(it.getROI().getGeometry());
                            } catch (Exception e) {
                                return false;
                            }
                        }));

        boolean hasAxonPathDetections = selected != null
                && selected.stream().noneMatch(this::isAxonPathClass)
                && selected.stream()
                .flatMap(p -> HierarchyTools.getAllDescendants(p).stream())
                .anyMatch(it -> isAxonPathClass(it) && it.isDetection());

        convertToAnnotationsButton.setDisable(!hasAxonPathDetections);
        recomputeButton.setDisable(!hasAxonPathAnnotations);
        lockAnnotationsButton.setDisable(!hasAxonPathAnnotations);
        unlockAnnotationsButton.setDisable(!hasAxonPathAnnotations);
    }

    private void refreshStatusLabel() {
        if (isRunning) return;
        var selected = QP.getSelectedObjects();
        boolean hasSelection = selected != null && !selected.isEmpty();
        labelMessage.setVisible(!hasSelection);
        labelMessage.setManaged(!hasSelection);
        if (!hasSelection) {
            if (!labelMessage.getStyleClass().contains("error-message"))
                labelMessage.getStyleClass().add("error-message");
            labelMessage.setText(resources.getString("ui.status.no-selection"));
        }
    }

    private void setStatusLabel(String text) {
        labelMessage.getStyleClass().remove("error-message");
        labelMessage.setText(text);
        labelMessage.setVisible(true);
        labelMessage.setManaged(true);
    }

    /**
     * Returns the currently selected image channel (1-based).
     */
    public int getSelectedChannel() {
        String selected = channelChoiceBox.getSelectionModel().getSelectedItem();
        if (selected == null) return 1;
        try {
            return Integer.parseInt(selected.substring(1, selected.indexOf(" - ")));
        } catch (Exception e) {
            return 1;
        }
    }

    @FXML
    private void openExportDialog() {
        var gui = QuPathGUI.getInstance();
        var project = gui.getProject();
        // Save current image data so export can read the latest measurements from disk
        var imageData = gui.getImageData();
        if (imageData != null && project != null) {
            try {
                var entry = project.getEntry(imageData);
                if (entry != null) entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.warn("Could not save image data before export", e);
            }
        }
        ExportDialog.show((javafx.stage.Stage) getScene().getWindow(), project);
    }

    @FXML
    private void openAnnotationExportDialog() {
        var gui = QuPathGUI.getInstance();
        var project = gui.getProject();
        // Save current image data so the exporter sees the latest annotations from disk
        var imageData = gui.getImageData();
        if (imageData != null && project != null) {
            try {
                var entry = project.getEntry(imageData);
                if (entry != null) entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.warn("Could not save image data before annotation export", e);
            }
        }
        AnnotationExportDialog.show((javafx.stage.Stage) getScene().getWindow(), project);
    }
}
