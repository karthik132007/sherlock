import re

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "r") as f:
    content = f.read()

# 1. Home should be default view
# Find the constructor of MainFrameView where buildSidebar() is added.
# Usually:
# this.getChildren().addAll(buildSidebar(), centerContainer);
# I'll add centerContainer.getChildren().setAll(buildHomeView()); right after initialization
if "centerContainer = new StackPane();" in content and "centerContainer.getChildren().setAll(buildHomeView());" not in content:
    content = content.replace(
        "centerContainer = new StackPane();\n        HBox.setHgrow(centerContainer, Priority.ALWAYS);",
        "centerContainer = new StackPane();\n        HBox.setHgrow(centerContainer, Priority.ALWAYS);\n        centerContainer.getChildren().setAll(buildHomeView());"
    )

# 2. Remove 'searchBar' from header
# In buildCenterView()
header_add_pattern = r"(header\.getChildren\(\)\.addAll\()searchBar,\s*(toggleWrapper, hSpacer, investigateBtn, refreshIndicator, caseBadge,[\s\S]*?refreshBtn\);)"
if re.search(header_add_pattern, content):
    content = re.sub(header_add_pattern, r"\1\2", content)
    
# Clean up searchBar declaration in buildCenterView
search_bar_decl = r"TextField searchBar = new TextField\(\);[\s\S]*?searchBar\.setPrefWidth\(200\);"
content = re.sub(search_bar_decl, "", content)

# 3. Remove "Saved Views" (checkpoints) from sidebar
# Find: Button navSaved = createNavButton("🔖", "Saved Views");
saved_nav_pattern = r"Button navSaved = createNavButton\(\"🔖\", \"Saved Views\"\);[\s\S]*?navSaved\.setOnAction\(e -> \{[\s\S]*?\}\);"
content = re.sub(saved_nav_pattern, "", content)

# Remove navSaved from navMenu.getChildren().addAll(...)
nav_add_pattern = r"(navMenu\.getChildren\(\)\.addAll\(.*?)(,\s*navSaved)(.*?\);)"
content = re.sub(nav_add_pattern, r"\1\3", content)

# 4. Remove "Save Checkpoint" button from Home View
save_btn_pattern = r"Button saveBtn = new Button\(\"💾 Save Checkpoint\"\);[\s\S]*?alert\.show\(\);\s*\}\);"
content = re.sub(save_btn_pattern, "", content)

# And remove it from home.getChildren().addAll(lbl, saveBtn);
home_add_pattern = r"(home\.getChildren\(\)\.addAll\(lbl)(,\s*saveBtn)(\);)"
content = re.sub(home_add_pattern, r"\1\3", content)

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "w") as f:
    f.write(content)

print("Applied final UI tweaks.")
