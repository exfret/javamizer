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
 * Swing viewer for a JGraphT DirectedPseudograph, tuned for large graphs.
 *
 * Interaction:
 *   - left-click a node        : select it (selection ONLY — never moves it);
 *                                name + connections appear in the right panel;
 *                                left-click empty space clears the selection
 *   - click a connection row   : selects that connected node and centers the
 *                                camera on it — click through chains this way
 *   - right-drag               : pan the camera (camera only, grabs nothing)
 *   - middle-drag a node       : move it, springily (the only way nodes move)
 *   - scroll wheel             : zoom about the cursor
 *
 * Layout ("smart shape"):
 *   Chosen from the data. Layers are assigned by longest path from the
 *   sources (cycles broken greedily); if the result is mostly feed-forward
 *   (< 30% back edges) the graph is drawn as a left-to-right layered DAG
 *   with barycenter crossing reduction. Otherwise: force-directed
 *   (Fruchterman-Reingold). The view starts zoomed to fit either way.
 *
 * Performance: OpenGL Java2D pipeline (also pass -Dsun.java2d.opengl=true),
 * edge lanes precomputed once, viewport culling, zoom-based level of detail.
 */
public class GraphFrame {

    public static <V, E> void show(DirectedPseudograph<V, E> graph,
                                   Function<V, String> vertexName,
                                   Function<E, String> edgeName) {
        System.setProperty("sun.java2d.opengl", "true");

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Graph Viewer");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.add(new GraphPanel<>(graph, vertexName, edgeName));
            frame.setSize(1100, 800);
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

        private static final int NODE_R = 28;
        private static final double LANE_GAP = 26;

        private static final double LAYER_GAP = 300;
        private static final double ROW_GAP   = 90;

        private static final double DT        = 1.0 / 60.0;
        private static final double STIFFNESS = 170.0;
        private static final double DAMPING   = 7.0;
        private static final double SETTLE_DIST  = 0.4;
        private static final double SETTLE_SPEED = 2.0;

        private static final double MIN_ZOOM = 0.02, MAX_ZOOM = 6.0;

        private static final double LOD_EDGE_LABELS = 0.60;
        private static final double LOD_NODE_LABELS = 0.35;
        private static final double LOD_ARROWHEADS  = 0.30;
        private static final double LOD_ANTIALIAS   = 0.25;

        private static final Color NODE_FILL    = new Color(0x2D6A9F);
        private static final Color NODE_BORDER  = new Color(0x1B4266);
        private static final Color SELECT_FILL  = new Color(0xE07A2F);
        private static final Color SELECT_EDGE  = new Color(0xC85A10);
        private static final Color EDGE_COLOR   = new Color(0x555555);
        private static final Color LABEL_BG     = new Color(255, 255, 255, 210);

        private final DirectedPseudograph<V, E> graph;
        private final Function<V, String> vertexName;
        private final Function<E, String> edgeName;

        private final Map<V, Point2D.Double> pos = new IdentityHashMap<>();
        private final Map<V, Point2D.Double> vel = new IdentityHashMap<>();
        private final Map<V, Point2D.Double> springTarget = new IdentityHashMap<>();

        private final List<EdgeDraw<V, E>> straightEdges = new ArrayList<>();
        private final List<LoopDraw<V, E>> loopEdges = new ArrayList<>();

        private double scale = 1.0, offX = 0, offY = 0;

        private V selected = null;
        private V springDragged = null;
        private Point panStart = null;
        private double panOffX0, panOffY0;

        /** Clickable rows in the sidebar, rebuilt each paint. */
        private final List<SidebarHit<V>> sidebarHits = new ArrayList<>();

        private boolean laidOut = false;
        private final Timer physics;

        private static final class EdgeDraw<V, E> {
            final E edge; final V s, t; final double lane;
            EdgeDraw(E edge, V s, V t, double lane) {
                this.edge = edge; this.s = s; this.t = t; this.lane = lane;
            }
        }

        private static final class LoopDraw<V, E> {
            final E edge; final V v; final int idx;
            LoopDraw(E edge, V v, int idx) { this.edge = edge; this.v = v; this.idx = idx; }
        }

        private static final class SidebarHit<V> {
            final Rectangle rect; final V node;
            SidebarHit(Rectangle rect, V node) { this.rect = rect; this.node = node; }
        }

        GraphPanel(DirectedPseudograph<V, E> graph,
                   Function<V, String> vertexName,
                   Function<E, String> edgeName) {
            this.graph = graph;
            this.vertexName = vertexName;
            this.edgeName = edgeName;
            setBackground(Color.WHITE);
            setDoubleBuffered(true);

            buildEdgeCache();

            physics = new Timer((int) (DT * 1000), ev -> stepPhysics());
            physics.setCoalesce(true);

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    // clicks on the sidebar: maybe a connection row
                    if (selected != null && sidebarBounds().contains(e.getPoint())) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            for (SidebarHit<V> h : sidebarHits) {
                                if (h.rect.contains(e.getPoint())) {
                                    selected = h.node;
                                    centerOn(h.node);
                                    break;
                                }
                            }
                            repaint();
                        }
                        return;   // panel consumes the click either way
                    }

