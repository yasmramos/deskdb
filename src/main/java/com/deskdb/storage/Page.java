package com.deskdb.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Página de almacenamiento de 4KB con acceso thread-safe.
 * Soporta lectura/escritura concurrente mediante RWLock.
 */
public class Page {
    public static final int PAGE_SIZE = 4096; // 4KB
    private static final int HEADER_SIZE = 16; // magic(4) + version(4) + flags(4) + checksum(4)
    
    private final MappedByteBuffer buffer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final long pageNumber;
    private boolean dirty;
    
    public Page(FileChannel channel, long pageNumber) throws IOException {
        this.pageNumber = pageNumber;
        long position = pageNumber * PAGE_SIZE;
        
        // Asegurar que el archivo tenga suficiente tamaño
        long currentSize = channel.size();
        if (currentSize < position + PAGE_SIZE) {
            long newSize = position + PAGE_SIZE;
            // Extender archivo si es necesario
            if (newSize > currentSize) {
                channel.position(newSize - 1);
                channel.write(ByteBuffer.wrap(new byte[]{0}));
            }
        }
        
        // Mapear página en memoria
        this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, PAGE_SIZE);
        this.dirty = false;
        
        // Inicializar header si es página nueva
        if (getInt(0) == 0) {
            initHeader();
        }
    }
    
    private void initHeader() {
        lock.writeLock().lock();
        try {
            // Magic number: 0x4445534B ("DESK")
            buffer.putInt(0, 0x4445534B);
            // Version: 1
            buffer.putInt(4, 1);
            // Flags: 0 (disponible)
            buffer.putInt(8, 0);
            // Checksum: calcular después de escribir datos
            buffer.putInt(12, 0);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // Lecturas thread-safe
    public int getInt(int offset) {
        lock.readLock().lock();
        try {
            return buffer.getInt(HEADER_SIZE + offset);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public long getLong(int offset) {
        lock.readLock().lock();
        try {
            return buffer.getLong(HEADER_SIZE + offset);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public byte[] getBytes(int offset, int length) {
        lock.readLock().lock();
        try {
            byte[] result = new byte[length];
            buffer.position(HEADER_SIZE + offset);
            buffer.get(result);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public String getString(int offset) {
        lock.readLock().lock();
        try {
            int length = buffer.getInt(HEADER_SIZE + offset);
            byte[] bytes = new byte[length];
            buffer.position(HEADER_SIZE + offset + 4);
            buffer.get(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // Escrituras thread-safe
    public void putInt(int offset, int value) {
        lock.writeLock().lock();
        try {
            buffer.putInt(HEADER_SIZE + offset, value);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void putLong(int offset, long value) {
        lock.writeLock().lock();
        try {
            buffer.putLong(HEADER_SIZE + offset, value);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void putBytes(int offset, byte[] data) {
        lock.writeLock().lock();
        try {
            buffer.position(HEADER_SIZE + offset);
            buffer.put(data);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void putString(int offset, String value) {
        lock.writeLock().lock();
        try {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer.putInt(HEADER_SIZE + offset, bytes.length);
            buffer.position(HEADER_SIZE + offset + 4);
            buffer.put(bytes);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // Métodos utilitarios
    public void flush() {
        if (dirty) {
            lock.writeLock().lock();
            try {
                // Calcular y escribir checksum
                int checksum = calculateChecksum();
                buffer.putInt(12, checksum);
                
                // Forzar escritura a disco
                buffer.force();
                dirty = false;
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
    
    private int calculateChecksum() {
        // CRC32 simple del contenido de la página (excluyendo el campo checksum)
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        
        // Leer header sin checksum (bytes 0-11)
        for (int i = 0; i < 12; i++) {
            crc.update(buffer.get(i));
        }
        
        // Leer datos (desde HEADER_SIZE hasta PAGE_SIZE)
        for (int i = HEADER_SIZE; i < PAGE_SIZE; i++) {
            crc.update(buffer.get(i));
        }
        
        return (int) crc.getValue();
    }
    
    public boolean verifyChecksum() {
        lock.readLock().lock();
        try {
            int stored = buffer.getInt(12);
            int calculated = calculateChecksum();
            return stored == calculated;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public long getPageNumber() {
        return pageNumber;
    }
    
    public int getVersion() {
        lock.readLock().lock();
        try {
            return buffer.getInt(4);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public void setFlags(int flags) {
        lock.writeLock().lock();
        try {
            buffer.putInt(8, flags);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public int getFlags() {
        lock.readLock().lock();
        try {
            return buffer.getInt(8);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Espacio disponible para datos en esta página.
     */
    public int getDataCapacity() {
        return PAGE_SIZE - HEADER_SIZE;
    }
    
    /**
     * Obtiene el ByteBuffer subyacente para acceso directo.
     */
    public ByteBuffer getByteBuffer() {
        lock.readLock().lock();
        try {
            // Duplicar para permitir posición independiente
            return buffer.duplicate();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Incrementa el contador de filas en la página.
     */
    public void incrementRowCount() {
        lock.writeLock().lock();
        try {
            int current = buffer.getInt(HEADER_SIZE);
            buffer.putInt(HEADER_SIZE, current + 1);
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Marca la página como sucia (necesita ser escrita a disco).
     */
    public void setDirty(boolean dirty) {
        lock.writeLock().lock();
        try {
            this.dirty = dirty;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Verifica si la página está sucia.
     */
    public boolean isDirty() {
        lock.readLock().lock();
        try {
            return dirty;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Constantes para tipos de página.
     */
    public static final int TYPE_DATA = 0x01;
    public static final int TYPE_INDEX = 0x02;
    public static final int TYPE_META = 0x03;
    
    /**
     * Tamaño del header de página.
     */
    public static final int PAGE_HEADER_SIZE = HEADER_SIZE;
}
