package org.blu3fishez.springpaper.pdc;

/**
 * Consumer API over an already selected holder; it never loads entities or searches inventories.
 * Host access is synchronous on the Paper thread. Writes validate/encode before changing live keys;
 * Paper PDC provides no transaction or immediate disk-flush guarantee.
 */
public interface PdcRepository<H, T> {
    /** Physical marker presence only; domain validity is reported by {@link #read(Object)}. */
    boolean exists(H holder);
    PdcReadResult<T> read(H holder);
    void write(H holder, T document);
    /** Removes this mapping's keys, preserving every unrelated key. */
    void remove(H holder);
}