                    Point2D.Double w = toWorld(e.getX(), e.getY());
                    V hit = nodeAt(w.x, w.y);

                    if (SwingUtilities.isLeftMouseButton(e)) {
                        // left = select only; never grabs, never pans
                        selected = hit;   // null on empty space clears
                        repaint();
                    } else if (SwingUtilities.isMiddleMouseButton(e) && hit != null) {
                        // middle = the only way to move a node (springily)
                        springDragged = hit;
                        vel.computeIfAbsent(hit, k -> new Point2D.Double());
                        springTarget.put(hit, new Point2D.Double(w.x, w.y));
                        physics.start();
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        // right = camera only
                        panStart = e.getPoint();
                        panOffX0 = offX;
                        panOffY0 = offY;
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    springDragged = null;
                    panStart = null;
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (springDragged != null) {
                        Point2D.Double w = toWorld(e.getX(), e.getY());
                        springTarget.get(springDragged).setLocation(w.x, w.y);
                    } else if (panStart != null) {
                        offX = panOffX0 + (e.getX() - panStart.x);
                        offY = panOffY0 + (e.getY() - panStart.y);
                        repaint();
                    }
                }

                @Override public void mouseMoved(MouseEvent e) {
                    // hand cursor over clickable connection rows
                    boolean hand = false;
                    if (selected != null && sidebarBounds().contains(e.getPoint())) {
                        for (SidebarHit<V> h : sidebarHits) {
                            if (h.rect.contains(e.getPoint())) { hand = true; break; }
                        }
                    }
                    setCursor(Cursor.getPredefinedCursor(
                            hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }

                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    double factor = Math.pow(1.1, -e.getPreciseWheelRotation());
                    double newScale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scale * factor));
                    factor = newScale / scale;
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

        private void centerOn(V v) {
            Point2D.Double p = pos.get(v);
            if (p == null) return;
            offX = getWidth() / 2.0 - p.x * scale;
            offY = getHeight() / 2.0 - p.y * scale;
        }

