package com.jnape.palatable.shoki.impl;

import com.jnape.palatable.lambda.adt.Maybe;
import com.jnape.palatable.lambda.adt.hlist.Tuple2;
import com.jnape.palatable.lambda.adt.product.Product2;
import com.jnape.palatable.shoki.api.EquivalenceRelation;
import com.jnape.palatable.shoki.api.HashingAlgorithm;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static com.jnape.palatable.lambda.adt.Maybe.just;
import static com.jnape.palatable.lambda.adt.Maybe.nothing;
import static com.jnape.palatable.lambda.adt.hlist.HList.tuple;
import static com.jnape.palatable.lambda.functions.builtin.fn1.Flatten.flatten;
import static com.jnape.palatable.lambda.functions.builtin.fn2.Eq.eq;
import static com.jnape.palatable.lambda.functions.builtin.fn2.Find.find;
import static com.jnape.palatable.lambda.functions.builtin.fn2.Map.map;
import static com.jnape.palatable.lambda.functions.builtin.fn3.FoldLeft.foldLeft;
import static com.jnape.palatable.shoki.api.Natural.one;
import static com.jnape.palatable.shoki.impl.Bitmap32.evictAtIndex;
import static com.jnape.palatable.shoki.impl.Bitmap32.lowerBits;
import static com.jnape.palatable.shoki.impl.Bitmap32.populateAtIndex;
import static com.jnape.palatable.shoki.impl.Bitmap32.populatedAtIndex;
import static java.lang.Integer.bitCount;
import static java.lang.Math.ceil;
import static java.lang.Math.pow;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;

interface HAMT<K, V> extends Iterable<Tuple2<K, V>> {

    int LEVEL_SIZE = 5;
    int FULL_LEVEL = (int) pow(2, LEVEL_SIZE) - 1;
    int MAX_LEVEL  = (int) ceil(32D / LEVEL_SIZE);

    HAMT<K, V> put(K key, V value, int keyHash, EquivalenceRelation<K> keyEqRel, HashingAlgorithm<K> keyHashAlg,
                   int level);

    Maybe<V> get(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level);

    Maybe<HAMT<K, V>> remove(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level);

    final class Node<K, V> implements HAMT<K, V> {

        private static final Node<?, ?> ROOT = new Node<>(0, new Object[0]);

        private final int      bitmap;
        private final Object[] table;

        public Node(int bitmap, Object[] table) {
            this.bitmap = bitmap;
            this.table  = table;
        }

        @Override
        public Maybe<V> get(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level) {
            int bitmapIndex = bitmapIndex(keyHash, level);
            return populatedAtIndex(bitmap, bitmapIndex)
                   ? valueAtIndex(tableIndex(bitmapIndex)).get(key, keyHash, keyEqRel, level + 1)
                   : Maybe.nothing();
        }

        @Override
        public Node<K, V> put(K key, V value, int keyHash, EquivalenceRelation<K> keyEqRel,
                              HashingAlgorithm<K> keyHashAlg, int level) {
            int bitmapIndex = bitmapIndex(keyHash, level);
            int tableIndex  = tableIndex(bitmapIndex);
            return populatedAtIndex(bitmap, bitmapIndex)
                   ? overrideAt(tableIndex,
                                valueAtIndex(tableIndex).put(key, value, keyHash, keyEqRel, keyHashAlg, level + 1))
                   : insertAt(tableIndex, bitmapIndex, new Entry<>(key, value));
        }

        @Override
        public Iterator<Tuple2<K, V>> iterator() {
            @SuppressWarnings("unchecked")
            List<HAMT<K, V>> bodies = (List<HAMT<K, V>>) (Object) asList(table);
            return flatten(bodies).iterator();
        }

        @Override
        public Maybe<HAMT<K, V>> remove(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level) {
            int bitmapIndex = bitmapIndex(keyHash, level);
            if (!populatedAtIndex(bitmap, bitmapIndex))
                return just(this);

            int tableIndex = tableIndex(bitmapIndex);
            return just(valueAtIndex(tableIndex)
                                .remove(key, keyHash, keyEqRel, level + 1)
                                .fmap(override -> overrideAt(tableIndex, override))
                                .orElseGet(() -> deleteAt(bitmapIndex, tableIndex)));
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof Node<?, ?>) {
                Node<?, ?> node = (Node<?, ?>) other;
                return Objects.equals(bitmap, node.bitmap) &&
                        java.util.Arrays.equals(table, node.table);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 31 * bitmap + java.util.Arrays.hashCode(table);
        }

        private int tableIndex(int bitmapIndex) {
            return bitCount(lowerBits(bitmap, bitmapIndex));
        }

        private int bitmapIndex(int keyHash, int level) {
            int shift = (level - 1) * LEVEL_SIZE;
            return (keyHash & FULL_LEVEL << shift) >>> shift;
        }

