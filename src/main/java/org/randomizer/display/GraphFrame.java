package org.randomizer.display;

import org.jgrapht.graph.DirectedPseudograph;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/**
 * Swing viewer for a JGraphT DirectedPseudograph.
 *
 * Handles the two things a pseudograph needs that naive drawing breaks on:
 *   - parallel edges (drawn as separate curved "lanes" between the same pair)
 *   - self-loops (drawn as loops above the node, nested if there are several)
 *
 * Usage:
 *   GraphFrame.show(graph, Node::getName, Route::getName);
 *
 * Nodes are draggable with the mouse.
 */
public class GraphFrame {

    /** Open a JFrame displaying the graph. */
    public static <V, E> void show(DirectedPseudograph<V, E> graph,
                                   Function<V, String> vertexName,
                                   Function<E, String> edgeName) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Graph Viewer");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.add(new GraphPanel<>(graph, vertexName, edgeName));
            frame.setSize(900, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    /** Convenience overload: calls getName() on vertices and edges via reflection. */
    public static <V, E> void show(DirectedPseudograph<V, E> graph) {
        show(graph, GraphFrame::reflectName, GraphFrame::reflectName);
    }

    private static String reflectName(Object o) {
        try {
            return String.valueOf(o.getClass().getMethod("getName").invoke(o));
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    // ------------------------------------------------------------------

    static class GraphPanel<V, E> extends JPanel {

        private static final int NODE_R = 28;          // node circle radius
        private static final double LANE_GAP = 26;     // separation between parallel edges
        private static final Color NODE_FILL   = new Color(0x2D6A9F);
        private static final Color NODE_BORDER = new Color(0x1B4266);
        private static final Color EDGE_COLOR  = new Color(0x555555);
        private static final Color LABEL_BG    = new Color(255, 255, 255, 210);

        private final DirectedPseudograph<V, E> graph;
        private final Function<V, String> vertexName;
        private final Function<E, String> edgeName;

        private final Map<V, Point2D.Double> pos = new IdentityHashMap<>();
        private V dragged = null;
        private boolean laidOut = false;

        GraphPanel(DirectedPseudograph<V, E> graph,
                   Function<V, String> vertexName,
                   Function<E, String> edgeName) {
            this.graph = graph;
            this.vertexName = vertexName;
            this.edgeName = edgeName;
            setBackground(Color.WHITE);

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    dragged = nodeAt(e.getX(), e.getY());
                }
                @Override public void mouseReleased(MouseEvent e) {
                    dragged = null;
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragged != null) {
                        pos.get(dragged).setLocation(e.getX(), e.getY());
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private V nodeAt(int x, int y) {
            for (Map.Entry<V, Point2D.Double> en : pos.entrySet()) {
                if (en.getValue().distance(x, y) <= NODE_R) return en.getKey();
            }
            return null;
        }

        /** Place vertices evenly on a circle. */
        private void layoutCircle() {
            List<V> vs = new ArrayList<>(graph.vertexSet());
            int n = vs.size();
            if (n == 0) return;
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            double r = Math.max(60, Math.min(cx, cy) - 90);
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n - Math.PI / 2;
                pos.put(vs.get(i), new Point2D.Double(
                        cx + r * Math.cos(a), cy + r * Math.sin(a)));
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (!laidOut && getWidth() > 0) {
                layoutCircle();
                laidOut = true;
            }
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));

            drawEdges(g);
            drawNodes(g);
        }

        // ---------------- edges ----------------

        /** Unordered vertex pair, identity-based (works even without equals on V). */
        private static final class PairKey {
            final Object a, b;
            PairKey(Object x, Object y) {
                // canonical order by identity hash so (x,y) == (y,x)
                if (System.identityHashCode(x) <= System.identityHashCode(y)) {
                    a = x; b = y;
                } else {
                    a = y; b = x;
                }
            }
            @Override public boolean equals(Object o) {
                return o instanceof PairKey && ((PairKey) o).a == a && ((PairKey) o).b == b;
            }
            @Override public int hashCode() {
                return 31 * System.identityHashCode(a) + System.identityHashCode(b);
            }
        }

        private void drawEdges(Graphics2D g) {
            // group non-loop edges by unordered endpoint pair so parallel edges
            // (in either direction) each get their own lane
            Map<PairKey, List<E>> groups = new LinkedHashMap<>();
            Map<V, List<E>> loops = new IdentityHashMap<>();

            for (E e : graph.edgeSet()) {
                V s = graph.getEdgeSource(e), t = graph.getEdgeTarget(e);
                if (s == t) {
                    loops.computeIfAbsent(s, k -> new ArrayList<>()).add(e);
                } else {
                    groups.computeIfAbsent(new PairKey(s, t), k -> new ArrayList<>()).add(e);
                }
            }

            g.setStroke(new BasicStroke(1.6f));

            for (List<E> group : groups.values()) {
                int n = group.size();
                for (int i = 0; i < n; i++) {
                    double lane = (i - (n - 1) / 2.0) * LANE_GAP;
                    drawCurvedEdge(g, group.get(i), lane);
                }
            }

            for (Map.Entry<V, List<E>> en : loops.entrySet()) {
                List<E> ls = en.getValue();
                for (int i = 0; i < ls.size(); i++) {
                    drawSelfLoop(g, en.getKey(), ls.get(i), i);
                }
            }
        }

        /**
         * Draw one edge as a quadratic curve bent sideways by {@code lane} px.
         * lane == 0 is a straight line; parallel edges get distinct lanes.
         */
        private void drawCurvedEdge(Graphics2D g, E e, double lane) {
            V sv = graph.getEdgeSource(e), tv = graph.getEdgeTarget(e);
            Point2D.Double s = pos.get(sv), t = pos.get(tv);
            if (s == null || t == null) return;

            double dx = t.x - s.x, dy = t.y - s.y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) return;
            // unit perpendicular
            double px = -dy / len, py = dx / len;

            // flip lane sign for edges running "backwards" along the canonical
            // pair orientation, so A->B and B->A never share a lane
            if (System.identityHashCode(sv) > System.identityHashCode(tv)) lane = -lane;

            // control point: bend of `lane` px at the midpoint needs a 2x control offset
            double cx = (s.x + t.x) / 2 + px * lane * 2;
            double cy = (s.y + t.y) / 2 + py * lane * 2;

            g.setColor(EDGE_COLOR);
            g.draw(new QuadCurve2D.Double(s.x, s.y, cx, cy, t.x, t.y));

            // arrowhead where the curve leaves the target node's circle
            double tip = 1.0;
            for (double u = 1.0; u >= 0; u -= 0.01) {
                if (quadPoint(s, cx, cy, t, u).distance(t) >= NODE_R + 2) {
                    tip = u;
                    break;
                }
            }
            Point2D.Double p = quadPoint(s, cx, cy, t, tip);
            // derivative of the quad Bezier at `tip` gives the arrow direction
            double ddx = 2 * (1 - tip) * (cx - s.x) + 2 * tip * (t.x - cx);
            double ddy = 2 * (1 - tip) * (cy - s.y) + 2 * tip * (t.y - cy);
            drawArrowHead(g, p.x, p.y, Math.atan2(ddy, ddx));

            // label at the curve midpoint
            Point2D.Double mid = quadPoint(s, cx, cy, t, 0.5);
            drawEdgeLabel(g, edgeName.apply(e), mid.x, mid.y);
        }

        private void drawSelfLoop(Graphics2D g, V v, E e, int index) {
            Point2D.Double c = pos.get(v);
            if (c == null) return;
            double loopR = 20 + index * 14;                 // nested loops grow outward
            double lcx = c.x, lcy = c.y - NODE_R - loopR + 6;

            g.setColor(EDGE_COLOR);
            g.draw(new Ellipse2D.Double(lcx - loopR, lcy - loopR, 2 * loopR, 2 * loopR));

            // arrowhead on the lower-right of the loop, pointing back into the node
            double a = Math.toRadians(55);                   // y-down screen coords
            double ax = lcx + loopR * Math.cos(a);
            double ay = lcy + loopR * Math.sin(a);
            drawArrowHead(g, ax, ay, a + Math.PI / 2);       // tangent, clockwise travel

            drawEdgeLabel(g, edgeName.apply(e), lcx, lcy - loopR - 9);
        }

        private static Point2D.Double quadPoint(Point2D.Double p0, double cx, double cy,
                                                Point2D.Double p1, double t) {
            double u = 1 - t;
            return new Point2D.Double(
                    u * u * p0.x + 2 * u * t * cx + t * t * p1.x,
                    u * u * p0.y + 2 * u * t * cy + t * t * p1.y);
        }

        private void drawArrowHead(Graphics2D g, double x, double y, double angle) {
            double size = 10;
            Path2D.Double head = new Path2D.Double();
            head.moveTo(x, y);
            head.lineTo(x - size * Math.cos(angle - 0.42), y - size * Math.sin(angle - 0.42));
            head.lineTo(x - size * Math.cos(angle + 0.42), y - size * Math.sin(angle + 0.42));
            head.closePath();
            g.fill(head);
        }

        private void drawEdgeLabel(Graphics2D g, String text, double x, double y) {
            if (text == null || text.isEmpty()) return;
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text), h = fm.getAscent();
            g.setColor(LABEL_BG);
            g.fillRect((int) (x - w / 2.0) - 3, (int) (y - h / 2.0) - 2, w + 6, h + 4);
            g.setColor(EDGE_COLOR.darker());
            g.drawString(text, (int) (x - w / 2.0), (int) (y + h / 2.0) - 1);
        }

        // ---------------- nodes ----------------

        private void drawNodes(Graphics2D g) {
            FontMetrics fm = g.getFontMetrics();
            for (V v : graph.vertexSet()) {
                Point2D.Double p = pos.get(v);
                if (p == null) continue;

                Shape circle = new Ellipse2D.Double(
                        p.x - NODE_R, p.y - NODE_R, 2 * NODE_R, 2 * NODE_R);
                g.setColor(NODE_FILL);
                g.fill(circle);
                g.setColor(NODE_BORDER);
                g.setStroke(new BasicStroke(2f));
                g.draw(circle);

                String name = vertexName.apply(v);
                if (name == null) name = "";
                // crude fit: trim with ellipsis if wider than the node
                while (name.length() > 1 && fm.stringWidth(name) > 2 * NODE_R - 8) {
                    name = name.substring(0, name.length() - 2) + "\u2026";
                }
                g.setColor(Color.WHITE);
                g.drawString(name,
                        (float) (p.x - fm.stringWidth(name) / 2.0),
                        (float) (p.y + fm.getAscent() / 2.0) - 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // Demo main — delete freely. Assumes Node/Route-style classes exist;
    // shown here inline so the file compiles standalone.
    // ------------------------------------------------------------------

    static class DemoNode {
        private final String name;
        DemoNode(String name) { this.name = name; }
        public String getName() { return name; }
    }

    static class DemoEdge {
        private final String name;
        DemoEdge(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public static void main(String[] args) {
        DirectedPseudograph<DemoNode, DemoEdge> g =
                new DirectedPseudograph<>(DemoEdge.class);

        DemoNode tampa = new DemoNode("Tampa");
        DemoNode atlanta = new DemoNode("Atlanta");
        DemoNode miami = new DemoNode("Miami");
        g.addVertex(tampa);
        g.addVertex(atlanta);
        g.addVertex(miami);

        g.addEdge(tampa, atlanta, new DemoEdge("Delta $129"));
        g.addEdge(tampa, atlanta, new DemoEdge("SW $99"));      // parallel
        g.addEdge(atlanta, tampa, new DemoEdge("Delta $140"));  // reverse direction
        g.addEdge(atlanta, miami, new DemoEdge("AA $180"));
        g.addEdge(tampa, tampa, new DemoEdge("loop A"));        // self-loop
        g.addEdge(tampa, tampa, new DemoEdge("loop B"));        // nested self-loop

        show(g);   // reflection overload; or: show(g, DemoNode::getName, DemoEdge::getName)
    }
}