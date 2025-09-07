package com.oleg.td.dump.api;

import java.util.List;

public class DumpRequest {
    private List<String> chats;
    private boolean photos;
    private boolean videos;
    private boolean links;
    private boolean messages;
    private boolean textDocuments;
    private boolean audio;
    private String textDocumentExtensions; // Новое поле для кастомных расширений

    public DumpRequest() {
    }

    public DumpRequest(List<String> chats, boolean photos, boolean videos, boolean links,
                       boolean messages, boolean textDocuments, boolean audio,
                       String textDocumentExtensions) {
        this.chats = chats;
        this.photos = photos;
        this.videos = videos;
        this.links = links;
        this.messages = messages;
        this.textDocuments = textDocuments;
        this.audio = audio;
        this.textDocumentExtensions = textDocumentExtensions;
    }

    // Геттеры и сеттеры
    public List<String> getChats() {
        return chats;
    }

    public void setChats(List<String> chats) {
        this.chats = chats;
    }

    public boolean isPhotos() {
        return photos;
    }

    public void setPhotos(boolean photos) {
        this.photos = photos;
    }

    public boolean isVideos() {
        return videos;
    }

    public void setVideos(boolean videos) {
        this.videos = videos;
    }

    public boolean isLinks() {
        return links;
    }

    public void setLinks(boolean links) {
        this.links = links;
    }

    public boolean isMessages() {
        return messages;
    }

    public void setMessages(boolean messages) {
        this.messages = messages;
    }

    public boolean isTextDocuments() {
        return textDocuments;
    }

    public void setTextDocuments(boolean textDocuments) {
        this.textDocuments = textDocuments;
    }

    public boolean isAudio() {
        return audio;
    }

    public void setAudio(boolean audio) {
        this.audio = audio;
    }

    public String getTextDocumentExtensions() {
        return textDocumentExtensions;
    }

    public void setTextDocumentExtensions(String textDocumentExtensions) {
        this.textDocumentExtensions = textDocumentExtensions;
    }

    @Override
    public String toString() {
        return "DumpRequest{" +
                "chats=" + chats +
                ", photos=" + photos +
                ", videos=" + videos +
                ", links=" + links +
                ", messages=" + messages +
                ", textDocuments=" + textDocuments +
                ", audio=" + audio +
                ", textDocumentExtensions='" + textDocumentExtensions + '\'' +
                '}';
    }
}