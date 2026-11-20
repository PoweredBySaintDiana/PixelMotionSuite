package dev.pixelmotion.a11.motion;

import android.animation.Animator;
import android.animation.AnimatorSet;
import java.util.Map;

final class OpeningStart {
    private OpeningStart() {}

    // 907 nests the icon ValueAnimator directly in a group with a one-refresh
    // delay. Seek the running root once so its synchronized siblings keep the
    // same clock as the app surface. 701 started the set and sought to 16 ms.
    static long advance(AnimatorSet root, Map<?, Boolean> iconAnimators) {
        if (root == null || !root.isStarted() || root.getStartDelay() != 0) return -1;
        for (Animator child : root.getChildAnimations()) {
            if (!(child instanceof AnimatorSet)) continue;
            for (Animator leaf : ((AnimatorSet) child).getChildAnimations()) {
                if (!Boolean.TRUE.equals(iconAnimators.get(leaf))) continue;
                long delay = child.getStartDelay();
                long firstFrame = delay + 16L;
                if (root.getCurrentPlayTime() >= firstFrame || root.getTotalDuration() < firstFrame) return -1;
                root.setCurrentPlayTime(firstFrame);
                return delay;
            }
        }
        return -1;
    }
}
