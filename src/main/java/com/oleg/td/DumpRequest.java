// ============================================================================
// File: src/main/java/com/oleg/td/DumpRequest.java
// Назначение: Входные параметры запуска дампа.
// ============================================================================
package com.oleg.td;

import java.util.List;

/**
 * Параметры дампа: список чатов и флаги типов данных.
 */
public class DumpRequest {
    private List<String> chats;
    private boolean photos;
    private boolean videos;
    private boolean links;
    private boolean messages;

    public List<String> getChats() { return chats; }
    public void setChats(List<String> chats) { this.chats = chats; }
    public boolean isPhotos() { return photos; }
    public void setPhotos(boolean photos) { this.photos = photos; }
    public boolean isVideos() { return videos; }
    public void setVideos(boolean videos) { this.videos = videos; }
    public boolean isLinks() { return links; }
    public void setLinks(boolean links) { this.links = links; }
    public boolean isMessages() { return messages; }
    public void setMessages(boolean messages) { this.messages = messages; }
}