package com.oleg.td;

import java.util.List;

public class DumpRequest {

    private List<String> chats;
    private boolean photos;
    private boolean videos;
    private boolean links;
    private boolean messages;
    private boolean textDocuments;
    private boolean audio;

    // геттеры и сеттеры
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

    public List<String> getChats() {
        return chats;
    }

    public void setChats(List<String> chats) {
        this.chats = chats;
    }
}