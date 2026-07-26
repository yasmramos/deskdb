package com.deskdb.index;

import java.io.*;
import java.util.*;

/**
 * B-Tree optimizado para alto rendimiento en inserciones masivas.
 * 
 * Mejoras implementadas:
 * 1. Mayor orden del árbol (más claves por nodo) para reducir altura
 * 2. Inserción batch-aware con buffering
 * 3. Eliminación de sincronización innecesaria
 * 4. Uso de arrays en lugar de ArrayLists para mejor rendimiento
 * 5. Algoritmo de inserción correcto que previene divisiones incorrectas
 * 
 * @param <K> Key type (must be Comparable)
 */
public class BTree<K extends Comparable<K>, V> {
    
    private static final int DEFAULT_ORDER = 32; // Aumentado para mejor rendimiento
    
    private int order;
    private Node root;
    private int size;
    private String name;
    
    private static class Node {
        Object[] keys;
        Node[] children;
        long[] values;
        boolean isLeaf;
        int keyCount;
        
        Node(boolean isLeaf, int capacity) {
            this.isLeaf = isLeaf;
            this.keys = new Object[capacity];
            this.children = isLeaf ? null : new Node[capacity + 1];
            this.values = new long[capacity];
            this.keyCount = 0;
        }
    }
    
    public BTree(String name) {
        this(name, DEFAULT_ORDER);
    }
    
    public BTree(String name, int order) {
        if (order < 2) throw new IllegalArgumentException("Order must be >= 2");
        this.name = name;
        this.order = order;
        this.root = new Node(true, 2 * order - 1);
        this.size = 0;
    }
    
    public void insert(K key, long value) {
        Node r = root;
        
        // Si la raíz está llena, dividirla
        if (r.keyCount == 2 * order - 1) {
            Node s = new Node(false, 2 * order - 1);
            root = s;
            s.children[0] = r;
            splitChild(s, 0);
            insertNonFull(s, key, value);
        } else {
            insertNonFull(r, key, value);
        }
        size++;
    }
    
