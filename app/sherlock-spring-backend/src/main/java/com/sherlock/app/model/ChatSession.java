package com.sherlock.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatSession {

    private String sessionId;
    private String title;
    private String createdAt;
    private String updatedAt;
    private int messageCount;
    private String lastMessage;
    private List<Map<String, Object>> messages = new ArrayList<>();

    public ChatSession() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public ChatSession(String sessionId, String title) {
        this();
        this.sessionId = sessionId;
        this.title = title;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getMessageCount() {
        return messages != null ? messages.size() : messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getLastMessage() {
        if (messages != null && !messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            Object content = last.get("content");
            return content != null ? content.toString() : lastMessage;
        }
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        this.messageCount = this.messages.size();
    }
}
