package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatSessionDto {

    private String sessionId;
    private String title;
    private String createdAt;
    private String updatedAt;
    private int messageCount;
    private String lastMessage;
    private List<ChatMessageDto> messages = new ArrayList<>();

    public ChatSessionDto() {}

    public ChatSessionDto(String sessionId, String title) {
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
        if (messages != null && !messages.isEmpty()) {
            return messages.size();
        }
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getLastMessage() {
        if (messages != null && !messages.isEmpty()) {
            ChatMessageDto last = messages.get(messages.size() - 1);
            String c = last.getContent() != null ? last.getContent() : last.getAnswer();
            if (c != null && !c.isBlank()) return c;
        }
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public List<ChatMessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessageDto> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        this.messageCount = this.messages.size();
    }
}
