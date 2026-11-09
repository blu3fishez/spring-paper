package org.blu3fishez.springpaper.pdc.internal;

import lombok.RequiredArgsConstructor;

import java.util.Objects;
import org.blu3fishez.springpaper.pdc.PdcReadResult;
import org.blu3fishez.springpaper.pdc.PdcRepository;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;

@RequiredArgsConstructor
public final class DefaultPdcRepository<H, T> implements PdcRepository<H, T> {
    private final PdcRecordMapper<T> mapper;
    private final PdcHolderAdapter<H> holder;
    private final PaperThreadAccess thread;

    @Override public boolean exists(H value) { check(value); return mapper.exists(holder.view(value)); }
    @Override public PdcReadResult<T> read(H value) { check(value); return mapper.read(holder.view(value)); }
    @Override public void write(H value, T document) { check(value); holder.edit(value, data -> mapper.write(data, document)); }
    @Override public void remove(H value) { check(value); holder.edit(value, mapper::remove); }
    private void check(H value) {
        if (!thread.isPrimaryThread()) throw new IllegalStateException("Access live Paper PDC on the server thread");
        Objects.requireNonNull(value, "PDC holder");
    }
}
