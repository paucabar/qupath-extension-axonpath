package qupath.ext.aimseg;

import java.util.ResourceBundle;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.aimseg.ui.AimSegController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;


public class AimSegExtension implements QuPathExtension, GitHubProject {
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.aimseg.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(AimSegExtension.class);

	private static final String EXTENSION_NAME = resources.getString("extension.title");
	private static final String EXTENSION_DESCRIPTION = resources.getString("extension.description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");
	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "paucabar", "AimSeg_QuPath_Extension");

	private boolean isInstalled = false;

	private static final BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"enableExtension", true);

	private Stage stage;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addMenuItem(qupath);
	}

	private void handleStageHeightChange() {
		stage.sizeToScene();
		// This fixes a bug where the stage would migrate to the corner of a screen if it is
		// resized, hidden, then shown again
		if (stage.isShowing() && Double.isFinite(stage.getX()) && Double.isFinite(stage.getY()))
			FXUtils.retainWindowPosition(stage);
	}

	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions", false);
		MenuItem menuItem = new MenuItem("AimSeg");
		menuItem.setOnAction(e -> createStage());
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}

	private void createStage() {
		if (stage == null) {
			try {
				stage = new Stage();
				var pane = AimSegController.createInstance();
				Scene scene = new Scene(new BorderPane(pane));
				pane.heightProperty().addListener((v, o, n) -> handleStageHeightChange());
				stage.initOwner(QuPathGUI.getInstance().getStage());
				stage.setTitle("AimSeg");
				stage.setScene(scene);
				stage.setResizable(false);
			} catch (IOException e) {
				Dialogs.showErrorMessage("Error", "GUI loading failed");
				logger.error("Unable to load extension interface FXML", e);
			}
		}
		stage.show();
	}


	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}

	@Override
	public GitHubRepo getRepository() {
		return EXTENSION_REPOSITORY;
	}
}
