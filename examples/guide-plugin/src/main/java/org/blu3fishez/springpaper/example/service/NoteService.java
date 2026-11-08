package org.blu3fishez.springpaper.example.service;

import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.example.domain.Note;
import org.blu3fishez.springpaper.example.repository.NoteRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {
    private final NoteRepository notes;
    private final AtomicInteger reads = new AtomicInteger();
    public NoteService(NoteRepository notes) { this.notes = notes; }
    @Transactional public void ensureNote(long id, String text) {
        if (!notes.existsById(id)) notes.save(new Note(id, text));
    }
    @Transactional(readOnly = true) @Cacheable("notes")
    public String read(long id) { reads.incrementAndGet(); return notes.findById(id).orElseThrow().text(); }
    public int reads() { return reads.get(); }
}
