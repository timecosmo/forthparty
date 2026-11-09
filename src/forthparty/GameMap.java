package forthparty;

import java.util.List;

/** 게임 안의 맵 하나 + 내가 찍은 마커들 */
record GameMap(String gameId, String id, String name, List<Marker> markers) {
    GameMap withName(String n) { return new GameMap(gameId, id, n, markers); }
    GameMap withMarkers(List<Marker> m) { return new GameMap(gameId, id, name, List.copyOf(m)); }
    @Override public String toString() { return name; }
}
