package qupath.ext.axonpath;

import java.nio.charset.StandardCharsets;
import java.util.ResourceBundle;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.axonpath.core.AxonPathClasses;
import qupath.ext.axonpath.ui.AxonPathController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;


public class AxonPathExtension implements QuPathExtension, GitHubProject {
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.axonpath.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(AxonPathExtension.class);

	private static final String EXTENSION_NAME = resources.getString("extension.title");
	private static final String EXTENSION_DESCRIPTION = resources.getString("extension.description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");
	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "paucabar", "qupath-extension-axonpath");

	private static final String SCRIPTS_PATH = "/qupath/ext/axonpath/scripts/";
	private static final String SEGMENTATION_SCRIPT = "AxonPath_segmentation.groovy";

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
		var extensionsMenu = qupath.getMenu("Extensions", false);
		Menu menu = new Menu(EXTENSION_NAME);
		menu.disableProperty().bind(enableExtensionProperty.not());

		MenuItem openItem = new MenuItem(resources.getString("menu.open"));
		openItem.setOnAction(e -> createStage());

		MenuItem scriptItem = new MenuItem(resources.getString("menu.script.segmentation"));
		scriptItem.setOnAction(e -> openScriptTemplate(qupath, SEGMENTATION_SCRIPT));

		menu.getItems().addAll(openItem, scriptItem);
		extensionsMenu.getItems().add(menu);
	}

	/**
	 * Opens a script bundled in the extension JAR as a new, unsaved script in the script editor.
	 */
	private void openScriptTemplate(QuPathGUI qupath, String scriptName) {
		try (var stream = AxonPathExtension.class.getResourceAsStream(SCRIPTS_PATH + scriptName)) {
			if (stream == null)
				throw new IOException("Script not found in extension: " + scriptName);
			var script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			qupath.getScriptEditor().showScript(scriptName, script);
		} catch (IOException e) {
			Dialogs.showErrorMessage(EXTENSION_NAME, "Unable to open script template: " + e.getMessage());
			logger.error("Unable to open script template {}", scriptName, e);
		}
	}

	private void createStage() {
		if (stage == null) {
			try {
				stage = new Stage();
				var pane = AxonPathController.createInstance();
				Scene scene = new Scene(new BorderPane(pane));
				pane.heightProperty().addListener((v, o, n) -> handleStageHeightChange());
				stage.initOwner(QuPathGUI.getInstance().getStage());
				stage.setTitle(EXTENSION_NAME);
				stage.setScene(scene);
				stage.setResizable(false);
			} catch (IOException e) {
				Dialogs.showErrorMessage("Error", "GUI loading failed");
				logger.error("Unable to load extension interface FXML", e);
			}
		}
		// Re-check on every open: opening a project replaces QuPath's class list
		AxonPathClasses.ensureClassesAvailable(QuPathGUI.getInstance().getAvailablePathClasses());
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
