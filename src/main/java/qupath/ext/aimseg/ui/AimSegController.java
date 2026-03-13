package qupath.ext.aimseg.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.aimseg.core.PredictionTools;
import qupath.ext.aimseg.core.PytorchManager;
import qupath.ext.aimseg.core.QuantificationTools;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.io.IOException;
import java.util.ResourceBundle;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.scripting.QP;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;

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

    private final StringProperty modelDir = PathPrefs.createPersistentPreference("aimseg.model.dir", null);

    public static AimSegController createInstance() throws IOException {
        return new AimSegController();
    }

    private AimSegController() throws IOException {
        var url = AimSegController.class.getResource("aimseg.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        deviceChoiceBox.getItems().addAll(PytorchManager.getAvailableDevices());
        refreshModels(modelDir.get());

        // Refresh channels whenever the image changes
        QuPathGUI.getInstance().imageDataProperty().addListener(
                (obs, oldImage, newImage) -> refreshChannels());
        refreshChannels();
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
        if (QP.getSelectedObject() == null) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.no-selection"));
            return;
        }
        if (QP.getCurrentImageData() == null) {
            Dialogs.showErrorMessage("AimSeg extension", resources.getString("ui.error.no-image"));
            return;
        }

        var pathObjects = PredictionTools.runAimSeg(
                modelPath, QP.getCurrentImageData(), QP.getSelectedObject(), 0.5, 1,
                getSelectedChannel());
        logger.info("{} objects created by AimSeg", pathObjects.size());
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
        if (dir == null) return; // user cancelled — do nothing
        modelDir.set(dir.toString());
        refreshModels(modelDir.get());
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

    /**
     * Returns the currently selected image channel (1-based).
     */
    public int getSelectedChannel() {
        String selected = channelChoiceBox.getSelectionModel().getSelectedItem();
        if (selected == null) return 1;
        // Format is "C1 - name", parse the number after "C"
        try {
            return Integer.parseInt(selected.substring(1, selected.indexOf(" - ")));
        } catch (Exception e) {
            return 1;
        }
    }
}