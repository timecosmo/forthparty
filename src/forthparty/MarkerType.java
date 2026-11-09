package forthparty;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.*;
import java.util.List;

/**
 * 마커 종류 하나 (예: 탈출구). 게임마다 자기 종류 목록을 갖는다.
 * 색뿐 아니라 모양(form)으로도 구분되게 해서 색을 구분하기 어려워도 알아볼 수 있게 한다.
 */
record MarkerType(String id, String label, Color color, Form form) {

    enum Form {
        CIRCLE("원"), SQUARE("사각형"), TRIANGLE("삼각형"), DIAMOND("마름모"), STAR("별"), HEXAGON("육각형"), CROSS("십자");

        final String label;
        Form(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** 새 게임에 처음 들어가는 기본 종류. id는 예전 버전 데이터(EXTRACT 등)와 같아서 기존 마커가 그대로 읽힌다 */
    static List<MarkerType> defaults() {
        return List.of(
                new MarkerType("EXTRACT", "탈출구", new Color(0x3DDC84), Form.CIRCLE),
                new MarkerType("KEY", "열쇠/잠긴 곳", new Color(0xFFD23F), Form.SQUARE),
                new MarkerType("LOOT", "루팅", new Color(0xB57BFF), Form.DIAMOND),
                new MarkerType("ENEMY", "적", new Color(0xFF4D4D), Form.TRIANGLE),
                new MarkerType("QUEST", "퀘스트", new Color(0x4DC3FF), Form.STAR),
                new MarkerType("DANGER", "위험", new Color(0xFF8A3D), Form.CROSS),
                new MarkerType("OTHER", "기타", new Color(0xC8C8C8), Form.HEXAGON));
    }

    /** 종류 목록에서 지워진 종류를 쓰는 마커용 */
    static MarkerType unknown(String id) {
        return new MarkerType(id, "알 수 없는 종류", new Color(0x8A8A8A), Form.CIRCLE);
    }

    MarkerType withLabel(String l) { return new MarkerType(id, l, color, form); }
    MarkerType withColor(Color c) { return new MarkerType(id, label, c, form); }
    MarkerType withForm(Form f) { return new MarkerType(id, label, color, f); }

    @Override public String toString() { return label; }

    /** 저장 형식: id|이름|#RRGGBB|모양 */
    String serialize() {
        return id + "|" + label.replace('|', '/') + "|" + String.format("#%06X", color.getRGB() & 0xFFFFFF) + "|" + form.name();
    }

    static MarkerType parse(String s) {
        String[] p = s.split("\\|", 4);
        Form f;
        try { f = Form.valueOf(p[3]); } catch (RuntimeException e) { f = Form.CIRCLE; }
        return new MarkerType(p[0], p[1], Color.decode(p[2]), f);
    }

    /** 중심 (cx, cy), 크기 r 인 도형. 모양마다 눈으로 보이는 크기가 비슷하도록 조금씩 보정 */
    static Shape outline(Form f, double cx, double cy, double r) {
        return switch (f) {
            case CIRCLE -> new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            case SQUARE -> new Rectangle2D.Double(cx - r * 0.88, cy - r * 0.88, r * 1.76, r * 1.76);
            case TRIANGLE -> poly(cx, cy, new double[]{0, -1.15, 1.1, 0.85, -1.1, 0.85}, r);
            case DIAMOND -> poly(cx, cy, new double[]{0, -1.2, 1.2, 0, 0, 1.2, -1.2, 0}, r);
            case STAR -> star(cx, cy, r * 1.3, r * 0.58);
            case HEXAGON -> regular(cx, cy, r * 1.08, 6, 0);
            case CROSS -> {
                Area a = new Area(new Rectangle2D.Double(cx - r * 1.05, cy - r * 0.38, r * 2.1, r * 0.76));
                a.add(new Area(new Rectangle2D.Double(cx - r * 0.38, cy - r * 1.05, r * 0.76, r * 2.1)));
                yield a;
            }
        };
    }

    private static Shape poly(double cx, double cy, double[] pts, double r) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < pts.length; i += 2) {
            if (i == 0) p.moveTo(cx + pts[i] * r, cy + pts[i + 1] * r); else p.lineTo(cx + pts[i] * r, cy + pts[i + 1] * r);
        }
        p.closePath();
        return p;
    }

    private static Shape regular(double cx, double cy, double r, int n, double startDeg) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(startDeg + 360.0 * i / n - 90);
            if (i == 0) p.moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a)); else p.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
        p.closePath();
        return p;
    }

    private static Shape star(double cx, double cy, double outer, double inner) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? outer : inner, a = Math.toRadians(36 * i - 90);
            if (i == 0) p.moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a)); else p.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
        p.closePath();
        return p;
    }
}
