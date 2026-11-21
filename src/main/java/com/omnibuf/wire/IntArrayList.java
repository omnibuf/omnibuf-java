package com.omnibuf.wire;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Unboxed {@code int[]}-backed {@link java.util.List} implementation eliminating {@link Integer}
 * heap boxing during repeated 32-bit scalar field encoding and decoding.
 */
public final class IntArrayList extends AbstractList<Integer> implements RandomAccess {

    private static final int[] EMPTY = new int[0];
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private int[] data;
    private int size;

    public IntArrayList() {
        this.data = EMPTY;
        this.size = 0;
    }

    public IntArrayList(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Negative initialCapacity: " + initialCapacity);
        }
        this.data = initialCapacity == 0 ? EMPTY : new int[initialCapacity];
        this.size = 0;
    }

    public IntArrayList(Collection<? extends Integer> c) {
        int n = c.size();
        if (n == 0) {
            this.data = EMPTY;
            this.size = 0;
        } else {
            int[] arr = new int[n];
            int i = 0;
            for (Integer v : c) {
                arr[i++] = Objects.requireNonNull(v, "IntArrayList does not permit null elements");
            }
            this.data = arr;
            this.size = i;
        }
    }

    public int[] rawArray() {
        return data;
    }

    public void setSizeUnchecked(int newSize) {
        this.size = newSize;
        this.modCount++;
    }

    public void ensureAdditional(int additional) {
        if (additional <= 0) {
            return;
        }
        long needed = (long) size + (long) additional;
        if (needed > data.length) {
            grow(needed);
        }
    }

    private void grow(long minCapacity) {
        if (minCapacity < 0L || minCapacity > MAX_ARRAY_SIZE) {
            throw new OutOfMemoryError("IntArrayList exceeds maximum array size");
        }
        int needed = (int) minCapacity;
        long newCap = data.length == 0 ? Math.max(10, needed) : data.length;
        while (newCap < needed) {
            newCap = Math.min(newCap + (newCap >> 1) + 1L, MAX_ARRAY_SIZE);
            if (newCap < needed && newCap == MAX_ARRAY_SIZE) {
                break;
            }
        }
        data = Arrays.copyOf(data, (int) newCap);
    }

    public int getInt(int index) {
        Objects.checkIndex(index, size);
        return data[index];
    }

    public void addInt(int value) {
        int s = this.size;
        if (s == data.length) {
            grow((long) s + 1L);
        }
        data[s] = value;
        this.size = s + 1;
        this.modCount++;
    }

    public void addAll(int[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        ensureAdditional(values.length);
        System.arraycopy(values, 0, data, size, values.length);
        size += values.length;
        modCount++;
    }

    public int[] toIntArray() {
        return size == 0 ? EMPTY : Arrays.copyOf(data, size);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Integer get(int index) {
        Objects.checkIndex(index, size);
        return data[index];
    }

    @Override
    public Integer set(int index, Integer element) {
        Objects.checkIndex(index, size);
        int v = Objects.requireNonNull(element, "IntArrayList does not permit null elements");
        int old = data[index];
        data[index] = v;
        return old;
    }

    @Override
    public boolean add(Integer element) {
        addInt(Objects.requireNonNull(element, "IntArrayList does not permit null elements"));
        return true;
    }

    @Override
    public void add(int index, Integer element) {
        Objects.checkIndex(index, size + 1);
        int v = Objects.requireNonNull(element, "IntArrayList does not permit null elements");
        int s = this.size;
        if (s == data.length) {
            grow((long) s + 1L);
        }
        if (index < s) {
            System.arraycopy(data, index, data, index + 1, s - index);
        }
        data[index] = v;
        this.size = s + 1;
        this.modCount++;
    }

    @Override
    public Integer remove(int index) {
        Objects.checkIndex(index, size);
        int old = data[index];
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(data, index + 1, data, index, moved);
        }
        size--;
        modCount++;
        return old;
    }

    @Override
    public void clear() {
        size = 0;
        modCount++;
    }
}
