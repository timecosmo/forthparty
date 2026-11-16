package forthparty;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/**
 * 맵 이미지 + 마커 뷰어.
 * 휠: 부드러운 확대/축소(커서 기준) · 빈 곳 드래그: 지도 이동(놓으면 미끄러지듯 멈춤) · 마커 드래그: 마커 옮기기
 * 이미지 파일을 끌어다 놓으면 지도 이미지로 지정
 * 마커 더블클릭: 편집 · 빈 곳 더블클릭: 원래 크기 · 우클릭: 추가/편집/삭제 메뉴
 *
 * 마커를 실제로 바꾸는 일(저장, 되돌리기 기록, 편집 창)은 App이 맡고, 이 클래스는 화면과 마우스만 다룬다.
 */
final class MapView extends JComponent {
    private static final Color BG = new Color(0x1E1F22);
    private static final int R = 8;
    // 매 프레임 새로 만들지 않도록 미리 만들어 두는 그리기 도구
    private static final Color OUTLINE = new Color(0, 0, 0, 200);
    private static final Stroke OUTLINE_STROKE = new BasicStroke(1.6f), RING_STROKE = new BasicStroke(3f);
    private static final Composite DIM = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f);
    private static final Shape RING = new Ellipse2D.Double(-R - 7, -R - 7, 2 * (R + 7), 2 * (R + 7));
    /** 모양별 도형 (원점 기준). 그릴 때는 좌표만 옮겨서 재사용한다 */
    private static final Map<MarkerType.Form, Shape> SHAPES = new EnumMap<>(MarkerType.Form.class);
    static { for (MarkerType.Form f : MarkerType.Form.values()) SHAPES.put(f, MarkerType.outline(f, 0, 0, R)); }

    /** App이 받아서 처리하는 사용자 동작 */
    interface Actions {
        void moved(List<Marker> markers);                 // 드래그로 마커 위치가 바뀜
        void add(MarkerType type, double x, double y);    // 우클릭 메뉴에서 추가
        void edit(int index);                             // 편집 (더블클릭 또는 메뉴)
        void delete(int index);                           // 삭제 (메뉴)
        void imageDropped(java.io.File file);             // 이미지 파일을 끌어다 놓음
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
    /** 마커별 검색 일치 여부. 검색어·마커·종류가 바뀔 때만 다시 계산한다 (그릴 때마다 계산하면 느림) */
    private boolean[] hits = new boolean[0];

    // 화면 상태(zoom, pan)는 목표값(tZoom, tPan)을 향해 매 프레임 조금씩 다가간다 → 부드러운 확대와 이동
    private static final double MAX_ZOOM = 24, OVERSCROLL = 80;
    private double zoom = 1, panX, panY;
    private double tZoom = 1, tPanX, tPanY;
    private long lastStep;
    private final javax.swing.Timer anim = new javax.swing.Timer(1000 / 60, e -> step());
    // 지도를 끌다 놓았을 때의 속도 (px/ms) → 관성으로 조금 더 미끄러짐
    private double velX, velY;
    private long lastDragTime;
    private Point lastDragPoint;
    // 축소 화면을 선명하고 빠르게 그리기 위한 1/2, 1/4, ... 크기 이미지
    private final List<BufferedImage> levels = new ArrayList<>();
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
                velX = velY = 0;
                lastDragPoint = e.getPoint();
                lastDragTime = System.nanoTime();
                if (dragIndex < 0) stopAnimation(); // 미끄러지는 중에 잡으면 그 자리에서 멈춤
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) popup(e);
                if (dragIndex >= 0 && dragMoved && actions != null) actions.moved(markers);
                // 지도를 빠르게 끌다 놓으면 그 방향으로 조금 더 미끄러진다
                boolean flick = dragIndex < 0 && dragStart != null && img != null
                        && (System.nanoTime() - lastDragTime) < 80_000_000L && Math.hypot(velX, velY) > 0.2;
                if (flick) {
                    tPanX = limitPan(panX + velX * 220, img.getWidth() * scale(), getWidth());
                    tPanY = limitPan(panY + velY * 220, img.getHeight() * scale(), getHeight());
                    animate();
                }
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
                    panX = tPanX = limitPan(dragPanX + e.getX() - dragStart.x, img.getWidth() * scale(), getWidth());
                    panY = tPanY = limitPan(dragPanY + e.getY() - dragStart.y, img.getHeight() * scale(), getHeight());
                    long now = System.nanoTime();
                    double dt = Math.max(1, (now - lastDragTime) / 1e6);
                    velX = velX * 0.5 + (e.getX() - lastDragPoint.x) / dt * 0.5;
                    velY = velY * 0.5 + (e.getY() - lastDragPoint.y) / dt * 0.5;
                    lastDragTime = now;
                    lastDragPoint = e.getPoint();
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
                if (m >= 0 && actions != null) actions.edit(m); else fit();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                // 터치패드처럼 조금씩 굴리는 입력도 그만큼만 확대되도록 정밀 회전값 사용
                zoomAt(e.getX(), e.getY(), Math.pow(1.25, -e.getPreciseWheelRotation()));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        // 창 크기가 바뀌면 지도가 이동 범위를 벗어나지 않게 다시 맞춘다
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (img == null || getWidth() == 0 || getHeight() == 0) return;
                double fit = scale() / zoom;
                panX = limitPan(panX, img.getWidth() * fit * zoom, getWidth());
                panY = limitPan(panY, img.getHeight() * fit * zoom, getHeight());
                tPanX = limitPan(tPanX, img.getWidth() * fit * tZoom, getWidth());
                tPanY = limitPan(tPanY, img.getHeight() * fit * tZoom, getHeight());
                repaint();
            }
        });

        // 탐색기에서 이미지 파일을 끌어다 놓으면 지도 이미지로 지정
        setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport t) { return t.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport t) {
                if (!canImport(t) || actions == null) return false;
                try {
                    List<?> files = (List<?>) t.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) return false;
                    java.io.File f = (java.io.File) files.get(0);
                    SwingUtilities.invokeLater(() -> actions.imageDropped(f));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
    }

    // ------------------------------------------------------------ App이 부르는 메서드

    void setContent(BufferedImage img, List<Marker> markers, boolean markersEnabled, String emptyText) {
        this.img = img;
        this.markers = markers;
        this.markersEnabled = markersEnabled;
        this.emptyText = emptyText;
        hovered = -1;
        dragIndex = -1;
        refreshHits();
        buildLevels(img);
        stopAnimation();
        zoom = tZoom = 1;
        panX = panY = tPanX = tPanY = 0;
        repaint();
    }

    /** 화면 가운데를 기준으로 확대/축소 (단축키용) */
    void zoomIn() { zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.5); }
    void zoomOut() { zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1 / 1.5); }

    /** 지도 전체가 보이는 크기로 부드럽게 돌아감 */
    void fit() { tZoom = 1; tPanX = tPanY = 0; animate(); }

    /** 게임이 바뀌면 종류 목록(우클릭 메뉴)과 id → 종류 찾는 방법을 새로 받는다 */
    void setTypes(List<MarkerType> menuTypes, Function<String, MarkerType> resolver) {
        this.menuTypes = menuTypes;
        this.types = resolver;
        refreshHits();
        repaint();
    }

    void setMarkers(List<Marker> markers) { this.markers = markers; hovered = -1; refreshHits(); repaint(); }
    void setActions(Actions a) { actions = a; }
    void setShowLabels(boolean v) { showLabels = v; repaint(); }
    void setHighlight(String q) { highlight = q == null ? "" : q.trim(); refreshHits(); repaint(); }

    private void refreshHits() {
        hits = new boolean[markers.size()];
        if (highlight.isEmpty()) return;
        for (int i = 0; i < hits.length; i++) hits[i] = hit(markers.get(i), types.apply(markers.get(i).typeId()), highlight);
    }
    void setTypeVisible(String typeId, boolean v) { if (v) hidden.remove(typeId); else hidden.add(typeId); repaint(); }

    /** 검색어가 마커 이름·메모·종류 이름 중 하나와 맞는지 */
    static boolean hit(Marker m, MarkerType t, String q) {
        return Search.matches(m.label() + " " + m.memo() + " " + t.label(), q);
    }

    // ------------------------------------------------------------ 좌표 계산

    /** (px, py) 아래의 지도 지점이 그대로 그 자리에 있도록 확대/축소 목표를 정한다 */
    private void zoomAt(double px, double py, double factor) {
        if (img == null || getWidth() == 0 || getHeight() == 0) return;
        double s0 = scale();
        double ix = (px - originX()) / s0, iy = (py - originY()) / s0;   // 커서 아래의 이미지 좌표
        tZoom = Math.max(1, Math.min(MAX_ZOOM, tZoom * factor));
        double s1 = scale() / zoom * tZoom;                               // 목표 배율
        tPanX = limitPan(px - ix * s1 - (getWidth() - img.getWidth() * s1) / 2, img.getWidth() * s1, getWidth());
        tPanY = limitPan(py - iy * s1 - (getHeight() - img.getHeight() * s1) / 2, img.getHeight() * s1, getHeight());
        animate();
    }

    /** 지도가 화면 밖으로 사라지지 않게 이동 범위를 제한. 지도가 화면보다 작으면 가운데 고정 */
    private static double limitPan(double pan, double imageSize, double viewSize) {
        double extra = imageSize - viewSize;
        if (extra <= 0) return 0;
        double max = extra / 2 + OVERSCROLL;
        return Math.max(-max, Math.min(max, pan));
    }

    private void animate() {
        if (!anim.isRunning()) { lastStep = System.nanoTime(); anim.start(); }
    }

    private void stopAnimation() {
        anim.stop();
        tZoom = zoom; tPanX = panX; tPanY = panY;
    }

    /** 한 프레임: 현재값을 목표값 쪽으로 일정 비율만큼 옮긴다 (처음엔 빠르게, 끝에선 천천히) */
    private void step() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastStep) / 1e9);
        lastStep = now;
        double f = 1 - Math.exp(-dt * 14);
        // 세 값을 같은 비율로 옮기므로 확대 중에도 커서 아래 지점이 흔들리지 않는다
        zoom += (tZoom - zoom) * f;
        panX += (tPanX - panX) * f;
        panY += (tPanY - panY) * f;
        if (Math.abs(tZoom - zoom) < 0.001 * zoom && Math.abs(tPanX - panX) < 0.4 && Math.abs(tPanY - panY) < 0.4) {
            zoom = tZoom; panX = tPanX; panY = tPanY;
            anim.stop();
        }
        repaint();
    }

    /** 원본과 1/2, 1/4 ... 크기 이미지를 미리 만들어 둔다. 축소해서 볼 때 가장 가까운 크기를 골라 그린다 */
    private void buildLevels(BufferedImage src) {
        if (src != null && src == source) return;   // 같은 이미지면 다시 만들지 않음
        levels.clear();
        source = src;
        if (src == null) return;
        BufferedImage cur = toFastFormat(src);
        int type = cur.getType();
        levels.add(cur);
        while (cur.getWidth() > 600 && cur.getHeight() > 600 && levels.size() < 6) {
            BufferedImage half = new BufferedImage((cur.getWidth() + 1) / 2, (cur.getHeight() + 1) / 2, type);
            Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null);
            hg.dispose();
            levels.add(half);
            cur = half;
        }
    }
    private BufferedImage source;

    /** 화면에 빨리 그릴 수 있는 형식(INT_RGB / INT_ARGB)으로 변환. 이미 그 형식이면 복사하지 않고 그대로 쓴다 */
    static BufferedImage toFastFormat(BufferedImage src) {
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (src.getType() == type) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), type);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
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
        // 지금 배율에 가장 가까운 크기의 이미지를 골라 그린다 (축소해도 자글거리지 않고 빠름)
        int k = 0;
        while (k + 1 < levels.size() && s * (1 << (k + 1)) <= 1.0) k++;
        BufferedImage level = levels.get(k);
        AffineTransform at = new AffineTransform();
        at.translate(originX(), originY());   // 소수점 좌표 그대로 그려야 움직일 때 떨리지 않는다
        at.scale(img.getWidth() * s / level.getWidth(), img.getHeight() * s / level.getHeight());
        g.drawImage(level, at, null);
        if (zoom > 1.01) drawZoomChip(g);
        if (!markersEnabled) { g.dispose(); return; }

        List<Integer> labelled = new ArrayList<>();
        boolean searching = !highlight.isEmpty();
        // 좌표 계산에 쓰는 값은 한 번만 구한다
        double ox = originX(), oy = originY(), iw = img.getWidth() * s, ih = img.getHeight() * s;
        int w = getWidth(), h = getHeight(), margin = R + 12;
        AffineTransform base = g.getTransform();
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (!visible(m)) continue;
            double x = ox + m.x() * iw, y = oy + m.y() * ih;
            if (x < -margin || y < -margin || x > w + margin || y > h + margin) continue;   // 화면 밖 마커는 그리지 않음
            MarkerType t = types.apply(m.typeId());
            boolean hl = searching && i < hits.length && hits[i];
            if (showLabels || hl) labelled.add(i);
            // 검색 중이면 맞는 마커만 또렷하게, 나머지는 흐리게
            g.setComposite(searching && !hl ? DIM : AlphaComposite.SrcOver);
            g.translate(x, y);
            if (hl || i == dragIndex) {
                g.setColor(Color.WHITE);
                g.setStroke(RING_STROKE);
                g.draw(RING);
            }
            Shape shape = SHAPES.get(t.form());
            g.setColor(t.color());
            g.fill(shape);
            g.setColor(OUTLINE);
            g.setStroke(OUTLINE_STROKE);
            g.draw(shape);
            g.setTransform(base);
        }
        g.setComposite(AlphaComposite.SrcOver);
        if (hovered >= 0 && hovered < markers.size() && visible(markers.get(hovered)) && !labelled.contains(hovered)) labelled.add(hovered);
        for (int i : labelled) drawLabel(g, markers.get(i), i == hovered);
        g.dispose();
    }

    /** 오른쪽 아래의 현재 배율 표시 */
    private void drawZoomChip(Graphics2D g) {
        String text = Math.round(zoom * 100) + "%";
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 14, h = fm.getHeight() + 4;
        int x = getWidth() - w - 10, y = getHeight() - h - 10;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x, y, w, h, h, h);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 7, y + 2 + fm.getAscent());
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