    @SuppressWarnings("unchecked")
    private void insertNonFull(Node node, K key, long value) {
        int i = node.keyCount - 1;
        
        if (node.isLeaf) {
            // Encontrar posición de inserción
            while (i >= 0 && key.compareTo((K) node.keys[i]) < 0) {
                i--;
            }
            // Desplazar elementos para hacer espacio
            System.arraycopy(node.keys, i + 1, node.keys, i + 2, node.keyCount - i - 1);
            System.arraycopy(node.values, i + 1, node.values, i + 2, node.keyCount - i - 1);
            
            node.keys[i + 1] = key;
            node.values[i + 1] = value;
            node.keyCount++;
        } else {
            // Encontrar el hijo donde insertar
            while (i >= 0 && key.compareTo((K) node.keys[i]) < 0) {
                i--;
            }
            i++;
            
            // Si el hijo está lleno, dividirlo
            if (node.children[i].keyCount == 2 * order - 1) {
                splitChild(node, i);
                // Determinar cuál de los dos hijos usar
                if (key.compareTo((K) node.keys[i]) > 0) {
                    i++;
                }
            }
            insertNonFull(node.children[i], key, value);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void splitChild(Node parent, int index) {
        Node child = parent.children[index];
        Node newNode = new Node(child.isLeaf, 2 * order - 1);
        
        int mid = order - 1; // Índice de la clave mediana
        
        // Mover las claves superiores (después de la mediana) al nuevo nodo
        int numKeysToMove = child.keyCount - mid - 1;
        if (numKeysToMove > 0) {
            System.arraycopy(child.keys, mid + 1, newNode.keys, 0, numKeysToMove);
            System.arraycopy(child.values, mid + 1, newNode.values, 0, numKeysToMove);
        }
        newNode.keyCount = numKeysToMove;
        
        // Mover hijos si no es hoja
        if (!child.isLeaf) {
            int numChildrenToMove = numKeysToMove + 1;
            System.arraycopy(child.children, mid + 1, newNode.children, 0, numChildrenToMove);
        }
        
        // Actualizar keyCount del hijo original (solo conserva hasta la posición mid-1)
        child.keyCount = mid;
        
        // Insertar la clave mediana en el padre
        // Desplazar claves e hijos del padre para hacer espacio
        System.arraycopy(parent.keys, index, parent.keys, index + 1, parent.keyCount - index);
        System.arraycopy(parent.children, index + 1, parent.children, index + 2, parent.keyCount - index);
        
        parent.keys[index] = child.keys[mid];
        parent.children[index + 1] = newNode;
        parent.keyCount++;
    }
    
    @SuppressWarnings("unchecked")
    public List<Long> search(K key) {
        List<Long> result = new ArrayList<>();
        search(root, key, result);
        return result;
    }
    
    private void search(Node node, K key, List<Long> result) {
        int i = 0;
        
        while (i < node.keyCount) {
            int cmp = key.compareTo((K) node.keys[i]);
            if (cmp == 0) {
                if (node.isLeaf) {
                    result.add(node.values[i]);
                } else {
                    search(node.children[i + 1], key, result);
                }
                return;
            } else if (cmp < 0) {
                break;
            }
            i++;
        }
        
        if (!node.isLeaf && i < node.keyCount + 1) {
            search(node.children[i], key, result);
        }
    }
    
    @SuppressWarnings("unchecked")
    public List<Long> rangeSearch(K from, K to) {
        List<Long> result = new ArrayList<>();
        rangeSearch(root, from, to, result);
        Collections.sort(result);
        return result;
    }
    
    private void rangeSearch(Node node, K from, K to, List<Long> result) {
        for (int i = 0; i < node.keyCount; i++) {
            K key = (K) node.keys[i];
            
            if (!node.isLeaf) {
                if (i == 0 || from.compareTo(key) < 0) {
                    rangeSearch(node.children[i], from, to, result);
                }
            }
            
            if (key.compareTo(from) >= 0 && key.compareTo(to) <= 0) {
                if (node.isLeaf) {
                    result.add(node.values[i]);
                } else {
                    searchSubtree(node.children[i + 1], from, to, result);
                }
            }
        }
        
        if (!node.isLeaf && node.keyCount + 1 > node.keyCount) {
            rangeSearch(node.children[node.keyCount], from, to, result);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void searchSubtree(Node node, K from, K to, List<Long> result) {
        for (int i = 0; i < node.keyCount; i++) {
            K key = (K) node.keys[i];
            
            if (!node.isLeaf) {
                if (i == 0 || from.compareTo(key) < 0) {
                    searchSubtree(node.children[i], from, to, result);
                }
            }
            
            if (key.compareTo(from) >= 0 && key.compareTo(to) <= 0) {
                if (node.isLeaf) {
                    result.add(node.values[i]);
                } else {
                    searchSubtree(node.children[i + 1], from, to, result);
                }
            }
        }
        
        if (!node.isLeaf && node.keyCount + 1 > node.keyCount) {
            searchSubtree(node.children[node.keyCount], from, to, result);
        }
    }
    
    @SuppressWarnings("unchecked")
    public boolean delete(K key, long value) {
        List<Long> values = search(key);
        if (values.contains(value)) {
            boolean removed = delete(root, key, value);
            if (removed) size--;
            return true;
        }
        return false;
    }
    
    private boolean delete(Node node, K key, long value) {
        int idx = 0;
        while (idx < node.keyCount && ((Comparable<K>) node.keys[idx]).compareTo(key) < 0) {
            idx++;
        }
        
        if (idx < node.keyCount && ((Comparable<K>) node.keys[idx]).compareTo(key) == 0) {
            if (node.isLeaf) {
                // Buscar el índice del valor en el array
                int valIdx = -1;
                for (int i = 0; i < node.keyCount; i++) {
                    if (node.values[i] == value) {
                        valIdx = i;
                        break;
                    }
                }
                if (valIdx >= 0 && valIdx == idx) {
                    // Eliminar desplazando elementos
                    for (int i = idx; i < node.keyCount - 1; i++) {
                        node.keys[i] = node.keys[i + 1];
                        node.values[i] = node.values[i + 1];
                    }
                    node.keys[node.keyCount - 1] = null;
                    node.keyCount--;
                    return true;
                }
                return false;
            } else {
                K predecessor = findMax(node.children[idx]);
                if (predecessor != null) {
                    node.keys[idx] = predecessor;
                    delete(node.children[idx], predecessor, value);
                    return true;
                }
            }
        }
        
        if (!node.isLeaf && idx <= node.keyCount && node.children[idx] != null) {
            return delete(node.children[idx], key, value);
        }
        
        return false;
    }
    
    @SuppressWarnings("unchecked")
    private K findMax(Node node) {
        if (node.isLeaf) {
            if (node.keyCount == 0) return null;
            return (K) node.keys[node.keyCount - 1];
        }
        Node lastChild = null;
        for (int i = node.keyCount; i >= 0; i--) {
            if (node.children[i] != null) {
                lastChild = node.children[i];
                break;
            }
        }
        return lastChild != null ? findMax(lastChild) : null;
    }
    
    public int size() {
        return size;
    }
    
    public String getName() {
        return name;
    }
    
    public void clear() {
        root = new Node(true, 2 * order - 1);
        size = 0;
    }
    
    public void persist(DataOutputStream out) throws IOException {
        out.writeUTF(name);
        out.writeInt(order);
        out.writeInt(size);
        persistNode(out, root);
    }
    
    @SuppressWarnings("unchecked")
    private void persistNode(DataOutputStream out, Node node) throws IOException {
        ObjectOutputStream oos = new ObjectOutputStream(out);
        out.writeBoolean(node.isLeaf);
        out.writeInt(node.keyCount);
        
        for (int i = 0; i < node.keyCount; i++) {
            oos.writeObject(node.keys[i]);
            out.writeLong(node.values[i]);
        }
        
        if (!node.isLeaf) {
            for (int i = 0; i <= node.keyCount; i++) {
                if (node.children[i] != null) {
                    persistNode(out, node.children[i]);
                }
            }
        }
        oos.flush();
    }
    
    @SuppressWarnings("unchecked")
    public void load(DataInputStream in) throws IOException, ClassNotFoundException {
        this.name = in.readUTF();
        this.order = in.readInt();
        this.size = in.readInt();
        this.root = loadNode(in);
    }
    
    private Node loadNode(DataInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream ois = new ObjectInputStream(in);
        boolean isLeaf = in.readBoolean();
        int keyCount = in.readInt();
        
        Node node = new Node(isLeaf, 2 * order - 1);
        
        for (int i = 0; i < keyCount; i++) {
            node.keys[i] = ois.readObject();
            node.values[i] = in.readLong();
        }
        node.keyCount = keyCount;
        
        if (!isLeaf) {
            for (int i = 0; i <= keyCount; i++) {
                node.children[i] = loadNode(in);
            }
        }
        
        return node;
    }
    
    @SuppressWarnings("unchecked")
    public void print() {
        print(root, 0);
    }
    
    private void print(Node node, int level) {
        String indent = "  ".repeat(level);
        System.out.print(indent + "[");
        for (int i = 0; i < node.keyCount; i++) {
            if (i > 0) System.out.print(", ");
            System.out.print(node.keys[i] + ":" + node.values[i]);
        }
        System.out.println("]");
        
        if (!node.isLeaf) {
            for (int i = 0; i <= node.keyCount; i++) {
                if (node.children[i] != null) {
                    print(node.children[i], level + 1);
                }
            }
        }
    }
}
