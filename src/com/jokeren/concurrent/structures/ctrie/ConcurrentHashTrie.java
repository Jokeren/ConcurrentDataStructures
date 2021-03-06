package com.jokeren.concurrent.structures.ctrie;

/**
 * Created by robin on 2015/11/17.
 */
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class ConcurrentHashTrie<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    /**
     * Root node of the trie
     */
    private final INode root;

    /**
     * Width in bits
     */
    private final byte width;

    /**
     * EntrySet
     */
    private final EntrySet entrySet = new EntrySet ();

    /**
     * Builds a {@link ConcurrentHashTrie} instance
     */
    public ConcurrentHashTrie () {
        this (6);
    }

    /**
     * Builds a {@link ConcurrentHashTrie} instance
     *
     * @param width
     *            the Trie width in power-of-two exponents. Values are expected
     *            between between 1 & 6, other values will be clamped.
     *            <p>
     *            The width defines the "speed" of the trie:
     *            <ul>
     *            <li>A value of 1: gives an actual width of two items per
     *            level, hence the trie is O(Log2(N))</li>
     *            <li>A value of 6: gives an actual width of 64 items per level,
     *            hence the trie is O(Log64(N)</li>
     *            </ul>
     */
    public ConcurrentHashTrie (final int width) {
        this.root = new INode (new CNode<K, V> ());
        if (width > 6) {
            this.width = 6;
        } else if (width < 1) {
            this.width = 1;
        } else {
            this.width = (byte) width;
        }
    }

    /**
     * Builds a {@link Map} based on the mapping of another {@link Map}.
     *
     * @param map
     *            a {@link Map}
     */
    public ConcurrentHashTrie (final Map<K, V> map) {
        this ();
        this.putAll (map);
    }

    /**
     * Builds a {@link Map} based on the mapping of another {@link Map}.
     *
     * @param map
     *            a {@link Map}
     * @param width the Trie width in power-of-two
     *            exponents. Values are expected between between 1 & 6, other
     *            values will be clamped.
     *            <p>
     *            The width defines the "speed" of the trie:
     *            <ul>
     *            <li>A value of 1: gives an actual width of two items per
     *            level, hence the trie is O(Log2(N))</li>
     *            <li>A value of 6: gives an actual width of 64 items per level,
     *            hence the trie is O(Log64(N)</li>
     *            </ul>
     */
    public ConcurrentHashTrie (final Map<K, V> map, final int width) {
        this (width);
        this.putAll (map);
    }

    @Override
    public boolean isEmpty () {
        final MainNode main = this.root.getMain ();
        if (main instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) main;
            return cn.bitmap == 0L;
        } else {
            return false;
        }
    }

    @Override
    public boolean containsKey (final Object key) {
        @SuppressWarnings("unchecked")
        final K k = (K) key;
        return null != lookup (k);
    }

    @Override
    public V get (final Object key) {
        @SuppressWarnings("unchecked")
        final K k = (K) key;
        return lookup (k);
    }

    @Override
    public V put (final K key, final V value) {
        return insert (key, value, new Constraint<V> (ConstraintType.NONE, null));
    }

    @Override
    public V remove (final Object key) {
        @SuppressWarnings("unchecked")
        final K k = (K) key;
        return delete (k, ConcurrentHashTrie.<V>noConstraint ());
    }

    @Override
    public void clear () {
        final MainNode main = this.root.getMain ();
        while (true) {
            if (this.root.casMain (main, new CNode<K, V> ())) {
                return;
            } else {
                continue;
            }
        }
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet () {
        return entrySet;
    }

    @Override
    public V putIfAbsent (final K key, final V value) {
        return insert (key, value,
                new Constraint<V> (ConstraintType.PUT_IF_ABSENT, null));
    }

    @Override
    public boolean remove (final Object key, final Object value) {
        @SuppressWarnings("unchecked")
        final K k = (K) key;
        @SuppressWarnings("unchecked")
        final V previous = delete (k,
                new Constraint<V> (ConstraintType.REMOVE_IF_MAPPED_TO, (V) value));
        return previous != null && value.equals (previous);
    }

    @Override
    public boolean replace (final Object key, final Object oldValue, final Object newValue) {
        @SuppressWarnings("unchecked")
        final V previous = insert ((K) key, (V)newValue,
                new Constraint<V> (ConstraintType.REPLACE_IF_MAPPED_TO, (V) oldValue));
        return null != previous && previous.equals (oldValue);
    }

    @Override
    public V replace (final Object key, final Object value) {
        @SuppressWarnings("unchecked")
        final V result = insert ((K) key, (V)value,
                new Constraint<V> (ConstraintType.REPLACE_IF_MAPPED, null));
        return result;
    }

    final class Iter implements Iterator<Map.Entry<K, V>> {
        public Iter () {
            advance ();
        }

        @Override
        public final boolean hasNext () {
            return nextKVN != null;
        }

        @Override
        public final Entry<K, V> next () {
            if (nextKVN == null) {
                throw new NoSuchElementException();
            }
            lastReturnedKVN = nextKVN;
            advance ();
            return new Entry<K, V> () {
                private V overriden = null;
                @Override
                public K getKey () {
                    return lastReturnedKVN.key;
                }

                @Override
                public V getValue () {
                    if (null == overriden) {
                        return lastReturnedKVN.value;
                    } else {
                        return overriden;
                    }
                }

                @Override
                public V setValue (final V value) {
                    final V old = getValue ();
                    notNullValue (value);
                    overriden = value;
                    ConcurrentHashTrie.this.put (lastReturnedKVN.key, value);
                    return old;
                }
            };
        }

        @Override
        public final void remove () {
            if (lastReturnedKVN == null) {
                throw new IllegalStateException();
            } else {
                ConcurrentHashTrie.this.remove (lastReturnedKVN.key);
                lastReturnedKVN = null;
            }
        }

        private final void advance () {
            if (null == nextSNode) {
                this.nextSNode = lookupNext (null);
            }
            if (null != this.nextSNode) {
                final KeyValueNode<K, V> nextKV = this.nextSNode.next (this.nextKVN);
                if (null != nextKV) {
                    this.nextKVN = nextKV;
                } else {
                    this.nextSNode = lookupNext (this.nextSNode);
                    if (null != this.nextSNode) {
                        this.nextKVN = this.nextSNode.next (null);
                    } else {
                        this.nextKVN = null;
                    }
                }
            } else {
                this.nextKVN = null;
            }
        }

        private KeyValueNode<K, V> lastReturnedKVN = null;
        private KeyValueNode<K, V> nextKVN = null;
        private SNode<K, V> nextSNode = null;
    }

    final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        @Override
        public Iterator<Map.Entry<K, V>> iterator () {
            return new Iter ();
        }

        @Override
        public final boolean contains (final Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            final Map.Entry<K, V> e = (Map.Entry<K, V>) o;
            final K k = e.getKey ();
            final V v = lookup (k);
            return v != null;
        }

        @Override
        public final boolean remove (final Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            final Map.Entry<K, V> e = (Map.Entry<K, V>) o;
            final K k = e.getKey ();
            return null != delete (k, ConcurrentHashTrie.<V>noConstraint ());
        }

        @Override
        public final int size () {
            int size = 0;
            for (final Iterator<?> i = iterator (); i.hasNext (); i.next ()) {
                size++;
            }
            return size;
        }

        @Override
        public final void clear () {
            ConcurrentHashTrie.this.clear ();
        }
    }

    /**
     * Inserts or updates a key/value mapping.
     *
     * @param key
     *            a key {@link Object}
     * @param value
     *            a value Object
     * @param constraint
     *            the {@link Constraint} value
     * @return the previous value associated to key
     */
    V insert (final K key, final V value, final Constraint<V> constraint) {
        notNullKey (key);
        notNullValue (value);
        final int hc = hash (key);
        while (true) {
            final Result<V> res = iinsert (this.root, hc, key, value, 0, null, constraint);
            switch (res.type) {
                case FOUND:
                    return res.result;
                case NOTFOUND:
                    return null;
                case RESTART:
                    continue;
                case REJECTED:
                    if (ConstraintType.PUT_IF_ABSENT == constraint.type) {
                        return res.result;
                    } else if (ConstraintType.REPLACE_IF_MAPPED == constraint.type) {
                        return null;
                    } else if (ConstraintType.REPLACE_IF_MAPPED_TO == constraint.type) {
                        return res.result;
                    } else {
                        throw new RuntimeException ("Unexpected case: " + constraint.type);
                    }
                default:
                    throw new RuntimeException ("Unexpected case: " + res.type);
            }
        }
    }

    /**
     * Looks up the value associated to a key
     *
     * @param key
     *            a key {@link Object}
     * @return the value associated to k
     */
    V lookup (final K key) {
        notNullKey (key);
        final int hc = hash (key);
        while (true) {
            // Getting lookup result
            final Result<V> res = ilookup (this.root, hc, key, 0, null);
            switch (res.type) {
                case FOUND:
                    return res.result;
                case NOTFOUND:
                    return null;
                case RESTART:
                    continue;
                default:
                    throw new RuntimeException ("Unexpected case: " + res.type);
            }
        }
    }

    /**
     * Removes a key/value mapping
     *
     * @param key
     *            the key Object
     * @param constraint
     *            the {@link Constraint} value
     * @return the removed value if removed was performed, null otherwise
     */
    V delete (final K key, final Constraint<V> constraint) {
        notNullKey (key);
        final int hc = hash (key);
        while (true) {
            // Getting remove result
            final Result<V> res = idelete (this.root, hc, key, 0, null, constraint);
            switch (res.type) {
                case FOUND:
                    return res.result;
                case NOTFOUND:
                    return null;
                case RESTART:
                    continue;
                case REJECTED:
                    if (ConstraintType.REMOVE_IF_MAPPED_TO == constraint.type) {
                        return res.result;
                    } else {
                        throw new RuntimeException ("Unexpected case: " + constraint.type);
                    }
                default:
                    throw new RuntimeException ("Unexpected case: " + res.type);
            }
        }
    }

    SNode<K, V> lookupNext (final SNode<K, V> current) {
        if (current != null) {
            final int hc = current.hash ();
            while (true) {
                // Getting lookup result
                final Result<SNode<K, V>> res = ilookupNext (this.root, hc, 0, null);
                switch (res.type) {
                    case FOUND:
                        return res.result;
                    case NOTFOUND:
                        return null;
                    case RESTART:
                        continue;
                    default:
                        throw new RuntimeException ("Unexpected case: " + res.type);
                }
            }
        } else {
            while (true) {
                // Getting lookup result
                final Result<SNode<K, V>> res = ilookupFirst (this.root, 0, null);
                switch (res.type) {
                    case FOUND:
                        return res.result;
                    case NOTFOUND:
                        return null;
                    case RESTART:
                        continue;
                    default:
                        throw new RuntimeException ("Unexpected case: " + res.type);
                }
            }
        }
    }

    private Result<V> ilookup (final INode i,
                               final int hashcode,
                               final K k,
                               final int level,
                               final INode parent) {
        final MainNode main = i.getMain ();

        // Usual case
        if (main instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) main;
            final FlagPos flagPos = flagPos (hashcode, level, cn.bitmap, this.width);

            // Asked for a hash not in trie
            if (0L == (flagPos.flag & cn.bitmap)) {
                return new Result<V> (ResultType.NOTFOUND, null);
            }

            final BranchNode an = cn.array [flagPos.position];
            if (an instanceof INode) {
                // Looking down
                final INode sin = (INode) an;
                return ilookup (sin, hashcode, k, level + this.width, i);
            }
            if (an instanceof SNode) {
                // Found the hash locally, let's see if it matches
                @SuppressWarnings("unchecked")
                final SNode<K, V> sn = (SNode<K, V>) an;
                if (sn.hash () == hashcode) {
                    final V v = sn.get (k);
                    if (null != v) {
                        return new Result<V> (ResultType.FOUND, v);
                    } else {
                        return new Result<V> (ResultType.NOTFOUND, null);
                    }
                } else {
                    return new Result<V> (ResultType.NOTFOUND, null);
                }
            }
        }

        // Cleaning up trie
        if (main instanceof TNode) {
            clean (parent, level - this.width);
            return new Result<V> (ResultType.RESTART, null);
        }
        throw new RuntimeException ("Unexpected case: " + main);
    }

    private Result<V> iinsert (final INode i,
                               final int hashcode,
                               final K k,
                               final V v,
                               final int level,
                               final INode parent,
                               final Constraint<V> constraint) {

        final MainNode main = i.getMain ();

        // Usual case
        if (main instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) main;
            final FlagPos flagPos = flagPos (hashcode, level, cn.bitmap, this.width);

            // Asked for a hash not in trie, let's insert it
            if (0L == (flagPos.flag & cn.bitmap)) {

                // Check constraints
                if (    ConstraintType.REPLACE_IF_MAPPED_TO == constraint.type ||
                        ConstraintType.REPLACE_IF_MAPPED == constraint.type) {
                    return new Result<V> (ResultType.REJECTED, null);
                }

                final SNode<K, V> snode = new SingletonSNode<K, V> (k, v);
                final CNode<K, V> ncn = cn.inserted (flagPos, snode);
                if (i.casMain (main, ncn)) {
                    return new Result<V> (ResultType.FOUND, null);
                } else {
                    return new Result<V> (ResultType.RESTART, null);
                }
            }

            final BranchNode an = cn.array [flagPos.position];
            if (an instanceof INode) {
                // Looking down
                final INode sin = (INode) an;
                return iinsert (sin, hashcode, k, v, level + this.width, i, constraint);
            }

            if (an instanceof SNode) {
                @SuppressWarnings("unchecked")
                final SNode<K, V> sn = (SNode<K, V>) an;

                // Found the hash locally, let's see if it matches
                if (sn.hash () == hashcode) {
                    final V previousValue = sn.get (k);

                    // Check constraints
                    if (ConstraintType.PUT_IF_ABSENT == constraint.type && null != previousValue) {
                        return new Result<V> (ResultType.REJECTED, previousValue);
                    }
                    if (ConstraintType.REPLACE_IF_MAPPED_TO == constraint.type &&
                            !previousValue.equals (constraint.to)) {
                        return new Result<V> (ResultType.REJECTED, previousValue);
                    }

                    final SNode<K, V> nsn = sn.put (k, v);
                    final CNode<K, V> ncn = cn.updated (flagPos.position, nsn);
                    if (i.casMain (main, ncn)) {
                        return new Result<V> (ResultType.FOUND, previousValue);
                    } else {
                        return new Result<V> (ResultType.RESTART, null);
                    }
                } else {
                    // Check constraints
                    if (    ConstraintType.REPLACE_IF_MAPPED_TO == constraint.type ||
                            ConstraintType.REPLACE_IF_MAPPED == constraint.type) {
                        return new Result<V> (ResultType.REJECTED, null);
                    }

                    final SNode<K, V> nsn = new SingletonSNode<K, V> (k, v);
                    // Creates a sub-level
                    final CNode<K, V> scn = new CNode<K, V> (sn, nsn, level + this.width, this.width);
                    final INode nin = new INode (scn);
                    final CNode<K, V> ncn = cn.updated (flagPos.position, nin);
                    if (i.casMain (main, ncn)) {
                        return new Result<V> (ResultType.FOUND, null);
                    } else {
                        return new Result<V> (ResultType.RESTART, null);
                    }
                }
            }
        }

        // Cleaning up trie
        if (main instanceof TNode) {
            clean (parent, level - this.width);
            return new Result<V> (ResultType.RESTART, null);
        }
        throw new RuntimeException ("Unexpected case: " + main);
    }

    private Result<V> idelete (final INode i,
                               final int hashcode,
                               final K k,
                               final int level,
                               final INode parent,
                               final Constraint<V> constraint) {
        final MainNode main = i.getMain ();

        // Usual case
        if (main instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) main;
            final FlagPos flagPos = flagPos (hashcode, level, cn.bitmap, this.width);

            // Asked for a hash not in trie
            if (0L == (flagPos.flag & cn.bitmap)) {
                return new Result<V> (ResultType.NOTFOUND, null);
            }

            Result<V> res = null;
            final BranchNode an = cn.array [flagPos.position];
            if (an instanceof INode) {
                // Looking down
                final INode sin = (INode) an;
                res = idelete (sin, hashcode, k, level + this.width, i, constraint);
            }
            if (an instanceof SNode) {
                // Found the hash locally, let's see if it matches
                @SuppressWarnings("unchecked")
                final SNode<K, V> sn = (SNode<K, V>) an;
                if (sn.hash () == hashcode) {
                    final V previous = sn.get (k);
                    // Checking constraint first
                    if (null == previous) {
                        res = new Result<V> (ResultType.NOTFOUND, null);
                    } else if (ConstraintType.REMOVE_IF_MAPPED_TO == constraint.type &&
                            !constraint.to.equals (previous)) {
                        res = new Result<V> (ResultType.REJECTED, previous);
                    } else {
                        final SNode<K, V> nsn = sn.removed (k);
                        final MainNode replacement;
                        if (null != nsn) {
                            replacement = cn.updated (flagPos.position, nsn);
                        } else {
                            final CNode<K, V> ncn = cn.removed (flagPos);
                            replacement = toContracted (ncn, level);
                        }
                        if (i.casMain (main, replacement)) {
                            res = new Result<V> (ResultType.FOUND, previous);
                        } else {
                            res = new Result<V> (ResultType.RESTART, null);
                        }
                    }
                } else {
                    res = new Result<V> (ResultType.NOTFOUND, null);
                }
            }
            if (null == res) {
                throw new RuntimeException ("Unexpected case: " + an);
            }
            if (res.type == ResultType.NOTFOUND || res.type == ResultType.RESTART) {
                return res;
            }

            if (i.getMain () instanceof TNode) {
                cleanParent (parent, i, hashcode, level - this.width);
            }
            return res;
        }

        // Cleaning up trie
        if (main instanceof TNode) {
            clean (parent, level - this.width);
            return new Result<V> (ResultType.RESTART, null);
        }
        throw new RuntimeException ("Unexpected case: " + main);
    }

    private Result<SNode<K, V>> ilookupFirst (final INode i, final int level, final INode parent) {
        final MainNode main = i.getMain ();

        // Usual case
        if (main instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) main;
            if (cn.bitmap == 0L) {
                return new Result<SNode<K, V>> (ResultType.NOTFOUND, null);
            } else {
                return ipickupFirst (cn.array [0], level, i);
            }
        }

        // Cleaning up trie
        if (main instanceof TNode) {
            clean (parent, level - this.width);
            return new Result<SNode<K, V>> (ResultType.RESTART, null);
        }
        throw new RuntimeException ("Unexpected case: " + main);
    }

    private Result<SNode<K, V>> ilookupNext (final INode i,
                                             final int hashcode,
                                             final int level,
                                             final INode parent) {
        final MainNode main = i.getMain ();

        // Usual case
        if (main instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) main;
            final FlagPos flagPos = flagPos (hashcode, level, cn.bitmap, this.width);

            // Asked for a hash not in trie
            if (0L == (flagPos.flag & cn.bitmap)) {
                return ipickupFirstSibling (cn, flagPos, 0, level, i);
            }

            final BranchNode an = cn.array [flagPos.position];
            if (an instanceof INode) {
                // Looking down
                final INode sin = (INode) an;
                final Result<SNode<K, V>> next = ilookupNext (sin, hashcode, level + this.width, i);
                switch (next.type) {
                    case FOUND:
                        return next;
                    case NOTFOUND:
                        return ipickupFirstSibling (cn, flagPos, 1, level, i);
                    case RESTART:
                        return next;
                    default:
                        throw new RuntimeException ("Unexpected case: " + next.type);
                }
            }
            if (an instanceof SNode) {
                @SuppressWarnings("unchecked")
                final SNode<K, V> sn = (SNode<K, V>) an;
                if (hashcode + Integer.MIN_VALUE >= sn.hash () + Integer.MIN_VALUE) {
                    return ipickupFirstSibling (cn, flagPos, 1, level, i);
                } else {
                    return new Result<SNode<K, V>> (ResultType.FOUND, sn);
                }
            }
        }

        // Cleaning up trie
        if (main instanceof TNode) {
            clean (parent, level - this.width);
            return new Result<SNode<K, V>> (ResultType.RESTART, null);
        }
        throw new RuntimeException ("Unexpected case: " + main);
    }

    private Result<SNode<K, V>> ipickupFirstSibling (final CNode<K, V> cn,
                                                     final FlagPos flagPos,
                                                     final int offset,
                                                     final int level,
                                                     final INode parent) {

        // Go directly to the next entry in the current node if possible
        if (flagPos.position + offset < cn.array.length) {
            final BranchNode an = cn.array [flagPos.position + offset];
            return ipickupFirst (an, level, parent);
        } else {
            return new Result<SNode<K, V>> (ResultType.NOTFOUND, null);
        }
    }

    private Result<SNode<K, V>> ipickupFirst (final BranchNode bn, final int level, final INode parent) {
        if (bn instanceof INode) {
            // Looking down
            final INode sin = (INode) bn;
            return ilookupFirst (sin, level + this.width, parent);
        }
        if (bn instanceof SNode) {
            // Found the SNode
            @SuppressWarnings("unchecked")
            final SNode<K, V> sn = (SNode<K, V>) bn;
            return new Result<SNode<K, V>> (ResultType.FOUND, sn);
        }
        throw new RuntimeException ("Unexpected case: " + bn);
    }

    private void cleanParent (final INode parent, final INode i, final int hashCode, final int level) {
        while (true) {
            final MainNode m = i.getMain ();
            final MainNode pm = parent.getMain ();
            if (pm instanceof CNode) {
                @SuppressWarnings("unchecked")
                final CNode<K, V> pcn = (CNode<K, V>) pm;
                final FlagPos flagPos = flagPos (hashCode, level, pcn.bitmap, this.width);
                if (0L == (flagPos.flag & pcn.bitmap)) {
                    return;
                }
                final BranchNode sub = pcn.array [flagPos.position];
                if (sub != i) {
                    return;
                }
                if (m instanceof TNode) {
                    @SuppressWarnings("unchecked")
                    final SNode<K, V> untombed = ((TNode<K, V>) m).untombed ();
                    final CNode<K, V> ncn = pcn.updated (flagPos.position, untombed);
                    if (parent.casMain (pcn, toContracted (ncn, level))) {
                        return;
                    } else {
                        continue;
                    }
                }
            } else {
                return;
            }
        }
    }

    private void clean (final INode i, final int level) {
        final MainNode m = i.getMain ();
        if (m instanceof CNode) {
            @SuppressWarnings("unchecked")
            final CNode<K, V> cn = (CNode<K, V>) m;
            i.casMain (m, toCompressed (cn, level));
        }
    }

    private MainNode toCompressed (final CNode<K, V> cn, final int level) {
        final CNode<K, V> ncn = cn.copied ();

        // Resurrect tombed nodes.
        for (int i = 0; i < ncn.array.length; i++) {
            final BranchNode an = ncn.array [i];
            final TNode<K, V> tn = getTombNode (an);
            if (null != tn) {
                ncn.array [i] = tn.untombed ();
            }
        }

        return toContracted (ncn, level);
    }

    private MainNode toContracted (final CNode<K, V> cn, final int level) {
        if (level > 0 && 1 == cn.array.length) {
            final BranchNode bn = cn.array [0];
            if (bn instanceof SNode) {
                @SuppressWarnings("unchecked")
                final SNode<K, V> sn = (SNode<K, V>) bn;
                return sn.tombed ();
            }
        }
        return cn;
    }

    private TNode<K, V> getTombNode (final BranchNode an) {
        if (an instanceof INode) {
            final INode in = (INode) an;
            final MainNode mn = in.getMain ();
            if (mn instanceof TNode) {
                @SuppressWarnings("unchecked")
                final TNode<K, V> tn = (TNode<K, V>) mn;
                return tn;
            }
        }
        return null;
    }

    private void notNullValue (final V value) {
        if (value == null) {
            throw new NullPointerException ("The value must be non-null");
        }
    }

    private void notNullKey (final K key) {
        if (key == null) {
            throw new NullPointerException ("The key must be non-null");
        }
    }

    @SuppressWarnings("unchecked")
    static <V> Constraint<V> noConstraint () {
        return (Constraint<V>) NO_CONSTRAINT;
    }

    static int hash (final Object key) {
        int h = key.hashCode ();
        // This function ensures that hashCodes that differ only by
        // constant multiples at each bit position have a bounded
        // number of collisions (approximately 8 at default load factor).
        h ^= h >>> 20 ^ h >>> 12;
        return h ^ h >>> 7 ^ h >>> 4;
    }

    /**
     * Returns a copy an array with an updated value at a certain position.
     * @param src
     *            the source array
     * @param dst
     *            the destination array
     * @param t
     *            the updated value
     * @param position
     *            the position
     * @return an updated copy of the source array
     */
    static <T> T[] updated (final T[] src, final T[] dst, final T t, final int position) {
        System.arraycopy (src, 0, dst, 0, src.length);
        dst [position] = t;
        return dst;
    }

    /**
     * Returns a copy an {@link BranchNode} array with an inserted
     * {@link BranchNode} value at a certain position.
     * @param src
     *            the source array
     * @param dst
     *            the destination arrau
     * @param t
     *            the inserted {@link BranchNode} value
     * @param position
     *            the position
     * @return an updated copy of the source {@link BranchNode} array
     */
    static <T> T[] inserted (final T[] src, final T[] dst, final T t, final int position) {
        System.arraycopy (src, 0, dst, 0, position);
        System.arraycopy (src, position, dst, position + 1, src.length - position);
        dst [position] = t;
        return dst;
    }

    /**
     * Returns a copy of an array with a removed value at a certain position.
     *
     * @param src
     *            the source array
     * @param dst
     *            the destination array
     * @param position
     *            the position
     * @return an updated copy of the source array
     */
    static <T> T[] removed (final T[] src, final T[] dst, final int position) {
        System.arraycopy (src, 0, dst, 0, position);
        System.arraycopy (src, position + 1, dst, position, src.length - position - 1);
        return dst;
    }

    /**
     * Gets the flag value and insert position for an hashcode, level & bitmap.
     *
     * @param hc
     *            the hashcode value
     * @param level
     *            the level (in bit progression)
     * @param bitmap
     *            the current {@link CNode}'s bitmap.
     * @param w
     *            the fan width (in bits)
     * @return a {@link FlagPos}'s instance for the specified hashcode, level &
     *         bitmap.
     */
    static FlagPos flagPos (final int hc, final int level, final long bitmap, final int w) {
        final long flag = flag (hc, level, w);
        final int pos = Long.bitCount (flag - 1 & bitmap);
        return new FlagPos (flag, pos);
    }

    /**
     * Gets the flag value for an hashcode level.
     *
     * @param hc
     *            the hashcode value
     * @param level
     *            the level (in bit progression)
     * @param w
     *            fan width (in bits)
     * @return the flag value
     */
    static long flag (final int hc, final int level, final int w) {
        final int bitsRemaining = Math.min (w, 32 - level);
        final int subHash = hc >> level & (1 << bitsRemaining) - 1;
        final long flag = 1L << subHash;
        return flag;
    }

    static enum ConstraintType {
        NONE,
        PUT_IF_ABSENT,
        REPLACE_IF_MAPPED_TO,
        REPLACE_IF_MAPPED,
        REMOVE_IF_MAPPED_TO,
    }

    static class Constraint<V> {
        public Constraint (final ConstraintType type, final V to) {
            this.type = type;
            this.to = to;
        }

        public final ConstraintType type;
        public final V to;
    }

    static enum ResultType {
        FOUND,
        NOTFOUND,
        REJECTED,
        RESTART
    }

    static class Result<V> {
        public Result (final ResultType type, final V result) {
            this.type = type;
            this.result = result;
        }

        public final V result;
        public final ResultType type;
    }

    /**
     * A Marker interface for what can be in an INode (CNode or SNode)
     */
    static interface MainNode {
    }

    /**
     * A Marker interface for what can be in a CNode array. (INode or SNode)
     */
    static interface BranchNode {
    }

    /**
     * A single node in the trie, why may contain several objects who share the
     * same hashcode.
     */
    static interface SNode<K, V> extends BranchNode {
        /**
         * Get the hashcode
         *
         * @return the hashcode
         */
        int hash ();

        /**
         * Gets an Object associated with the given key
         *
         * @param k
         *            a key {@link Object}
         * @return its associated value
         */
        V get (K k);

        /**
         * Sets a mapping and returns a modified {@link SNode} copy.
         *
         * @param k
         *            the key {@link Object}
         * @param v
         *            the value {@link Object}
         * @return the copy of this {@link SNode} with the updated mapping
         */
        SNode<K, V> put (K k, V v);

        /**
         * Removes a mapping and returns a modified {@link SNode} copy
         * <p>
         * This method only works on an existing mapping, make sure there is
         * one, before calling this method.
         *
         * @param k
         *            the key {@link Object}
         * @return the copy of this {@link SNode} with the updated removal
         */
        SNode<K, V> removed (K k);

        /**
         * @return a copied {@link TNode} for this instance.
         */
        TNode<K, V> tombed ();

        /**
         * Gets the next {@link KeyValueNode} instance following the current
         * one, or the first one if the current one is null.
         *
         * @param current
         *            the current {@link KeyValueNode} instance
         * @return the next {@link KeyValueNode} in this {@link SNode}, null, if
         *         no more {@link SNode} is after this one.
         */
        KeyValueNode<K, V> next (KeyValueNode<K, V> current);
    }

    static interface TNode<K, V> extends MainNode {
        SNode<K, V> untombed ();
    }

    /**
     * A CAS-able Node which may reference either a CNode or and SNode
     */
    static class INode implements BranchNode {
        /**
         * Builds an {@link INode} instance
         *
         * @param n
         *            a {@link MainNode}
         */
        public INode (final MainNode n) {
            INODE_UPDATER.set (this, n);
        }

        /**
         * Gets the {@link MainNode} instance this {@link INode} contains
         *
         * @return the {@link MainNode}
         */
        public MainNode getMain () {
            return this.main;
        }

        /**
         * Compare and set the {@link MainNode} instance of this {@link INode}
         *
         * @param expected
         *            the expected {@link MainNode} instance
         * @param update
         *            the updated {@link MainNode} instance
         * @return true if it sets
         */
        public boolean casMain (final MainNode expected, final MainNode update) {
            return INODE_UPDATER.compareAndSet (this, expected, update);
        }

        /**
         * Atomic Updater for the INode.main field
         */
        private static final AtomicReferenceFieldUpdater<INode, MainNode> INODE_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater (INode.class, MainNode.class, "main");
        /**
         * The {@link MainNode} instance
         */
        private volatile MainNode main;
    }

    /**
     * A Node that may contain sub-nodes.
     */
    static class CNode<K, V> implements MainNode {
        /**
         * Builds a copy of this {@link CNode} instance where a sub-node
         * designated by a position has been added .
         *
         * @param flagPos
         *            a {@link FlagPos} instance
         * @param snode
         *            a {@link SNode} instance
         * @return a copy of this {@link CNode} instance with the inserted node.
         */
        public CNode<K, V> inserted (final FlagPos flagPos, final SNode<K, V> snode) {
            final BranchNode[] narr = ConcurrentHashTrie.inserted (this.array,
                    new BranchNode [this.array.length + 1],
                    snode,
                    flagPos.position);
            return new CNode<K, V> (narr, flagPos.flag | this.bitmap);
        }

        /**
         * Builds a copy of this {@link CNode} instance where a sub
         * {@link BranchNode} designated by a position has been replaced by
         * another one.
         *
         * @param position
         *            an integer position
         * @param bn
         *            a {@link BranchNode} instance
         * @return a copy of this {@link CNode} instance with the updated node.
         */
        public CNode<K, V> updated (final int position, final BranchNode bn) {
            final BranchNode[] narr = ConcurrentHashTrie.updated (this.array,
                    new BranchNode [this.array.length],
                    bn,
                    position);
            return new CNode<K, V> (narr, this.bitmap);
        }

        /**
         * Builds a copy of this {@link CNode} instance where a sub-node
         * designated by flag & a position has been removed.
         *
         * @param flagPos
         *            a {@link FlagPos} instance
         * @return a copy of this {@link CNode} instance where where a sub-node
         *         designated by flag & a position has been removed.
         */
        public CNode<K, V> removed (final FlagPos flagPos) {
            final BranchNode[] narr = ConcurrentHashTrie.removed (this.array,
                    new BranchNode[this.array.length - 1],
                    flagPos.position);
            return new CNode<K, V> (narr, this.bitmap ^ flagPos.flag);
        }

        /**
         * Builds a copy of the current node.
         *
         * @return a {@link CNode} copy
         */
        public CNode<K, V> copied () {
            final BranchNode[] narr = new BranchNode[this.array.length];
            System.arraycopy (this.array, 0, narr, 0, this.array.length);
            return new CNode<K, V> (narr, this.bitmap);
        }

        /**
         * Builds an empty {@link CNode} instance
         */
        CNode () {
            this.array = new BranchNode[] {};
            this.bitmap = 0L;
        }

        /**
         * Builds a {@link CNode} instance from a single {@link SNode} instance
         *
         * @param sNode
         *            a {@link SNode} instance
         * @param width
         *            the width (in power-of-two exponents)
         */
        CNode (final SNode<K, V> sNode, final int width) {
            final long flag = ConcurrentHashTrie.flag (sNode.hash (), 0, width);
            this.array = new BranchNode[] { sNode };
            this.bitmap = flag;
        }

        /**
         * Builds a {@link CNode} instance from two {@link SNode} objects
         *
         * @param sn1
         *            a first {@link SNode} instance
         * @param sn2
         *            a second {@link SNode} instance
         * @param level
         *            the current level (in bit progression)
         * @param width
         *            the width (in power-of-two exponents)
         */
        CNode (final SNode<K, V> sn1, final SNode<K, V> sn2, final int level, final int width) {
            final int h1 = sn1.hash ();
            final int h2 = sn2.hash ();
            final long flag1 = ConcurrentHashTrie.flag (h1, level, width);
            final long flag2 = ConcurrentHashTrie.flag (h2, level, width);
            if (flag1 != flag2) {
                // Make sure the two values are comparable by adding Long.MIN_VALUE so that
                // indexes 0 & -1 are written in the correct order : 0 and then -1
                if (flag1 + Long.MIN_VALUE < flag2 + Long.MIN_VALUE) {
                    this.array = new BranchNode[] { sn1, sn2 };
                } else {
                    this.array = new BranchNode[] { sn2, sn1 };
                }
            } else {
                // Else goes down one level and create sub nodes
                this.array = new BranchNode[] {
                        new INode (
                                new CNode<K, V> (sn1, sn2, level+width, width))};
            }
            this.bitmap = flag1 | flag2;
        }

        /**
         * Builds a {@link CNode} from an array of {@link BranchNode} and its
         * computed bitmap.
         *
         * @param array
         *            the {@link BranchNode} array
         * @param bitmap
         *            the bitmap
         */
        CNode (final BranchNode[] array, final long bitmap) {
            this.array = array;
            this.bitmap = bitmap;
        }

        /**
         * The internal {@link BranchNode} array.
         */
        public final BranchNode[] array;

        /**
         * The bitmap of the currently allocated objects.
         */
        public final long bitmap;
    }

    static class KeyValueNode<K, V> {
        /**
         * Builds a {@link KeyValueNode} instance
         *
         * @param k
         *            its {@link Object} key
         * @param v
         *            its {@link Object} value
         */
        KeyValueNode (final K k, final V v) {
            this.key = k;
            this.value = v;
        }

        /**
         * The key object
         */
        protected final K key;

        /**
         * The value object
         */
        protected final V value;
    }

    /**
     * A Single Node class, holds a key, a value & a tomb flag.
     */
    static class SingletonSNode<K, V> extends KeyValueNode<K, V> implements SNode<K, V> {
        /**
         * Builds a {@link SingletonSNode} instance
         *
         * @param k
         *            its {@link Object} key
         * @param v
         *            its {@link Object} value
         */
        SingletonSNode (final K k, final V v) {
            super (k, v);
        }

        @Override
        public int hash () {
            return ConcurrentHashTrie.hash (this.key);
        }

        @Override
        public TNode<K, V> tombed () {
            return new SingletonTNode<K, V> (this.key, this.value);
        }

        @Override
        public V get (final Object k) {
            if (this.key.equals (k)) {
                return this.value;
            } else {
                return null;
            }
        }

        @Override
        public SNode<K, V> put (final K k, final V v) {
            if (this.key.equals (k)) {
                return new SingletonSNode<K, V> (k, v);
            } else {
                @SuppressWarnings("unchecked")
                final KeyValueNode<K, V>[] array = new KeyValueNode[] {
                        new KeyValueNode<K, V> (this.key, this.value),
                        new KeyValueNode<K, V> (k, v), };
                return new MultiSNode<K, V> (array);
            }
        }

        @Override
        public SNode<K, V> removed (final Object k) {
            return null;
        }

        @Override
        public KeyValueNode<K, V> next (final KeyValueNode<K, V> current) {
            return current == null ? this : null;
        }
    }

    /**
     * A Tombed node instance
     */
    static class SingletonTNode<K, V> extends KeyValueNode<K, V> implements TNode<K, V> {
        /**
         * Builds a {@link SingletonTNode} instance
         *
         * @param k
         *            its {@link Object} key
         * @param v
         *            its {@link Object} value
         */
        SingletonTNode (final K k, final V v) {
            super (k, v);
        }

        /**
         * @return a copied {@link SNode} of this instance
         */
        @Override
        public SNode<K, V> untombed () {
            return new SingletonSNode<K, V> (this.key, this.value);
        }
    }

    /**
     * Base class for multiple SNode & TNode implementations
     */
    static class BaseMultiNode<K, V> {
        /**
         * Builds a {@link BaseMultiNode} instance
         *
         * @param array
         *            a {@link KeyValueNode} instance
         */
        public BaseMultiNode (final KeyValueNode<K, V>[] array) {
            this.content = array;
        }

        protected final KeyValueNode<K, V>[] content;
    }

    /**
     * A Multiple key/values SNode
     */
    static class MultiSNode<K, V> extends BaseMultiNode<K, V> implements SNode<K, V> {
        /**
         * Builds a {@link MultiSNode} instance
         *
         * @param content
         *            a {@link KeyValueNode} content array
         */
        public MultiSNode (final KeyValueNode<K, V>[] content) {
            super (content);
        }

        @Override
        public int hash () {
            return ConcurrentHashTrie.hash (this.content [0].key);
        }

        @Override
        public V get (final K k) {
            for (int i = 0; i < this.content.length; i++) {
                final KeyValueNode<K, V> n = this.content [i];
                if (n.key.equals (k)) {
                    return n.value;
                }
            }
            return null;
        }

        @Override
        public SNode<K, V> put (final K k, final V v) {
            int index = -1;
            for (int i = 0; i < this.content.length; i++) {
                final KeyValueNode<K, V> n = this.content [i];
                if (n.key.equals (k)) {
                    index = i;
                    break;
                }
            }

            final KeyValueNode<K, V>[] array;
            if (index >= 0) {
                @SuppressWarnings("unchecked")
                final KeyValueNode<K, V>[] ar = ConcurrentHashTrie.updated (
                        this.content,
                        new KeyValueNode [this.content.length],
                        new KeyValueNode<K, V> (k, v),
                        index);
                array = ar;
            } else {
                @SuppressWarnings("unchecked")
                final KeyValueNode<K, V>[] ar = ConcurrentHashTrie.inserted (
                        this.content,
                        new KeyValueNode [this.content.length + 1],
                        new KeyValueNode<K, V> (k, v),
                        this.content.length);
                array = ar;
            }

            return new MultiSNode<K, V> (array);
        }

        @Override
        public SNode<K, V> removed (final Object k) {
            for (int i = 0; i < this.content.length; i++) {
                final KeyValueNode<K, V> n = this.content [i];
                if (n.key.equals (k)) {
                    if (2 == this.content.length) {
                        final KeyValueNode<K, V> kvn = this.content [(i + 1) % 2];
                        return new SingletonSNode<K, V> (kvn.key, kvn.value);
                    } else {
                        @SuppressWarnings("unchecked")
                        final KeyValueNode<K, V>[] narr = ConcurrentHashTrie.removed (
                                this.content,
                                new KeyValueNode [this.content.length - 1],
                                i);
                        return new MultiSNode<K, V> (narr);
                    }
                }
            }
            throw new RuntimeException ("Key not found:" + k);
        }

        @Override
        public TNode<K, V> tombed () {
            return new MultiTNode<K, V> (this.content);
        }

        @Override
        public KeyValueNode<K, V> next (final KeyValueNode<K, V> current) {
            if (null == current) {
                return this.content [0];
            } else {
                boolean found = false;
                for (int i = 0; i < this.content.length; i++) {
                    final KeyValueNode<K, V> kvn = this.content [i];
                    if (found) {
                        return kvn;
                    }
                    if (kvn.key.equals (current.key)) {
                        found = true;
                    }
                }
            }
            return null;
        }
    }

    /**
     * A Multiple values {@link TNode} implementation
     */
    static class MultiTNode<K, V> extends BaseMultiNode<K, V> implements TNode<K, V> {

        /**
         * Builds a {@link MultiTNode} instance
         *
         * @param array
         *            a {@link KeyValueNode} array
         */
        public MultiTNode (final KeyValueNode<K, V>[] array) {
            super (array);
        }

        @Override
        public SNode<K, V> untombed () {
            return new MultiSNode<K, V> (this.content);
        }
    }

    /**
     * The result of a
     * {@link ConcurrentHashTrie#flagPos(int, int, long, int)} call. Contains
     * a single bit flag & a position
     */
    static class FlagPos {
        /**
         * Builds a {@link FlagPos} instance
         *
         * @param flag
         *            the bit flag
         * @param position
         *            the array location.
         */
        FlagPos (final long flag, final int position) {
            this.flag = flag;
            this.position = position;
        }

        /**
         * A single bit flag that may bit compared to a {@link CNode}'s bitmap.
         */
        public final long flag;
        /**
         * Its position in the array
         */
        public final int position;
    }

    private static final Constraint<Object> NO_CONSTRAINT = new Constraint<Object> (ConstraintType.NONE, null);
}