        @SuppressWarnings("unchecked")
        private HAMT<K, V> valueAtIndex(int tableIndex) {
            return (HAMT<K, V>) table[tableIndex];
        }

        private Node<K, V> insertAt(int tableIndex, int bitmapIndex, HAMT<K, V> valueForSlot) {
            return new Node<>(populateAtIndex(bitmap, bitmapIndex), Arrays.insertAt(tableIndex, table, valueForSlot));
        }

        private Node<K, V> overrideAt(int tableIndex, HAMT<K, V> valueForSlot) {
            return new Node<>(bitmap, Arrays.overrideAt(tableIndex, table, valueForSlot));
        }

        private Node<K, V> deleteAt(int bitmapIndex, int tableIndex) {
            return new Node<>(evictAtIndex(bitmap, bitmapIndex), Arrays.deleteAt(tableIndex, table));
        }

        @SuppressWarnings("unchecked")
        static <K, V> HAMT<K, V> rootNode() {
            return (HAMT<K, V>) ROOT;
        }
    }

    final class Entry<K, V> implements HAMT<K, V>, Product2<K, V> {

        private final K k;
        private final V v;

        Entry(K k, V v) {
            this.k = k;
            this.v = v;
        }

        @Override
        public K _1() {
            return k;
        }

        @Override
        public V _2() {
            return v;
        }

        @Override
        public HAMT<K, V> put(K newKey, V newValue, int keyHash,
                              EquivalenceRelation<K> keyEqRel, HashingAlgorithm<K> keyHashAlg,
                              int level) {
            if (keyEqRel.apply(newKey, k))
                return new Entry<>(newKey, newValue);

            Integer existingKeyHash = keyHashAlg.apply(k);
            return level <= MAX_LEVEL
                   ? new Node<K, V>(0, new Object[0])
                           .put(k, v, existingKeyHash, keyEqRel, keyHashAlg, level)
                           .put(newKey, newValue, keyHash, keyEqRel, keyHashAlg, level)
                   : new Collision<>(existingKeyHash, StrictStack.of(this, new Entry<>(newKey, newValue)));
        }

        @Override
        public Iterator<Tuple2<K, V>> iterator() {
            return singleton(tuple(k, v)).iterator();
        }

        @Override
        public Maybe<V> get(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level) {
            return keyEqRel.apply(key, k) ? just(v) : nothing();
        }

        @Override
        public Maybe<HAMT<K, V>> remove(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level) {
            return keyEqRel.apply(key, k) ? nothing() : just(this);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof Entry<?, ?>) {
                Entry<?, ?> entry = (Entry<?, ?>) other;
                return Objects.equals(k, entry.k) && Objects.equals(v, entry.v);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(k, v);
        }
    }

    final class Collision<K, V> implements HAMT<K, V> {
        private final int                      keyHash;
        private final StrictStack<Entry<K, V>> kvPairs;

        Collision(int keyHash,
                  StrictStack<Entry<K, V>> kvPairs) {
            this.keyHash = keyHash;
            this.kvPairs = kvPairs;
        }

        @Override
        public HAMT<K, V> put(K key, V value, int keyHash, EquivalenceRelation<K> keyEqRel,
                              HashingAlgorithm<K> keyHashAlg, int level) {
            return new Collision<>(keyHash, foldLeft(((s, kv) -> !keyEqRel.apply(key, kv._1()) ? s.cons(kv) : s),
                                                     StrictStack.of(new Entry<>(key, value)),
                                                     kvPairs));
        }

        @Override
        public Iterator<Tuple2<K, V>> iterator() {
            return map(Tuple2::fromEntry, kvPairs).iterator();
        }

        @Override
        public Maybe<V> get(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level) {
            return keyHash == this.keyHash
                   ? find(kvPair -> keyEqRel.apply(key, kvPair._1()), kvPairs).fmap(Entry::_2)
                   : nothing();
        }

        @Override
        public Maybe<HAMT<K, V>> remove(K key, int keyHash, EquivalenceRelation<K> keyEqRel, int level) {
            if (keyHash != this.keyHash)
                return just(this);

            StrictStack<Entry<K, V>> withoutKey = foldLeft(((s, kv) -> !keyEqRel.apply(key, kv._1())
                                                                       ? s.cons(kv)
                                                                       : s),
                                                           StrictStack.empty(),
                                                           kvPairs);
            return just(eq(withoutKey.sizeInfo().getSize(), one())
                        ? withoutKey.iterator().next()
                        : new Collision<>(keyHash, withoutKey));
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof Collision<?, ?>) {
                Collision<?, ?> collision = (Collision<?, ?>) other;
                return Objects.equals(keyHash, collision.keyHash) &&
                        Objects.equals(kvPairs, collision.kvPairs);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(keyHash, kvPairs);
        }
    }
}
