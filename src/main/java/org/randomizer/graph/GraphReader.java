package org.randomizer.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jgrapht.graph.DirectedPseudograph;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

public class GraphReader {

    public static DirectedPseudograph<Node, Edge> readFile(String filename) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new File(filename));

            DirectedPseudograph<Node, Edge> graph = new DirectedPseudograph<>(Edge.class);
            Map<String, Node> byId = new HashMap<>();

            // pass 1: create every declared node
            for (JsonNode n : root.get("nodes")) {
                getOrCreate(graph, byId, key(n.get("id")));
            }

            // pass 2: one edge per prereq, prereq -> dependent
            for (JsonNode n : root.get("nodes")) {
                Node target = byId.get(key(n.get("id")));
                String op = n.path("op").asText("");   // "and" / "or" — the edge label
                for (JsonNode p : n.get("prereqs")) {
                    Node source = getOrCreate(graph, byId, key(p));  // handles missing_prereqs
                    graph.addEdge(source, target, new Edge(op));
                }
            }
            return graph;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read graph from " + filename, e);
        }
    }

    private static String key(JsonNode id) {
        return id.get(0).asText() + ":" + id.get(1).asText();   // "technology:automation"
    }

    private static Node getOrCreate(DirectedPseudograph<Node, Edge> g,
                                    Map<String, Node> byId, String key) {
        return byId.computeIfAbsent(key, k -> {
            Node node = new Node(k);
            g.addVertex(node);
            return node;
        });
    }

}
