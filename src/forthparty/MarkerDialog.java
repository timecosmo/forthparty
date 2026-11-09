package forthparty;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Optional;

/** 마커 추가·편집 창: 종류, 이름, 메모 */
final class MarkerDialog {
    private MarkerDialog() {}

    static Optional<Marker> show(Component parent, String title, Marker m, List<MarkerType> types, MarkerType current) {
        JComboBox<MarkerType> type = new JComboBox<>(types.toArray(MarkerType[]::new));
        type.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof MarkerType t) setIcon(new MapView.TypeIcon(t));
                return this;
            }
        });
        // 목록에서 지워진 종류를 쓰는 마커면 그 종류도 선택지에 넣어 둔다
        if (!types.contains(current)) type.addItem(current);
        type.setSelectedItem(current);

        JTextField name = new JTextField(m.label(), 24);
        JTextArea memo = new JTextArea(m.memo(), 6, 24);
        memo.setLineWrap(true);
        memo.setWrapStyleWord(true);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("종류"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(type, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("이름"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(name, c);
        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("메모"), c);
        c.gridx = 1; c.fill = GridBagConstraints.BOTH; c.weighty = 1; form.add(new JScrollPane(memo), c);
        c.gridx = 1; c.gridy = 3; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        JLabel hint = new JLabel("예) 열쇠는 2층 책상 서랍");
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 2f));
        hint.setForeground(UIManager.getColor("textInactiveText"));
        form.add(hint, c);

        // 창이 뜨면 이름 칸에 바로 입력할 수 있게
        SwingUtilities.invokeLater(name::requestFocusInWindow);
        int r = JOptionPane.showConfirmDialog(parent, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return Optional.empty();
        MarkerType t = (MarkerType) type.getSelectedItem();
        return Optional.of(m.withContent(t.id(), name.getText().trim(), memo.getText().strip()));
    }
}
