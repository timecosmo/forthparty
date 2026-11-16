package forthparty;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * forthparty — 게임 목록 → 맵 목록 → 맵 마킹.
 * 단축키: Enter 열기 · Esc 뒤로 · Ctrl+Z 되돌리기 · Ctrl+Y 다시 하기 · Ctrl+F 검색 · Ctrl+L 이름 전체 표시 · Ctrl+T 항상 위
 */
public final class App {
    private static final String GAMES = "games", MAPS = "maps", VIEW = "view";
    private static final int UNDO_LIMIT = 100;

    private final Store store;
    // 지도 이미지는 지금 보고 있는 맵 하나만 메모리에 둔다 (큰 이미지가 맵마다 쌓이지 않게)
    private String loadedKey;
    private BufferedImage loadedImage;
    private SwingWorker<BufferedImage, Void> imageLoader;
    /** 맵 목록에 "이미지 없음"을 표시하기 위한 정보. 목록을 열 때 한 번만 디스크를 확인한다 */
    private final Set<String> mapsWithImage = new HashSet<>();
    private static final String NO_IMAGE_HINT = "맵 이미지가 없습니다\n\n게임 지도를 캡처한 뒤 여기서 Ctrl+V 로 붙여넣거나\n이미지 파일을 이 화면에 끌어다 놓으세요\n(오른쪽 위 '맵 이미지 지정…'으로 파일을 고를 수도 있습니다)";

    private final JFrame frame = new JFrame("forthparty");
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private String screen = GAMES;

    private final ListScreen<Game> gamesScreen = new ListScreen<>();
    private final ListScreen<GameMap> mapsScreen = new ListScreen<>();

    private final JLabel viewTitle = new JLabel();
    private final MapView mapView = new MapView();
    private final JLabel viewStatus = new JLabel(" ");
    private final JTextField markerSearch = new JTextField(14);
    private final JPanel typeFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final Map<String, JCheckBox> typeBoxes = new LinkedHashMap<>();

    // 되돌리기 / 다시 하기: 바뀌기 전 마커 목록을 통째로 쌓아 둔다 (맵 하나에 마커가 많아야 수백 개라 충분히 가볍다)
    private final Deque<List<Marker>> undo = new ArrayDeque<>();
    private final Deque<List<Marker>> redo = new ArrayDeque<>();

    private Game game;      // 현재 게임
    private GameMap map;    // 현재 맵

