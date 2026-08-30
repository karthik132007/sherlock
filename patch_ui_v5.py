with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "r") as f:
    content = f.read()

# Shrink search bar
content = content.replace("searchBar.setPrefWidth(300);", "searchBar.setPrefWidth(200);")
content = content.replace("searchBar.setPrefWidth(360.0);", "searchBar.setPrefWidth(200);")
content = content.replace("searchBar.setPrefWidth(288.0);", "searchBar.setPrefWidth(200);")

# Shrink toggles
content = content.replace("-fx-font-size: 16.8px; -fx-padding: 8px 16px;", "-fx-font-size: 13.5px; -fx-padding: 6px 12px;")

# Shrink investigate btn
content = content.replace("-fx-padding: 8px 16px; -fx-font-size: 16.8px;", "-fx-padding: 6px 12px; -fx-font-size: 14px;")

with open("app/UI/src/main/java/com/sherlock/ui/view/MainFrameView.java", "w") as f:
    f.write(content)

# Now check ChatPanel.java for inputField
with open("app/UI/src/main/java/com/sherlock/ui/view/ChatPanel.java", "r") as f:
    chat_content = f.read()

# If chat input is too big, shrink it slightly
chat_content = chat_content.replace("-fx-font-size: 16.8px;", "-fx-font-size: 14px;")
chat_content = chat_content.replace("-fx-font-size: 18px;", "-fx-font-size: 15px;")
chat_content = chat_content.replace("-fx-font-size: 15.6px;", "-fx-font-size: 13.5px;")

with open("app/UI/src/main/java/com/sherlock/ui/view/ChatPanel.java", "w") as f:
    f.write(chat_content)

print("UI sizes tweaked")
