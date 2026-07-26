package org.randomizer.algorithm;

import org.jgrapht.Graph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SortAlgorithms {

    private SortAlgorithms() {
    }

    public static <V, E> Set<V> ancestorsOf(
            Graph<V, E> graph,
            V target
    ) {
        List<V> open = new ArrayList<>();
        open.add(target);
        Set<V> in_open = new HashSet<>();
        int curr_ind = 0;

        while (curr_ind < open.size()) {
            V curr_node = open.get(curr_ind);

            for (E prereq : graph.incomingEdgesOf(curr_node)) {
                V prereq_node = graph.getEdgeSource(prereq);

                if (!in_open.contains(prereq_node)) {
                    open.add(prereq_node);
                    in_open.add(prereq_node);
                }
            }

            curr_ind += 1;
        }

        return in_open;
    }
}