    App(Store store) {
        this.store = store;
        buildGamesScreen();
        buildMapsScreen();
        root.add(gamesScreen.panel, GAMES);
        root.add(mapsScreen.panel, MAPS);
        root.add(buildViewScreen(), VIEW);

        JToggleButton darkBtn = new JToggleButton("어두운 테마", Theme.isDark());
        darkBtn.setFocusable(false);
        darkBtn.addActionListener(e -> {
            Theme.apply(darkBtn.isSelected(), frame);
            save(() -> store.putSetting("theme", darkBtn.isSelected() ? "dark" : "light"));
        });
        JToggleButton onTop = new JToggleButton("항상 위");
        onTop.setFocusable(false);
        onTop.addActionListener(e -> frame.setAlwaysOnTop(onTop.isSelected()));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        top.add(darkBtn);
        top.add(onTop);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(root, BorderLayout.CENTER);
        bind(content, "ESCAPE", this::back);
        bind(content, "ctrl T", onTop::doClick);

        frame.setContentPane(content);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1180, 780);
        frame.setLocationByPlatform(true);
        showGames();
    }

    // ================================================================ 1. 게임 목록

    private void buildGamesScreen() {
        gamesScreen.init("게임 목록", null, "게임 추가", this::openMaps);
        gamesScreen.list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                Game g = (Game) v;
                super.getListCellRendererComponent(l, g.name() + "   · 마커 종류 " + g.types().size() + "개", i, s, f);
                setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
                return this;
            }
        });
        gamesScreen.onAdd = () -> {
            String name = ask("게임 이름", "");
            if (name == null) return;
            Game g = new Game(store.newId("g_"), name, MarkerType.defaults());
            save(() -> store.saveGame(g));
            showGames();
            gamesScreen.list.setSelectedValue(g, true);
        };
        gamesScreen.onRename = g -> {
            String name = ask("게임 이름 변경", g.name());
            if (name != null) { save(() -> store.saveGame(g.withName(name))); showGames(); }
        };
        gamesScreen.onDelete = g -> {
            if (confirm("'" + g.name() + "' 게임과 안의 맵·마커를 모두 삭제할까요?")) {
                save(() -> store.deleteGame(g));
                showGames();
            }
        };
        gamesScreen.extraButton("마커 종류…", () -> {
            Game g = gamesScreen.list.getSelectedValue();
            if (g != null) editTypes(g);
        });
    }

    private void showGames() {
        try { gamesScreen.setItems(store.listGames()); } catch (Exception e) { error(e); }
        gamesScreen.hint.setText("Enter 또는 더블클릭으로 열기");
        switchTo(GAMES);
        gamesScreen.list.requestFocusInWindow();
    }

    // ================================================================ 2. 맵 목록

    private void buildMapsScreen() {
        mapsScreen.init("", "← 게임 목록", "맵 추가", this::openView);
        mapsScreen.onBack = this::showGames;
        mapsScreen.list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                GameMap m = (GameMap) v;
                String extra = (mapsWithImage.contains(m.id()) ? "" : "  · 이미지 없음")
                        + (m.markers().isEmpty() ? "" : "  · 마커 " + m.markers().size());
                super.getListCellRendererComponent(l, m.name() + extra, i, s, f);
                setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
                return this;
            }
        });
        mapsScreen.onAdd = () -> {
            String name = ask("맵 이름", "");
            if (name == null) return;
            GameMap m = new GameMap(game.id(), store.newId("m_"), name, List.of());
            save(() -> store.saveMap(m));
            openMaps(game);
            mapsScreen.list.setSelectedValue(m, true);
        };
        mapsScreen.onRename = m -> {
            String name = ask("맵 이름 변경", m.name());
            if (name != null) { save(() -> store.saveMap(m.withName(name))); openMaps(game); }
        };
        mapsScreen.onDelete = m -> {
            if (confirm("'" + m.name() + "' 맵과 마커 " + m.markers().size() + "개를 삭제할까요?")) {
                save(() -> store.deleteMap(m));
                if (key(m).equals(loadedKey)) { loadedKey = null; loadedImage = null; }
                openMaps(game);
            }
        };
        mapsScreen.extraButton("가져오기…", this::importMap);
        mapsScreen.extraButton("내보내기…", this::exportMap);
    }

    private void openMaps(Game g) {
        try { game = store.loadGame(g.id()); } catch (Exception e) { game = g; }
        mapsScreen.title.setText(game.name());
        try {
            List<GameMap> maps = store.listMaps(game);
            mapsWithImage.clear();
            for (GameMap m : maps) if (store.imageFor(m).isPresent()) mapsWithImage.add(m.id());
            mapsScreen.setItems(maps);
        } catch (Exception e) { error(e); }
        mapsScreen.hint.setText("Enter 또는 더블클릭으로 열기 · Esc 뒤로");
        switchTo(MAPS);
        mapsScreen.list.requestFocusInWindow();
    }

    /** 선택한 맵을 .fpmap 파일 하나로 저장 → 친구에게 전달 */
    private void exportMap() {
        GameMap m = mapsScreen.list.getSelectedValue();
        if (m == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("forthparty 맵 (." + Store.SHARE_EXT + ")", Store.SHARE_EXT));
        fc.setSelectedFile(new java.io.File(m.name().replaceAll("[\\\\/:*?\"<>|]", "_") + "." + Store.SHARE_EXT));
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        if (!out.toString().endsWith("." + Store.SHARE_EXT)) out = out.resolveSibling(out.getFileName() + "." + Store.SHARE_EXT);
        Path target = out;
        save(() -> store.exportMap(game, m, target));
        mapsScreen.hint.setText("내보냄: " + target.getFileName() + " (마커 " + m.markers().size() + "개)");
    }

    /** 받은 .fpmap 파일을 현재 게임에 새 맵으로 추가 (없는 마커 종류도 함께 추가됨) */
    private void importMap() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("forthparty 맵 (." + Store.SHARE_EXT + ")", Store.SHARE_EXT));
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            GameMap m = store.importMap(game, fc.getSelectedFile().toPath());
            openMaps(game);
            mapsScreen.list.setSelectedValue(m, true);
            mapsScreen.hint.setText("가져옴: " + m.name() + " (마커 " + m.markers().size() + "개)");
        } catch (Exception e) { error(e); }
    }

    // ================================================================ 3. 맵 화면 (마킹)

    private JPanel buildViewScreen() {
        JButton back = new JButton("← 맵 목록");
        back.setFocusable(false);
        back.addActionListener(e -> back());
        viewTitle.setFont(viewTitle.getFont().deriveFont(Font.BOLD, 20f));

        JButton typesBtn = new JButton("마커 종류…");
        typesBtn.setFocusable(false);
        typesBtn.addActionListener(e -> editTypes(game));
        JButton imgBtn = new JButton("맵 이미지 지정…");
        imgBtn.setFocusable(false);
        imgBtn.addActionListener(e -> chooseImage());
        JToggleButton labels = new JToggleButton("이름 전체 표시");
        labels.setFocusable(false);
        labels.addActionListener(e -> mapView.setShowLabels(labels.isSelected()));

        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.add(back, BorderLayout.WEST);
        head.add(viewTitle, BorderLayout.CENTER);
        JPanel headRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headRight.add(labels);
        headRight.add(typesBtn);
        headRight.add(imgBtn);
        head.add(headRight, BorderLayout.EAST);

        // 마커 검색: 입력하는 즉시 맞는 마커만 강조 (이름·메모·종류, 초성 가능)
        markerSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchChanged(); }
            public void removeUpdate(DocumentEvent e) { searchChanged(); }
            public void changedUpdate(DocumentEvent e) { searchChanged(); }
        });
        JPanel searchBox = new JPanel(new BorderLayout(6, 0));
        searchBox.add(new JLabel("검색"), BorderLayout.WEST);
        searchBox.add(markerSearch, BorderLayout.CENTER);

        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
        filterRow.add(typeFilters, BorderLayout.CENTER);
        filterRow.add(searchBox, BorderLayout.EAST);

        JPanel north = new JPanel(new GridLayout(0, 1, 0, 6));
        north.add(head);
        north.add(filterRow);

        mapView.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        mapView.setActions(new MapView.Actions() {
            public void moved(List<Marker> markers) { commit(markers, "마커를 옮겼습니다"); }
            public void add(MarkerType type, double x, double y) { addMarker(type, x, y); }
            public void edit(int index) { editMarker(index); }
            public void delete(int index) { deleteMarker(index); }
            public void imageDropped(java.io.File file) { setImageFromFile(file.toPath()); }
        });

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        p.add(north, BorderLayout.NORTH);
        p.add(mapView, BorderLayout.CENTER);
        p.add(viewStatus, BorderLayout.SOUTH);
        bind(p, "ctrl L", labels::doClick);
        bind(p, "ctrl F", () -> { markerSearch.requestFocusInWindow(); markerSearch.selectAll(); });
        bind(p, "ctrl Z", this::undo);
        bind(p, "ctrl Y", this::redo);
        bind(p, "ctrl shift Z", this::redo);
        bind(p, "ctrl V", this::pasteImage);
        for (String k : new String[]{"ctrl EQUALS", "ctrl ADD", "ctrl shift EQUALS"}) bind(p, k, mapView::zoomIn);
        for (String k : new String[]{"ctrl MINUS", "ctrl SUBTRACT"}) bind(p, k, mapView::zoomOut);
        for (String k : new String[]{"ctrl 0", "ctrl NUMPAD0"}) bind(p, k, mapView::fit);
        return p;
    }

    private void openView(GameMap m) {
        map = m;
        viewTitle.setText(game.name() + "  ›  " + m.name());
        applyGameTypes();
        loadImage(null);
        undo.clear();
        redo.clear();
        markerSearch.setText("");
        updateKindCounts();
        updateViewStatus(null);
        switchTo(VIEW);
        mapView.requestFocusInWindow();
    }

    /** 현재 게임의 마커 종류로 필터 체크박스와 우클릭 메뉴를 다시 만든다 */
    private void applyGameTypes() {
        mapView.setTypes(game.types(), game::type);
        typeFilters.removeAll();
        typeBoxes.clear();
        for (MarkerType t : game.types()) {
            JCheckBox cb = new JCheckBox(t.label(), new MapView.TypeIcon(t), true);
            cb.setFocusable(false);
            cb.addActionListener(e -> setTypeVisible(t, cb.isSelected()));
            typeBoxes.put(t.id(), cb);
            typeFilters.add(cb);
            mapView.setTypeVisible(t.id(), true);
        }
        JButton showAll = new JButton("전체 표시"), hideAll = new JButton("전체 숨김");
        for (JButton b : new JButton[]{showAll, hideAll}) { b.setFocusable(false); b.setMargin(new Insets(2, 8, 2, 8)); typeFilters.add(b); }
        showAll.addActionListener(e -> game.types().forEach(t -> setTypeVisible(t, true)));
        hideAll.addActionListener(e -> game.types().forEach(t -> setTypeVisible(t, false)));
        typeFilters.revalidate();
        typeFilters.repaint();
    }

    private void setTypeVisible(MarkerType t, boolean on) {
        JCheckBox cb = typeBoxes.get(t.id());
        cb.setSelected(on);
        cb.setIcon(on ? new MapView.TypeIcon(t) : MapView.dimIcon(t));
        mapView.setTypeVisible(t.id(), on);
    }

    /** 체크박스 옆 종류별 개수 갱신 (예: "탈출구 2") */
    private void updateKindCounts() {
        Map<String, Integer> n = new HashMap<>();
        for (Marker m : map.markers()) n.merge(m.typeId(), 1, Integer::sum);
        for (MarkerType t : game.types()) {
            JCheckBox cb = typeBoxes.get(t.id());
            if (cb != null) cb.setText(t.label() + " " + n.getOrDefault(t.id(), 0));
        }
    }

    private void searchChanged() {
        mapView.setHighlight(markerSearch.getText());
        updateViewStatus(null);
    }

    private void updateViewStatus(String message) {
        String q = markerSearch.getText().trim();
        StringBuilder sb = new StringBuilder();
        if (message != null) sb.append(message).append("   ·   ");
        if (!q.isEmpty()) sb.append("'").append(q).append("' 일치 ")
                .append(map.markers().stream().filter(m -> MapView.hit(m, game.type(m.typeId()), q)).count()).append("개   ·   ");
        sb.append("마커 ").append(map.markers().size()).append("개   ·   우클릭: 추가   ·   드래그: 옮기기   ·   더블클릭: 편집   ·   휠: 확대   ·   ")
                .append("Ctrl+Z: 되돌리기").append(undo.isEmpty() ? "" : " (" + undo.size() + ")").append("   ·   Ctrl+F: 검색");
        viewStatus.setText(sb.toString());
    }

    // ---------------------------------------------------------------- 마커 변경 (모두 commit을 거쳐 저장 + 되돌리기 기록)

    private void addMarker(MarkerType type, double x, double y) {
        MarkerDialog.show(frame, "마커 추가", new Marker(type.id(), x, y, "", ""), game.types(), type).ifPresent(m -> {
            List<Marker> next = new ArrayList<>(map.markers());
            next.add(m);
            commit(next, "마커를 추가했습니다");
        });
    }

    private void editMarker(int index) {
        Marker old = map.markers().get(index);
        MarkerDialog.show(frame, "마커 편집", old, game.types(), game.type(old.typeId())).ifPresent(m -> {
            if (m.equals(old)) return;
            List<Marker> next = new ArrayList<>(map.markers());
            next.set(index, m);
            commit(next, "마커를 고쳤습니다");
        });
    }

    private void deleteMarker(int index) {
        List<Marker> next = new ArrayList<>(map.markers());
        next.remove(index);
        commit(next, "마커를 삭제했습니다 (Ctrl+Z로 되돌리기)");
    }

    /** 마커 목록을 바꾸고 즉시 저장. 바뀌기 전 상태는 되돌리기용으로 쌓는다 */
    private void commit(List<Marker> next, String message) {
        undo.push(map.markers());
        if (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
        persist(next, message);
    }

    private void undo() {
        if (!VIEW.equals(screen) || undo.isEmpty()) return;
        redo.push(map.markers());
        persist(undo.pop(), "되돌렸습니다");
    }

    private void redo() {
        if (!VIEW.equals(screen) || redo.isEmpty()) return;
        undo.push(map.markers());
        persist(redo.pop(), "다시 했습니다");
    }

    private void persist(List<Marker> markers, String message) {
        GameMap updated = map.withMarkers(markers);
        try {
            store.saveMap(updated);
        } catch (Exception e) {
            error(e);
            mapView.setMarkers(map.markers()); // 저장 실패 시 화면을 원래대로
            return;
        }
        map = updated;
        mapView.setMarkers(map.markers());
        updateKindCounts();
        updateViewStatus(message);
    }

    // ---------------------------------------------------------------- 마커 종류 편집

    private void editTypes(Game g) {
        try {
            Game fresh = store.loadGame(g.id());
            Map<String, Integer> usage = new HashMap<>();
            for (GameMap m : store.listMaps(fresh)) for (Marker mk : m.markers()) usage.merge(mk.typeId(), 1, Integer::sum);
            List<Game> others = store.listGames().stream().filter(x -> !x.id().equals(fresh.id())).toList();
            Optional<List<MarkerType>> result = TypesDialog.edit(frame, fresh, others, usage, store);
            if (result.isEmpty()) return;
            Game updated = fresh.withTypes(result.get());
            store.saveGame(updated);
            if (game != null && game.id().equals(updated.id())) game = updated;
            switch (screen) {
                case VIEW -> { applyGameTypes(); updateKindCounts(); updateViewStatus("마커 종류를 저장했습니다"); }
                case MAPS -> openMaps(updated);
                default -> { showGames(); gamesScreen.list.setSelectedValue(updated, true); }
            }
        } catch (Exception e) { error(e); }
    }

    private void chooseImage() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("이미지 (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        setImageFromFile(fc.getSelectedFile().toPath());
    }

    /** 파일 선택 또는 끌어다 놓기로 받은 이미지 파일을 지도로 지정 */
    private void setImageFromFile(Path file) {
        if (!VIEW.equals(screen)) return;
        if (!Store.isImageFile(file)) { error(new Exception("png, jpg, gif 이미지 파일만 넣을 수 있습니다")); return; }
        if (!confirmReplaceImage()) return;
        try { store.setImage(map, file); } catch (Exception e) { error(e); return; }
        loadedKey = null;
        loadImage("지도 이미지를 지정했습니다");
    }

    /** Ctrl+V: 클립보드의 캡처 이미지(또는 복사한 이미지 파일)를 지도로 지정 */
    private void pasteImage() {
        if (!VIEW.equals(screen)) return;
        try {
            var clip = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                Image img = (Image) clip.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
                BufferedImage copy = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = copy.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                if (img.getWidth(null) <= 0 || !confirmReplaceImage()) return;
                store.setImage(map, copy);
                // 방금 붙여넣은 이미지를 그대로 화면에 쓴다 (저장한 파일을 다시 읽지 않음)
                loadedKey = key(map);
                loadedImage = copy;
                loadImage("붙여넣은 이미지를 지도로 지정했습니다");
            } else if (clip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                List<?> files = (List<?>) clip.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                if (!files.isEmpty()) setImageFromFile(((java.io.File) files.get(0)).toPath());
            } else {
                updateViewStatus("클립보드에 이미지가 없습니다 (지도를 캡처한 뒤 Ctrl+V)");
            }
        } catch (Exception e) { error(e); }
    }

    /** 이미 지도와 마커가 있으면, 실수로 바꾸지 않도록 한 번 물어본다 */
    private boolean confirmReplaceImage() {
        if (store.imageFor(map).isEmpty() || map.markers().isEmpty()) return true;
        return JOptionPane.showConfirmDialog(frame,
                "이 맵에는 이미 지도 이미지와 마커 " + map.markers().size() + "개가 있습니다.\n"
                        + "다른 구도의 이미지로 바꾸면 마커 위치가 지도와 어긋날 수 있습니다. 바꿀까요?",
                "지도 이미지 바꾸기", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    /**
     * 현재 맵의 지도 이미지를 화면에 올린다.
     * 큰 이미지는 읽는 데 시간이 걸리므로 별도 스레드에서 읽고, 그동안 화면이 멈추지 않게 "불러오는 중"을 보여준다.
     */
    private void loadImage(String message) {
        if (imageLoader != null) imageLoader.cancel(false);
        GameMap target = map;
        String k = key(target);
        if (k.equals(loadedKey)) {                       // 이미 메모리에 있는 이미지
            mapView.setContent(loadedImage, target.markers(), true, "이미지를 읽을 수 없습니다");
            if (message != null) updateViewStatus(message);
            return;
        }
        loadedKey = null;
        loadedImage = null;                              // 이전 맵의 이미지는 메모리에서 내려놓는다
        Optional<Path> file = store.imageFor(target);
        if (file.isEmpty()) { mapView.setContent(null, target.markers(), true, NO_IMAGE_HINT); return; }
        mapView.setContent(null, target.markers(), true, "지도를 불러오는 중…");
        imageLoader = new SwingWorker<>() {
            @Override protected BufferedImage doInBackground() throws Exception {
                BufferedImage raw = ImageIO.read(file.get().toFile());
                return raw == null ? null : MapView.toFastFormat(raw);
            }
            @Override protected void done() {
                // 읽는 사이에 다른 맵으로 갔으면 버린다
                if (isCancelled() || map == null || !k.equals(key(map))) return;
                BufferedImage img = null;
                try { img = get(); } catch (Exception ignored) { }
                loadedKey = k;
                loadedImage = img;
                mapView.setContent(img, map.markers(), true, "이미지를 읽을 수 없습니다");
                if (message != null) updateViewStatus(message);
            }
        };
        imageLoader.execute();
    }

    private static String key(GameMap m) { return m.gameId() + "/" + m.id(); }

    // ================================================================ 공통

    private void back() {
        switch (screen) {
            case VIEW -> {
                if (!markerSearch.getText().isEmpty()) { markerSearch.setText(""); return; }
                openMaps(game);
                mapsScreen.list.setSelectedValue(map, true);
            }
            case MAPS -> showGames();
            default -> { }
        }
    }

    private void switchTo(String s) { screen = s; cards.show(root, s); }

    private String ask(String msg, String initial) {
        Object r = JOptionPane.showInputDialog(frame, msg, "forthparty", JOptionPane.PLAIN_MESSAGE, null, null, initial);
        if (r == null || r.toString().isBlank()) return null;
        return r.toString().trim();
    }

    private boolean confirm(String msg) {
        return JOptionPane.showConfirmDialog(frame, msg, "삭제", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private interface IoAction { void run() throws Exception; }

    private void save(IoAction a) {
        try { a.run(); } catch (Exception e) { error(e); }
    }

    private void error(Exception e) {
        JOptionPane.showMessageDialog(frame, "오류: " + e.getMessage(), "forthparty", JOptionPane.ERROR_MESSAGE);
    }

    private static void bind(JComponent c, String key, Runnable action) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        c.getActionMap().put(key, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /** 게임 목록 / 맵 목록 화면이 공유하는 "제목 + 목록 + 버튼" 화면 */
    private static final class ListScreen<T> {
        final JPanel panel = new JPanel(new BorderLayout(0, 8));
        final JLabel title = new JLabel();
        final JLabel hint = new JLabel(" ");
        final DefaultListModel<T> model = new DefaultListModel<>();
        final JList<T> list = new JList<>(model);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        Runnable onBack = () -> {}, onAdd = () -> {};
        Consumer<T> onRename = t -> {}, onDelete = t -> {};

        void init(String titleText, String backText, String addText, Consumer<T> onOpen) {
            title.setText(titleText);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            list.setFont(list.getFont().deriveFont(17f));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && list.getSelectedValue() != null) onOpen.accept(list.getSelectedValue());
                }
            });
            list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
            list.getActionMap().put("open", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { if (list.getSelectedValue() != null) onOpen.accept(list.getSelectedValue()); }
            });
            list.getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "del");
            list.getActionMap().put("del", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { if (list.getSelectedValue() != null) onDelete.accept(list.getSelectedValue()); }
            });

            JPanel head = new JPanel(new BorderLayout(10, 0));
            if (backText != null) {
                JButton back = new JButton(backText);
                back.setFocusable(false);
                back.addActionListener(e -> onBack.run());
                head.add(back, BorderLayout.WEST);
            }
            head.add(title, BorderLayout.CENTER);

            JButton add = new JButton(addText), rename = new JButton("이름 변경"), del = new JButton("삭제");
            add.addActionListener(e -> onAdd.run());
            rename.addActionListener(e -> { if (list.getSelectedValue() != null) onRename.accept(list.getSelectedValue()); });
            del.addActionListener(e -> { if (list.getSelectedValue() != null) onDelete.accept(list.getSelectedValue()); });
            for (JButton b : new JButton[]{add, rename, del}) { b.setFocusable(false); buttons.add(b); }
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(hint, BorderLayout.WEST);
            bottom.add(buttons, BorderLayout.EAST);

            panel.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
            panel.add(head, BorderLayout.NORTH);
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
            panel.add(bottom, BorderLayout.SOUTH);
        }

        /** 기본 버튼(추가/이름 변경/삭제) 앞에 버튼 하나 추가 */
        void extraButton(String text, Runnable action) {
            JButton b = new JButton(text);
            b.setFocusable(false);
            b.addActionListener(e -> action.run());
            buttons.add(b, 0);
        }

        void setItems(List<T> items) {
            model.clear();
            model.addAll(items);
            if (!items.isEmpty()) list.setSelectedIndex(0);
        }
    }

    public static void main(String[] args) {
        Path dataDir = args.length > 0 ? Path.of(args[0]) : defaultDataDir();
        SwingUtilities.invokeLater(() -> {
            try {
                Store store = new Store(dataDir);
                Theme.apply("dark".equals(store.setting("theme", "light")), null);
                new App(store).frame.setVisible(true);
            } catch (Exception e) {
                // jar를 더블클릭해서 실행하면 콘솔이 없으므로 오류를 창으로 보여준다
                JOptionPane.showMessageDialog(null, "forthparty를 시작할 수 없습니다.\n" + e + "\n\n데이터 폴더: " + dataDir,
                        "forthparty", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    /**
     * 데이터 폴더 위치: 프로그램 파일 바로 옆의 data 폴더.
     * jar로 실행하면 forthparty.jar가 있는 폴더, 컴파일한 클래스(out 폴더)로 실행하면 프로젝트 폴더 기준.
     * 더블클릭 실행은 '현재 폴더'가 어디로 잡힐지 모르기 때문에 이렇게 정한다.
     */
    private static Path defaultDataDir() {
        try {
            Path location = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.getParent().resolve("data");
        } catch (Exception e) {
            return Path.of("data");
        }
    }
}
