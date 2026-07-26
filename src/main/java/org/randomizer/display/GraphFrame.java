package org.randomizer.display;

import org.jgrapht.graph.DirectedPseudograph;

import javax.swing.*;
import javax.swing.Timer;
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
 * Interaction:
 *   - middle-click drag a node : springy ("boingy") drag — the node chases the
 *     cursor on an underdamped spring and wobbles into place on release
 *   - left-click drag a node   : rigid drag (node pinned to cursor)
 *   - drag empty background    : pan the view
 *   - scroll wheel             : zoom, centered on the cursor
 *
 * Usage:
 *   GraphFrame.show(graph, Node::getName, Route::getName);
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

        private static final int NODE_R = 28;          // node circle radius (world units)
        private static final double LANE_GAP = 26;     // separation between parallel edges

        // spring physics (underdamped on purpose — that's the boing)
        private static final double DT        = 1.0 / 60.0;
        private static final double STIFFNESS = 170.0; // spring constant k
        private static final double DAMPING   = 7.0;   // velocity damping c
        private static final double SETTLE_DIST  = 0.4;
        private static final double SETTLE_SPEED = 2.0;

        private static final double MIN_ZOOM = 0.15, MAX_ZOOM = 6.0;

        private static final Color NODE_FILL   = new Color(0x2D6A9F);
        private static final Color NODE_BORDER = new Color(0x1B4266);
        private static final Color EDGE_COLOR  = new Color(0x555555);
        private static final Color LABEL_BG    = new Color(255, 255, 255, 210);

        private final DirectedPseudograph<V, E> graph;
        private final Function<V, String> vertexName;
        private final Function<E, String> edgeName;

        // world-space node state
        private final Map<V, Point2D.Double> pos = new IdentityHashMap<>();
        private final Map<V, Point2D.Double> vel = new IdentityHashMap<>();
        private final Map<V, Point2D.Double> springTarget = new IdentityHashMap<>();

        // view transform: screen = world * scale + offset
        private double scale = 1.0, offX = 0, offY = 0;

        // interaction state
        private V rigidDragged = null;   // left-button drag
        private V springDragged = null;  // middle-button drag
        private Point panStart = null;   // screen coords of a background drag
        private double panOffX0, panOffY0;

        private boolean laidOut = false;
        private final Timer physics;

        GraphPanel(DirectedPseudograph<V, E> graph,
                   Function<V, String> vertexName,
                   Function<E, String> edgeName) {
            this.graph = graph;
            this.vertexName = vertexName;
            this.edgeName = edgeName;
            setBackground(Color.WHITE);

            physics = new Timer((int) (DT * 1000), ev -> stepPhysics());
            physics.setCoalesce(true);

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    Point2D.Double w = toWorld(e.getX(), e.getY());
                    V hit = nodeAt(w.x, w.y);
                    if (hit != null && SwingUtilities.isMiddleMouseButton(e)) {
                        springDragged = hit;
                        vel.computeIfAbsent(hit, k -> new Point2D.Double());
                        springTarget.put(hit, new Point2D.Double(w.x, w.y));
                        physics.start();
                    } else if (hit != null && SwingUtilities.isLeftMouseButton(e)) {
                        rigidDragged = hit;
                        springTarget.remove(hit);   // rigid drag overrides any leftover spring
                        vel.remove(hit);
                    } else {
                        panStart = e.getPoint();
                        panOffX0 = offX;
                        panOffY0 = offY;
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    // spring target stays where the cursor let go, so the node
                    // wobbles into place; the physics loop clears it once settled
                    springDragged = null;
                    rigidDragged = null;
                    panStart = null;
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (springDragged != null) {
                        Point2D.Double w = toWorld(e.getX(), e.getY());
                        springTarget.get(springDragged).setLocation(w.x, w.y);
                    } else if (rigidDragged != null) {
                        Point2D.Double w = toWorld(e.getX(), e.getY());
                        pos.get(rigidDragged).setLocation(w.x, w.y);
                        repaint();
                    } else if (panStart != null) {
                        offX = panOffX0 + (e.getX() - panStart.x);
                        offY = panOffY0 + (e.getY() - panStart.y);
                        repaint();
                    }
                }

                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    double factor = Math.pow(1.1, -e.getPreciseWheelRotation());
                    double newScale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scale * factor));
                    factor = newScale / scale;
                    // zoom about the cursor: keep the world point under the mouse fixed
                    offX = e.getX() - (e.getX() - offX) * factor;
                    offY = e.getY() - (e.getY() - offY) * factor;
                    scale = newScale;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        private Point2D.Double toWorld(double sx, double sy) {
            return new Point2D.Double((sx - offX) / scale, (sy - offY) / scale);
        }

        private V nodeAt(double wx, double wy) {
            for (Map.Entry<V, Point2D.Double> en : pos.entrySet()) {
                if (en.getValue().distance(wx, wy) <= NODE_R) return en.getKey();
            }
            return null;
        }

        // ---------------- physics ----------------

        /**
         * Semi-implicit Euler on a spring-damper per active node:
         *   a = k * (target - p) - c * v
         * Underdamped (c &lt; 2*sqrt(k)) so nodes overshoot and boing.
         */
        private void stepPhysics() {
            boolean anyActive = false;
            Iterator<Map.Entry<V, Point2D.Double>> it = springTarget.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<V, Point2D.Double> en = it.next();
                V v = en.getKey();
                Point2D.Double p = pos.get(v);
                Point2D.Double target = en.getValue();
                Point2D.Double velo = vel.computeIfAbsent(v, k -> new Point2D.Double());
                if (p == null) { it.remove(); continue; }

                double ax = STIFFNESS * (target.x - p.x) - DAMPING * velo.x;
                double ay = STIFFNESS * (target.y - p.y) - DAMPING * velo.y;
                velo.x += ax * DT;
                velo.y += ay * DT;
                p.x += velo.x * DT;
                p.y += velo.y * DT;

                boolean settled = p.distance(target) < SETTLE_DIST
                        && Math.hypot(velo.x, velo.y) < SETTLE_SPEED
                        && v != springDragged;    // never settle while held
                if (settled) {
                    p.setLocation(target);
                    vel.remove(v);
                    it.remove();
                } else {
                    anyActive = true;
                }
            }
            if (!anyActive) physics.stop();
            repaint();
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
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));

            g.translate(offX, offY);
            g.scale(scale, scale);

            drawEdges(g);
            drawNodes(g);
            g.dispose();
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
}