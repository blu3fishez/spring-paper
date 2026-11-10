package org.blu3fishez.springpaper.example.service;

import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.example.domain.Note;
import org.blu3fishez.springpaper.example.repository.NoteRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NoteService {
    private final NoteRepository notes;
    private final JdbcClient jdbc;
    private final AtomicInteger reads = new AtomicInteger();
    @Transactional public void ensureNote(long id, String text) {
        if (!notes.existsById(id)) notes.save(new Note(id, text));
    }
    @Transactional(readOnly = true) @Cacheable("notes")
    public String read(long id) { reads.incrementAndGet(); return notes.findById(id).orElseThrow().text(); }
    @Transactional @CachePut(cacheNames = "notes", key = "#p0")
    public String replace(long id, String text) {
        var note = notes.findById(id).orElseThrow();
        note.replaceText(text);
        return note.text();
    }
    @Transactional @CacheEvict(cacheNames = "notes", key = "#p0")
    public void delete(long id) { notes.deleteById(id); }
    @Transactional(readOnly = true)
    public String readJdbc(long id) {
        return jdbc.sql("SELECT text FROM Note WHERE id=:id").param("id", id).query(String.class).single();
    }
    public int reads() { return reads.get(); }
}
