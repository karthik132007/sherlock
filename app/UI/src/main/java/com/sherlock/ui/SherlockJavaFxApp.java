package com.sherlock.ui;

import com.sherlock.ui.model.CaseDto;
import com.sherlock.ui.view.InitialView;
import com.sherlock.ui.view.MainFrameView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class SherlockJavaFxApp extends Application {

    private final SherlockBackendClient client = new SherlockBackendClient();
    private Stage primaryStage;
    private Scene scene;
    private InitialView initialView;
    private MainFrameView mainFrameView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("Sherlock — AI Investigation Intelligence");

        this.initialView = new InitialView(client, this::onCaseLockedIn);
        this.mainFrameView = new MainFrameView(client, this::onNewCaseRequested);

        scene = new Scene(initialView, 1100, 750);

        URL cssUrl = getClass().getResource("/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(680);
        stage.show();
    }

    private void onCaseLockedIn(CaseDto caseDto) {
        mainFrameView.loadCase(caseDto);
        scene.setRoot(mainFrameView);
        if (primaryStage.getWidth() < 1200) {
            primaryStage.setWidth(1280);
        }
        if (primaryStage.getHeight() < 800) {
            primaryStage.setHeight(840);
        }
    }

    private void onNewCaseRequested() {
        initialView.loadRecentCases();
        scene.setRoot(initialView);
    }
}
