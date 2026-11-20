package dev.pixelmotion.a10.recents;

public class DockEdgeTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }

    public static void main(String[] args) {
        for (int buffer = 0; buffer < 4; buffer++) {
            check(DockEdge.forOverview(0, buffer) == DockEdge.BOTTOM);
            check(DockEdge.forOverview(2, buffer) == DockEdge.BOTTOM);
            for (int touch : new int[]{1, 3}) {
                DockEdge edge = DockEdge.forOverview(touch, buffer);
                double angle = (touch - buffer) * Math.PI / 2;
                int cos = (int) Math.round(Math.cos(angle)), sin = (int) Math.round(Math.sin(angle));
                check(edge.outwardX * cos + edge.outwardY * sin == 1);
                check(-edge.outwardX * sin + edge.outwardY * cos == 0);
                check(edge.vertical() == (sin == 0));
                int expectedInset = cos == 1 ? 30 : cos == -1 ? 10 : sin == 1 ? 40 : 20;
                check(edge.inset(10, 20, 30, 40) == expectedInset);
                for (int count = 1; count <= 5; count++) {
                    int previous = -10000;
                    for (int index = 0; index < count; index++) {
                        int center = edge.slotCenter(index, count, 5, 951);
                        check(center > 0 && center < 951);
                        int visual = edge.vertical() ? center * cos : -center * sin;
                        check(visual > previous);
                        previous = visual;
                    }
                }
            }
        }
        System.out.println("PASS: visual right edge, outward motion, insets and ordered slots in both landscape directions");
    }
}
