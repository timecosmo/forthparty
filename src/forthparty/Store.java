package forthparty;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 로컬 파일 저장소. 서버 없이 폴더 구조가 곧 데이터베이스.
 *
 * data/settings.properties                    프로그램 설정 (테마 등)
 * data/games/<게임id>/game.properties        게임 이름 + 마커 종류 목록
 * data/games/<게임id>/maps/<맵id>.properties  맵 이름 + 마커
 * data/games/<게임id>/maps/<맵id>.png         맵 이미지
 */
final class Store {
    private static final String[] IMAGE_EXTS = {"png", "jpg", "jpeg", "gif"};
    static final String SHARE_EXT = "fpmap";
    private static final String SHARE_FORMAT = "forthparty-map-1";

    private final Path dataDir;
    private final Path gamesDir;

    Store(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        gamesDir = dataDir.resolve("games");
        Files.createDirectories(gamesDir);
    }

    // ------------------------------------------------------------ 설정

    String setting(String key, String def) {
        try { return read(dataDir.resolve("settings.properties")).getProperty(key, def); }
        catch (IOException e) { return def; }
    }

    void putSetting(String key, String value) throws IOException {
        Path f = dataDir.resolve("settings.properties");
        Properties p = read(f);
        p.setProperty(key, value);
        write(f, p);
    }

    // ------------------------------------------------------------ 게임

