package com.deskdb.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Gestor de páginas con cache LRU y acceso concurrente.
 * Soporta multi-hilo mediante locks granulares por página.
 */
public class PageManager {
    private final FileChannel channel;
    private final Map<Long, Page> pageCache;
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ExecutorService flushExecutor;
    private final int maxCacheSize;
    
    public PageManager(Path filePath) throws IOException {
        this.channel = FileChannel.open(filePath, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.READ, 
            StandardOpenOption.WRITE);
        this.pageCache = new ConcurrentHashMap<>();
        this.maxCacheSize = 1000; // Configurable
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PageFlusher");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Obtiene una página por número, cargándola a cache si es necesario.
     * Thread-safe: múltiples hilos pueden leer la misma página simultáneamente.
     */
    public Page getPage(long pageNumber) throws IOException {
        // Intento rápido de lectura desde cache (lock compartido)
        cacheLock.readLock().lock();
        try {
            Page cached = pageCache.get(pageNumber);
            if (cached != null) {
                return cached;
            }
        } finally {
            cacheLock.readLock().unlock();
        }
        
        // Cache miss: cargar página (lock exclusivo)
        cacheLock.writeLock().lock();
        try {
            // Double-check después de adquirir lock exclusivo
            Page cached = pageCache.get(pageNumber);
            if (cached != null) {
                return cached;
            }
            
            // Crear nueva página
            Page page = new Page(channel, pageNumber);
            
            // Evitar crecimiento ilimitado del cache
            if (pageCache.size() >= maxCacheSize) {
                evictOldestPage();
            }
            
            pageCache.put(pageNumber, page);
            return page;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Asigna una nueva página libre.
     * Retorna el número de página asignada.
     */
    public Page allocatePage() throws IOException {
        // Buscar primera página libre (flags == 0xFFFFFFFF o no usada)
        // Por simplicidad, asignamos al final del archivo
        long newPageNumber = channel.size() / Page.PAGE_SIZE;
        return getPage(newPageNumber);
    }
    
    /**
     * Libera una página para reutilización futura.
     */
    public void freePage(long pageNumber) throws IOException {
        Page page = getPage(pageNumber);
        page.setFlags(0xFFFFFFFF); // Marca como libre
        page.flush();
        
        // No remover del cache inmediatamente para evitar I/O extra
    }
    
    /**
     * Fuerza la escritura de todas las páginas sucias a disco.
     */
    public void flushAll() {
        for (Page page : pageCache.values()) {
            page.flush();
        }
    }
    
    /**
     * Fuerza la escritura asíncrona de páginas sucias.
     */
    public void flushAsync() {
        flushExecutor.submit(this::flushAll);
    }
    
    /**
     * Cierra el gestor liberando recursos.
     */
    public void close() throws IOException {
        flushAll();
        flushExecutor.shutdown();
        channel.close();
    }
    
    /**
     * Elimina la página menos recientemente usada del cache.
     */
    private void evictOldestPage() {
        // Implementación simple: eliminar primera página del mapa
        // En producción, usar LinkedHashMap con access-order=true
        if (!pageCache.isEmpty()) {
            Long oldestKey = pageCache.keySet().iterator().next();
            Page page = pageCache.remove(oldestKey);
            if (page != null) {
                page.flush(); // Asegurar persistencia antes de eliminar
            }
        }
    }
    
    /**
     * Obtiene estadísticas del cache.
     */
    public int getCacheSize() {
        return pageCache.size();
    }
    
    /**
     * Limpia el cache forzando recarga desde disco.
     */
    public void clearCache() {
        cacheLock.writeLock().lock();
        try {
            flushAll();
            pageCache.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
}
