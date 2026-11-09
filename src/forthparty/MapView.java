package forthparty;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/**
 * 맵 이미지 + 마커 뷰어.
 * 휠: 확대/축소(커서 기준) · 빈 곳 드래그: 지도 이동 · 마커 드래그: 마커 옮기기
 * 마커 더블클릭: 편집 · 빈 곳 더블클릭: 원래 크기 · 우클릭: 추가/편집/삭제 메뉴
 *
 * 마커를 실제로 바꾸는 일(저장, 되돌리기 기록, 편집 창)은 App이 맡고, 이 클래스는 화면과 마우스만 다룬다.
 */
final class MapView extends JComponent {
    private static final Color BG = new Color(0x1E1F22);
    private static final int R = 8;

    /** App이 받아서 처리하는 사용자 동작 */
    interface Actions {
        void moved(List<Marker> markers);                 // 드래그로 마커 위치가 바뀜
        void add(MarkerType type, double x, double y);    // 우클릭 메뉴에서 추가
        void edit(int index);                             // 편집 (더블클릭 또는 메뉴)
        void delete(int index);                           // 삭제 (메뉴)
    }

    private BufferedImage img;
    private List<Marker> markers = List.of();
    private List<MarkerType> menuTypes = List.of();
    private Function<String, MarkerType> types = MarkerType::unknown;
    private boolean markersEnabled;
    private String emptyText = "";
    private String highlight = "";
    private boolean showLabels;
    private final Set<String> hidden = new HashSet<>();
    private int hovered = -1;
    private Actions actions;

    private double zoom = 1, panX, panY;
    private Point dragStart;
    private double dragPanX, dragPanY;
    private int dragIndex = -1;      // 드래그 중인 마커
    private boolean dragMoved;

