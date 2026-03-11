package qupath.ext.aimseg.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.layout.BorderPane;
import org.controlsfx.control.SearchableComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.aimseg.core.PredictionTools;
import qupath.ext.aimseg.core.PytorchManager;
import qupath.ext.aimseg.core.QuantificationTools;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.util.ResourceBundle;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.scripting.QP;

/**
 * Controller for UI pane contained in interface.fxml
 */
public class AimSegController extends BorderPane {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.aimseg.ui.strings");
    private static final Logger logger = LoggerFactory.getLogger(AimSegController.class);

    @FXML
    private SearchableComboBox<Path> modelChoiceBox;
    @FXML
    private ChoiceBox<String> deviceChoiceBox;
    @FXML
    private CheckBox bfCheckBox;
    private final StringProperty modelDir = PathPrefs.createPersistentPreference("aimseg.model.dir", null);

    /**
     * Create a new instance of the interface controller.
     * @return a new instance of the interface controller
     * @throws IOException If reading the extension FXML files fails.
     */
    public static AimSegController createInstance() throws IOException {
        return new AimSegController();
    }

    /**
     * This method reads an FXML file. These are markup files containing the structure of a UI element.
     * <p>
     * Fields in this class tagged with <code>@FXML</code> correspond to UI elements, and methods tagged with <code>@FXML</code> are methods triggered by actions on the UI (e.g., mouse clicks).
     * <p>
     * We consider the use of FXML to be "best practice" for UI creation, as it separates logic from layout and enables easier use of CSS. However, it is not mandatory, and you could instead define the layout of the UI using code.
     */
    private AimSegController() throws IOException {
        var url = AimSegController.class.getResource("aimseg.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        deviceChoiceBox.getItems().addAll(PytorchManager.getAvailableDevices());
        refreshModels(modelDir.get());
    }

    @FXML
    private void runAimSeg() throws IOException {
        Path modelPath = modelChoiceBox.getSelectionModel().getSelectedItem();
        if (modelPath == null) {
            Dialogs.showErrorMessage(
                    "AimSeg extension",
                    """
                            Model not set - point me to the directory containing a model, then select one.
                            """
            );
            return;
        }
        if (!Files.exists(modelPath)) {
            Dialogs.showErrorMessage("AimSeg extension", "Model not found!");
            return;
        }

        // Check if a parent object is selected
        if (QP.getSelectedObject() == null) {
            throw new IllegalArgumentException("A parent object is required to run the AimSeg model.");
        }

        var pathObjects = PredictionTools.runAimSeg(modelPath, QP.getCurrentImageData(), QP.getSelectedObject(), 0.5, 1);
        logger.info("{} objects created by AimSeg", pathObjects.size());
    }

    private PredictionTools.DataType getDataType() {
        // TODO: This method is no longer needed, review were it's still used
        return bfCheckBox.isSelected() ? PredictionTools.DataType.BRIGHTFIELD : PredictionTools.DataType.ELECTRON_MICROSCOPY;
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
        this.modelDir.set(FileChoosers.promptForDirectory().toString());
        refreshModels(modelDir.get());
    }

    private void refreshModels(String pathString) {
        if (pathString == null) {
            return;
        }
        var path = Path.of(pathString);
        if (!Files.exists(path)) {
            return;
        }
        modelChoiceBox.getItems().clear();
        try (var pathStream = Files.list(path)) {
            modelChoiceBox.getItems().addAll(
                pathStream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("weights.pt")) && Files.exists(p.resolve("rdf.yaml")))
                    .toList()
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void runQuantification() {
        QuantificationTools.computeFeatures(
                QP.getCurrentImageData(),
                QP.getAnnotationObjects().stream()
                        .filter(it -> it.getPathClass() == PathClass.getInstance("Fibre"))
                        .toList(),
                getDataType());
    }
}