        private void buildEdgeCache() {
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
            for (List<E> group : groups.values()) {
                int n = group.size();
                for (int i = 0; i < n; i++) {
                    E e = group.get(i);
                    V s = graph.getEdgeSource(e), t = graph.getEdgeTarget(e);
                    double lane = (i - (n - 1) / 2.0) * LANE_GAP;
                    if (System.identityHashCode(s) > System.identityHashCode(t)) lane = -lane;
                    straightEdges.add(new EdgeDraw<>(e, s, t, lane));
                }
            }
            for (Map.Entry<V, List<E>> en : loops.entrySet()) {
                List<E> ls = en.getValue();
                for (int i = 0; i < ls.size(); i++) {
                    loopEdges.add(new LoopDraw<>(ls.get(i), en.getKey(), i));
                }
            }
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
                        && v != springDragged;
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

        // ---------------- smart layout ----------------

        private void smartLayout() {
            List<V> vs = new ArrayList<>(graph.vertexSet());
            if (vs.isEmpty()) return;

            Map<V, Integer> layer = assignLayers(vs);
            if (layer != null) layeredLayout(vs, layer);
            else forceLayout(vs);
            fitView();
        }

        private Map<V, Integer> assignLayers(List<V> vs) {
            if (straightEdges.isEmpty()) return null;

            Map<V, Integer> indeg = new IdentityHashMap<>();
            Map<V, List<V>> succs = new IdentityHashMap<>();
            for (V v : vs) indeg.put(v, 0);
            for (EdgeDraw<V, E> d : straightEdges) {
                indeg.merge(d.t, 1, Integer::sum);
                succs.computeIfAbsent(d.s, k -> new ArrayList<>()).add(d.t);
            }

            Map<V, Integer> layer = new IdentityHashMap<>();
            Deque<V> queue = new ArrayDeque<>();
            Set<V> done = Collections.newSetFromMap(new IdentityHashMap<>());
            for (V v : vs) if (indeg.get(v) == 0) { queue.add(v); layer.put(v, 0); }

            int processed = 0;
            while (processed < vs.size()) {
                if (queue.isEmpty()) {
                    V best = null;
                    for (V v : vs) {
                        if (done.contains(v)) continue;
                        if (best == null || indeg.get(v) < indeg.get(best)) best = v;
                    }
                    layer.putIfAbsent(best, 0);
                    queue.add(best);
                }
                V u = queue.poll();
                if (!done.add(u)) continue;
                processed++;
                int lu = layer.getOrDefault(u, 0);
                for (V w : succs.getOrDefault(u, Collections.emptyList())) {
                    layer.merge(w, lu + 1, Math::max);
                    if (indeg.merge(w, -1, Integer::sum) <= 0 && !done.contains(w)) {
                        queue.add(w);
                    }
                }
            }

            long back = straightEdges.stream()
                    .filter(d -> layer.getOrDefault(d.s, 0) >= layer.getOrDefault(d.t, 0))
                    .count();
            return back > straightEdges.size() * 0.30 ? null : layer;
        }

        private void layeredLayout(List<V> vs, Map<V, Integer> layer) {
            int maxLayer = 0;
            for (int l : layer.values()) maxLayer = Math.max(maxLayer, l);

            List<List<V>> cols = new ArrayList<>();
            for (int i = 0; i <= maxLayer; i++) cols.add(new ArrayList<>());
            for (V v : vs) cols.get(layer.get(v)).add(v);

            Map<V, List<V>> preds = new IdentityHashMap<>();
            Map<V, List<V>> succs = new IdentityHashMap<>();
            for (EdgeDraw<V, E> d : straightEdges) {
                preds.computeIfAbsent(d.t, k -> new ArrayList<>()).add(d.s);
                succs.computeIfAbsent(d.s, k -> new ArrayList<>()).add(d.t);
            }

            Map<V, Double> row = new IdentityHashMap<>();
            for (List<V> col : cols) {
                for (int i = 0; i < col.size(); i++) row.put(col.get(i), (double) i);
            }

            for (int iter = 0; iter < 6; iter++) {
                boolean forward = iter % 2 == 0;
                for (int ci = forward ? 1 : maxLayer - 1;
                     forward ? ci <= maxLayer : ci >= 0;
                     ci += forward ? 1 : -1) {
                    List<V> col = cols.get(ci);
                    Map<V, List<V>> nbrs = forward ? preds : succs;
                    Map<V, Double> bary = new IdentityHashMap<>();
                    for (V v : col) {
                        List<V> ns = nbrs.getOrDefault(v, Collections.emptyList());
                        if (ns.isEmpty()) { bary.put(v, row.get(v)); continue; }
                        double sum = 0;
                        for (V n : ns) sum += row.get(n);
                        bary.put(v, sum / ns.size());
                    }
                    col.sort(Comparator.comparingDouble(bary::get));
                    for (int i = 0; i < col.size(); i++) row.put(col.get(i), (double) i);
                }
            }

            for (int ci = 0; ci <= maxLayer; ci++) {
                List<V> col = cols.get(ci);
                double x = ci * LAYER_GAP;
                for (int i = 0; i < col.size(); i++) {
                    double y = (i - (col.size() - 1) / 2.0) * ROW_GAP;
                    pos.put(col.get(i), new Point2D.Double(x, y));
                }
            }
        }

        private void forceLayout(List<V> vs) {
            int n = vs.size();
            double k = 140;
            double side = Math.sqrt((double) n) * k;
            Random rnd = new Random(42);
            for (V v : vs) {
                pos.put(v, new Point2D.Double(rnd.nextDouble() * side,
                        rnd.nextDouble() * side));
            }

            Map<V, Point2D.Double> disp = new IdentityHashMap<>();
            for (V v : vs) disp.put(v, new Point2D.Double());

            int iters = Math.max(60, Math.min(200, 30000 / Math.max(1, n)) + 60);
            double temp = side / 8;
            for (int it = 0; it < iters; it++) {
                for (Point2D.Double dp : disp.values()) dp.setLocation(0, 0);

                for (int i = 0; i < n; i++) {
                    Point2D.Double pi = pos.get(vs.get(i));
                    Point2D.Double di = disp.get(vs.get(i));
                    for (int j = i + 1; j < n; j++) {
                        Point2D.Double pj = pos.get(vs.get(j));
                        double dx = pi.x - pj.x, dy = pi.y - pj.y;
                        double d2 = dx * dx + dy * dy + 0.01;
                        double f = k * k / d2;
                        di.x += dx * f; di.y += dy * f;
                        Point2D.Double dj = disp.get(vs.get(j));
                        dj.x -= dx * f; dj.y -= dy * f;
                    }
                }
                for (EdgeDraw<V, E> e : straightEdges) {
                    Point2D.Double ps = pos.get(e.s), pt = pos.get(e.t);
                    double dx = ps.x - pt.x, dy = ps.y - pt.y;
                    double d = Math.sqrt(dx * dx + dy * dy) + 0.01;
                    double f = d / k;
                    Point2D.Double ds = disp.get(e.s), dt = disp.get(e.t);
                    ds.x -= dx * f; ds.y -= dy * f;
                    dt.x += dx * f; dt.y += dy * f;
                }
                for (V v : vs) {
                    Point2D.Double d = disp.get(v), p = pos.get(v);
                    double len = Math.hypot(d.x, d.y);
                    if (len < 1e-9) continue;
                    double lim = Math.min(len, temp);
                    p.x += d.x / len * lim;
                    p.y += d.y / len * lim;
                }
                temp *= 0.96;
            }
        }

        private void fitView() {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Point2D.Double p : pos.values()) {
                minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            }
            double w = maxX - minX + 300, h = maxY - minY + 300;
            scale = Math.max(MIN_ZOOM, Math.min(1.0,
                    Math.min(getWidth() / w, getHeight() / h)));
            offX = getWidth() / 2.0 - (minX + maxX) / 2.0 * scale;
            offY = getHeight() / 2.0 - (minY + maxY) / 2.0 * scale;
        }

