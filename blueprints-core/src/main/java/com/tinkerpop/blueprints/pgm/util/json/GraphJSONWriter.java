package com.tinkerpop.blueprints.pgm.util.json;


import com.tinkerpop.blueprints.pgm.Edge;
import com.tinkerpop.blueprints.pgm.Graph;
import com.tinkerpop.blueprints.pgm.Vertex;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.codehaus.jettison.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class GraphJSONWriter {
    private final Graph graph;

    /**
     * @param graph the Graph to pull the data from
     */
    public GraphJSONWriter(final Graph graph) {
        this.graph = graph;
    }

    /**
     * Write the data in a Graph to a JSON OutputStream.
     *
     * @param jsonOutputStream the JSON OutputStream to write the Graph data to
     * @param edgePropertyKeys the keys of the edge elements to write to JSON
     * @param vertexPropertyKeys the keys of the vertex elements to write to JSON
     * @param showTypes determines if the types should be written into the JSON
     * @throws org.codehaus.jettison.json.JSONException thrown if there is an error generating the JSON data
     */
    public void outputGraph(final OutputStream jsonOutputStream, final List<String> edgePropertyKeys, final List<String> vertexPropertyKeys, final boolean showTypes) throws JSONException {

        try {
            JsonFactory jsonFactory = new MappingJsonFactory();
            JsonGenerator jg = jsonFactory.createJsonGenerator(jsonOutputStream);

            jg.writeStartObject();

            jg.writeArrayFieldStart(JSONTokens.VERTICES);
            for (Vertex v : this.graph.getVertices()) {
                jg.writeTree(JSONWriter.createJSONElementAsObjectNode(v, vertexPropertyKeys, showTypes));
            }

            jg.writeEndArray();

            jg.writeArrayFieldStart(JSONTokens.EDGES);
            for (Edge e : this.graph.getEdges()) {
                jg.writeTree(JSONWriter.createJSONElementAsObjectNode(e, edgePropertyKeys, showTypes));
            }
            jg.writeEndArray();

            jg.writeEndObject();

            jg.flush();
            jg.close();


        } catch (IOException ioe) {
            throw new JSONException(ioe);
        }
    }

    /**
     * Write the data in a Graph to a JSON OutputStream. All keys are written to JSON with types not shown.
     *
     * @param graph the graph to serialize to JSON
     * @param jsonOutputStream the JSON OutputStream to write the Graph data to
     * @throws org.codehaus.jettison.json.JSONException thrown if there is an error generating the JSON data
     */
    public static void outputGraph(final Graph graph, final OutputStream jsonOutputStream) throws JSONException {
        GraphJSONWriter writer = new GraphJSONWriter(graph);
        writer.outputGraph(jsonOutputStream, null, null, false);
    }

    /**
     * Write the data in a Graph to a JSON OutputStream. All keys are written to JSON.
     *
     * @param graph the graph to serialize to JSON
     * @param jsonOutputStream the JSON OutputStream to write the Graph data to
     * @param showTypes determines if the types should be written into the JSON
     * @throws org.codehaus.jettison.json.JSONException thrown if there is an error generating the JSON data
     */
    public static void outputGraph(final Graph graph, final OutputStream jsonOutputStream, final boolean showTypes) throws JSONException {
        GraphJSONWriter writer = new GraphJSONWriter(graph);
        writer.outputGraph(jsonOutputStream, null, null, showTypes);
    }

    /**
     * Write the data in a Graph to a JSON OutputStream.
     *
     * @param graph the graph to serialize to JSON
     * @param jsonOutputStream the JSON OutputStream to write the Graph data to
     * @param edgePropertyKeys the keys of the edge elements to write to JSON
     * @param vertexPropertyKeys the keys of the vertex elements to write to JSON
     * @param showTypes determines if the types should be written into the JSON
     * @throws org.codehaus.jettison.json.JSONException thrown if there is an error generating the JSON data
     */
    public static void outputGraph(final Graph graph, final OutputStream jsonOutputStream, final List<String> edgePropertyKeys, final List<String> vertexPropertyKeys, final boolean showTypes) throws JSONException {
        GraphJSONWriter writer = new GraphJSONWriter(graph);
        writer.outputGraph(jsonOutputStream, edgePropertyKeys, vertexPropertyKeys, showTypes);
    }

}
