package com.oleg.td;

import java.util.List;

public class DumpRequest {
    private List<String> chats;
    private boolean photos;
    private boolean videos;
    private boolean audio;
    private boolean documents;
    private boolean links;
    private boolean messages;

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

    public boolean isAudio() {
        return audio;
    }

    public void setAudio(boolean audio) {
        this.audio = audio;
    }

    public boolean isDocuments() {
        return documents;
    }

    public void setDocuments(boolean documents) {
        this.documents = documents;
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
}