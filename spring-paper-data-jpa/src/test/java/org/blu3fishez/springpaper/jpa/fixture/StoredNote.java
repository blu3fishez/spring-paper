package org.blu3fishez.springpaper.jpa.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class StoredNote {
    @Id private Long id;
    @Version private Long revision;
    private String text;
    protected StoredNote() {}
    public StoredNote(long id, String text) { this.id = id; this.text = text; }
    public String text() { return text; }
    public Long revision() { return revision; }
}
