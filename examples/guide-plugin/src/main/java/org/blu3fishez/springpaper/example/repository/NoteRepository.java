package org.blu3fishez.springpaper.example.repository;

import org.blu3fishez.springpaper.example.domain.Note;
import org.springframework.data.repository.CrudRepository;

public interface NoteRepository extends CrudRepository<Note, Long> {}
