package com.oleg.td.dump.core;

public interface DumpListener {
    void onProgress();
    void onSavedMessage();
    void onSavedPhoto();
    void onSavedVideo();
    void onSavedAudio();
    void onSavedDocument(String ext); // ext без точки, в lower-case; может быть null/пусто
    void onSavedLinks(int count);
}
