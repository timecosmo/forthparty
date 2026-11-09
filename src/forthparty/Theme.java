package forthparty;

import javax.swing.*;
import javax.swing.plaf.nimbus.NimbusLookAndFeel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 밝은 테마 / 어두운 테마.
 * 자바에 기본으로 들어 있는 Nimbus 디자인의 기준 색만 바꿔서 만든다 (외부 라이브러리 없음).
 * 윈도우·맥·리눅스 어디서든 같은 모양으로 보인다.
 */
final class Theme {
    private Theme() {}

    private static boolean dark;

    private static final Map<String, Color> LIGHT = new LinkedHashMap<>();
    private static final Map<String, Color> DARK = new LinkedHashMap<>();
    static {
        // Nimbus 기본값
        LIGHT.put("control", new Color(214, 217, 223));
        LIGHT.put("info", new Color(242, 242, 189));
        LIGHT.put("nimbusBase", new Color(51, 98, 140));
        LIGHT.put("nimbusBlueGrey", new Color(169, 176, 190));
        LIGHT.put("nimbusDisabledText", new Color(142, 143, 145));
        LIGHT.put("nimbusFocus", new Color(115, 164, 209));
        LIGHT.put("nimbusLightBackground", new Color(255, 255, 255));
        LIGHT.put("nimbusSelectedText", new Color(255, 255, 255));
        LIGHT.put("nimbusSelectionBackground", new Color(57, 105, 138));
        LIGHT.put("text", new Color(0, 0, 0));
        LIGHT.put("textInactiveText", new Color(110, 112, 118));
        // 게임 화면 옆에 두기 좋은 어두운 색
        DARK.put("control", new Color(43, 45, 49));
        DARK.put("info", new Color(43, 45, 49));
        DARK.put("nimbusBase", new Color(30, 34, 40));
        DARK.put("nimbusBlueGrey", new Color(70, 74, 82));
        DARK.put("nimbusDisabledText", new Color(120, 122, 128));
        DARK.put("nimbusFocus", new Color(88, 140, 200));
        DARK.put("nimbusLightBackground", new Color(30, 31, 34));
        DARK.put("nimbusSelectedText", new Color(255, 255, 255));
        DARK.put("nimbusSelectionBackground", new Color(53, 96, 160));
        DARK.put("text", new Color(228, 230, 234));
        DARK.put("textInactiveText", new Color(150, 154, 162));
    }

    static boolean isDark() { return dark; }

    /** 테마 적용. root가 있으면 이미 만들어진 화면도 새 색으로 다시 그린다 */
    static void apply(boolean darkMode, Component root) {
        dark = darkMode;
        (darkMode ? DARK : LIGHT).forEach(UIManager::put);
        try {
            UIManager.setLookAndFeel(new NimbusLookAndFeel());
        } catch (UnsupportedLookAndFeelException e) {
            return; // Nimbus가 없으면 기본 디자인 그대로 사용
        }
        UIManager.getLookAndFeelDefaults().put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        // 확인 창 버튼을 한국어로
        UIManager.put("OptionPane.okButtonText", "확인");
        UIManager.put("OptionPane.cancelButtonText", "취소");
        UIManager.put("OptionPane.yesButtonText", "예");
        UIManager.put("OptionPane.noButtonText", "아니오");
        if (root != null) {
            SwingUtilities.updateComponentTreeUI(root);
            root.repaint();
        }
    }
}
