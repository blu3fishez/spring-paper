package org.blu3fishez.springpaper.jpa.fixture;

import org.springframework.data.repository.CrudRepository;

public interface NoteRepository extends CrudRepository<StoredNote, Long> {}
