package org.blu3fishez.springpaper.example.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class Note {
    @Id private Long id;
    @Version private Long revision;
    private String text;
    protected Note() {}
    public Note(long id, String text) { this.id = id; this.text = text; }
    public String text() { return text; }
    public void replaceText(String text) { this.text = java.util.Objects.requireNonNull(text); }
}
