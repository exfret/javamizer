package org.example;

import org.jgrapht.graph.DirectedPseudograph;
import org.randomizer.display.GraphFrame;
import org.randomizer.graph.Edge;
import org.randomizer.graph.Node;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        DirectedPseudograph<Node, Edge> dpg = new DirectedPseudograph<>(Edge.class);

        Node node1 = new Node("One");
        Node node2 = new Node("Two");
        Node node3 = new Node("Three");

        dpg.addVertex(node1);
        dpg.addVertex(node2);
        dpg.addVertex(node3);

        dpg.addEdge(node1, node2, new Edge("One"));
        dpg.addEdge(node2, node3, new Edge("Two"));

        GraphFrame.show(dpg);
    }
}