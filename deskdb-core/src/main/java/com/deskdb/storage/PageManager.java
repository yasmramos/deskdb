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
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * Gestor de páginas con cache LRU verdadero y acceso concurrente optimizado.
 * Soporta multi-hilo mediante lock striping para mayor paralelismo.
 * Incluye Free List para reutilización de páginas eliminadas.
 * 
 * OPTIMIZACIONES IMPLEMENTADAS:
 * - LRU Cache real con LinkedHashMap (access-order)
 * - Lock Striping para concurrencia mejorada
 * - Flush asíncrono batcheado
 */
public class PageManager {
    private final FileChannel channel;
    
    // LRU Cache verdadero con LinkedHashMap (access-order = true)
    private final Map<Long, Page> pageCache;
    private final Object cacheLock = new Object();
    
    // Lock striping para mayor concurrencia (16 stripes)
    private static final int NUM_STRIPES = 16;
    private final ReentrantReadWriteLock[] stripeLocks;
    
    private final ExecutorService flushExecutor;
    private static final int DEFAULT_MAX_CACHE_SIZE = 2048;
    private final int maxCacheSize;
    
    // Free List: cola de páginas libres para reutilización
    private final Queue<Long> freePageList;
    private final Object freeListLock = new Object();
    
    // Estadísticas para monitoreo
    private long cacheHits = 0;
    private long cacheMisses = 0;
    
    @SuppressWarnings("unchecked")
    public PageManager(Path filePath) throws IOException {
        this.channel = FileChannel.open(filePath, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.READ, 
            StandardOpenOption.WRITE);
        
        // LRU Cache verdadero con access-order
        this.maxCacheSize = DEFAULT_MAX_CACHE_SIZE;
        this.pageCache = Collections.synchronizedMap(new LinkedHashMap<Long, Page>(maxCacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Page> eldest) {
                return size() > maxCacheSize;
            }
        });
        
        // Inicializar lock striping
        this.stripeLocks = new ReentrantReadWriteLock[NUM_STRIPES];
        for (int i = 0; i < NUM_STRIPES; i++) {
            stripeLocks[i] = new ReentrantReadWriteLock();
        }
        
        this.freePageList = new ConcurrentLinkedQueue<>();
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PageFlusher");
            t.setDaemon(true);
            return t;
        });
        
        // Registrar shutdown hook para cierre limpio del executor
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!flushExecutor.isShutdown()) {
                flushExecutor.shutdown();
                try {
                    if (!flushExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        System.err.println("[PageManager] Flush executor did not terminate in time, forcing shutdown");
                        flushExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    System.err.println("[PageManager] Interrupted while waiting for flush executor shutdown");
                    flushExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }));
        
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
     * Thread-safe: usa lock striping para mayor concurrencia.
     * LRU automático gracias a LinkedHashMap con access-order.
     */
    public Page getPage(long pageNumber) throws IOException {
        // Intento rápido de lectura desde cache (LRU automático)
        synchronized (cacheLock) {
            Page cached = pageCache.get(pageNumber);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
        }
        
        cacheMisses++;
        
        // Cache miss: cargar página con lock de stripe específico
        int stripeIndex = getStripeIndex(pageNumber);
        ReentrantReadWriteLock stripeLock = stripeLocks[stripeIndex];
        
        stripeLock.writeLock().lock();
        try {
            // Double-check después de adquirir lock
            synchronized (cacheLock) {
                Page cached = pageCache.get(pageNumber);
                if (cached != null) {
                    return cached;
                }
            }
            
            // Crear nueva página
            Page page = new Page(channel, pageNumber);
            
            // Agregar al cache (LRU automático se encarga de evict)
            synchronized (cacheLock) {
                pageCache.put(pageNumber, page);
            }
            
            return page;
        } finally {
            stripeLock.writeLock().unlock();
        }
    }
    
    /**
     * Calcula el índice de stripe para un número de página dado.
     */
    private int getStripeIndex(long pageNumber) {
        return (int)(Math.abs(pageNumber) % NUM_STRIPES);
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
     * No longer needed - LinkedHashMap with access-order handles LRU automatically.
     */
    private void evictOldestPage() {
        // Automatic eviction via removeEldestEntry in LinkedHashMap
        // This method kept for compatibility but no longer does anything
    }
    
    /**
     * Obtiene estadísticas del cache.
     */
    public int getCacheSize() {
        return pageCache.size();
    }
    
    /**
     * Obtiene el ratio de hits del cache (0.0 a 1.0).
     */
    public double getCacheHitRatio() {
        long total = cacheHits + cacheMisses;
        return total == 0 ? 0.0 : (double)cacheHits / total;
    }
    
    /**
     * Limpia el cache forzando recarga desde disco.
     */
    public void clearCache() {
        synchronized (cacheLock) {
            flushAll();
            pageCache.clear();
            cacheHits = 0;
            cacheMisses = 0;
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
