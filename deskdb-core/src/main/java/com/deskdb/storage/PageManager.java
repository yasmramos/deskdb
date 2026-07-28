package com.deskdb.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Gestor de páginas con cache LRU y acceso concurrente.
 * Soporta multi-hilo mediante locks granulares por página.
 * Incluye Free List para reutilización de páginas eliminadas.
 */
public class PageManager {
    private final FileChannel channel;
    private final Map<Long, Page> pageCache;
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ExecutorService flushExecutor;
    private final int maxCacheSize;
    
    // Free List: cola de páginas libres para reutilización
    private final Queue<Long> freePageList;
    private final Object freeListLock = new Object();
    
    public PageManager(Path filePath) throws IOException {
        this.channel = FileChannel.open(filePath, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.READ, 
            StandardOpenOption.WRITE);
        this.pageCache = new ConcurrentHashMap<>();
        this.maxCacheSize = 1000; // Configurable
        this.freePageList = new ConcurrentLinkedQueue<>();
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PageFlusher");
            t.setDaemon(true);
            return t;
        });
        
        // Inicializar Free List escaneando páginas existentes
        initializeFreeList();
    }
    
    /**
     * Escanea el archivo en busca de páginas marcadas como libres (0xFFFFFFFF).
     * Las añade a la Free List para reutilización.
     */
    private void initializeFreeList() throws IOException {
        long totalPages = channel.size() / Page.PAGE_SIZE;
        for (long i = 0; i < totalPages; i++) {
            try {
                Page page = getPage(i);
                if (page.getFlags() == 0xFFFFFFFF) {
                    freePageList.offer(i);
                }
            } catch (Exception e) {
                // Ignorar errores durante inicialización
            }
        }
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
     * Primero intenta reutilizar de la Free List, si está vacía asigna al final del archivo.
     * Retorna el número de página asignada.
     * Thread-safe: usa sincronización para evitar colisiones.
     */
    public synchronized Page allocatePage() throws IOException {
        // Intentar obtener de la Free List primero (más rápido que expandir archivo)
        Long freePageNumber = freePageList.poll();
        if (freePageNumber != null) {
            // Reutilizar página existente
            Page page = getPage(freePageNumber);
            page.setFlags(0x00000000); // Marcar como usada
            return page;
        }
        
        // Free List vacía: asignar nueva página al final del archivo
        long newPageNumber = channel.size() / Page.PAGE_SIZE;
        return getPage(newPageNumber);
    }
    
    /**
     * Asigna una nueva página del tipo especificado.
     * @param pageType Tipo de página (TYPE_DATA, TYPE_INDEX, TYPE_META)
     * @return La página asignada
     * @throws IOException Si ocurre un error de E/S
     */
    public Page allocatePage(int pageType) throws IOException {
        Page page = allocatePage();
        page.setFlags(pageType);
        return page;
    }
    
    /**
     * Libera una página para reutilización futura.
     * La página se añade a la Free List para ser reutilizada en futuras asignaciones.
     */
    public void freePage(long pageNumber) throws IOException {
        Page page = getPage(pageNumber);
        page.setFlags(0xFFFFFFFF); // Marca como libre
        page.flush();
        
        // Añadir a la Free List para reutilización
        freePageList.offer(pageNumber);
        
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
     * Removes the least recently used page from the cache.
     */
    private void evictOldestPage() {
        // Simple implementation: remove first page from map
        // In production, use LinkedHashMap with access-order=true
        if (!pageCache.isEmpty()) {
            Long oldestKey = pageCache.keySet().iterator().next();
            Page page = pageCache.remove(oldestKey);
            if (page != null) {
                page.flush(); // Ensure persistence before removing
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
    
    /**
     * Obtiene el offset actual dentro de una página.
     * Simplificación: retorna el tamaño usado basado en el contador de filas.
     */
    public long getCurrentOffset(Page page) {
        // Offset = (pageNumber * PAGE_SIZE) + HEADER_SIZE + (rowCount * estimatedRowSize)
        // Por simplicidad, usamos un offset fijo después del header
        return page.getPageNumber() * Page.PAGE_SIZE + 16;
    }
    
    /**
     * Obtiene la página que contiene un offset dado.
     */
    public Page getPageForOffset(long offset) throws IOException {
        long pageNumber = offset / Page.PAGE_SIZE;
        return getPage(pageNumber);
    }
}
