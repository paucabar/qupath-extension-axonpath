package qupath.ext.aimseg.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.aimseg.core.HierarchyTools;
import qupath.ext.aimseg.core.PredictionTools;
import qupath.ext.aimseg.core.PytorchManager;
import qupath.ext.aimseg.core.QuantificationTools;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.scripting.QP;
import javafx.fxml.FXMLLoader;

public class AimSegController extends BorderPane {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.aimseg.ui.strings");
    private static final Logger logger = LoggerFactory.getLogger(AimSegController.class);

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
    private TextField pixelSizeField;
    @FXML
    private TextField minDiameterField;
    @FXML
    private CheckBox predictInnerTongueCheckBox;
    @FXML
    private Button resetParamsButton;
    @FXML
    private Label labelMessage;

    private double defaultPixelSize;
    private double defaultMinDiameter;
    private boolean defaultPredictInnerTongue;

    private final StringProperty modelDir = PathPrefs.createPersistentPreference("aimseg.model.dir", null);
    private final StringProperty preferredDevice = PathPrefs.createPersistentPreference("aimseg.inference.device", null);

    public static AimSegController createInstance() throws IOException {
        return new AimSegController();
    }

    private AimSegController() throws IOException {
        var url = AimSegController.class.getResource("aimseg.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        configureDevices();
        refreshModels(modelDir.get());

        // Refresh model params when selection changes
        modelChoiceBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> refreshModelParams(newVal));

        // Refresh channels and post-processing buttons whenever the image changes
        QuPathGUI.getInstance().imageDataProperty().addListener(
                (obs, oldImage, newImage) -> {
                    refreshChannels();
                    refreshPostProcessingButtons();
                });
        refreshChannels();

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
    private void runAimSeg() throws IOException {
        Path modelPath = modelChoiceBox.getSelectionModel().getSelectedItem();
        if (modelPath == null) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.no-model"));
            return;
        }
        if (!Files.exists(modelPath)) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.model-not-downloaded"));
            return;
        }
        if (QP.getCurrentImageData() == null) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.no-image"));
            return;
        }
        var selectedObjects = QP.getSelectedObjects();
        if (selectedObjects == null || selectedObjects.isEmpty()) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.no-selection"));
            return;
        }

        double pixelSize, minDiameter;
        try {
            pixelSize = Double.parseDouble(pixelSizeField.getText());
            minDiameter = Double.parseDouble(minDiameterField.getText());
        } catch (NumberFormatException e) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.invalid-params"));
            return;
        }
        boolean predictInnerTongue = predictInnerTongueCheckBox.isSelected();

        int totalObjects = 0;
        for (var parentObject : selectedObjects) {
            var pathObjects = PredictionTools.runAimSeg(
                    modelPath, QP.getCurrentImageData(), parentObject, 0.5, 1,
                    getSelectedChannel(), pixelSize, minDiameter, predictInnerTongue, getDevice());
            totalObjects += pathObjects.size();
        }
        logger.info("{} total objects created by AimSeg", totalObjects);

        var allFibres = selectedObjects.stream()
                .flatMap(p -> p.getChildObjects().stream())
                .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                .toList();
        if (allFibres.isEmpty()) {
            Dialogs.showWarningNotification("AimSeg extension", resources.getString("ui.error.no-valid-fibres"));
        }

        refreshPostProcessingButtons();
    }

    @FXML
    private void convertToAnnotations() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();

        var detections = QP.getSelectedObjects().stream()
                .filter(p -> !isAimSegClass(p))
                .flatMap(p -> HierarchyTools.getAllDescendants(p).stream())
                .filter(it -> isAimSegClass(it) && it.isDetection())
                .toList();

        if (detections.isEmpty()) return;

        var annotations = detections.stream()
                .map(d -> PathObjects.createAnnotationObject(d.getROI(), d.getPathClass()))
                .toList();

        hierarchy.removeObjects(detections, false);
        hierarchy.addObjects(annotations);
        hierarchy.fireHierarchyChangedEvent(this);

        refreshPostProcessingButtons();
    }

    @FXML
    private void recompute() {
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;

        for (var parentObject : QP.getSelectedObjects()) {
            if (isAimSegClass(parentObject)) {
                logger.info("Skipping AimSeg-classed object as parent: {}", parentObject);
                continue;
            }
            var hierarchy = imageData.getHierarchy();
            var parentROI = parentObject.getROI();

            // Find all AimSeg-classed annotations in the image that fall within this parent
            var allAimSeg = hierarchy.getFlattenedObjectList(null).stream()
                    .filter(it -> isAimSegClass(it) && it.isAnnotation())
                    .filter(it -> parentROI.getGeometry().covers(it.getROI().getGeometry()))
                    .toList();

            if (allAimSeg.isEmpty()) {
                logger.info("No AimSeg objects found within selected parent, skipping");
                continue;
            }

            // Convert annotations to detections
            var detections = allAimSeg.stream()
                    .map(d -> PathObjects.createDetectionObject(d.getROI(), d.getPathClass()))
                    .toList();
            hierarchy.removeObjects(allAimSeg, false);

            var fibres = detections.stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                    .toList();
            var axons = detections.stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Axon"))
                    .toList();
            var innerTongues = detections.stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Inner Tongue"))
                    .toList();

            if (fibres.isEmpty()) {
                logger.info("No Fibre objects found within selected parent, skipping");
                continue;
            }

            HierarchyTools.updateHierarchy(hierarchy, parentObject, fibres, axons, innerTongues);

            var validFibres = parentObject.getChildObjects().stream()
                    .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                    .toList();
            QuantificationTools.computeFeatures(imageData, validFibres);
        }

        logger.info("Hierarchy and measurements recomputed");
        refreshPostProcessingButtons();
    }

    @FXML
    private void resetParams() {
        applyDefaultParams();
    }

    @FXML
    private void showModelDirInfo() {
        Dialogs.showMessageDialog("Model directory",
                "AimSeg expects each model to be a folder containing:\n" +
                "  - weights.pt  (PyTorch model weights)\n" +
                "  - rdf.yaml    (BioImage.IO model config)\n\n" +
                "Point the model directory to the folder containing these subfolders.");
    }

    @FXML
    private void selectAllAnnotations() {
        QP.selectAnnotations();
    }

    @FXML
    private void selectAllDetections() {
        QP.selectDetections();
    }

    @FXML
    private void chooseModel() {
        File dir = FileChoosers.promptForDirectory();
        if (dir == null) return;
        modelDir.set(dir.toString());
        refreshModels(modelDir.get());
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
        } catch (IOException e) {
            logger.error("Error listing model directory: {}", pathString, e);
        }
    }

    private void refreshModelParams(Path modelPath) {
        if (modelPath == null || !Files.exists(modelPath.resolve("rdf.yaml"))) {
            pixelSizeField.setDisable(true);
            minDiameterField.setDisable(true);
            predictInnerTongueCheckBox.setDisable(true);
            resetParamsButton.setDisable(true);
            pixelSizeField.setText("");
            minDiameterField.setText("");
            predictInnerTongueCheckBox.setSelected(false);
            return;
        }
        try {
            var params = PredictionTools.extractParametersFromYaml(modelPath.resolve("rdf.yaml"));
            defaultPixelSize = (double) params.get("pixel_size");
            defaultMinDiameter = (double) params.get("min_diameter");
            defaultPredictInnerTongue = (boolean) params.get("predict_inner_tongue");
            applyDefaultParams();
            pixelSizeField.setDisable(false);
            minDiameterField.setDisable(false);
            predictInnerTongueCheckBox.setDisable(false);
            resetParamsButton.setDisable(false);
        } catch (IOException e) {
            logger.error("Could not read model parameters from rdf.yaml", e);
        }
    }

    private void applyDefaultParams() {
        pixelSizeField.setText(String.valueOf(defaultPixelSize));
        minDiameterField.setText(String.valueOf(defaultMinDiameter));
        predictInnerTongueCheckBox.setSelected(defaultPredictInnerTongue);
    }

    private void refreshChannels() {
        channelChoiceBox.getItems().clear();
        var imageData = QP.getCurrentImageData();
        if (imageData == null) return;

        var server = imageData.getServer();
        var channels = server.getMetadata().getChannels();
        for (int i = 0; i < channels.size(); i++) {
            String name = "C" + (i + 1) + " - " + channels.get(i).getName();
            channelChoiceBox.getItems().add(name);
        }
        if (!channelChoiceBox.getItems().isEmpty()) {
            channelChoiceBox.getSelectionModel().selectFirst();
        }
    }

    private boolean isAimSegClass(PathObject obj) {
        return obj.getPathClass() == PathClass.getInstance("Fibre")
                || obj.getPathClass() == PathClass.getInstance("Axon")
                || obj.getPathClass() == PathClass.getInstance("Inner Tongue");
    }

    private void refreshPostProcessingButtons() {
        var selected = QP.getSelectedObjects();

        boolean hasAimSegAnnotations = selected != null && !selected.isEmpty()
                && selected.stream().noneMatch(this::isAimSegClass)
                && QP.getCurrentImageData() != null
                && QP.getCurrentImageData().getHierarchy().getFlattenedObjectList(null).stream()
                .filter(it -> isAimSegClass(it) && it.isAnnotation())
                .anyMatch(it -> selected.stream()
                        .anyMatch(p -> p.getROI().getGeometry().covers(it.getROI().getGeometry())));

        boolean hasAimSegDetections = selected != null
                && selected.stream().noneMatch(this::isAimSegClass)
                && selected.stream()
                .flatMap(p -> HierarchyTools.getAllDescendants(p).stream())
                .anyMatch(it -> isAimSegClass(it) && it.isDetection());

        convertToAnnotationsButton.setDisable(!hasAimSegDetections);
        recomputeButton.setDisable(!hasAimSegAnnotations);
    }

    private void refreshStatusLabel() {
        var selected = QP.getSelectedObjects();
        boolean hasSelection = selected != null && !selected.isEmpty();
        labelMessage.setVisible(!hasSelection);
        labelMessage.setManaged(!hasSelection);
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
}