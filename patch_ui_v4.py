import re

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "r") as f:
    content = f.read()

center_view_pattern = r"StackPane mainAreaStack = new StackPane\(\);\s*VBox\.setVgrow\(mainAreaStack, Priority\.ALWAYS\);\s*StackPane\.setAlignment\(detailsPanel, Pos\.BOTTOM_CENTER\);\s*mainAreaStack\.getChildren\(\)\.addAll\(viewStack, detailsPanel\);\s*center\.getChildren\(\)\.addAll\(header, mainAreaStack\);"

new_center_view = r"""SplitPane mainAreaSplit = new SplitPane();
        mainAreaSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox.setVgrow(mainAreaSplit, Priority.ALWAYS);
        
        mainAreaSplit.getItems().addAll(viewStack, detailsPanel);
        // Set initial divider position (e.g. 70% graph, 30% details)
        mainAreaSplit.setDividerPositions(0.7);
        
        detailsPanel.setMinHeight(150.0);
        detailsPanel.setPrefHeight(300.0);
        detailsPanel.setMaxHeight(600.0);

        center.getChildren().addAll(header, mainAreaSplit);"""

if re.search(center_view_pattern, content):
    content = re.sub(center_view_pattern, new_center_view, content, flags=re.DOTALL)
    print("Patched MainFrameView with SplitPane successfully.")
else:
    print("Could not find pattern in MainFrameView.java")

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "w") as f:
    f.write(content)
