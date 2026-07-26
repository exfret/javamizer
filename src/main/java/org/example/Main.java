package org.example;

import org.jgrapht.graph.DirectedPseudograph;
import org.randomizer.display.GraphFrame;
import org.randomizer.graph.Edge;
import org.randomizer.graph.GraphReader;
import org.randomizer.graph.Node;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        GraphFrame.show(GraphReader.readFile("data/mini_graph.json"));
    }
}