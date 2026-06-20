package com.deskdb.index;

import java.io.*;
import java.util.*;

/**
 * Implementación de B-Tree para índices en DeskDB.
 * 
 * @param <K> Tipo de clave (debe ser Comparable)
 */
public class BTree<K extends Comparable<K>, V> {
    
    private static final int DEFAULT_ORDER = 4;
    
    private int order;
    private Node root;
    private int size;
    private String name;
    
    private static class Node {
        List<Object> keys;
        List<Node> children;
        List<Long> values;
        boolean isLeaf;
        
        Node(boolean isLeaf, int capacity) {
            this.isLeaf = isLeaf;
            this.keys = new ArrayList<>(capacity);
            this.children = isLeaf ? null : new ArrayList<>(capacity + 1);
            this.values = new ArrayList<>(capacity);
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
        if (root.keys.size() >= 2 * order - 1 && root.children == null) {
            Node oldRoot = root;
            root = new Node(false, 2 * order - 1);
            root.children.add(oldRoot);
            splitChild(root, 0);
        }
        insertNonFull(root, key, value);
        size++;
    }
    
    @SuppressWarnings("unchecked")
    private void insertNonFull(Node node, K key, long value) {
        int i = node.keys.size() - 1;
        
        if (node.isLeaf) {
            while (i >= 0 && key.compareTo((K) node.keys.get(i)) < 0) {
                i--;
            }
            node.keys.add(i + 1, key);
            node.values.add(i + 1, value);
        } else {
            while (i >= 0 && key.compareTo((K) node.keys.get(i)) < 0) {
                i--;
            }
            i++;
            
            if (node.children.get(i).keys.size() >= 2 * order - 1) {
                splitChild(node, i);
                if (key.compareTo((K) node.keys.get(i)) > 0) {
                    i++;
                }
            }
            insertNonFull(node.children.get(i), key, value);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void splitChild(Node parent, int index) {
        Node child = parent.children.get(index);
        Node newNode = new Node(child.isLeaf, 2 * order - 1);
        
        int mid = order - 1;
        
        // Mover las claves superiores al nuevo nodo
        for (int j = 0; j < order - 1 && (mid + 1 + j) < child.keys.size(); j++) {
            newNode.keys.add(child.keys.get(mid + 1 + j));
            newNode.values.add(child.values.get(mid + 1 + j));
        }
        
        // Mover hijos si no es hoja
        if (!child.isLeaf) {
            for (int j = 0; j < order && (mid + 1 + j) < child.children.size(); j++) {
                newNode.children.add(child.children.get(mid + 1 + j));
            }
        }
        
        // Eliminar las claves movidas del hijo original
        for (int j = child.keys.size() - 1; j > mid; j--) {
            child.keys.remove(j);
            child.values.remove(j);
        }
        
        // Eliminar hijos movidos
        if (!child.isLeaf) {
            for (int j = child.children.size() - 1; j > mid; j--) {
                child.children.remove(j);
            }
        }
        
        // Insertar la clave mediana en el padre
        parent.keys.add(index, child.keys.get(mid));
        parent.children.add(index + 1, newNode);
        
        // Eliminar la clave mediana del hijo
        child.keys.remove(mid);
    }
    
    @SuppressWarnings("unchecked")
    public List<Long> search(K key) {
        List<Long> result = new ArrayList<>();
        search(root, key, result);
        return result;
    }
    
    private void search(Node node, K key, List<Long> result) {
        int i = 0;
        
        while (i < node.keys.size()) {
            int cmp = key.compareTo((K) node.keys.get(i));
            if (cmp == 0) {
                if (node.isLeaf) {
                    result.add(node.values.get(i));
                } else {
                    search(node.children.get(i + 1), key, result);
                }
                return;
            } else if (cmp < 0) {
                break;
            }
            i++;
        }
        
        if (!node.isLeaf && i < node.children.size()) {
            search(node.children.get(i), key, result);
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
        for (int i = 0; i < node.keys.size(); i++) {
            K key = (K) node.keys.get(i);
            
            if (!node.isLeaf) {
                if (i == 0 || from.compareTo(key) < 0) {
                    rangeSearch(node.children.get(i), from, to, result);
                }
            }
            
            if (key.compareTo(from) >= 0 && key.compareTo(to) <= 0) {
                if (node.isLeaf) {
                    result.add(node.values.get(i));
                } else {
                    searchSubtree(node.children.get(i + 1), from, to, result);
                }
            }
        }
        
        if (!node.isLeaf && node.children.size() > node.keys.size()) {
            rangeSearch(node.children.get(node.children.size() - 1), from, to, result);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void searchSubtree(Node node, K from, K to, List<Long> result) {
        for (int i = 0; i < node.keys.size(); i++) {
            K key = (K) node.keys.get(i);
            
            if (!node.isLeaf) {
                if (i == 0 || from.compareTo(key) < 0) {
                    searchSubtree(node.children.get(i), from, to, result);
                }
            }
            
            if (key.compareTo(from) >= 0 && key.compareTo(to) <= 0) {
                if (node.isLeaf) {
                    result.add(node.values.get(i));
                } else {
                    searchSubtree(node.children.get(i + 1), from, to, result);
                }
            }
        }
        
        if (!node.isLeaf && node.children.size() > node.keys.size()) {
            searchSubtree(node.children.get(node.children.size() - 1), from, to, result);
        }
    }
    
    @SuppressWarnings("unchecked")
    public boolean delete(K key) {
        boolean removed = delete(root, key);
        if (removed) size--;
        return removed;
    }
    
    private boolean delete(Node node, K key) {
        int idx = 0;
        while (idx < node.keys.size() && ((Comparable<K>) node.keys.get(idx)).compareTo(key) < 0) {
            idx++;
        }
        
        if (idx < node.keys.size() && ((Comparable<K>) node.keys.get(idx)).compareTo(key) == 0) {
            if (node.isLeaf) {
                node.keys.remove(idx);
                node.values.remove(idx);
                return true;
            } else {
                K predecessor = findMax(node.children.get(idx));
                if (predecessor != null) {
                    node.keys.set(idx, predecessor);
                    delete(node.children.get(idx), predecessor);
                    return true;
                }
            }
        }
        
        if (!node.isLeaf && idx < node.children.size()) {
            return delete(node.children.get(idx), key);
        }
        
        return false;
    }
    
    @SuppressWarnings("unchecked")
    private K findMax(Node node) {
        if (node.isLeaf) {
            if (node.keys.isEmpty()) return null;
            return (K) node.keys.get(node.keys.size() - 1);
        }
        return findMax(node.children.get(node.children.size() - 1));
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
    
    public void persist(ObjectOutput out) throws IOException {
        out.writeUTF(name);
        out.writeInt(order);
        out.writeInt(size);
        persistNode(out, root);
    }
    
    @SuppressWarnings("unchecked")
    private void persistNode(ObjectOutput out, Node node) throws IOException {
        out.writeBoolean(node.isLeaf);
        out.writeInt(node.keys.size());
        
        for (int i = 0; i < node.keys.size(); i++) {
            out.writeObject(node.keys.get(i));
            out.writeLong(node.values.get(i));
        }
        
        if (!node.isLeaf) {
            for (Node child : node.children) {
                persistNode(out, child);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    public void load(ObjectInput in) throws IOException, ClassNotFoundException {
        this.name = in.readUTF();
        this.order = in.readInt();
        this.size = in.readInt();
        this.root = loadNode(in);
    }
    
    private Node loadNode(ObjectInput in) throws IOException, ClassNotFoundException {
        boolean isLeaf = in.readBoolean();
        int keyCount = in.readInt();
        
        Node node = new Node(isLeaf, 2 * order - 1);
        
        for (int i = 0; i < keyCount; i++) {
            node.keys.add(in.readObject());
            node.values.add(in.readLong());
        }
        
        if (!isLeaf) {
            for (int i = 0; i <= keyCount; i++) {
                node.children.add(loadNode(in));
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
        for (int i = 0; i < node.keys.size(); i++) {
            if (i > 0) System.out.print(", ");
            System.out.print(node.keys.get(i) + ":" + node.values.get(i));
        }
        System.out.println("]");
        
        if (!node.isLeaf) {
            for (Node child : node.children) {
                print(child, level + 1);
            }
        }
    }
}