    MapView() {
        setPreferredSize(new Dimension(480, 320));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { popup(e); return; }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                dragIndex = markerAt(e.getPoint());
                dragMoved = false;
                dragStart = e.getPoint();
                dragPanX = panX;
                dragPanY = panY;
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) popup(e);
                if (dragIndex >= 0 && dragMoved && actions != null) actions.moved(markers);
                dragIndex = -1;
                dragStart = null;
                setCursor(Cursor.getDefaultCursor());
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null || img == null) return;
                if (dragIndex >= 0) {
                    // 마커 옮기기: 화면 좌표 → 이미지 비율 좌표 (0~1 사이로 제한)
                    double s = scale();
                    double nx = clamp((e.getX() - originX()) / s / img.getWidth());
                    double ny = clamp((e.getY() - originY()) / s / img.getHeight());
                    List<Marker> next = new ArrayList<>(markers);
                    next.set(dragIndex, next.get(dragIndex).moveTo(nx, ny));
                    markers = next;
                    dragMoved = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    panX = dragPanX + e.getX() - dragStart.x;
                    panY = dragPanY + e.getY() - dragStart.y;
                }
                repaint();
            }
            @Override public void mouseMoved(MouseEvent e) {
                int m = markerAt(e.getPoint());
                if (m != hovered) {
                    hovered = m;
                    setCursor(m >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    repaint();
                }
            }
            @Override public void mouseExited(MouseEvent e) { if (hovered >= 0) { hovered = -1; repaint(); } }
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2) return;
                int m = markerAt(e.getPoint());
                if (m >= 0 && actions != null) actions.edit(m); else resetView();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (img == null) return;
                double s0 = scale();
                double ix = (e.getX() - originX()) / s0, iy = (e.getY() - originY()) / s0;
                zoom = Math.max(1, Math.min(16, zoom * (e.getWheelRotation() < 0 ? 1.25 : 0.8)));
                double s1 = scale();
                panX = e.getX() - ix * s1 - (getWidth() - img.getWidth() * s1) / 2;
                panY = e.getY() - iy * s1 - (getHeight() - img.getHeight() * s1) / 2;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    // ------------------------------------------------------------ App이 부르는 메서드

    void setContent(BufferedImage img, List<Marker> markers, boolean markersEnabled, String emptyText) {
        this.img = img;
        this.markers = markers;
        this.markersEnabled = markersEnabled;
        this.emptyText = emptyText;
        hovered = -1;
        resetView();
    }

    /** 게임이 바뀌면 종류 목록(우클릭 메뉴)과 id → 종류 찾는 방법을 새로 받는다 */
    void setTypes(List<MarkerType> menuTypes, Function<String, MarkerType> resolver) {
        this.menuTypes = menuTypes;
        this.types = resolver;
        repaint();
    }

    void setMarkers(List<Marker> markers) { this.markers = markers; hovered = -1; repaint(); }
    void setActions(Actions a) { actions = a; }
    void setShowLabels(boolean v) { showLabels = v; repaint(); }
    void setHighlight(String q) { highlight = q == null ? "" : q.trim(); repaint(); }
    void setTypeVisible(String typeId, boolean v) { if (v) hidden.remove(typeId); else hidden.add(typeId); repaint(); }

    /** 검색어가 마커 이름·메모·종류 이름 중 하나와 맞는지 */
    static boolean hit(Marker m, MarkerType t, String q) {
        return Search.matches(m.label() + " " + m.memo() + " " + t.label(), q);
    }

    // ------------------------------------------------------------ 좌표 계산

    private void resetView() { zoom = 1; panX = panY = 0; repaint(); }
    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    private double scale() {
        return Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight()) * zoom;
    }
    private double originX() { return (getWidth() - img.getWidth() * scale()) / 2 + panX; }
    private double originY() { return (getHeight() - img.getHeight() * scale()) / 2 + panY; }
    private double sx(Marker m) { return originX() + m.x() * img.getWidth() * scale(); }
    private double sy(Marker m) { return originY() + m.y() * img.getHeight() * scale(); }

    private boolean visible(Marker m) { return !hidden.contains(m.typeId()); }

    /** 마우스 위치에 있는 마커의 번호 (없으면 -1). 겹치면 나중에 그린(위에 있는) 마커 우선 */
    private int markerAt(Point p) {
        if (img == null || !markersEnabled) return -1;
        double best = (R + 5) * (R + 5);
        int found = -1;
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (!visible(m)) continue;
            double dx = sx(m) - p.x, dy = sy(m) - p.y, d = dx * dx + dy * dy;
            if (d <= best) { best = d; found = i; }
        }
        return found;
    }

    // ------------------------------------------------------------ 우클릭 메뉴

    private void popup(MouseEvent e) {
        if (img == null || !markersEnabled || actions == null) return;
        JPopupMenu menu = new JPopupMenu();
        int hit = markerAt(e.getPoint());
        if (hit >= 0) {
            Marker m = markers.get(hit);
            String name = m.label().isEmpty() ? types.apply(m.typeId()).label() : m.label();
            JMenuItem edit = new JMenuItem("편집: " + name);
            edit.addActionListener(a -> actions.edit(hit));
            JMenuItem del = new JMenuItem("삭제: " + name);
            del.addActionListener(a -> actions.delete(hit));
            menu.add(edit);
            menu.add(del);
        } else {
            double s = scale();
            double nx = (e.getX() - originX()) / s / img.getWidth();
            double ny = (e.getY() - originY()) / s / img.getHeight();
            if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return;
            JMenu add = new JMenu("여기에 마커 추가");
            for (MarkerType t : menuTypes) {
                JMenuItem it = new JMenuItem(t.label(), new TypeIcon(t));
                it.addActionListener(a -> actions.add(t, nx, ny));
                add.add(it);
            }
            menu.add(add);
        }
        menu.show(this, e.getX(), e.getY());
    }

    // ------------------------------------------------------------ 그리기

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(getFont());
        if (img == null) {
            g.setColor(new Color(0x9A9DA3));
            FontMetrics fm = g.getFontMetrics();
            String[] lines = emptyText.split("\n");
            int y = getHeight() / 2 - lines.length * fm.getHeight() / 2 + fm.getAscent();
            for (String l : lines) { g.drawString(l, (getWidth() - fm.stringWidth(l)) / 2, y); y += fm.getHeight(); }
            g.dispose();
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double s = scale();
        g.drawImage(img, (int) originX(), (int) originY(), (int) (img.getWidth() * s), (int) (img.getHeight() * s), null);
        if (!markersEnabled) { g.dispose(); return; }

        List<Integer> labelled = new ArrayList<>();
        boolean searching = !highlight.isEmpty();
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (!visible(m)) continue;
            MarkerType t = types.apply(m.typeId());
            double x = sx(m), y = sy(m);
            boolean hl = searching && hit(m, t, highlight);
            if (showLabels || hl) labelled.add(i);
            // 검색 중이면 맞는 마커만 또렷하게, 나머지는 흐리게
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, searching && !hl ? 0.3f : 1f));
            if (hl || i == dragIndex) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(3f));
                g.draw(new Ellipse2D.Double(x - R - 7, y - R - 7, 2 * (R + 7), 2 * (R + 7)));
            }
            Shape shape = MarkerType.outline(t.form(), x, y, R);
            g.setColor(t.color());
            g.fill(shape);
            g.setColor(new Color(0, 0, 0, 200));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(shape);
        }
        g.setComposite(AlphaComposite.SrcOver);
        if (hovered >= 0 && hovered < markers.size() && !labelled.contains(hovered)) labelled.add(hovered);
        for (int i : labelled) drawLabel(g, markers.get(i), i == hovered);
        g.dispose();
    }

    /** 마커 이름표. 마우스를 올린 마커는 메모도 함께 보여준다 */
    private void drawLabel(Graphics2D g, Marker m, boolean withMemo) {
        MarkerType t = types.apply(m.typeId());
        List<String> lines = new ArrayList<>();
        lines.add(t.label() + (m.label().isEmpty() ? "" : " · " + m.label()));
        if (withMemo && !m.memo().isBlank()) {
            String[] memo = m.memo().strip().split("\n");
            for (int i = 0; i < Math.min(memo.length, 6); i++) lines.add(memo[i].length() > 40 ? memo[i].substring(0, 40) + "…" : memo[i]);
            if (memo.length > 6) lines.add("…");
        }
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        w += 12;
        int h = fm.getHeight() * lines.size() + 6;
        int x = (int) sx(m) + R + 8, y = (int) sy(m) - fm.getHeight() / 2 - 3;
        // 화면 오른쪽/위아래를 벗어나면 마커 왼쪽·안쪽으로 옮김
        if (x + w > getWidth() - 4) x = (int) sx(m) - R - 8 - w;
        y = Math.max(4, Math.min(y, getHeight() - h - 4));
        g.setColor(new Color(0, 0, 0, 210));
        g.fillRoundRect(x, y, w, h, 8, 8);
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(i == 0 ? Color.WHITE : new Color(0xC9CDD3));
            g.drawString(lines.get(i), x + 6, y + 3 + fm.getAscent() + i * fm.getHeight());
        }
    }

    /** 메뉴·체크박스·목록에 쓰는 종류 아이콘 (모양 + 색) */
    static final class TypeIcon implements Icon {
        private final MarkerType t;
        TypeIcon(MarkerType t) { this.t = t; }
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape s = MarkerType.outline(t.form(), x + 7, y + 7, 5);
            g.setColor(c != null && !c.isEnabled() ? Color.GRAY : t.color());
            g.fill(s);
            g.setColor(new Color(0, 0, 0, 160));
            g.draw(s);
            g.dispose();
        }
        public int getIconWidth() { return 14; }
        public int getIconHeight() { return 14; }
    }

    /** 숨긴 종류의 체크박스용 회색 아이콘 */
    static Icon dimIcon(MarkerType t) { return new TypeIcon(t.withColor(new Color(0x555555))); }
}
