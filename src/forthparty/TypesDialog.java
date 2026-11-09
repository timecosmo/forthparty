package forthparty;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * 게임별 마커 종류 편집 창.
 * 종류 추가·삭제·순서 변경, 이름·색·모양 바꾸기, 다른 게임의 종류 가져오기, 기본값으로 되돌리기.
 */
final class TypesDialog {
    private TypesDialog() {}

    /**
     * @param usage 종류 id → 그 종류를 쓰는 마커 수 (삭제할 때 경고용)
     * @return 확인을 누르면 새 종류 목록
     */
    static Optional<List<MarkerType>> edit(Component parent, Game game, List<Game> others, Map<String, Integer> usage, Store store) {
        List<MarkerType> rows = new ArrayList<>(game.types());
        String[] cols = {"모양", "이름", "색", "모양 선택", "사용 중"};
        AbstractTableModel model = new AbstractTableModel() {
            public int getRowCount() { return rows.size(); }
            public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                MarkerType t = rows.get(r);
                return switch (c) {
                    case 0 -> t;
                    case 1 -> t.label();
                    case 2 -> t.color();
                    case 3 -> t.form();
                    default -> usage.getOrDefault(t.id(), 0) + "개";
                };
            }
            @Override public boolean isCellEditable(int r, int c) { return c == 1 || c == 3; }
            @Override public void setValueAt(Object v, int r, int c) {
                if (c == 1 && !v.toString().isBlank()) rows.set(r, rows.get(r).withLabel(v.toString().trim()));
                if (c == 3) rows.set(r, rows.get(r).withForm((MarkerType.Form) v));
                fireTableRowsUpdated(r, r);
            }
        };
        JTable table = new JTable(model);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        // 0: 미리보기 아이콘
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, "", s, f, r, c);
                setIcon(new MapView.TypeIcon((MarkerType) v));
                setHorizontalAlignment(CENTER);
                return this;
            }
        });
        // 2: 색 (클릭하면 색 고르기 창)
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                Color col = (Color) v;
                super.getTableCellRendererComponent(t, String.format("#%06X  (클릭해서 변경)", col.getRGB() & 0xFFFFFF), s, f, r, c);
                setIcon(new Icon() {
                    public void paintIcon(Component cc, Graphics g, int x, int y) { g.setColor(col); g.fillRect(x, y, 16, 16); g.setColor(Color.DARK_GRAY); g.drawRect(x, y, 16, 16); }
                    public int getIconWidth() { return 18; }
                    public int getIconHeight() { return 17; }
                });
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (r < 0 || c != 2) return;
                Color picked = JColorChooser.showDialog(table, "색 선택", rows.get(r).color());
                if (picked != null) { rows.set(r, rows.get(r).withColor(picked)); model.fireTableRowsUpdated(r, r); }
            }
        });
        // 3: 모양 선택 (콤보박스)
        JComboBox<MarkerType.Form> forms = new JComboBox<>(MarkerType.Form.values());
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(forms));
        int[] widths = {50, 170, 190, 100, 70};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        Runnable stopEditing = () -> { if (table.isEditing()) table.getCellEditor().stopCellEditing(); };

        JButton add = new JButton("추가"), del = new JButton("삭제"), up = new JButton("▲"), down = new JButton("▼");
        JButton fromOther = new JButton("다른 게임에서 가져오기…"), reset = new JButton("기본값으로");
        add.addActionListener(e -> {
            stopEditing.run();
            String name = JOptionPane.showInputDialog(table, "새 종류 이름 (예: 보급 상자)");
            if (name == null || name.isBlank()) return;
            MarkerType.Form[] f = MarkerType.Form.values();
            Color[] palette = {new Color(0x4DC3FF), new Color(0xFF6FB5), new Color(0x9BE15D), new Color(0xFFB347), new Color(0x7FD1C7)};
            rows.add(new MarkerType(store.newId("t_"), name.trim(), palette[rows.size() % palette.length], f[rows.size() % f.length]));
            model.fireTableDataChanged();
            table.setRowSelectionInterval(rows.size() - 1, rows.size() - 1);
        });
        del.addActionListener(e -> {
            stopEditing.run();
            int r = table.getSelectedRow();
            if (r < 0) return;
            if (rows.size() == 1) { JOptionPane.showMessageDialog(table, "종류는 하나 이상 있어야 합니다."); return; }
            int used = usage.getOrDefault(rows.get(r).id(), 0);
            if (used > 0 && JOptionPane.showConfirmDialog(table,
                    "'" + rows.get(r).label() + "' 종류를 쓰는 마커가 " + used + "개 있습니다.\n삭제하면 그 마커들은 회색 '알 수 없는 종류'로 표시됩니다. 삭제할까요?",
                    "종류 삭제", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            rows.remove(r);
            model.fireTableDataChanged();
        });
        up.addActionListener(e -> move(table, rows, model, -1, stopEditing));
        down.addActionListener(e -> move(table, rows, model, 1, stopEditing));
        fromOther.addActionListener(e -> {
            stopEditing.run();
            if (others.isEmpty()) { JOptionPane.showMessageDialog(table, "다른 게임이 없습니다."); return; }
            Game src = (Game) JOptionPane.showInputDialog(table, "어느 게임의 마커 종류를 가져올까요?\n(이미 있는 종류는 건너뜁니다)",
                    "종류 가져오기", JOptionPane.PLAIN_MESSAGE, null, others.toArray(), others.get(0));
            if (src == null) return;
            int n = 0;
            for (MarkerType t : src.types()) {
                boolean exists = rows.stream().anyMatch(x -> x.id().equals(t.id()) || x.label().equals(t.label()));
                if (!exists) { rows.add(t); n++; }
            }
            model.fireTableDataChanged();
            JOptionPane.showMessageDialog(table, n + "개 종류를 가져왔습니다.");
        });
        reset.addActionListener(e -> {
            stopEditing.run();
            if (JOptionPane.showConfirmDialog(table, "기본 7종으로 되돌릴까요?\n직접 추가한 종류를 쓰는 마커는 회색으로 표시됩니다.", "기본값으로",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            rows.clear();
            rows.addAll(MarkerType.defaults());
            model.fireTableDataChanged();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : new JButton[]{add, del, up, down, fromOther, reset}) buttons.add(b);
        JLabel hint = new JLabel("이름은 더블클릭해서 고치고, 색은 클릭해서 바꿉니다. 모양을 다르게 하면 색을 구분하기 어려워도 알아볼 수 있습니다.");
        hint.setForeground(UIManager.getColor("textInactiveText"));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(hint, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(640, 300));
        panel.add(sp, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        int r = JOptionPane.showConfirmDialog(parent, panel, game.name() + " · 마커 종류", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        stopEditing.run();
        return r == JOptionPane.OK_OPTION ? Optional.of(List.copyOf(rows)) : Optional.empty();
    }

    private static void move(JTable table, List<MarkerType> rows, AbstractTableModel model, int dir, Runnable stopEditing) {
        stopEditing.run();
        int r = table.getSelectedRow(), to = r + dir;
        if (r < 0 || to < 0 || to >= rows.size()) return;
        Collections.swap(rows, r, to);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(to, to);
    }
}
