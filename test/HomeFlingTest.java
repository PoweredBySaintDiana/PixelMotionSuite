package dev.pixelmotion.a11.motion;

public final class HomeFlingTest {
    enum Target {HOME, RECENTS, NEW_TASK, LAST_TASK, ALL_APPS}
    static int checks;
    static void check(boolean condition) {
        if (!condition) throw new AssertionError("check " + (checks + 1));
        checks++;
    }

    static void direction(float releaseX, float releaseY, float nativeX, float nativeY) {
        float[] corrected = HomeFling.redirect(releaseX, releaseY, nativeX, nativeY);
        check(corrected != null);
        check(Math.signum(corrected[0]) == Math.signum(releaseX) && Math.signum(corrected[1]) == Math.signum(releaseY));
        double angle = Math.atan2(corrected[1], corrected[0]);
        check(Math.abs(angle - Math.atan2(releaseY, releaseX)) < 0.000001);
        check(Math.abs(Math.hypot(corrected[0], corrected[1]) - Math.hypot(nativeX, nativeY)) < 0.001);
    }

    public static void main(String[] args) {
        for (float[] right : new float[][]{{5.429168f,-4.681723f},{7.571098f,-5.535965f},{6.508449f,-6.477472f}}) {
            check(HomeFling.diagonalSwitch(Target.NEW_TASK,true,false,right[0],right[1],right[1]));
            check(HomeFling.diagonalSwitch(Target.NEW_TASK,true,false,-right[0],right[1],right[1]));
            check(!HomeFling.diagonalSwitch(Target.HOME,true,false,-right[0],right[1],right[1]));
        }
        float edge = (float) Math.tan(Math.toRadians(15));
        for (float sign : new float[]{-1f,1f}) {
            for (float vertical : new float[]{0f,-.1f,-edge,edge})
                check(!HomeFling.diagonalSwitch(Target.NEW_TASK,true,false,sign,vertical,vertical));
            float outside = -Math.nextUp(edge);
            check(HomeFling.diagonalSwitch(Target.NEW_TASK,true,false,sign,outside,outside));
        }
        for (Target target : Target.values()) if (target != Target.NEW_TASK)
            check(!HomeFling.diagonalSwitch(target,true,false,3f,-2f,-2f));
        check(!HomeFling.diagonalSwitch(Target.NEW_TASK,false,false,3f,-2f,-2f));
        check(!HomeFling.diagonalSwitch(Target.NEW_TASK,true,true,3f,-2f,-2f));
        check(!HomeFling.diagonalSwitch(Target.NEW_TASK,true,false,3f,-2f,-3f));
        check(!HomeFling.diagonalSwitch(Target.NEW_TASK,true,false,Float.NaN,-2f,-2f));
        direction(1f,-1f,584.6907f,-2000f);
        direction(-1f,-1f,-584.6907f,-2000f);
        direction(.002f,-2f,300f,-2000f);
        direction(-4f,-1.2f,-840f,-2000f);
        direction(12f,-9f,1300f,-5800f);
        check(HomeFling.redirect(0f,-2f,0f,-2000f) == null);
        check(HomeFling.redirect(2f,0f,500f,0f) == null);
        check(HomeFling.redirect(0f,0f,0f,0f) == null);
        check(HomeFling.redirect(1f,-1f,0f,0f) == null);
        check(HomeFling.redirect(Float.NaN,-1f,0f,1f) == null);
        check(HomeFling.redirect(1f,-1f,1f,Float.POSITIVE_INFINITY) == null);
        HomeFling scope = new HomeFling();
        Object swipe = new Object(), other = new Object(), first = new Object(), second = new Object();
        Object[] pair = {first,null,second};
        for (Target target : Target.values()) {
            for (boolean fling : new boolean[]{false,true}) {
                for (boolean cancel : new boolean[]{false,true}) {
                    scope.decide(swipe,target,fling,cancel);
                    scope.bind(other,pair);
                    check(!scope.take(first));
                    scope.bind(swipe,pair);
                    boolean expected = target == Target.HOME && fling && !cancel;
                    check(scope.take(first) == expected && scope.take(second) == expected);
                    check(!scope.take(first) && !scope.take(second));
                    scope.bind(swipe,pair);
                    check(!scope.take(first));
                }
            }
        }
        scope.decide(swipe,Target.HOME,true,false);
        scope.decide(swipe,null,true,false);
        scope.bind(swipe,pair);
        check(!scope.take(first));
        scope.decide(swipe,Target.HOME,true,false);
        scope.bind(swipe,null);
        scope.bind(swipe,pair);
        check(!scope.take(first));
        System.out.println("PASS: " + checks + " home fling direction and scope assertions");
    }
}
