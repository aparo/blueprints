package com.tinkerpop.blueprints.pgm.oupls.sail;

import com.tinkerpop.blueprints.pgm.CloseableSequence;
import com.tinkerpop.blueprints.pgm.Edge;
import com.tinkerpop.blueprints.pgm.Element;
import com.tinkerpop.blueprints.pgm.Index;
import com.tinkerpop.blueprints.pgm.IndexableGraph;
import com.tinkerpop.blueprints.pgm.TransactionalGraph;
import com.tinkerpop.blueprints.pgm.Vertex;
import com.tinkerpop.blueprints.pgm.oupls.GraphSource;
import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.sail.NotifyingSailConnection;
import org.openrdf.sail.SailException;
import org.openrdf.sail.helpers.NotifyingSailBase;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An RDF storage interface for any graph database with a Blueprints IndexableGraph implementation.  It models
 * RDF graphs as property graphs which can be easily traversed and manipulated with other Blueprints-compatible tools.
 * At the same time, it can be used with OpenRDF-based tools to power a SPARQL endpoint, an RDF reasoner, etc.
 * <p/>
 * RDF resources are stored as vertices, RDF statements as edges using the Blueprints default (automatic) indices.
 * Namespaces are stored at a special vertex with the id "urn:com.tinkerpop.blueprints.sail:namespaces".
 * <p/>
 * This Sail is as transactional as the underlying graph database: if the provided Graph implements TransactionalGraph
 * and is in manual transaction mode, then the SailConnection's commit and rollback methods will be used correspondingly.
 * <p/>
 * Retrieval of RDF statements from the store involves both "index-based" and "graph-based" matching, as follows.
 * For each new statement edge which is added to the store, "p" (predicate), "c" (context), and "pc" (predicate and context)
 * property values are added and indexed.  These allow the statement to be quickly retrieved in a query where only the
 * predicate and/or context is specified.  However, BlueprintsSail will additionally index on any triple pattern which
 * is supplied to the constructor, boosting query reactivity at the expense of additional storage overhead.
 * For example, if a "so" pattern is supplied, each new statement edge will also receive an "so" property value which stores the
 * combination of subject and object of the statement.  A subsequent call such as <code>getStatements(john, null, jane)</code> will
 * match both values simultaneously.  This may succeed more quickly than the corresponding graph-based match, which picks
 * either the john or jane vertex as a starting point and filters on adjacent edges.  Graph-based matches are used for
 * all of the triple patterns s,o,sp,so,sc,po,oc,spo,spc,soc,poc,spoc which have not been explicitly flagged for
 * index-based matching.
 * <p/>
 * Note: this implementation attaches no semantics to Vertex and Edge IDs, so as to be compatible with Graph
 * implementations which do no not allow IDs to be chosen.
 *
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public class GraphSail extends NotifyingSailBase implements GraphSource {
    public static final String SEPARATOR = " ";

    public static final String
            PREDICATE_PROP = "p",
            CONTEXT_PROP = "c";

    public static final char
            URI_PREFIX = 'U',
            BLANK_NODE_PREFIX = 'B',
            PLAIN_LITERAL_PREFIX = 'P',
            TYPED_LITERAL_PREFIX = 'T',
            LANGUAGE_TAG_LITERAL_PREFIX = 'L',
            NULL_CONTEXT_PREFIX = 'N';

    public static final Pattern INDEX_PATTERN = Pattern.compile("s?p?o?c?");

    public static final String
            BNODE = "bnode",
            INFERRED = "inferred",
            KIND = "kind",
            LANG = "lang",
            LITERAL = "literal",
            TYPE = "type",
            URI = "uri",
            VALUE = "value";

    public static final String NULL_CONTEXT_NATIVE = "" + GraphSail.NULL_CONTEXT_PREFIX;

    private static final String[][] ALTERNATIVES = {{"s", ""}, {"p", ""}, {"o", ""}, {"c", ""}, {"sp", "s", "p"}, {"so", "s", "o"}, {"sc", "s", "c"}, {"po", "o", "p"}, {"pc", "p", "c"}, {"oc", "o", "c"}, {"spo", "so", "sp", "po"}, {"spc", "sc", "sp", "pc"}, {"soc", "so", "sc", "oc"}, {"poc", "po", "oc", "pc"}, {"spoc", "spo", "soc", "spc", "poc"},};

    private static final String NAMESPACES_VERTEX_ID = "urn:com.tinkerpop.blueprints.pgm.oupls.sail:namespaces";

    // Index name
    private static final String VALUES = "values";

    private final DataStore store = new DataStore();

    /**
     * Create a new RDF store using the provided Blueprints graph.  Default edge indices will be used.
     *
     * @param graph the storage layer.  If the provided graph implements TransactionalGraph and is in manual transaction
     *              mode, then this Sail will also be transactional.
     */
    public GraphSail(final IndexableGraph graph) {
        this(graph, "p,c,pc");
        //this(graph, "s,p,o,c,sp,so,sc,po,pc,oc,spo,spc,soc,poc,spoc");
    }

    /**
     * Create a new RDF store using the provided Blueprints graph.  Additionally, create edge indices for the provided
     * triple patterns (potentially speeding up certain queries, while increasing storage overhead).
     *
     * @param graph           the storage layer.  If the provided graph implements TransactionalGraph and is in manual transaction
     *                        mode, then this Sail will also be transactional.
     * @param indexedPatterns a comma-delimited list of triple patterns for index-based statement matching.  Only p,c are required,
     *                        while the default patterns are p,c,pc.
     */
    public GraphSail(final IndexableGraph graph, final String indexedPatterns) {
        //if (graph instanceof TransactionalGraph)
        //    ((TransactionalGraph) graph).setTransactionMode(TransactionalGraph.Mode.AUTOMATIC);
        //printGraphInfo(graph);

        store.sail = this;
        store.graph = graph;

        // For now, use the default EDGES and VERTICES indices, which *must exist* in Blueprints and are automatically indexed.
        // Think harder about collision issues (if someone hands a non-empty, non-Sail graph to this constructor) later on.
        store.edges = graph.getIndex(Index.EDGES, Edge.class);
        store.values = getOrCreateValuesIndex(graph);

        store.manualTransactions = store.graph instanceof TransactionalGraph && TransactionalGraph.Mode.MANUAL == ((TransactionalGraph) store.graph).getTransactionMode();

        store.namespaces = store.getReferenceVertex();
        if (null == store.namespaces) {
            if (store.manualTransactions) {
                ((TransactionalGraph) graph).startTransaction();
            }
            try {
                // FIXME: with FAKE_VERTEX_IDS, an extra "namespace" called "value" is present.  Perhaps namespaces
                // should be given individual nodes, rather than being encapsulated in properties of the namespaces node.
                store.namespaces = store.addVertex(NAMESPACES_VERTEX_ID);
            } finally {
                if (store.manualTransactions) {
                    ((TransactionalGraph) graph).stopTransaction(TransactionalGraph.Conclusion.SUCCESS);
                }
            }
        }

        store.matchers[0] = new TrivialMatcher(graph);

        parseTripleIndices(indexedPatterns);
        assignUnassignedTriplePatterns();
    }

    private Index<Vertex> getOrCreateValuesIndex(final IndexableGraph graph) {
        for (Index<? extends Element> index : graph.getIndices()) {
            if (index.getIndexName().equals(VALUES)) {
                return (Index<Vertex>) index;
            }
        }

        Set<String> keys = new HashSet<String>();
        keys.add(VALUE);
        return graph.createAutomaticIndex(VALUES, Vertex.class, keys);
    }

    /*
    private void printGraphInfo(final IndexableGraph graph) {
        boolean trans = graph instanceof TransactionalGraph;

        StringBuilder sb = new StringBuilder("graph ").append(graph).append("\n");
        sb.append("\ttransactional: ").append(trans).append("\n");
        if (trans) {
            sb.append("\tmode: ").append(((TransactionalGraph) graph).getTransactionMode()).append("\n");
        }

        System.out.println(sb.toString());
    }
    //*/

    public IndexableGraph getGraph() {
        return this.store.getGraph();
    }

    public void setDataDir(final File file) {
        throw new UnsupportedOperationException();
    }

    public File getDataDir() {
        throw new UnsupportedOperationException();
    }

    public void initializeInternal() throws SailException {
        // Do nothing.
    }

    public void shutDownInternal() throws SailException {
        store.graph.shutdown();
    }

    public boolean isWritable() throws SailException {
        // For now, we assume the store is writable.
        return true;
    }

    public NotifyingSailConnection getConnectionInternal() throws SailException {
        return new GraphSailConnection(store);
    }

    public ValueFactory getValueFactory() {
        return store.valueFactory;
    }

    /**
     * Enables or disables the use of efficient, short-lived statements in the iterators returned by
     * <code>GraphSailConnection.getStatements()</code> and <code>GraphSailConnection.evaluate()</code>.
     * This feature is disabled by default, and in typical usage scenarios, Java compiler optimization makes it superfluous.
     * However, it potentially confers a performance advantage when a single thread consumes the iterator,
     * inspecting and then immediately discarding each statement.
     *
     * @param flag whether to use volatile statements.
     *             When this method is called, only subsequently created iterators are affected.
     */
    public void useVolatileStatements(final boolean flag) {
        store.volatileStatements = flag;
    }

    /**
     * Enables or disables enforcement of a unique statements policy,
     * which ensures that no new statement will be added which is identical
     * (in all of its subject, predicate, object and context) to an existing statement.
     * If enabled, this policy will first remove any existing statements identical to the to-be-added statement,
     * before adding the latter statement.
     *
     * @param flag whether this policy should be enforced
     */
    public void enforceUniqueStatements(final boolean flag) {
        store.uniqueStatements = flag;
    }

    public String toString() {
        String type = store.graph.getClass().getSimpleName().toLowerCase();
        return "graphsail[" + type + "]";
    }

    ////////////////////////////////////////////////////////////////////////////

    /**
     * A context object which is shared between the Blueprints Sail and its connections.
     */
    class DataStore {
        public IndexableGraph graph;
        public NotifyingSailBase sail;

        // We don't need a special ValueFactory implementation.
        public final ValueFactory valueFactory = new ValueFactoryImpl();

        public final Collection<IndexingMatcher> indexers = new LinkedList<IndexingMatcher>();

        // A triple pattern matcher for each spoc combination
        public final Matcher[] matchers = new Matcher[16];

        public boolean manualTransactions;
        public boolean volatileStatements = false;
        public boolean uniqueStatements = true;

        public Index<Vertex> values;
        public Index<Edge> edges;

        public Vertex namespaces;

        public Vertex getReferenceVertex() {
            //System.out.println("value = " + value);
            CloseableSequence<Vertex> i = values.get(VALUE, NAMESPACES_VERTEX_ID);
            try {
                return i.hasNext() ? i.next() : null;
            } finally {
                i.close();
            }
        }

        public Vertex addVertex(final Value value) {
            Vertex v = graph.addVertex(null);

            if (value instanceof URI) {
                v.setProperty(KIND, URI);
                v.setProperty(VALUE, value.stringValue());
            } else if (value instanceof Literal) {
                Literal l = (Literal) value;
                v.setProperty(KIND, LITERAL);
                v.setProperty(VALUE, l.getLabel());
                if (null != l.getDatatype()) {
                    v.setProperty(TYPE, l.getDatatype().stringValue());
                }
                if (null != l.getLanguage()) {
                    v.setProperty(LANG, l.getLanguage());
                }
            } else if (value instanceof BNode) {
                BNode b = (BNode) value;
                v.setProperty(KIND, BNODE);
                v.setProperty(VALUE, b.getID());
            } else {
                throw new IllegalStateException("value of unexpected type: " + value);
            }

            return v;
        }

        public Vertex findVertex(final Value value) {
            CloseableSequence<Vertex> i = values.get(VALUE, value.stringValue());
            try {
                while (i.hasNext()) {
                    Vertex v = i.next();

                    if (matches(v, value)) {
                        return v;
                    }
                }

                return null;
            } finally {
                i.close();
            }
        }

        public boolean matches(final Vertex vertex,
                               final Value value) {
            String kind = (String) vertex.getProperty(KIND);
            String val = (String) vertex.getProperty(VALUE);
            if (value instanceof URI) {
                return kind.equals(URI) && val.equals(value.stringValue());
            } else if (value instanceof Literal) {
                if (kind.equals(LITERAL)) {
                    if (!val.equals(((Literal) value).getLabel())) {
                        return false;
                    }

                    String type = (String) vertex.getProperty(TYPE);
                    String lang = (String) vertex.getProperty(LANG);

                    URI vType = ((Literal) value).getDatatype();
                    String vLang = ((Literal) value).getLanguage();

                    return null == type && null == vType && null == lang && null == vLang
                            || null != type && null != vType && type.equals(vType.stringValue())
                            || null != lang && null != vLang && lang.equals(vLang);

                } else {
                    return false;
                }
            } else if (value instanceof BNode) {
                return kind.equals(BNODE) && ((BNode) value).getID().equals(val);
            } else {
                throw new IllegalStateException("value of unexpected kind: " + value);
            }
        }

        public Vertex addVertex(final String id) {
            Vertex v = graph.addVertex(null);
            //vertices.put(VALUE, id, store.namespaces);
            v.setProperty(VALUE, id);
            return v;
        }

        public String getValueOf(final Vertex v) {
            return (String) v.getProperty(VALUE);
        }

        public IndexableGraph getGraph() {
            return this.graph;
        }

        public String valueToNative(final Value value) {
            if (value instanceof Resource) {
                return resourceToNative((Resource) value);
            } else if (value instanceof Literal) {
                return literalToNative((Literal) value);
            } else {
                throw new IllegalStateException("Value has unfamiliar type: " + value);
            }
        }

        public String resourceToNative(final Resource value) {
            if (value instanceof URI) {
                return uriToNative((URI) value);
            } else if (value instanceof BNode) {
                return bnodeToNative((BNode) value);
            } else {
                throw new IllegalStateException("Resource has unfamiliar type: " + value);
            }
        }

        public String uriToNative(final URI value) {
            return GraphSail.URI_PREFIX + GraphSail.SEPARATOR + value.toString();
        }

        public String bnodeToNative(final BNode value) {
            return GraphSail.BLANK_NODE_PREFIX + GraphSail.SEPARATOR + value.getID();
        }

        public String literalToNative(final Literal literal) {
            URI datatype = literal.getDatatype();

            if (null == datatype) {
                String language = literal.getLanguage();

                if (null == language) {
                    return GraphSail.PLAIN_LITERAL_PREFIX + GraphSail.SEPARATOR + literal.getLabel();
                } else {
                    return GraphSail.LANGUAGE_TAG_LITERAL_PREFIX + GraphSail.SEPARATOR + language + GraphSail.SEPARATOR + literal.getLabel();
                }
            } else {
                // FIXME
                return "" + GraphSail.TYPED_LITERAL_PREFIX + GraphSail.SEPARATOR + datatype + GraphSail.SEPARATOR + literal.getLabel();
            }
        }
    }

    private void parseTripleIndices(final String tripleIndexes) {
        if (null == tripleIndexes) {
            throw new IllegalArgumentException("index list, if supplied, must be non-null");
        }

        Set<String> u = new HashSet<String>();

        String[] a = tripleIndexes.split(",");
        if (0 == a.length) {
            throw new IllegalArgumentException("index list, if supplied, must be non-empty");
        }
        for (String s : a) {
            u.add(s.trim());
        }

        // These two patterns are required for efficient operation.
        u.add("p");
        u.add("c");

        for (String s : u) {
            createIndexingMatcher(s);
        }
    }

    private void assignUnassignedTriplePatterns() {
        // As a first pass, fill in all suitable patterns (those containing
        // subject and/or object) not already assigned to indexing matchers,
        // with graph-based matchers.
        for (int i = 0; i < 16; i++) {
            if (null == store.matchers[i] && ((0 != (i & 0x1)) || (0 != (i & 0x4)))) {
                store.matchers[i] = new GraphBasedMatcher((0 != (i & 0x1)), (0 != (i & 0x2)), (0 != (i & 0x4)), (0 != (i & 0x8)), store);
            }
        }

        // Now fill in any remaining patterns with alternative indexing matchers.
        Matcher[] n = new Matcher[16];
        n[0] = store.matchers[0];
        for (String[] alts : ALTERNATIVES) {
            String p = alts[0];
            int i = indexFor(p);

            Matcher m = store.matchers[i];

            // if no matcher has been assigned for this pattern
            if (null == m) {
                // try primary alternatives in the order they are specified
                for (int j = 1; j < alts.length; j++) {
                    m = store.matchers[indexFor(alts[j])];
                    if (null != m) {
                        break;
                    }
                }

                // if no primary alternatives are assigned, choose the first secondary alternative
                if (null == m) {
                    m = n[1];
                }
            }

            n[i] = m;
        }

        System.arraycopy(n, 0, store.matchers, 0, 16);
    }

    private int indexFor(final boolean s, final boolean p, final boolean o, final boolean c) {
        int index = 0;

        if (s) {
            index |= 0x1;
        }
        if (p) {
            index |= 0x2;
        }
        if (o) {
            index |= 0x4;
        }
        if (c) {
            index |= 0x8;
        }

        return index;
    }

    private int indexFor(final String pattern) {
        boolean s = false, p = false, o = false, c = false;
        for (byte ch : pattern.getBytes()) {
            switch (ch) {
                case 's':
                    s = true;
                    break;
                case 'p':
                    p = true;
                    break;
                case 'o':
                    o = true;
                    break;
                case 'c':
                    c = true;
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        return indexFor(s, p, o, c);
    }

    private void createIndexingMatcher(final String pattern) {
        boolean s = false, p = false, o = false, c = false;
        for (byte ch : pattern.getBytes()) {
            switch (ch) {
                case 's':
                    s = true;
                    break;
                case 'p':
                    p = true;
                    break;
                case 'o':
                    o = true;
                    break;
                case 'c':
                    c = true;
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        int index = indexFor(s, p, o, c);
        IndexingMatcher m = new IndexingMatcher(s, p, o, c, store);
        store.matchers[index] = m;
        store.indexers.add(m);
    }

    private static void debugEdge(final Edge e) {
        System.out.println("edge " + e + ":");
        for (String key : e.getPropertyKeys()) {
            System.out.println("\t" + key + ":\t'" + e.getProperty(key) + "'");
        }
        System.out.println("\t[in vertex]: " + e.getInVertex());
        System.out.println("\t[out vertex]: " + e.getOutVertex());
    }

    private static void debugVertex(final Vertex v) {
        System.out.println("vertex " + v + ":");
        for (String key : v.getPropertyKeys()) {
            System.out.println("\t" + key + ":\t'" + v.getProperty(key) + "'");
        }
        Iterator<Edge> i;
        i = v.getInEdges().iterator();
        System.out.println("\t[in edges]:");
        while (i.hasNext()) {
            System.out.println("\t\t" + i.next());
        }
        i = v.getOutEdges().iterator();
        System.out.println("\t[out edges]:");
        while (i.hasNext()) {
            System.out.println("\t\t" + i.next());
        }
    }
}