        // ---------------- painting ----------------

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (!laidOut && getWidth() > 0) {
                smartLayout();
                laidOut = true;
            }
            Graphics2D g = (Graphics2D) g0.create();

            boolean aa = scale >= LOD_ANTIALIAS;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    aa ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));

            g.translate(offX, offY);
            g.scale(scale, scale);

            Rectangle2D view = new Rectangle2D.Double(
                    -offX / scale - 100, -offY / scale - 100,
                    getWidth() / scale + 200, getHeight() / scale + 200);

            drawEdges(g, view);
            drawNodes(g, view);
            g.dispose();

            drawSidebar((Graphics2D) g0);
        }

        // ---------------- edges ----------------

        private static final class PairKey {
            final Object a, b;
            PairKey(Object x, Object y) {
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

        private void drawEdges(Graphics2D g, Rectangle2D view) {
            g.setStroke(new BasicStroke(1.6f));
            boolean arrows = scale >= LOD_ARROWHEADS;
            boolean labels = scale >= LOD_EDGE_LABELS;

            for (EdgeDraw<V, E> d : straightEdges) {
                Point2D.Double s = pos.get(d.s), t = pos.get(d.t);
                if (s == null || t == null) continue;

                double pad = Math.abs(d.lane) * 2 + 10;
                double minX = Math.min(s.x, t.x) - pad, maxX = Math.max(s.x, t.x) + pad;
                double minY = Math.min(s.y, t.y) - pad, maxY = Math.max(s.y, t.y) + pad;
                if (!view.intersects(minX, minY, maxX - minX + 1, maxY - minY + 1)) continue;

                boolean hot = selected != null && (d.s == selected || d.t == selected);
                drawCurvedEdge(g, d, s, t, arrows, labels || hot, hot);
            }
            for (LoopDraw<V, E> d : loopEdges) {
                Point2D.Double c = pos.get(d.v);
                if (c == null || !view.contains(c.x, c.y)) continue;
                boolean hot = d.v == selected;
                drawSelfLoop(g, d, c, arrows, labels || hot, hot);
            }
        }

        private void drawCurvedEdge(Graphics2D g, EdgeDraw<V, E> d,
                                    Point2D.Double s, Point2D.Double t,
                                    boolean arrows, boolean labels, boolean hot) {
            double dx = t.x - s.x, dy = t.y - s.y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) return;
            double px = -dy / len, py = dx / len;

            double cx = (s.x + t.x) / 2 + px * d.lane * 2;
            double cy = (s.y + t.y) / 2 + py * d.lane * 2;

            g.setColor(hot ? SELECT_EDGE : EDGE_COLOR);
            g.draw(new QuadCurve2D.Double(s.x, s.y, cx, cy, t.x, t.y));

            if (arrows || hot) {
                double tip = 1.0;
                for (double u = 1.0; u >= 0; u -= 0.02) {
                    if (quadPoint(s, cx, cy, t, u).distance(t) >= NODE_R + 2) {
                        tip = u;
                        break;
                    }
                }
                Point2D.Double p = quadPoint(s, cx, cy, t, tip);
                double ddx = 2 * (1 - tip) * (cx - s.x) + 2 * tip * (t.x - cx);
                double ddy = 2 * (1 - tip) * (cy - s.y) + 2 * tip * (t.y - cy);
                drawArrowHead(g, p.x, p.y, Math.atan2(ddy, ddx));
            }
            if (labels) {
                Point2D.Double mid = quadPoint(s, cx, cy, t, 0.5);
                drawEdgeLabel(g, edgeName.apply(d.edge), mid.x, mid.y);
            }
        }

        private void drawSelfLoop(Graphics2D g, LoopDraw<V, E> d, Point2D.Double c,
                                  boolean arrows, boolean labels, boolean hot) {
            double loopR = 20 + d.idx * 14;
            double lcx = c.x, lcy = c.y - NODE_R - loopR + 6;

            g.setColor(hot ? SELECT_EDGE : EDGE_COLOR);
            g.draw(new Ellipse2D.Double(lcx - loopR, lcy - loopR, 2 * loopR, 2 * loopR));

            if (arrows || hot) {
                double a = Math.toRadians(55);
                drawArrowHead(g, lcx + loopR * Math.cos(a), lcy + loopR * Math.sin(a),
                        a + Math.PI / 2);
            }
            if (labels) {
                drawEdgeLabel(g, edgeName.apply(d.edge), lcx, lcy - loopR - 9);
            }
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
            Color keep = g.getColor();
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text), h = fm.getAscent();
            g.setColor(LABEL_BG);
            g.fillRect((int) (x - w / 2.0) - 3, (int) (y - h / 2.0) - 2, w + 6, h + 4);
            g.setColor(EDGE_COLOR.darker());
            g.drawString(text, (int) (x - w / 2.0), (int) (y + h / 2.0) - 1);
            g.setColor(keep);
        }

        // ---------------- nodes ----------------

        private void drawNodes(Graphics2D g, Rectangle2D view) {
            FontMetrics fm = g.getFontMetrics();
            boolean labels = scale >= LOD_NODE_LABELS;
            g.setStroke(new BasicStroke(2f));

            for (Map.Entry<V, Point2D.Double> en : pos.entrySet()) {
                Point2D.Double p = en.getValue();
                if (!view.contains(p.x, p.y)) continue;
                boolean isSel = en.getKey() == selected;

                Shape circle = new Ellipse2D.Double(
                        p.x - NODE_R, p.y - NODE_R, 2 * NODE_R, 2 * NODE_R);
                g.setColor(isSel ? SELECT_FILL : NODE_FILL);
                g.fill(circle);
                g.setColor(isSel ? SELECT_EDGE : NODE_BORDER);
                g.draw(circle);

                if (labels || isSel) {
                    String name = vertexName.apply(en.getKey());
                    if (name == null) name = "";
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

        // ---------------- selection sidebar ----------------

        private Rectangle sidebarBounds() {
            int w = 300;
            return new Rectangle(getWidth() - w - 12, 12, w, getHeight() - 24);
        }

        private void drawSidebar(Graphics2D g) {
            sidebarHits.clear();
            if (selected == null) return;
            Rectangle r = sidebarBounds();

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(255, 255, 255, 238));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 14, 14);
            g.setColor(new Color(0xBBBBBB));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 14, 14);

            Shape oldClip = g.getClip();
            g.clip(r);
            int pad = 14;
            int x = r.x + pad;
            int y = r.y + pad;
            int textW = r.width - 2 * pad;

            Font title = getFont().deriveFont(Font.BOLD, 16f);
            Font header = getFont().deriveFont(Font.BOLD, 12f);
            Font body = getFont().deriveFont(Font.PLAIN, 12f);

            g.setFont(title);
            g.setColor(new Color(0x222222));
            FontMetrics fm = g.getFontMetrics();
            y += fm.getAscent();
            g.drawString(ellipsize(vertexName.apply(selected), fm, textW), x, y);
            y += 10;

            Set<E> out = graph.outgoingEdgesOf(selected);
            Set<E> in = graph.incomingEdgesOf(selected);
            int lineH = g.getFontMetrics(body).getHeight() + 2;
            int bottom = r.y + r.height - pad;

            y = drawEdgeSection(g, "Outgoing (" + out.size() + ")", out, true,
                    x, y, textW, lineH, bottom, header, body);
            drawEdgeSection(g, "Incoming (" + in.size() + ")", in, false,
                    x, y, textW, lineH, bottom, header, body);

            g.setClip(oldClip);
        }

        private int drawEdgeSection(Graphics2D g, String heading, Set<E> edges,
                                    boolean outgoing, int x, int y, int textW,
                                    int lineH, int bottom, Font header, Font body) {
            if (y + lineH * 2 > bottom) return y;
            y += 12;
            g.setFont(header);
            g.setColor(new Color(0x444444));
            y += g.getFontMetrics().getAscent();
            g.drawString(heading, x, y);
            y += 4;

            g.setFont(body);
            FontMetrics fm = g.getFontMetrics();
            int shown = 0, total = edges.size();
            for (E e : edges) {
                if (y + lineH > bottom - lineH && shown < total - 1) {
                    g.setColor(new Color(0x888888));
                    y += lineH;
                    g.drawString("… +" + (total - shown) + " more", x, y);
                    return y;
                }
                V other = outgoing ? graph.getEdgeTarget(e) : graph.getEdgeSource(e);
                String en = edgeName.apply(e);
                String line = (outgoing ? "→ " : "← ") + vertexName.apply(other)
                        + (en == null || en.isEmpty() ? "" : "   [" + en + "]");
                g.setColor(new Color(0x1A5FB4));   // link-blue: rows are clickable
                y += lineH;
                g.drawString(ellipsize(line, fm, textW), x, y);
                sidebarHits.add(new SidebarHit<>(
                        new Rectangle(x, y - fm.getAscent(), textW, lineH), other));
                shown++;
            }
            return y;
        }

        private static String ellipsize(String s, FontMetrics fm, int maxW) {
            if (s == null) return "";
            if (fm.stringWidth(s) <= maxW) return s;
            while (s.length() > 1 && fm.stringWidth(s + "\u2026") > maxW) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "\u2026";
        }
    }

    // ------------------------------------------------------------------
    // Demo main — delete freely.
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
        g.addEdge(tampa, atlanta, new DemoEdge("SW $99"));
        g.addEdge(atlanta, tampa, new DemoEdge("Delta $140"));
        g.addEdge(atlanta, miami, new DemoEdge("AA $180"));
        g.addEdge(tampa, tampa, new DemoEdge("loop A"));
        g.addEdge(tampa, tampa, new DemoEdge("loop B"));

        show(g);
    }
}