    List<Game> listGames() throws IOException {
        List<Game> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(gamesDir)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) out.add(loadGame(d.getFileName().toString()));
        }
        out.sort(Comparator.comparing(Game::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    Game loadGame(String id) throws IOException {
        Properties p = read(gamesDir.resolve(id).resolve("game.properties"));
        List<MarkerType> types = new ArrayList<>();
        for (int i = 0; p.containsKey("kind." + i); i++) {
            try { types.add(MarkerType.parse(p.getProperty("kind." + i))); }
            catch (RuntimeException ignored) { /* 깨진 줄은 건너뜀 */ }
        }
        // 종류 목록이 없는 게임(예전 버전에서 만든 게임)은 기본 종류를 씀
        return new Game(id, p.getProperty("name", id), types.isEmpty() ? MarkerType.defaults() : List.copyOf(types));
    }

    void saveGame(Game g) throws IOException {
        Path dir = gamesDir.resolve(g.id());
        Files.createDirectories(dir.resolve("maps"));
        Properties p = new Properties();
        p.setProperty("name", g.name());
        for (int i = 0; i < g.types().size(); i++) p.setProperty("kind." + i, g.types().get(i).serialize());
        write(dir.resolve("game.properties"), p);
    }

    void deleteGame(Game g) throws IOException {
        deleteRecursive(gamesDir.resolve(g.id()));
    }

    // ------------------------------------------------------------ 맵

    List<GameMap> listMaps(Game g) throws IOException {
        Path dir = mapsDir(g.id());
        Files.createDirectories(dir);
        List<GameMap> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".properties")).toList()) {
                String fn = f.getFileName().toString();
                String id = fn.substring(0, fn.length() - ".properties".length());
                Properties p = read(f);
                out.add(new GameMap(g.id(), id, p.getProperty("name", id), readMarkers(p)));
            }
        }
        out.sort(Comparator.comparing(GameMap::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    void saveMap(GameMap m) throws IOException {
        Properties p = new Properties();
        p.setProperty("name", m.name());
        writeMarkers(p, m.markers());
        write(mapsDir(m.gameId()).resolve(m.id() + ".properties"), p);
    }

    void deleteMap(GameMap m) throws IOException {
        Files.deleteIfExists(mapsDir(m.gameId()).resolve(m.id() + ".properties"));
        for (String e : IMAGE_EXTS) Files.deleteIfExists(mapsDir(m.gameId()).resolve(m.id() + "." + e));
    }

    Optional<Path> imageFor(GameMap m) {
        for (String ext : IMAGE_EXTS) {
            Path p = mapsDir(m.gameId()).resolve(m.id() + "." + ext);
            if (Files.exists(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** 선택한 이미지를 맵 폴더로 복사 (기존 이미지는 교체) */
    void setImage(GameMap m, Path source) throws IOException {
        String fn = source.getFileName().toString();
        String ext = fn.substring(fn.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        for (String e : IMAGE_EXTS) Files.deleteIfExists(mapsDir(m.gameId()).resolve(m.id() + "." + e));
        Files.copy(source, mapsDir(m.gameId()).resolve(m.id() + "." + ext), StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------ 공유 (서버 없이 파일로)

    /** 맵 하나(이름 + 마커 + 쓰인 마커 종류 + 이미지)를 .fpmap 파일 하나로 내보냄 */
    void exportMap(Game g, GameMap m, Path out) throws IOException {
        Properties p = new Properties();
        p.setProperty("format", SHARE_FORMAT);
        p.setProperty("name", m.name());
        writeMarkers(p, m.markers());
        // 받는 사람 게임에 없는 종류도 같은 모양·색으로 보이도록, 이 맵에 쓰인 종류를 함께 담는다
        Set<String> used = new LinkedHashSet<>();
        m.markers().forEach(x -> used.add(x.typeId()));
        int i = 0;
        for (String id : used) {
            MarkerType t = g.type(id);
            if (!t.label().equals(MarkerType.unknown(id).label())) p.setProperty("kind." + i++, t.serialize());
        }
        Optional<Path> img = imageFor(m);
        if (img.isPresent()) {
            String fn = img.get().getFileName().toString();
            p.setProperty("imageExt", fn.substring(fn.lastIndexOf('.') + 1));
            p.setProperty("image", Base64.getEncoder().encodeToString(Files.readAllBytes(img.get())));
        }
        write(out, p);
    }

    /**
     * .fpmap 파일을 게임에 새 맵으로 추가. 같은 이름이 있으면 "(가져옴)"을 붙인다.
     * 파일에 담긴 마커 종류 중 이 게임에 없는 것은 게임 종류 목록에 추가한다.
     */
    GameMap importMap(Game g, Path in) throws IOException {
        Properties p = read(in);
        if (!SHARE_FORMAT.equals(p.getProperty("format"))) throw new IOException("forthparty 맵 파일이 아닙니다");
        List<MarkerType> types = new ArrayList<>(g.types());
        boolean added = false;
        for (int i = 0; p.containsKey("kind." + i); i++) {
            try {
                MarkerType t = MarkerType.parse(p.getProperty("kind." + i));
                if (types.stream().noneMatch(x -> x.id().equals(t.id()))) { types.add(t); added = true; }
            } catch (RuntimeException ignored) { }
        }
        if (added) saveGame(g.withTypes(types));

        String name = p.getProperty("name", "가져온 맵");
        String base = name;
        if (listMaps(g).stream().anyMatch(x -> x.name().equals(base))) name += " (가져옴)";
        GameMap m = new GameMap(g.id(), newId("m_"), name, readMarkers(p));
        saveMap(m);
        String ext = p.getProperty("imageExt", "").toLowerCase(Locale.ROOT);
        if (p.containsKey("image") && Arrays.asList(IMAGE_EXTS).contains(ext)) {
            Files.write(mapsDir(g.id()).resolve(m.id() + "." + ext), Base64.getDecoder().decode(p.getProperty("image")));
        }
        return m;
    }

    String newId(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
    }

    // ------------------------------------------------------------ 내부

    private Path mapsDir(String gameId) { return gamesDir.resolve(gameId).resolve("maps"); }

    private static List<Marker> readMarkers(Properties p) {
        List<Marker> markers = new ArrayList<>();
        for (int i = 0; p.containsKey("marker." + i); i++) {
            try { markers.add(Marker.parse(p.getProperty("marker." + i))); }
            catch (RuntimeException ignored) { /* 깨진 줄은 건너뜀 */ }
        }
        return List.copyOf(markers);
    }

    private static void writeMarkers(Properties p, List<Marker> markers) {
        for (int i = 0; i < markers.size(); i++) p.setProperty("marker." + i, markers.get(i).serialize());
    }

    private static Properties read(Path f) throws IOException {
        Properties p = new Properties();
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
        return p;
    }

    /** 임시 파일에 쓴 뒤 교체 → 저장 중 꺼져도 기존 파일이 깨지지 않음 */
    private static void write(Path f, Properties p) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { p.store(w, null); }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
